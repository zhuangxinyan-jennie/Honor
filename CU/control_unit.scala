import chisel3._
import chisel3.util._

class ControlUnit extends Module {
  val io = IO(new Bundle {
    // Input signals
    val D_master_read_rs   = Input(Bool())
    val D_master_read_rt   = Input(Bool())
    val D_master_rs        = Input(UInt(5.W))
    val D_master_rt        = Input(UInt(5.W))
    val E_master_memtoReg  = Input(Bool())
    val E_master_reg_waddr = Input(UInt(5.W))
    val E_slave_memtoReg   = Input(Bool())
    val E_slave_reg_waddr  = Input(UInt(5.W))
    val E_alu_stall        = Input(Bool())
    val d_stall            = Input(Bool())
    val M_flush_all        = Input(Bool())
    val E_bj               = Input(Bool())

    // Output signals
    val F_ena              = Output(Bool())
    val D_ena              = Output(Bool())
    val E_ena              = Output(Bool())
    val M_ena              = Output(Bool())
    val W_ena              = Output(Bool())
    val F_flush            = Output(Bool())
    val D_flush            = Output(Bool())
    val E_flush            = Output(Bool())
    val M_flush            = Output(Bool())
    val W_flush            = Output(Bool())
    val delay_slot_flush   = Output(Bool())
  })

  // 检测Load-Use冒险 (lwstall)
  val loadUseHazard = Wire(Bool())
  loadUseHazard := (io.E_master_memtoReg && (io.E_master_reg_waddr.orR) &&
    ((io.D_master_read_rs && (io.D_master_rs === io.E_master_reg_waddr)) ||
     (io.D_master_read_rt && (io.D_master_rt === io.E_master_reg_waddr)))) ||
    (io.E_slave_memtoReg && (io.E_slave_reg_waddr.orR) &&
    ((io.D_master_read_rs && (io.D_master_rs === io.E_slave_reg_waddr)) ||
     (io.D_master_read_rt && (io.D_master_rt === io.E_slave_reg_waddr))))

  // 控制冒险信号 (来自分支预测错误)
  val branchHazard = io.E_bj

  // 最长停顿信号 (longest_stall)
  val longestStall = io.E_alu_stall || io.d_stall

  // 异常和全局冲刷信号
  val flushAll = io.M_flush_all

  io.F_ena := true.B
  io.D_ena := !(loadUseHazard || longestStall)
  io.E_ena := !longestStall
  io.M_ena := !longestStall
  io.W_ena := (!longestStall) || flushAll

  io.F_flush := false.B
  io.D_flush := flushAll || branchHazard
  io.E_flush := flushAll || branchHazard
  io.M_flush := flushAll
  io.W_flush := false.B

  io.delay_slot_flush := flushAll
}

