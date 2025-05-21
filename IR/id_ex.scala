import chisel3._
import chisel3.util._

// 根据 defines.vh 定义对应的 Chisel Bundles
class ctrl_sign extends Bundle {
  val aluop = UInt(8.W)
  val flush_all = Bool()
  val read_rs = Bool()
  val read_rt = Bool()
  val reg_write = Bool()
  val mem_en = Bool()
  val mem_write_reg = Bool()
  val mem_read = Bool()
  val mem_write = Bool()
  val cp0_read = Bool()
  val cp0_write = Bool()
  val hilo_read = Bool()
  val hilo_write = Bool()
  val may_bring_flush = Bool()
  val only_one_issue = Bool()
  val icache_fence = Bool()
  val dcache_fence = Bool()
  val tlb_fence = Bool()
  val mul_en = Bool()
  val div_en = Bool()
}

class except_bus extends Bundle {
  val if_adel = Bool()
  val if_tlbl = Bool()
  val if_tlbrf = Bool()
  val id_ri = Bool()
  val id_syscall = Bool()
  val id_break = Bool()
  val id_eret = Bool()
  val id_int = Bool()
  val id_cpu = Bool()
  val ex_ov = Bool()
  val ex_adel = Bool()
  val ex_ades = Bool()
  val ex_tlbl = Bool()
  val ex_tlbs = Bool()
  val ex_tlbm = Bool()
  val ex_tlbrf = Bool()
  val ex_trap = Bool()
}

class cop0_info extends Bundle {
  val mtc0_en = Bool()
  val TLBP = Bool()
  val TLBR = Bool()
  val TLBWI = Bool()
  val TLBWR = Bool()
  val reg_addr = UInt(5.W)
  val sel_addr = UInt(3.W)
}

