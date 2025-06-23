// --- 文件: PCReg.scala (最终版) ---

package mycpu
import chisel3._
import chisel3.util._

class PCRegIO extends Bundle {
  val PAddrBits = 32

  val EX_mispredict_resolved       = Input(Bool())
  val EX_pc_of_mispredicted_branch = Input(UInt(PAddrBits.W))
  val EX_actual_branch_target      = Input(UInt(PAddrBits.W))
  val EX_branch_actually_taken     = Input(Bool())
  val E_master_is_BJ               = Input(Bool())

  val D_bj        = Input(Bool())
  val D_bj_target = Input(UInt(PAddrBits.W))

  val F_fifo_full          = Input(Bool())
  val F_fifo_almost_full   = Input(Bool())
  val F_fifo_is_settling_flush = Input(Bool())
  val F_stall_by_ID        = Input(Bool())

  val mem_is_ready         = Input(Bool())

  val pc_effective_out = Output(UInt(PAddrBits.W))
  val req_valid        = Output(Bool()) // --- NEW ---: 明确输出PC的请求是否有效
}

class PCReg extends Module {
  val PAddrBits   = 32
  val ResetPCVal    = "h00000000".U(PAddrBits.W)
  val FetchWidthInt = 2
  val InstrBytes    = 4.U(PAddrBits.W)

  val io = IO(new PCRegIO)

  val pc_reg = RegInit(ResetPCVal)

  // --- 1. 确定本周期的PC输出 (组合逻辑) ---
  val is_ex_redirect = io.EX_mispredict_resolved && io.E_master_is_BJ
  val ex_redirect_target = Mux(io.EX_branch_actually_taken,
                                 io.EX_actual_branch_target,
                                 io.EX_pc_of_mispredicted_branch + InstrBytes)
  val is_id_redirect = io.D_bj && !is_ex_redirect

  val actual_pc_for_current_fetch = Mux(is_ex_redirect, ex_redirect_target,
                                      Mux(is_id_redirect, io.D_bj_target,
                                          pc_reg))
  io.pc_effective_out := actual_pc_for_current_fetch

  // --- 2. 确定PC是否应该暂停，并输出请求有效信号 ---
  val stall_condition = io.F_fifo_full || io.F_stall_by_ID || !io.mem_is_ready
  
  // 仅当没有暂停且没有复位时，PC发出的请求才是有效的
  io.req_valid := !stall_condition && !reset.asBool

  // --- 3. 确定下一周期的PC寄存器状态 (时序逻辑) ---
  val can_advance_sequentially = !stall_condition && !is_ex_redirect && !is_id_redirect

  val pc_increment = Mux(io.F_fifo_almost_full && (FetchWidthInt > 1).B,
                         InstrBytes,
                         (InstrBytes * FetchWidthInt.U))
  val next_sequential_pc = pc_reg + pc_increment
  
  val next_pc_state = Mux(is_ex_redirect, ex_redirect_target,
                        Mux(is_id_redirect, io.D_bj_target,
                            Mux(can_advance_sequentially, next_sequential_pc,
                                pc_reg))) // 若不能前进则保持

  pc_reg := next_pc_state
}