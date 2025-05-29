import chisel3._
import chisel3.util._

class FifoEntry extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32

  val addr  = UInt(PAddrBits.W)
  val data  = UInt(InstrBits.W)
  val valid = Bool()
}

class InstFifoIO extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32
  // val FetchWidth = 2 // Not strictly needed in IO if InstFifo defines it internally

  // Write interface from I-Mem (PCReg provides write_pc)
  val write_pc      = Input(UInt(PAddrBits.W)) // Base PC for the write
  val write_data1   = Input(UInt(InstrBits.W)) // First instruction from I-Mem
  val write_data2   = Input(UInt(InstrBits.W)) // Second instruction (if dual fetch)

  // Read interface to ID stage
  val read_en1      = Input(Bool()) // ID requests to read first instruction
  val read_en2      = Input(Bool()) // ID requests to read second instruction
  val read_data1    = Output(UInt(InstrBits.W))
  val read_address1 = Output(UInt(PAddrBits.W))
  val read_data2    = Output(UInt(InstrBits.W))
  val read_address2 = Output(UInt(PAddrBits.W))

  // Status signals
  val full         = Output(Bool())
  val almost_full  = Output(Bool())
  val empty        = Output(Bool())
  val almost_empty = Output(Bool())

  // Pipeline stall signal
  val i_stall = Input(Bool()) // Stall from ID or later stages

  // Flush command interface
  val flush_command_in             = Input(Bool())
  val flush_due_to_ID_predict_taken= Input(Bool())
  val flush_due_to_EX_mispredict   = Input(Bool())
}

class InstFifo extends Module {
  // Define constants
  val PAddrBits   = 32
  val InstrBits   = 32
  val FifoDepth   = 16 // Power of 2 for simple pointer wrap-around
  val FetchWidth  = 2  // Max instructions written/read per cycle
  val InstrBytes  = 4

  // Derived constants
  val FifoPtrBits   = log2Ceil(FifoDepth)
  val FifoCountBits = log2Ceil(FifoDepth) + 1

  val io = IO(new InstFifoIO)

  val lines     = Reg(Vec(FifoDepth, new FifoEntry))
  val write_ptr = RegInit(0.U(FifoPtrBits.W))
  val read_ptr  = RegInit(0.U(FifoPtrBits.W))
  val count     = RegInit(0.U(FifoCountBits.W))

  // --- Intermediate Wires for Next State Calculation ---
  val next_write_ptr = WireDefault(write_ptr)
  val next_read_ptr  = WireDefault(read_ptr)
  val next_count_intermediate = WireDefault(count) // Intermediate count after writes, before reads

  val lines_next_valid = Wire(Vec(FifoDepth, Bool()))
  for(i <- 0 until FifoDepth) { lines_next_valid(i) := lines(i).valid } // Default: keep current valid state

  // --- Flush Decision ---
  val actual_perform_full_flush = io.flush_command_in && io.flush_due_to_EX_mispredict
  val actual_perform_partial_flush = io.flush_command_in && io.flush_due_to_ID_predict_taken && !io.flush_due_to_EX_mispredict
  val perform_any_flush = actual_perform_full_flush || actual_perform_partial_flush

  // --- Write Logic (No explicit write_en; based on FIFO space and FetchWidth) ---
  var num_actually_written = 0.U(log2Ceil(FetchWidth + 1).W)

  // Determine how many instructions PCReg would be trying to provide based on FIFO status
  // This mirrors PCReg's decision process.
  val pcreg_attempts_fetch_two = FetchWidth > 1 && !io.full && !io.almost_full
  val pcreg_attempts_fetch_one = !io.full && ((FetchWidth > 1 && io.almost_full) || FetchWidth == 1)

  when(pcreg_attempts_fetch_two) { // PCReg provides 2 instructions
    when(count <= (FifoDepth - 2).U) { // Space for 2
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B

      lines(write_ptr + 1.U).addr := io.write_pc + InstrBytes.U
      lines(write_ptr + 1.U).data := io.write_data2
      lines_next_valid(write_ptr + 1.U) := true.B

      num_actually_written := 2.U
      next_write_ptr := write_ptr + 2.U
    } .elsewhen(count <= (FifoDepth - 1).U) { // Space for only 1, PCReg provided 2, but we take 1
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B
      num_actually_written := 1.U
      next_write_ptr := write_ptr + 1.U
    }
  } .elsewhen(pcreg_attempts_fetch_one) { // PCReg provides 1 instruction
    when(count <= (FifoDepth - 1).U) { // Space for 1
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B
      num_actually_written := 1.U
      next_write_ptr := write_ptr + 1.U
    }
  }
  next_count_intermediate := count + num_actually_written

  // --- Read Logic ---
  var num_actually_read = 0.U(log2Ceil(FetchWidth + 1).W)
  val can_read_1 = count > 0.U && lines(read_ptr).valid // Based on count *before* current cycle's writes
  val can_read_2 = if (FetchWidth > 1) (count > 1.U && lines(read_ptr + 1.U).valid && can_read_1) else false.B

