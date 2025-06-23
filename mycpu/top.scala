// --- 文件: Top.scala (修改后) ---

package mycpu
import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

class Top extends Module {
    
    val memDepth = 536870912
    val instWidth = 2
    // 模块实例化
    val pcReg=Module(new PCReg)
    val instFifo=Module(new InstFifo)
    val decode=Module(new DualIssueDecode)
    val forward=Module(new Forward)
    val cu=Module(new ControlUnit)
    val regFile=Module(new RegisterFile)
    val id_ex=Module(new id_ex)
    val ex=Module(new DualIssueEx)
    val ex_mem=Module(new ex_mem)
    val mem=Module(new MEM)
    val mem_wb=Module(new mem_wb)
    val outside_mem = Module(new mem(memDepth,instWidth))
    val bp=Module(new BTB)
    val csr=Module(new Csr)
    
    dontTouch(instFifo.io)
    //dontTouch(outside_mem.memInside)
    dontTouch(csr.io)
    // ===== outside_mem (公用内存) 连接 ===== 


    //outside_mem.io.data_en := mem.io.sram.en
    //mem.io.sram.sram_ready := outside_mem.io.data_ready
   
    outside_mem.io.if_mem.instAddr := Cat(0.U(32.W),pcReg.io.pc_effective_out)
    
    // 1. 判断PC的取指请求是否有效（即PC没有被停顿）
    val is_pc_stalled = pcReg.io.F_fifo_full || pcReg.io.F_fifo_is_settling_flush
    val fetch_request_valid = !is_pc_stalled

    // 2. 将“有效”信号和“冲刷”信号传递给 mem 模块
    outside_mem.io.if_mem.valid := fetch_request_valid
    outside_mem.io.if_mem.flush := cu.io.cu_ififo_flush_o
     outside_mem.io.if_mem.valid := pcReg.io.req_valid
    
    // // (访存连接)
    // outside_mem.io.ex_mem.atomFlag := false.B
    // outside_mem.io.ex_mem.dataAddr := Cat(0.U(32.W),mem.io.sram.addr)
    // outside_mem.io.ex_mem.writeEn := mem.io.sram.en
    // outside_mem.io.ex_mem.writeData := Cat(0.U(32.W),mem.io.sram.wdata)
    // outside_mem.io.ex_mem.func3 := mem.io.sram.funct3
    // mem.io.sram.rdata:= Cat(outside_mem.io.mem_lsu.data(7,0),outside_mem.io.mem_lsu.data(15,8),outside_mem.io.mem_lsu.data(23,16),outside_mem.io.mem_lsu.data(31,24)) 


     // --- [关键修改] 修正访存逻辑连接 ---
    outside_mem.io.ex_mem.atomFlag  := false.B
    outside_mem.io.ex_mem.dataAddr  := Cat(0.U(32.W), mem.io.sram.addr)
    outside_mem.io.ex_mem.writeData := Cat(0.U(32.W),mem.io.sram.wdata)
    outside_mem.io.ex_mem.func3     := mem.io.sram.funct3

    // 1. outside_mem 的 is_load 输入直接由缓存控制器 mem 的 is_load 输出驱动
    outside_mem.io.ex_mem.is_load := mem.io.sram.is_load

    // 2. outside_mem 的 writeEn 只在 sram 接口有效且操作不为 load (即为 store) 时有效
    outside_mem.io.ex_mem.writeEn := mem.io.sram.en && !mem.io.sram.is_load

    // 3. 主存读出的数据连接到缓存控制器
    // mem.io.sram.rdata := Cat(
    //     outside_mem.io.mem_lsu.data(7, 0),
    //     outside_mem.io.mem_lsu.data(15, 8),
    //     outside_mem.io.mem_lsu.data(23, 16),
    //     outside_mem.io.mem_lsu.data(31, 24)
    // )

    mem.io.sram.rdata := outside_mem.io.mem_lsu.data(31,0)

    // ===== I-Cache 接口连接 (取指流水线) =====
    // 3. 将 mem 返回的 valid 信号用作 instFifo 的写使能
    instFifo.io.write_en := outside_mem.io.mem_id.valid
    // *** 核心修改 2：将下游停顿信号同时送给 FIFO 和 PCReg ***
    pcReg.io.F_stall_by_ID := cu.io.cu_ififo_rd_stall_o


    
    instFifo.io.write_pc    := outside_mem.io.mem_id.pc
    instFifo.io.write_data1 := outside_mem.io.mem_id.inst(0)
    instFifo.io.write_data2 := outside_mem.io.mem_id.inst(1)
    
