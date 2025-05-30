import chisel3._
import chisel3.util._

class PCRegIO extends Bundle {
  val PAddrBits = 32

  val EX_mispredict_resolved       = Input(Bool())
  val EX_pc_of_mispredicted_branch = Input(UInt(PAddrBits.W))
  val EX_actual_branch_target      = Input(UInt(PAddrBits.W))
  val EX_branch_actually_taken     = Input(Bool())

  val D_bj         = Input(Bool())
  val D_bj_target  = Input(UInt(PAddrBits.W))

  val F_fifo_full         = Input(Bool())
  val F_fifo_almost_full  = Input(Bool())

  val pc_effective_out = Output(UInt(PAddrBits.W))
}

class PCReg extends Module {
  val PAddrBits     = 32
  val ResetPCVal    = "hbfc00000".U(PAddrBits.W)
  val FetchWidthInt = 2
  val InstrBytes    = 4.U(PAddrBits.W)

  val io = IO(new PCRegIO)

  val pc_reg = RegInit(ResetPCVal)

  val actual_pc_for_current_fetch = Wire(UInt(PAddrBits.W))

  val is_ex_redirect = io.EX_mispredict_resolved
  val ex_redirect_target = Mux(io.EX_branch_actually_taken,
                               io.EX_actual_branch_target,
                               io.EX_pc_of_mispredicted_branch + InstrBytes)

  val is_id_redirect = io.D_bj && !is_ex_redirect

  when(reset.asBool) {
    actual_pc_for_current_fetch := ResetPCVal
  } .elsewhen(is_ex_redirect) {
    actual_pc_for_current_fetch := ex_redirect_target
  } .elsewhen(is_id_redirect) {
    actual_pc_for_current_fetch := io.D_bj_target
  } .otherwise {
    actual_pc_for_current_fetch := pc_reg
  }

  io.pc_effective_out := actual_pc_for_current_fetch

  val next_pc_reg_val_candidate = Wire(UInt(PAddrBits.W))

  when(io.F_fifo_full) {
    next_pc_reg_val_candidate := actual_pc_for_current_fetch
  } .elsewhen(io.F_fifo_almost_full && (FetchWidthInt > 1)) {
    next_pc_reg_val_candidate := actual_pc_for_current_fetch + InstrBytes
  } .elsewhen(FetchWidthInt > 1) {
    next_pc_reg_val_candidate := actual_pc_for_current_fetch + (InstrBytes * FetchWidthInt.U)
  } .otherwise {
    next_pc_reg_val_candidate := actual_pc_for_current_fetch + InstrBytes
  }

  when(reset.asBool) {
    pc_reg := ResetPCVal
  } .otherwise {
    pc_reg := next_pc_reg_val_candidate
  }
}
