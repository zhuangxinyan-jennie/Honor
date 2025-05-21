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

class ex_mem extends Module {
  val io = IO(new Bundle {
    val clear1 = Input(Bool())
    val clear2 = Input(Bool())
    val ena1 = Input(Bool())
    val ena2 = Input(Bool())

    // 公共内存信号
    val E_mem_en = Input(Bool())
    val E_mem_ren = Input(Bool())
    val E_mem_wen = Input(Bool())
    val E_mem_op = Input(UInt(6.W))
    val E_mem_addr = Input(UInt(32.W))
    val E_mem_wdata = Input(UInt(32.W))
    val E_mem_va = Input(UInt(32.W))
    
    val M_mem_en = Output(Bool())
    val M_mem_ren = Output(Bool())
    val M_mem_wen = Output(Bool())
    val M_mem_op = Output(UInt(6.W))
    val M_mem_addr = Output(UInt(32.W))
    val M_mem_wdata = Output(UInt(32.W))
    val M_mem_va = Output(UInt(32.W))

    // Master通道输入
    val E_master_ctrl_sign = Input(new ctrl_sign())
    val E_master_except = Input(new except_bus())
    val E_master_reg_wen = Input(Bool())
    val E_master_mem_sel = Input(Bool())
    val E_master_is_in_delayslot = Input(Bool())
    val E_master_reg_waddr = Input(UInt(5.W))
    val E_master_inst = Input(UInt(32.W))
    val E_master_alu_res = Input(UInt(32.W))
    val E_master_pc = Input(UInt(32.W))
    val E_master_debug_cp0_count = Input(UInt(32.W))
    val E_master_debug_cp0_random = Input(UInt(32.W))
    val E_master_debug_cp0_cause = Input(UInt(32.W))
    
    // Slave通道输入
    val E_slave_ctrl_sign = Input(new ctrl_sign())
    val E_slave_except = Input(new except_bus())
    val E_slave_reg_wen = Input(Bool())
    val E_slave_mem_sel = Input(Bool())
    val E_slave_is_in_delayslot = Input(Bool())
    val E_slave_reg_waddr = Input(UInt(5.W))
    val E_slave_pc = Input(UInt(32.W))
    val E_slave_inst = Input(UInt(32.W))
    val E_slave_alu_res = Input(UInt(32.W))

    // Master通道输出
    val M_master_ctrl_sign = Output(new ctrl_sign())
    val M_master_except = Output(new except_bus())
    val M_master_reg_wen = Output(Bool())
    val M_master_mem_sel = Output(Bool())
    val M_master_is_in_delayslot = Output(Bool())
    val M_master_reg_waddr = Output(UInt(5.W))
    val M_master_inst = Output(UInt(32.W))
    val M_master_alu_res = Output(UInt(32.W))
    val M_master_pc = Output(UInt(32.W))
    val M_master_debug_cp0_count = Output(UInt(32.W))
    val M_master_debug_cp0_random = Output(UInt(32.W))
    val M_master_debug_cp0_cause = Output(UInt(32.W))
    
    // Slave通道输出
    val M_slave_ctrl_sign = Output(new ctrl_sign())
    val M_slave_except = Output(new except_bus())
    val M_slave_reg_wen = Output(Bool())
    val M_slave_mem_sel = Output(Bool())
    val M_slave_is_in_delayslot = Output(Bool())
    val M_slave_reg_waddr = Output(UInt(5.W))
    val M_slave_pc = Output(UInt(32.W))
    val M_slave_inst = Output(UInt(32.W))
    val M_slave_alu_res = Output(UInt(32.W))
  })

  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val mem_en = Bool()
    val mem_ren = Bool()
    val mem_wen = Bool()
    val mem_op = UInt(6.W)
    val mem_addr = UInt(32.W)
    val mem_wdata = UInt(32.W)
    val mem_va = UInt(32.W)
    val ctrl_sign = new ctrl_sign()
    val except = new except_bus()
    val reg_wen = Bool()
    val mem_sel = Bool()
    val is_in_delayslot = Bool()
    val reg_waddr = UInt(5.W)
    val inst = UInt(32.W)
    val alu_res = UInt(32.W)
    val pc = UInt(32.W)
    val debug_cp0_count = UInt(32.W)
    val debug_cp0_random = UInt(32.W)
    val debug_cp0_cause = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new ctrl_sign()
    val except = new except_bus()
    val reg_wen = Bool()
    val mem_sel = Bool()
    val is_in_delayslot = Bool()
    val reg_waddr = UInt(5.W)
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val alu_res = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() | io.clear1) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena1) {
    masterReg.mem_en := io.E_mem_en
    masterReg.mem_ren := io.E_mem_ren
    masterReg.mem_wen := io.E_mem_wen
    masterReg.mem_op := io.E_mem_op
    masterReg.mem_addr := io.E_mem_addr
    masterReg.mem_wdata := io.E_mem_wdata
    masterReg.mem_va := io.E_mem_va
    masterReg.ctrl_sign := io.E_master_ctrl_sign
    masterReg.except := io.E_master_except
    masterReg.reg_wen := io.E_master_reg_wen
    masterReg.mem_sel := io.E_master_mem_sel
    masterReg.is_in_delayslot := io.E_master_is_in_delayslot
    masterReg.reg_waddr := io.E_master_reg_waddr
    masterReg.inst := io.E_master_inst
    masterReg.alu_res := io.E_master_alu_res
    masterReg.pc := io.E_master_pc
    masterReg.debug_cp0_count := io.E_master_debug_cp0_count
    masterReg.debug_cp0_random := io.E_master_debug_cp0_random
    masterReg.debug_cp0_cause := io.E_master_debug_cp0_cause
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() | io.clear2) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena2) {
    slaveReg.ctrl_sign := io.E_slave_ctrl_sign
    slaveReg.except := io.E_slave_except
    slaveReg.reg_wen := io.E_slave_reg_wen
    slaveReg.mem_sel := io.E_slave_mem_sel
    slaveReg.is_in_delayslot := io.E_slave_is_in_delayslot
    slaveReg.reg_waddr := io.E_slave_reg_waddr
    slaveReg.pc := io.E_slave_pc
    slaveReg.inst := io.E_slave_inst
    slaveReg.alu_res := io.E_slave_alu_res
  }

  // 连接Master输出
  io.M_mem_en := masterReg.mem_en
  io.M_mem_ren := masterReg.mem_ren
  io.M_mem_wen := masterReg.mem_wen
  io.M_mem_op := masterReg.mem_op
  io.M_mem_addr := masterReg.mem_addr
  io.M_mem_wdata := masterReg.mem_wdata
  io.M_mem_va := masterReg.mem_va

  io.M_master_ctrl_sign := masterReg.ctrl_sign
  io.M_master_except := masterReg.except
  io.M_master_reg_wen := masterReg.reg_wen
  io.M_master_mem_sel := masterReg.mem_sel
  io.M_master_is_in_delayslot := masterReg.is_in_delayslot
  io.M_master_reg_waddr := masterReg.reg_waddr
  io.M_master_inst := masterReg.inst
  io.M_master_alu_res := masterReg.alu_res
  io.M_master_pc := masterReg.pc
  io.M_master_debug_cp0_count := masterReg.debug_cp0_count
  io.M_master_debug_cp0_random := masterReg.debug_cp0_random
  io.M_master_debug_cp0_cause := masterReg.debug_cp0_cause

  // 连接Slave输出
  io.M_slave_ctrl_sign := slaveReg.ctrl_sign
  io.M_slave_except := slaveReg.except
  io.M_slave_reg_wen := slaveReg.reg_wen
  io.M_slave_mem_sel := slaveReg.mem_sel
  io.M_slave_is_in_delayslot := slaveReg.is_in_delayslot
  io.M_slave_reg_waddr := slaveReg.reg_waddr
  io.M_slave_pc := slaveReg.pc
  io.M_slave_inst := slaveReg.inst
  io.M_slave_alu_res := slaveReg.alu_res
}