    // --- NEW ---: 连接 Valid-Ready 握手信号
    outside_mem.io.mem_id.ready := instFifo.io.ready_for_mem

    instFifo.io.flush_command_in := cu.io.cu_ififo_flush_o 
    instFifo.io.i_stall := cu.io.cu_ififo_rd_stall_o
    instFifo.io.read_en1 := true.B
    instFifo.io.read_en2 := !decode.io.fifo_push && !instFifo.io.empty && !instFifo.io.almost_empty
    instFifo.io.flush_due_to_ID_predict_taken := bp.io.predTakeD
    instFifo.io.flush_due_to_EX_mispredict := ex.io.cu.ex_branch_mispredict_detected_i

    // ===== PCReg 控制信号 =====
    pcReg.io.F_fifo_full:=instFifo.io.full
    pcReg.io.F_fifo_almost_full:=instFifo.io.almost_full
    pcReg.io.D_bj:=bp.io.predTakeD
    pcReg.io.D_bj_target:=bp.io.branchTargetD
    pcReg.io.EX_mispredict_resolved:= ex.io.cu.ex_branch_mispredict_detected_i
    pcReg.io.EX_pc_of_mispredicted_branch:= ex.io.pc1Out
    pcReg.io.EX_actual_branch_target:= ex.io.branchTarget1
    pcReg.io.EX_branch_actually_taken:= ex.io.branch1
    pcReg.io.E_master_is_BJ := id_ex.io.E_master_is_BJ
    pcReg.io.F_fifo_is_settling_flush := instFifo.io.fifo_is_settling_flush
    pcReg.io.mem_is_ready := outside_mem.io.if_mem.ready_for_pc
    
    // ===== 控制单元输入输出 =====
    cu.io.if_fifo_empty_i := instFifo.io.empty
    cu.io.ex_master_path_data_ready_i := ex.io.cu.ex_master_path_data_ready_i
    cu.io.ex_slave_path_data_ready_i  := ex.io.cu.ex_slave_path_data_ready_i
    cu.io.ex_branch_mispredict_detected_i := ex.io.cu.ex_branch_mispredict_detected_i
    cu.io.idex_master_is_load_i:= id_ex.io.E_master_ctrl_sign.mem_read || id_ex.io.E_master_ctrl_sign.mem_write
    cu.io.is_master_path_active_in_ex_i:= !(id_ex.io.E_master_ctrl_sign.inst_type===0.U)
    cu.io.is_slave_path_active_in_ex_i := !(id_ex.io.E_slave_ctrl_sign.inst_type===0.U)
    cu.io.BP_predTakeD := bp.io.predTakeD
    cu.io.E_master_is_BJ := id_ex.io.E_master_is_BJ
    cu.io.cpu_mem_ready := mem.io.cpu.mem_ready

    // ===== RegFile 连接 =====
    regFile.io.ra1_a:=decode.io.decode1.rs1
    regFile.io.ra1_b:=decode.io.decode1.rs2
    regFile.io.ra2_a:=decode.io.decode2.rs1
    regFile.io.ra2_b:=decode.io.decode2.rs2
    regFile.io.wen1:=mem_wb.io.W_master_reg_wen
    regFile.io.wa1:=mem_wb.io.W_master_reg_waddr
    regFile.io.wd1:=mem_wb.io.W_master_reg_wdata
    regFile.io.wen2:=mem_wb.io.W_slave_reg_wen
    regFile.io.wa2:=mem_wb.io.W_slave_reg_waddr
    regFile.io.wd2:=mem_wb.io.W_slave_reg_wdata

    // ===== CSR 连接 =====
    csr.io.csrrw_valid := ex.io.csr.writeEn
    csr.io.csrrw_addr:=ex.io.csr.writeAddr
    csr.io.csrrw_wdata:=ex.io.csr.writeData
    csr.io.csrrw_wr_en:=ex.io.csr.writeEn
    csr.io.csrrw_rd_en:=ex.io.csr.writeEn

    // ===== DualIssueDecode状态 =====
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