class id_ex extends Module {
  val io = IO(new Bundle {
    // 控制信号
    val clear1 = Input(Bool())
    val clear2 = Input(Bool())
    val ena1 = Input(Bool())
    val ena2 = Input(Bool())
    
    // Master通道输入
    val D_master_ctrl_sign = Input(new ctrl_sign())
    val D_master_except = Input(new except_bus())
    val D_master_cop0_info = Input(new cop0_info())
    val D_master_is_link_pc8 = Input(Bool())
    val D_master_is_in_delayslot = Input(Bool())
    val D_master_is_branch = Input(Bool())
    val D_master_pred_take = Input(Bool())
    val D_master_jump_conflict = Input(Bool())
    val D_master_branch_type = Input(UInt(4.W))
    val D_master_trap_type = Input(UInt(4.W))
    val D_master_rs = Input(UInt(5.W))
    val D_master_rt = Input(UInt(5.W))
    val D_master_reg_waddr = Input(UInt(5.W))
    val D_master_op = Input(UInt(6.W))
    val D_master_cmov_type = Input(UInt(2.W)) // CmovBus
    val D_master_pc = Input(UInt(32.W))
    val D_master_inst = Input(UInt(32.W))
    val D_master_rs_value = Input(UInt(32.W))
    val D_master_rt_value = Input(UInt(32.W))
    val D_master_imm_value = Input(UInt(32.W))
    val D_master_shamt_value = Input(UInt(32.W))
    val D_master_pc_plus4 = Input(UInt(32.W))
    val D_master_branch_target = Input(UInt(32.W))
    
    // Slave通道输入
    val D_slave_ctrl_sign = Input(new ctrl_sign())
    val D_slave_except = Input(new except_bus())
    val D_slave_cop0_info = Input(new cop0_info())
    val D_slave_is_in_delayslot = Input(Bool())
    val D_slave_rs = Input(UInt(5.W))
    val D_slave_rt = Input(UInt(5.W))
    val D_slave_reg_waddr = Input(UInt(5.W))
    val D_slave_op = Input(UInt(6.W))
    val D_slave_cmov_type = Input(UInt(2.W)) // CmovBus
    val D_slave_pc = Input(UInt(32.W))
    val D_slave_inst = Input(UInt(32.W))
    val D_slave_rs_value = Input(UInt(32.W))
    val D_slave_rt_value = Input(UInt(32.W))
    val D_slave_imm_value = Input(UInt(32.W))
    val D_slave_shamt_value = Input(UInt(32.W))
    
    // Master通道输出
    val E_master_ctrl_sign = Output(new ctrl_sign())
    val E_master_except = Output(new except_bus())
    val E_master_cop0_info = Output(new cop0_info())
    val E_master_is_link_pc8 = Output(Bool())
    val E_master_is_in_delayslot = Output(Bool())
    val E_master_is_branch = Output(Bool())
    val E_master_pred_take = Output(Bool())
    val E_master_jump_conflict = Output(Bool())
    val E_master_branch_type = Output(UInt(4.W))
    val E_master_trap_type = Output(UInt(4.W))
    val E_master_rs = Output(UInt(5.W))
    val E_master_rt = Output(UInt(5.W))
    val E_master_reg_waddr = Output(UInt(5.W))
    val E_master_op = Output(UInt(6.W))
    val E_master_cmov_type = Output(UInt(2.W))
    val E_master_pc = Output(UInt(32.W))
    val E_master_inst = Output(UInt(32.W))
    val E_master_rs_value = Output(UInt(32.W))
    val E_master_rt_value = Output(UInt(32.W))
    val E_master_imm_value = Output(UInt(32.W))
    val E_master_shamt_value = Output(UInt(32.W))
    val E_master_pc_plus4 = Output(UInt(32.W))
    val E_master_branch_target = Output(UInt(32.W))
    
    // Slave通道输出
    val E_slave_ctrl_sign = Output(new ctrl_sign())
    val E_slave_except = Output(new except_bus())
    val E_slave_cop0_info = Output(new cop0_info())
    val E_slave_is_in_delayslot = Output(Bool())
    val E_slave_rs = Output(UInt(5.W))
    val E_slave_rt = Output(UInt(5.W))
    val E_slave_reg_waddr = Output(UInt(5.W))
    val E_slave_op = Output(UInt(6.W))
    val E_slave_cmov_type = Output(UInt(2.W))
    val E_slave_pc = Output(UInt(32.W))
    val E_slave_inst = Output(UInt(32.W))
    val E_slave_rs_value = Output(UInt(32.W))
    val E_slave_rt_value = Output(UInt(32.W))
    val E_slave_imm_value = Output(UInt(32.W))
    val E_slave_shamt_value = Output(UInt(32.W))
  })
  
    // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new ctrl_sign()
    val except = new except_bus()
    val cop0_info = new cop0_info()
    val is_link_pc8 = Bool()
    val is_in_delayslot = Bool()
    val is_branch = Bool()
    val pred_take = Bool()
    val jump_conflict = Bool()
    val branch_type = UInt(4.W)
    val trap_type = UInt(4.W)
    val rs = UInt(5.W)
    val rt = UInt(5.W)
    val reg_waddr = UInt(5.W)
    val op = UInt(6.W)
    val cmov_type = UInt(2.W)
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val rs_value = UInt(32.W)
    val rt_value = UInt(32.W)
    val imm_value = UInt(32.W)
    val shamt_value = UInt(32.W)
    val pc_plus4 = UInt(32.W)
    val branch_target = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new ctrl_sign()
    val except = new except_bus()
    val cop0_info = new cop0_info()
    val is_in_delayslot = Bool()
    val rs = UInt(5.W)
    val rt = UInt(5.W)
    val reg_waddr = UInt(5.W)
    val op = UInt(6.W)
    val cmov_type = UInt(2.W)
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val rs_value = UInt(32.W)
    val rt_value = UInt(32.W)
    val imm_value = UInt(32.W)
    val shamt_value = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() | io.clear1) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena1) {
    masterReg.ctrl_sign := io.D_master_ctrl_sign
    masterReg.except := io.D_master_except
    masterReg.cop0_info := io.D_master_cop0_info
    masterReg.is_link_pc8 := io.D_master_is_link_pc8
    masterReg.is_in_delayslot := io.D_master_is_in_delayslot
    masterReg.is_branch := io.D_master_is_branch
    masterReg.pred_take := io.D_master_pred_take
    masterReg.jump_conflict := io.D_master_jump_conflict
    masterReg.branch_type := io.D_master_branch_type
    masterReg.trap_type := io.D_master_trap_type
    masterReg.rs := io.D_master_rs
    masterReg.rt := io.D_master_rt
    masterReg.reg_waddr := io.D_master_reg_waddr
    masterReg.op := io.D_master_op
    masterReg.cmov_type := io.D_master_cmov_type
    masterReg.pc := io.D_master_pc
    masterReg.inst := io.D_master_inst
    masterReg.rs_value := io.D_master_rs_value
    masterReg.rt_value := io.D_master_rt_value
    masterReg.imm_value := io.D_master_imm_value
    masterReg.shamt_value := io.D_master_shamt_value
    masterReg.pc_plus4 := io.D_master_pc_plus4
    masterReg.branch_target := io.D_master_branch_target
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() | io.clear2) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena2) {
    slaveReg.ctrl_sign := io.D_slave_ctrl_sign
    slaveReg.except := io.D_slave_except
    slaveReg.cop0_info := io.D_slave_cop0_info
    slaveReg.is_in_delayslot := io.D_slave_is_in_delayslot
    slaveReg.rs := io.D_slave_rs
    slaveReg.rt := io.D_slave_rt
    slaveReg.reg_waddr := io.D_slave_reg_waddr
    slaveReg.op := io.D_slave_op
    slaveReg.cmov_type := io.D_slave_cmov_type
    slaveReg.pc := io.D_slave_pc
    slaveReg.inst := io.D_slave_inst
    slaveReg.rs_value := io.D_slave_rs_value
    slaveReg.rt_value := io.D_slave_rt_value
    slaveReg.imm_value := io.D_slave_imm_value
    slaveReg.shamt_value := io.D_slave_shamt_value
  }

  // 连接寄存器输出到IO
  io.E_master_ctrl_sign := masterReg.ctrl_sign
  io.E_master_except := masterReg.except
  io.E_master_cop0_info := masterReg.cop0_info
  io.E_master_is_link_pc8 := masterReg.is_link_pc8
  io.E_master_is_in_delayslot := masterReg.is_in_delayslot
  io.E_master_is_branch := masterReg.is_branch
  io.E_master_pred_take := masterReg.pred_take
  io.E_master_jump_conflict := masterReg.jump_conflict
  io.E_master_branch_type := masterReg.branch_type
  io.E_master_trap_type := masterReg.trap_type
  io.E_master_rs := masterReg.rs
  io.E_master_rt := masterReg.rt
  io.E_master_reg_waddr := masterReg.reg_waddr
  io.E_master_op := masterReg.op
  io.E_master_cmov_type := masterReg.cmov_type
  io.E_master_pc := masterReg.pc
  io.E_master_inst := masterReg.inst
  io.E_master_rs_value := masterReg.rs_value
  io.E_master_rt_value := masterReg.rt_value
  io.E_master_imm_value := masterReg.imm_value
  io.E_master_shamt_value := masterReg.shamt_value
  io.E_master_pc_plus4 := masterReg.pc_plus4
  io.E_master_branch_target := masterReg.branch_target

  io.E_slave_ctrl_sign := slaveReg.ctrl_sign
  io.E_slave_except := slaveReg.except
  io.E_slave_cop0_info := slaveReg.cop0_info
  io.E_slave_is_in_delayslot := slaveReg.is_in_delayslot
  io.E_slave_rs := slaveReg.rs
  io.E_slave_rt := slaveReg.rt
  io.E_slave_reg_waddr := slaveReg.reg_waddr
  io.E_slave_op := slaveReg.op
  io.E_slave_cmov_type := slaveReg.cmov_type
  io.E_slave_pc := slaveReg.pc
  io.E_slave_inst := slaveReg.inst
  io.E_slave_rs_value := slaveReg.rs_value
  io.E_slave_rt_value := slaveReg.rt_value
  io.E_slave_imm_value := slaveReg.imm_value
  io.E_slave_shamt_value := slaveReg.shamt_value
}