  when(!io.i_stall) {
    when(io.read_en1 && can_read_1) {
      lines_next_valid(read_ptr) := false.B
      num_actually_read := 1.U
      next_read_ptr := read_ptr + 1.U

      if (FetchWidth > 1) {
        when(io.read_en2 && can_read_2) {
          lines_next_valid(read_ptr + 1.U) := false.B
          num_actually_read := 2.U
          next_read_ptr := read_ptr + 2.U
        }
      }
    }
  }
  val final_next_count = next_count_intermediate - num_actually_read


  // --- Register Update Logic ---
  when(reset.asBool) {
    write_ptr := 0.U
    read_ptr  := 0.U
    count     := 0.U
    lines.foreach(entry => entry.valid := false.B)
  } .elsewhen(perform_any_flush) {
    // 1. Clear all valid bits initially for flush
    for (i <- 0 until FifoDepth) { lines(i).valid := false.B }

    // 2. Determine pointers after flush logic
    val rptr_after_flush_decision = WireDefault(0.U)
    val wptr_after_flush_decision = WireDefault(0.U)

    when(actual_perform_partial_flush) {
      // BI was read in Cycle 0 by normal read logic.
      // `next_read_ptr` (calculated by read logic above) reflects BI being read.
      rptr_after_flush_decision := next_read_ptr
      wptr_after_flush_decision := next_read_ptr // New writes start here
    } .otherwise { // actual_perform_full_flush
      rptr_after_flush_decision := 0.U
      wptr_after_flush_decision := 0.U
    }
    read_ptr  := rptr_after_flush_decision
    // write_ptr and count will be set by new writes below

    // 3. Write new instructions in the same flush cycle
    var num_written_during_flush = 0.U(log2Ceil(FetchWidth + 1).W)
    val base_wptr_for_flush_write = wptr_after_flush_decision

    // Assumption: io.write_pc & data are from the new path
    // PCReg would have decided to fetch based on the *pre-flush* FIFO state.
    // For simplicity, we assume if PCReg provided data for a flush cycle, it's valid.
    // A more robust system might have explicit valid signals for data during flush.
    // Here, we reuse the pcreg_attempts_fetch logic for data availability.
    when(pcreg_attempts_fetch_two) {
       when(true.B) { // Always space after flush (count is 0 initially for this calc)
            lines(base_wptr_for_flush_write).addr  := io.write_pc
            lines(base_wptr_for_flush_write).data  := io.write_data1
            lines(base_wptr_for_flush_write).valid := true.B

            lines(base_wptr_for_flush_write + 1.U).addr := io.write_pc + InstrBytes.U
            lines(base_wptr_for_flush_write + 1.U).data := io.write_data2
            lines(base_wptr_for_flush_write + 1.U).valid := true.B
            num_written_during_flush := 2.U
       }
    } .elsewhen(pcreg_attempts_fetch_one) {
        when(true.B) {
            lines(base_wptr_for_flush_write).addr  := io.write_pc
            lines(base_wptr_for_flush_write).data  := io.write_data1
            lines(base_wptr_for_flush_write).valid := true.B
            num_written_during_flush := 1.U
        }
    }

    write_ptr := base_wptr_for_flush_write + num_written_during_flush
    count     := num_written_during_flush

  } .otherwise { // Normal operation
    write_ptr := next_write_ptr
    read_ptr  := next_read_ptr
    count     := final_next_count

    for (i <- 0 until FifoDepth) {
      lines(i).valid := lines_next_valid(i)
    }
    // Data/address fields of lines are updated by the write logic directly
    // e.g., lines(write_ptr).addr := io.write_pc
  }

  // --- Combinational Outputs for Read Data ---
  // Use registered read_ptr and count for outputs to avoid combinational loops with ID stage
  val out_can_read_1 = count > 0.U && lines(read_ptr).valid
  val out_can_read_2 = if (FetchWidth > 1) (count > 1.U && lines(read_ptr + 1.U).valid && out_can_read_1) else false.B

  io.read_data1    := Mux(out_can_read_1, lines(read_ptr).data, 0.U)
  io.read_address1 := Mux(out_can_read_1, lines(read_ptr).addr, 0.U)
  if (FetchWidth > 1) {
    io.read_data2    := Mux(out_can_read_2, lines(read_ptr + 1.U).data, 0.U)
    io.read_address2 := Mux(out_can_read_2, lines(read_ptr + 1.U).addr, 0.U)
  } else {
    io.read_data2    := 0.U
    io.read_address2 := 0.U
  }

  // --- Status Signals ---
  // Based on the current state of the count register
  io.empty        := count === 0.U
  io.almost_empty := count === 1.U && FetchWidth > 1 // For dual read, 1 left is almost empty
  io.full         := count === FifoDepth.U
  io.almost_full  := count === (FifoDepth - 1).U && FetchWidth > 1 // For dual write, 1 slot left is almost full
}