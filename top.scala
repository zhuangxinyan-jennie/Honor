package mycpu
import chisel3._
import chisel3.util._

class TopIO extends Bundle{
    // Instruction memory interface (I-Cache)
    val inst_addr1=Output(UInt(32.W))
    val inst_addr2=Output(UInt(32.W))
    val inst_data1=Input(UInt(32.W))
    val inst_data2=Input(UInt(32.W))
  
    // Data memory interface (D-Cache)
    val sram_en=Output(Bool())
    val sram_funct3=Output(UInt(3.W))
    val sram_wdata=Output(UInt(32.W))
    val sram_addr=Output(UInt(32.W))
    val sram_rdata=Input(UInt(32.W))
}
class Top extends Module {
    val io = IO(new TopIO)
    // 模块实例化
    //IF
    val pcReg=Module(new PCReg)
    val instFifo=Module(new InstFifo)
    //ID与EX
    val decode=Module(new DualIssueDecode)
    //forward
    val forward=Module(new Forward)
    //CU
    val cu=Module(new ControlUnit)
    //寄存器堆
    val regFile=Module(new RegisterFile)
    //级间寄存器
    val id_ex=Module(new id_ex)

    val ex=Module(new DualIssueEx)
    val ex_mem=Module(new ex_mem)

    val mem=Module(new MEM)
    val mem_wb=Module(new mem_wb)


    val bp=Module(new BTB)
    val csr=Module(new Csr)


  
    // ===== I-Cache 接口连接 =====
    instFifo.io.flush_command_in:=cu.io.cu_ififo_flush_o 
    instFifo.io.i_stall:=cu.io.cu_ififo_rd_stall_o
   
    instFifo.io.write_data1:=io.inst_data1
    instFifo.io.write_data2:=io.inst_data2
    instFifo.io.write_pc:=pcReg.io.pc_effective_out

    instFifo.io.read_en1 := true.B
    instFifo.io.read_en2 := !decode.io.fifo_push
    instFifo.io.flush_due_to_ID_predict_taken := bp.io.predTakeD
    instFifo.io.flush_due_to_EX_mispredict := ex.io.cu.ex_branch_mispredict_detected_i


    // ===== D-Cache 接口连接 =====
    io.sram_en:=mem.io.sram.en
    io.sram_funct3:=mem.io.sram.funct3
    io.sram_addr:=mem.io.sram.addr
    io.sram_wdata:=mem.io.sram.wdata
    mem.io.sram.rdata:=io.sram_rdata

    // ===== PCReg 控制信号 =====
    pcReg.io.F_fifo_full:=instFifo.io.full
    pcReg.io.F_fifo_almost_full:=instFifo.io.almost_full
    pcReg.io.D_bj:=bp.io.predTakeD
    pcReg.io.D_bj_target:=bp.io.branchTargetD
    pcReg.io.EX_mispredict_resolved:= ex.io.cu.ex_branch_mispredict_detected_i
    pcReg.io.EX_pc_of_mispredicted_branch:= ex.io.pc1Out
    pcReg.io.EX_actual_branch_target:= ex.io.branchTarget1
    pcReg.io.EX_branch_actually_taken:= ex.io.branch1

    io.inst_addr1 := pcReg.io.pc_effective_out
    io.inst_addr2 := pcReg.io.pc_effective_out + 4.U
    
     // ===== 控制单元输入输出 =====(完成)
    cu.io.ex_master_path_data_ready_i := ex.io.cu.ex_master_path_data_ready_i
    cu.io.ex_slave_path_data_ready_i  := ex.io.cu.ex_slave_path_data_ready_i

    // 分支预测校验：如果失败就认为是mispredict
    cu.io.ex_branch_mispredict_detected_i := ex.io.cu.ex_branch_mispredict_detected_i


    cu.io.cu_idex_master_is_load_i:= id_ex.io.E_master_ctrl_sign.mem_read || id_ex.io.E_master_ctrl_sign.mem_write
    cu.io.is_master_path_active_in_ex_i:=!(id_ex.io.E_master_ctrl_sign.inst_type===0.U)
    