    // ===== Forward =====
    forward.io.id_master_rs_addrs(0)   := decode.io.decode1.rs1
    forward.io.id_master_rs_addrs(1)   := decode.io.decode1.rs2
    forward.io.id_master_rs_data_rf(0) := decode.io.decode1.ex_rs1_data
    forward.io.id_master_rs_data_rf(1) := decode.io.decode1.ex_rs2_data
    forward.io.id_slave_rs_addrs(0)    := decode.io.decode2.rs1
    forward.io.id_slave_rs_addrs(1)    := decode.io.decode2.rs2
    forward.io.id_slave_rs_data_rf(0)  := decode.io.decode2.ex_rs1_data
    forward.io.id_slave_rs_data_rf(1)  := decode.io.decode2.ex_rs2_data
    forward.io.ex_master_alu_valid   := id_ex.io.E_master_ctrl_sign.reg_write && !id_ex.io.E_master_ctrl_sign.mem_read && !id_ex.io.E_master_ctrl_sign.mem_write && ex.io.valid1
    forward.io.ex_master_alu_rd_addr := ex.io.rd1
    forward.io.ex_master_alu_data    := ex.io.result1
    forward.io.ex_slave_alu_valid   := !(id_ex.io.E_slave_ctrl_sign.rd===0.U) && ex.io.valid2
    forward.io.ex_slave_alu_rd_addr := ex.io.rd2
    forward.io.ex_slave_alu_data    := ex.io.result2
    forward.io.exmem_master_alu_valid   := !ex_mem.io.M_master_ctrl_sign.mem_en && !(ex_mem.io.W_rd1===0.U)
    forward.io.exmem_master_alu_rd_addr := ex_mem.io.W_rd1
    forward.io.exmem_master_alu_data    := ex_mem.io.W_result1
    forward.io.exmem_slave_alu_valid   := !(ex_mem.io.W_rd2===0.U)
    forward.io.exmem_slave_alu_rd_addr := ex_mem.io.W_rd2
    forward.io.exmem_slave_alu_data    := ex_mem.io.W_result2
    forward.io.mem_load_valid   := mem.io.mem_bypass_valid && ex_mem.io.M_master_ctrl_sign.mem_en && !ex_mem.io.M_master_ctrl_sign.mem_write
    forward.io.mem_load_rd_addr := ex_mem.io.W_rd1
    forward.io.mem_load_data    := mem.io.mem_bypass_data

    // ===== ID -> EX 连接 =====
    id_ex.io.D_master_ctrl_sign.inst_type:=decode.io.decode1.inst_type
    id_ex.io.D_master_ctrl_sign.rs1:= decode.io.decode1.rs1 
    id_ex.io.D_master_ctrl_sign.rs2:= decode.io.decode1.rs2
    id_ex.io.D_master_ctrl_sign.ex_rs1_data:= forward.io.final_master_rs_data(0)
    id_ex.io.D_master_ctrl_sign.ex_rs2_data:= forward.io.final_master_rs_data(1)
    id_ex.io.D_master_ctrl_sign.rd:=decode.io.decode1.rd
    id_ex.io.D_master_ctrl_sign.imm:=decode.io.decode1.imm
    id_ex.io.D_master_ctrl_sign.alu_op:=decode.io.decode1.alu_op
    id_ex.io.D_master_ctrl_sign.alu_op1:=decode.io.decode1.alu_op1
    id_ex.io.D_master_ctrl_sign.alu_op2:=decode.io.decode1.alu_op2
    id_ex.io.D_master_ctrl_sign.reg_write:=decode.io.decode1.reg_write
    id_ex.io.D_master_ctrl_sign.mem_read:=decode.io.decode1.mem_read
    id_ex.io.D_master_ctrl_sign.mem_write:=decode.io.decode1.mem_write
    id_ex.io.D_master_ctrl_sign.mem_type:=decode.io.decode1.mem_type
    id_ex.io.D_master_ctrl_sign.mem_unsigned:=decode.io.decode1.mem_unsigned
    id_ex.io.D_master_ctrl_sign.exception:=decode.io.decode1.exception
    id_ex.io.D_master_ctrl_sign.pc_out:=decode.io.decode1.pc_out
    id_ex.io.D_slave_ctrl_sign.inst_type:=decode.io.decode2.inst_type
    id_ex.io.D_slave_ctrl_sign.rs1:= decode.io.decode2.rs1
    id_ex.io.D_slave_ctrl_sign.rs2:= decode.io.decode2.rs2
    id_ex.io.D_slave_ctrl_sign.ex_rs1_data:=forward.io.final_slave_rs_data(0)
    id_ex.io.D_slave_ctrl_sign.ex_rs2_data:=forward.io.final_slave_rs_data(1)
    id_ex.io.D_slave_ctrl_sign.rd:=decode.io.decode2.rd
    id_ex.io.D_slave_ctrl_sign.imm:=decode.io.decode2.imm
    id_ex.io.D_slave_ctrl_sign.alu_op:=decode.io.decode2.alu_op
    id_ex.io.D_slave_ctrl_sign.alu_op1:=decode.io.decode2.alu_op1
    id_ex.io.D_slave_ctrl_sign.alu_op2:=decode.io.decode2.alu_op2
    id_ex.io.D_slave_ctrl_sign.reg_write:=decode.io.decode2.reg_write
    id_ex.io.D_slave_ctrl_sign.mem_read:=decode.io.decode2.mem_read
    id_ex.io.D_slave_ctrl_sign.mem_write:=decode.io.decode2.mem_write
    id_ex.io.D_slave_ctrl_sign.mem_type:=decode.io.decode2.mem_type
    id_ex.io.D_slave_ctrl_sign.mem_unsigned:=decode.io.decode2.mem_unsigned
    id_ex.io.D_slave_ctrl_sign.exception:=decode.io.decode2.exception
    id_ex.io.D_slave_ctrl_sign.pc_out:=decode.io.decode2.pc_out
    id_ex.io.D_slave_ena := decode.io.slave_ena
    id_ex.io.F_final_master_rs_data(0) := forward.io.final_master_rs_data(0)
    id_ex.io.F_final_master_rs_data(1) := forward.io.final_master_rs_data(1)
    id_ex.io.F_final_slave_rs_data(0) := forward.io.final_slave_rs_data(0)
    id_ex.io.F_final_slave_rs_data(1) := forward.io.final_slave_rs_data(1)
    id_ex.io.D_master_is_BJ := decode.io.decode_branch_valid
    id_ex.io.ena:= !cu.io.cu_idex_stall_o
    id_ex.io.clear := cu.io.cu_ififo_flush_o

