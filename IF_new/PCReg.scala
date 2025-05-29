import chisel3._
import chisel3.util._

class PCRegIO extends Bundle {
  // Define constants
  val PAddrBits = 32

  // Inputs from EX stage (or higher-level control)
  val EX_mispredict_resolved       = Input(Bool())
  val EX_pc_of_mispredicted_branch = Input(UInt(PAddrBits.W))
  val EX_actual_branch_target      = Input(UInt(PAddrBits.W))
  val EX_branch_actually_taken     = Input(Bool())

  // Inputs from ID stage
  val D_bj         = Input(Bool()) // ID predicts branch taken
  val D_bj_target  = Input(UInt(PAddrBits.W)) // ID's predicted branch target

  // Feedback from InstFifo (for backpressure)
  val F_fifo_full         = Input(Bool())
  val F_fifo_almost_full  = Input(Bool())

  // Outputs of PCReg
  val pc_next = Output(UInt(PAddrBits.W)) // Next PC (combinational, to I-Mem)
  val pc_curr = Output(UInt(PAddrBits.W)) // Current PC (sequential, to InstFifo write addr)
}

class PCReg extends Module {
  // Define constants
  val PAddrBits  = 32
  val ResetPCVal = "hbfc00000".U(PAddrBits.W)
  val FetchWidth = 2 // Number of instructions fetched per cycle
  val InstrBytes = 4 // Bytes per instruction

  val io = IO(new PCRegIO)

  val pc_reg = RegInit(ResetPCVal)

  // Calculate potential next PC values
  val pc_plus_4 = pc_reg + InstrBytes.U
  val pc_plus_8 = pc_reg + (InstrBytes * FetchWidth).U

  // --- Determine pc_next (combinational logic) ---
  val next_pc_val_on_ex_mispredict = Mux(io.EX_branch_actually_taken,
                                         io.EX_actual_branch_target,
                                         io.EX_pc_of_mispredicted_branch + InstrBytes.U)

  val next_pc_val_on_d_bj = io.D_bj_target

  val next_pc_val_sequential = Mux(io.F_fifo_full,
                                   pc_reg,
                                   Mux(io.F_fifo_almost_full && FetchWidth > 1,
                                       pc_plus_4,
                                       Mux(FetchWidth > 1, pc_plus_8, pc_plus_4)
                                   )
                                )
  // Assign pc_next based on priority
  when (reset.asBool) {
    io.pc_next := ResetPCVal
  } .elsewhen(io.EX_mispredict_resolved) {
    io.pc_next := next_pc_val_on_ex_mispredict
  } .elsewhen(io.D_bj) {
    io.pc_next := next_pc_val_on_d_bj
  } .otherwise {
    io.pc_next := next_pc_val_sequential
  }

  // --- Update pc_reg (sequential logic) ---
  when(io.EX_mispredict_resolved) {
    pc_reg := next_pc_val_on_ex_mispredict
  } .elsewhen(io.D_bj) {
    pc_reg := next_pc_val_on_d_bj
  } .elsewhen(io.F_fifo_full) {
    pc_reg := pc_reg // Stall
  } .elsewhen(io.F_fifo_almost_full && FetchWidth > 1) {
    pc_reg := pc_plus_4 // Fetch one instruction
  } .elsewhen(!io.F_fifo_full && !io.F_fifo_almost_full && FetchWidth > 1) {
    pc_reg := pc_plus_8 // Fetch two instructions
  } .elsewhen(!io.F_fifo_full) { // Covers single fetch width or dual width with only space for 1 (but not almost_full)
    pc_reg := pc_plus_4 // Fetch one instruction
  } .otherwise {
    pc_reg := pc_reg // Should be covered by F_fifo_full
  }

  io.pc_curr := pc_reg
}