    // ===== RegFile 连接 =====(完成)
    //与ID相关
    regFile.io.ra1_a:=decode.io.decode1.rs1
    regFile.io.ra1_b:=decode.io.decode2.rs1
    
    regFile.io.ra2_a:=decode.io.decode1.rs2
    regFile.io.ra2_b:=decode.io.decode2.rs2
    //与WRITE相关
    regFile.io.wen1:=mem_wb.io.W_master_reg_wen
    regFile.io.wa1:=mem_wb.io.W_master_reg_waddr
    regFile.io.wd1:=mem_wb.io.W_master_reg_wdata
    regFile.io.wen2:=mem_wb.io.W_slave_reg_wen
    regFile.io.wa2:=mem_wb.io.W_slave_reg_waddr
    regFile.io.wd2:=mem_wb.io.W_slave_reg_wdata

    // ===== CSR 连接 =====(完成)
    csr.io.csrrw_valid := ex.csr.writeEn
    csr.io.csrrw_addr:=ex.csr.writeAddr
    csr.io.csrrw_wdata:=ex.csr.writeData
    csr.io.csrrw_wr_en:=ex.csr.writeEn
    csr.io.csrrw_rd_en:=ex.csr.writeEn


    // ===== DualIssueDecode状态 =====（完成）
    decode.io.inst1:=instFifo.io.read_data1
    decode.io.inst2:=instFifo.io.read_data2
    decode.io.fifo_empty:=instFifo.io.empty
    decode.io.fifo_almost_empty:=instFifo.io.almost_empty
    decode.io.pc1:=instFifo.io.read_address1
    decode.io.pc2:=instFifo.io.read_address2
    decode.io.reg1_rs1_data:=regFile.io.rd1_a
    decode.io.reg1_rs2_data:=regFile.io.rd1_b
    decode.io.reg2_rs1_data:=regFile.io.rd2_a
    decode.io.reg2_rs2_data:=regFile.io.rd2_b




    // ===== Forward ======(完全完成)
    
    forward.io.id_master_rs_addrs(0)   := decode.io.decode1.rs1
    forward.io.id_master_rs_addrs(1)   := decode.io.decode1.rs2

    forward.io.id_master_rs_data_rf(0) := decode.io.decode1.ex_rs1_data
    forward.io.id_master_rs_data_rf(1) := decode.io.decode1.ex_rs2_data


    forward.io.id_slave_rs_addrs(0)    := decode.io.decode2.rs1
    forward.io.id_slave_rs_addrs(1)    := decode.io.decode2.rs2
    forward.io.id_slave_rs_data_rf(0)  := decode.io.decode2.ex_rs1_data
    forward.io.id_slave_rs_data_rf(1)  := decode.io.decode2.ex_rs2_data

    forward.io.ex_master_alu_valid   := id_ex.io.E_master_ctrl_sign.reg_write && !id_ex.io.E_master_ctrl_sign.mem_read && !id_ex.io.E_master_ctrl_sign.mem_write && ex.valid1
    forward.io.ex_master_alu_rd_addr := ex.rd1
    forward.io.ex_master_alu_data    := ex.result1

    forward.io.ex_slave_alu_valid   := id_ex.io.E_slave_ctrl_sign.reg_write && !id_ex.io.E_slave_ctrl_sign.mem_read && !id_ex.io.E_slave_ctrl_sign.mem_write && ex.valid2
    forward.io.ex_slave_alu_rd_addr := ex.rd2
    forward.io.ex_slave_alu_data    := ex.result2

    forward.io.exmem_master_alu_valid   := !ex_mem.io.M_master_ctrl_sign.mem_write && !ex_mem.io.M_master_ctrl_sign.mem_read && !(ex_mem.W_rd1===0.U)
    forward.io.exmem_master_alu_rd_addr := ex_mem.W_rd1
    forward.io.exmem_master_alu_data    := ex_mem.result1