    // ===== EX =====
    ex.io.ExIn1.inst_type:=id_ex.io.E_master_ctrl_sign.inst_type
    ex.io.ExIn1.rs1:=id_ex.io.E_master_ctrl_sign.rs1
    ex.io.ExIn1.rs2:=id_ex.io.E_master_ctrl_sign.rs2
    ex.io.ExIn1.ex_rs1_data:=id_ex.io.E_master_ctrl_sign.ex_rs1_data
    ex.io.ExIn1.ex_rs2_data:=id_ex.io.E_master_ctrl_sign.ex_rs2_data
    ex.io.ExIn1.rd:=id_ex.io.E_master_ctrl_sign.rd
    ex.io.ExIn1.imm :=id_ex.io.E_master_ctrl_sign.imm
    ex.io.ExIn1.alu_op:=id_ex.io.E_master_ctrl_sign.alu_op
    ex.io.ExIn1.alu_op1:=id_ex.io.E_master_ctrl_sign.alu_op1
    ex.io.ExIn1.alu_op2:=id_ex.io.E_master_ctrl_sign.alu_op2 
    ex.io.ExIn1.reg_write:=id_ex.io.E_master_ctrl_sign.reg_write
    ex.io.ExIn1.mem_read:=id_ex.io.E_master_ctrl_sign.mem_read
    ex.io.ExIn1.mem_write:=id_ex.io.E_master_ctrl_sign.mem_write
    ex.io.ExIn1.mem_type:=id_ex.io.E_master_ctrl_sign.mem_type
    ex.io.ExIn1.mem_unsigned:=id_ex.io.E_master_ctrl_sign.mem_unsigned
    ex.io.ExIn1.pc_out:=id_ex.io.E_master_ctrl_sign.pc_out
    ex.io.ExIn1.exception := id_ex.io.E_master_ctrl_sign.exception
    ex.io.ExIn2.inst_type:=id_ex.io.E_slave_ctrl_sign.inst_type
    ex.io.ExIn2.rs1:=id_ex.io.E_slave_ctrl_sign.rs1
    ex.io.ExIn2.rs2:=id_ex.io.E_slave_ctrl_sign.rs2
    ex.io.ExIn2.ex_rs1_data:=id_ex.io.E_slave_ctrl_sign.ex_rs1_data
    ex.io.ExIn2.ex_rs2_data:=id_ex.io.E_slave_ctrl_sign.ex_rs2_data
    ex.io.ExIn2.rd:=id_ex.io.E_slave_ctrl_sign.rd
    ex.io.ExIn2.imm :=id_ex.io.E_slave_ctrl_sign.imm
    ex.io.ExIn2.alu_op:=id_ex.io.E_slave_ctrl_sign.alu_op
    ex.io.ExIn2.alu_op1:=id_ex.io.E_slave_ctrl_sign.alu_op1
    ex.io.ExIn2.alu_op2:=id_ex.io.E_slave_ctrl_sign.alu_op2
    ex.io.ExIn2.reg_write:=id_ex.io.E_slave_ctrl_sign.reg_write
    ex.io.ExIn2.mem_read:=id_ex.io.E_slave_ctrl_sign.mem_read
    ex.io.ExIn2.mem_write:=id_ex.io.E_slave_ctrl_sign.mem_write
    ex.io.ExIn2.mem_type:=id_ex.io.E_slave_ctrl_sign.mem_type
    ex.io.ExIn2.mem_unsigned:=id_ex.io.E_slave_ctrl_sign.mem_unsigned
    ex.io.ExIn2.pc_out:=id_ex.io.E_slave_ctrl_sign.pc_out
    ex.io.ExIn2.exception := id_ex.io.E_slave_ctrl_sign.exception
    ex.io.csr.readData:=csr.io.csrrw_rdata
    ex.io.bpPredict.valid := bp.io.predTakeD
    ex.io.bpPredict.bits := bp.io.branchTargetD
    ex.io.mem1.rspValid:=mem.io.cpu.mem_ready && mem.io.cpu.mem_en
    ex.io.mem1.rspData:=mem.io.mem_bypass_data
    ex.io.mem2.rspData:=0.U
    ex.io.mem2.rspValid:=0.B
    ex.io.exceptionFeedback.handlerPC:=0.U
    ex.io.slave_ena:=id_ex.io.E_slave_ena
    ex.io.exceptionFeedback.flush:=0.U