    forward.io.exmem_slave_alu_valid   :=!ex_mem.io.M_slave_ctrl_sign.mem_write && !ex_mem.io.M_slave_ctrl_sign.mem_read && !(ex_mem.W_rd2===0.U)
    forward.io.exmem_slave_alu_rd_addr := ex_mem.W_rd2
    forward.io.exmem_slave_alu_data    := ex_mem.result2

    forward.io.mem_load_valid   := mem.io.cpu.mem_bypass_valid && ex_mem.io.M_master_ctrl_sign.mem_en && !ex_mem.io.M_master_ctrl_sign.mem_write
    forward.io.mem_load_rd_addr := ex_mem.W_rd1
    forward.io.mem_load_data    := mem.io.cpu.mem_bypass_data


    // ===== ID -> EX 连接 =====(基本完成  )
    
    id_ex.io.D_master_ctrl_sign.inst_type=decode.io.decode1.inst_type
    id_ex.io.D_master_ctrl_sign.rs1:= forward.io.final_master_rs_data(0)
    id_ex.io.D_master_ctrl_sign.rs2:= forward.io.final_master_rs_data(1)
    id_ex.io.D_master_ctrl_sign.ex_rs1_data:=decode.io.decode1.ex_rs1_data
    id_ex.io.D_master_ctrl_sign.ex_rs2_data:=decode.io.decode1.ex_rs2_data
    id_ex.io.D_master_ctrl_sign.rd:=decode.io.decode1.rd
    id_ex.io.D_master_ctrl_sign.imm:=decode.io.decode1.imm
    id_ex.io.D_master_ctrl_sign.op:=decode.io.decode1.alu_op
    id_ex.io.D_master_ctrl_sign.op1:=decode.io.decode1.alu_op1
    id_ex.io.D_master_ctrl_sign.op2:=decode.io.decode1.alu_op2
    id_ex.io.D_master_ctrl_sign.reg_write:=decode.io.decode1.reg_write
    id_ex.io.D_master_ctrl_sign.mem_read:=decode.io.decode1.mem_read
    id_ex.io.D_master_ctrl_sign.mem_write:=decode.io.decode1.mem_write
    id_ex.io.D_master_ctrl_sign.mem_type:=decode.io.decode1.mem_type
    id_ex.io.D_master_ctrl_sign.mem_unsigned:=decode.io.decode1.mem_unsigned
    id_ex.io.D_master_ctrl_sign.exception:=decode.io.decode1.exception
    id_ex.io.D_master_ctrl_sign.pc_out:=decode.io.decode1.pc_out

    id_ex.io.D_slave_ctrl_sign.inst_type=decode.io.decode2.inst_type
    id_ex.io.D_slave_ctrl_sign.rs1:= forward.io.final_slave_rs_data(0)
    id_ex.io.D_slave_ctrl_sign.rs2:= forward.io.final_slave_rs_data(1)
    id_ex.io.D_slave_ctrl_sign.ex_rs1_data:=decode.io.decode2.ex_rs1_data
    id_ex.io.D_slave_ctrl_sign.ex_rs2_data:=decode.io.decode2.ex_rs2_data
    id_ex.io.D_slave_ctrl_sign.rd:=decode.io.decode2.rd
    id_ex.io.D_slave_ctrl_sign.imm:=decode.io.decode2.imm
    id_ex.io.D_slave_ctrl_sign.op:=decode.io.decode2.alu_op
    id_ex.io.D_slave_ctrl_sign.op1:=decode.io.decode2.alu_op1
    id_ex.io.D_slave_ctrl_sign.op2:=decode.io.decode2.alu_op2
    id_ex.io.D_slave_ctrl_sign.reg_write:=decode.io.decode2.reg_write
    id_ex.io.D_slave_ctrl_sign.mem_read:=decode.io.decode2.mem_read
    id_ex.io.D_slave_ctrl_sign.mem_write:=decode.io.decode2.mem_write
    id_ex.io.D_slave_ctrl_sign.mem_type:=decode.io.decode2.mem_type
    id_ex.io.D_slave_ctrl_sign.mem_unsigned:=decode.io.decode2.mem_unsigned
    id_ex.io.D_slave_ctrl_sign.exception:=decode.io.decode2.exception
    id_ex.io.D_slave_ctrl_sign.pc_out:=decode.io.decode2.pc_out

    id_ex.io.ena:=cu.io.cu_idex_stall_o
    id_ex.io.clear := false.B

    // ===== EX ===== //(与ID相关的基本完成)
    //与ID相关
    ex.io.EXIn1.inst_type:=id_ex.io.E_master_ctrl_sign.inst_type
    ex.io.EXIn1.rs1:=id_ex.io.E_master_ctrl_sign.rs1
    ex.io.EXIn1.rs2:=id_ex.io.E_master_ctrl_sign.rs2
    ex.io.EXIn1.ex_rs1_data:=id_ex.io.E_master_ctrl_sign.ex_rs1_data
    ex.io.EXIn1.ex_rs2_data:=id_ex.io.E_master_ctrl_sign.ex_rs2_data
    ex.io.EXIn1.rd:=id_ex.io.E_master_ctrl_sign.rd
    ex.io.EXIn1.imm :=id_ex.io.E_master_ctrl_sign.imm
    ex.io.EXIn1.alu_op:=id_ex.io.E_master_ctrl_sign.alu_op
    ex.io.EXIn1.alu_op1:=id_ex.io.E_master_ctrl_sign.alu_op1
    ex.io.EXIn1.alu_op2:=id_ex.io.E_master_ctrl_sign.alu_op2 
    ex.io.EXIn1.reg_write:=id_ex.io.E_master_ctrl_sign.reg_write
    ex.io.EXIn1.mem_read:=id_ex.io.E_master_ctrl_sign.mem_read
    ex.io.EXIn1.mem_write=id_ex.io.E_master_ctrl_sign.mem_write
    ex.io.EXIn1.mem_type=id_ex.io.E_master_ctrl_sign.mem_type
    ex.io.EXIn1.mem_unsigned=id_ex.io.E_master_ctrl_sign.mem_unsigned
    ex.io.exception:=id_ex.io.E_master_ctrl_sign.exception
    ex.io.pc_out:=id_ex.io.E_master_ctrl_sign.pc_out
    
    ex.io.EXIn2.inst_type:=id_ex.io.E_slave_ctrl_sign.inst_type
    ex.io.EXIn2.rs1:=id_ex.io.E_slave_ctrl_sign.rs1
    ex.io.EXIn2.rs2:=id_ex.io.E_slave_ctrl_sign.rs2
    ex.io.EXIn2.ex_rs1_data:=id_ex.io.E_slave_ctrl_sign.ex_rs1_data
    ex.io.EXIn2.ex_rs2_data:=id_ex.io.E_slave_ctrl_sign.ex_rs2_data
    ex.io.EXIn2.rd:=id_ex.io.E_slave_ctrl_sign.rd
    ex.io.EXIn2.imm :=id_ex.io.E_slave_ctrl_sign.imm
    ex.io.EXIn2.alu_op:=id_ex.io.E_slave_ctrl_sign.alu_op
    ex.io.EXIn2.alu_op1:=id_ex.io.E_slave_ctrl_sign.alu_op1
    ex.io.EXIn2.alu_op2:=id_ex.io.E_slave_ctrl_sign.alu_op2
    ex.io.EXIn2.reg_write:=id_ex.io.E_slave_ctrl_sign.reg_write
    ex.io.EXIn2.mem_read:=id_ex.io.E_slave_ctrl_sign.mem_read
    ex.io.EXIn2.mem_write=id_ex.io.E_slave_ctrl_sign.mem_write
    ex.io.EXIn2.mem_type=id_ex.io.E_slave_ctrl_sign.mem_type
    ex.io.EXIn2.mem_unsigned=id_ex.io.E_slave_ctrl_sign.mem_unsigned
    ex.io.exception:=id_ex.io.E_slave_ctrl_sign.exception
    ex.io.pc_out:=id_ex.io.E_slave_ctrl_sign.pc_out
    //与CSR相关
    ex.csr.readData:=csr.io.csrrw_rdata
    //与BTB相关
    