    //=======BP==========
    bp.io.enaD:=decode.io.decode_branch_valid
    bp.io.pcD:=decode.io.decode1.pc_out
    bp.io.branchE:=ex.io.branch1
    bp.io.pcE:=ex.io.pc1Out
    bp.io.pcETarget:=ex.io.branchTarget1
    bp.io.actualTakeE:=ex.io.branch1

    // ===== EX -> MEM 连接 =====
    ex_mem.io.ena:= !cu.io.cu_exmem_stall_o
    ex_mem.io.E_master_ctrl_sign.mem_en:=ex.io.mem1.reqValid 
    ex_mem.io.E_master_ctrl_sign.mem_write:=ex.io.mem1.reqWrite
    ex_mem.io.E_master_ctrl_sign.mem_type:=ex.io.mem1.reqWidth
    ex_mem.io.E_master_ctrl_sign.mem_unsigned:=ex.io.mem1.memUnsigned
    ex_mem.io.E_master_ctrl_sign.mem_wdata:=ex.io.mem1.reqData
    ex_mem.io.E_master_ctrl_sign.mem_addr:=ex.io.mem1.reqAddr
    ex_mem.io.E_rd1:=ex.io.rd1
    ex_mem.io.E_result1:=ex.io.result1
    ex_mem.io.E_valid1:=ex.io.valid1
    ex_mem.io.E_rd2:=ex.io.rd2
    ex_mem.io.E_result2:=ex.io.result2
    ex_mem.io.E_valid2:=ex.io.valid2

    // ==== MEM =====
    mem.io.cpu.mem_en:=ex_mem.io.M_master_ctrl_sign.mem_en
    mem.io.cpu.mem_type:=ex_mem.io.M_master_ctrl_sign.mem_type
    mem.io.cpu.mem_wdata:=ex_mem.io.M_master_ctrl_sign.mem_wdata
    mem.io.cpu.mem_addr:=ex_mem.io.M_master_ctrl_sign.mem_addr
    mem.io.cpu.mem_unsigned:=ex_mem.io.M_master_ctrl_sign.mem_unsigned
    mem.io.cpu.mem_write:=ex_mem.io.M_master_ctrl_sign.mem_write

    // ===== MEM -> MEM_WB 连接 =====
    mem_wb.io.M_master_reg_wen := Mux(ex_mem.io.M_master_ctrl_sign.mem_en && !ex_mem.io.M_master_ctrl_sign.mem_write, mem.io.cpu.mem_ready, ex_mem.io.W_valid1)
    mem_wb.io.M_master_reg_waddr:=ex_mem.io.W_rd1
    mem_wb.io.M_master_reg_wdata := Mux(ex_mem.io.M_master_ctrl_sign.mem_en && !ex_mem.io.M_master_ctrl_sign.mem_write, mem.io.cpu.mem_rdata, ex_mem.io.W_result1)
    mem_wb.io.M_slave_reg_wen:=ex_mem.io.W_valid2
    mem_wb.io.M_slave_reg_waddr:=ex_mem.io.W_rd2
    mem_wb.io.M_slave_reg_wdata:= ex_mem.io.W_result2
}