    //与mem相关
    ex.io.mem1_rspValid:=mem.io.cpu.mem_bypass_valid
    ex.io.mem2_rspData:=mem.io.cpu.mem_bypass_data

    //=======BP==========
    bp.io.enaD:=decode.io.decode_branch_valid
    bp.io.pcD:=decode.io.decode1.pc_out
   
    bp.io.branchE:=ex.io.branch1
    bp.io.pcE:=ex.io.pc1Out
    bp.io.pcETarget:=ex.io.branchTarget1
    bp.io.actualTakeE:=ex.io.branch1


    // ===== EX -> MEM 连接 =====
    //master通道输入
    ex_mem.io.ena:=cu.io.cu_exmem_stall_o
    ex_mem.io.E_master_ctrl_sign.mem_en:=ex.io.mem1.reqValid 
    ex_mem.io.E_master_ctrl_sign.mem_write:=ex.io.mem1.reqWrite
    ex_mem.io.E_master_ctrl_sign.mem_type:=ex.io.mem1.reqWidth
    ex_mem.io.E_master_ctrl_sign.mem_unsigned:=ex.io.mem1.memUnsigned
    ex_mem.io.E_master_ctrl_sign.mem_wdata:=ex.io.mem1.reqData
    ex_mem.io.E_master_ctrl_sign.mem_addr:=ex.io.mem1.reqAddr
    ex_mem.rd1:=ex.io.rd1
    ex_mem.result1:=ex.io.result1
    ex_mem.valid1:=ex.io.valid1

    //slave通道输入
    ex_mem.io.E_slave_ctrl_sign.mem_en:=ex.io.mem2.reqValid 
    ex_mem.io.E_slave_ctrl_sign.mem_write:=ex.io.mem2.reqWrite
    ex_mem.io.E_slave_ctrl_sign.mem_type:=ex.io.mem2.reqWidth
    ex_mem.io.E_slave_ctrl_sign.mem_unsigned:=ex.io.mem2.memUnsigned
    ex_mem.io.E_slave_ctrl_sign.mem_wdata:=ex.io.mem2.reqData
    ex_mem.io.E_slave_ctrl_sign.mem_addr:=ex.io.mem2.reqAddr
    ex_mem.rd2:=ex.io.rd2
    ex_mem.result2:=ex.io.result2
    ex_mem.valid2:=ex.io.valid2



    // ==== MEM =====//
    mem.io.cpu.mem_en:=ex_mem.io.M_master_ctrl_sign.mem_en
    mem.io.cpu.mem_type:=ex_mem.io.M_master_ctrl_sign.mem_type
    mem.io.cpu.mem_wdata:=ex_mem.io.M_master_ctrl_sign.mem_wdata
    mem.io.cpu.mem_addr:=ex_mem.io.M_master_ctrl_sign.mem_addr
    mem.io.cpu.mem_unsigned:=ex_mem.io.M_master_ctrl_sign.mem_unsigned
    mem.io.cpu.mem_write:=ex_mem.io.M_master_ctrl_sign.mem_write


   // ===== MEM -> MEM_WB 连接 =====
    mem_wb.io.M_master_reg_wen:=id_ex.io.E_master_ctrl_sign.mem_read ? mem.io.cpu.mem_ready : ex_mem.io.W_valid1
    mem_wb.io.M_master_reg_waddr:=ex_mem.io.W_rd1
    mem_wb.io.M_master_reg_wdata:=id_ex.io.E_master_ctrl_sign.mem_read ? mem.io.cpu.mem_rdata : ex_mem.io.W_result1
    mem_wb.io.M_slave_reg_wen:=ex_mem.io.W_valid2
    mem_wb.io.M_slave_reg_waddr:=ex_mem.io.W_rd2
    mem_wb.io.M_slave_reg_wdata:= ex_mem.io.W_result2

}
