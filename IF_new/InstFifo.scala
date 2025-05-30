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
  val write_pc      = Input(UInt(PAddrBits.W)) // Base PC for the write pc_effective_out
  val write_data1   = Input(UInt(InstrBits.W)) // First instruction from I-Mem
  val write_data2   = Input(UInt(InstrBits.W)) // Second instruction (if dual fetch)

  // Read interface to ID stage
  val read_en1      = Input(Bool()) // ID requests to read first instruction，没有就置为true
  val read_en2      = Input(Bool()) // ID requests to read second instruction，与slave_ena连接
  val read_data1    = Output(UInt(InstrBits.W))
  val read_address1 = Output(UInt(PAddrBits.W))
  val read_data2    = Output(UInt(InstrBits.W))
  val read_address2 = Output(UInt(PAddrBits.W))

  // Status signals
  val full          = Output(Bool()) // 前两个是给pcreg的，后两个是给id的
  val almost_full   = Output(Bool())
  val empty         = Output(Bool())
  val almost_empty  = Output(Bool())

  // Pipeline stall signal
  val i_stall = Input(Bool()) // Stall from ID or later stages 来自CU的fifo_stall信号

  // Flush command interface
  val flush_command_in          = Input(Bool()) // CU的flush
  val flush_due_to_ID_predict_taken = Input(Bool()) // BP预测的跳转与否的结果
  val flush_due_to_EX_mispredict    = Input(Bool()) // EX判断是否BP预测错误，与CU和pcreg的输入都是一样的
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

  val lines       = Reg(Vec(FifoDepth, new FifoEntry))
  val write_ptr   = RegInit(0.U(FifoPtrBits.W))
  val read_ptr    = RegInit(0.U(FifoPtrBits.W))
  val count       = RegInit(0.U(FifoCountBits.W))

  // --- Intermediate Wires for Next State Calculation ---
  val next_write_ptr = WireDefault(write_ptr)
  val next_read_ptr  = WireDefault(read_ptr) // Defaults to current read_ptr
  val next_count_intermediate = WireDefault(count) // Intermediate count after writes, before reads

  val lines_next_valid = Wire(Vec(FifoDepth, Bool()))
  for(i <- 0 until FifoDepth) { lines_next_valid(i) := lines(i).valid } // Default: keep current valid state

  // --- Flush Decision ---
  val actual_perform_full_flush = io.flush_command_in && io.flush_due_to_EX_mispredict
  val actual_perform_partial_flush = io.flush_command_in && io.flush_due_to_ID_predict_taken && !io.flush_due_to_EX_mispredict
  val perform_any_flush = actual_perform_full_flush || actual_perform_partial_flush

  // --- Write Logic (Normal Operation - No explicit write_en; based on FIFO space and FetchWidth) ---
  // These pcreg_attempts_fetch signals are for NORMAL operation write.
  // During flush, the write logic is different.
  val pcreg_attempts_fetch_two_normal = FetchWidth > 1 && !io.full && !io.almost_full
  val pcreg_attempts_fetch_one_normal = !io.full && ((FetchWidth > 1 && io.almost_full) || FetchWidth == 1)
  var num_actually_written_normal = 0.U(log2Ceil(FetchWidth + 1).W)


  when(pcreg_attempts_fetch_two_normal) { // PCReg provides 2 instructions
    when(count <= (FifoDepth - 2).U) { // Space for 2
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B

      lines(write_ptr + 1.U).addr := io.write_pc + InstrBytes.U
      lines(write_ptr + 1.U).data := io.write_data2
      lines_next_valid(write_ptr + 1.U) := true.B

      num_actually_written_normal := 2.U
      next_write_ptr := write_ptr + 2.U
    }.elsewhen(count <= (FifoDepth - 1).U) { // Space for only 1, PCReg provided 2, but we take 1
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B
      num_actually_written_normal := 1.U
      next_write_ptr := write_ptr + 1.U
    }
  }.elsewhen(pcreg_attempts_fetch_one_normal) { // PCReg provides 1 instruction
    when(count <= (FifoDepth - 1).U) { // Space for 1
      lines(write_ptr).addr  := io.write_pc
      lines(write_ptr).data  := io.write_data1
      lines_next_valid(write_ptr) := true.B
      num_actually_written_normal := 1.U
      next_write_ptr := write_ptr + 1.U
    }
  }
  next_count_intermediate := count + num_actually_written_normal

  // --- Read Logic ---
  var num_actually_read = 0.U(log2Ceil(FetchWidth + 1).W)
  val can_read_1 = count > 0.U && lines(read_ptr).valid // Based on count *before* current cycle's writes
  val can_read_2 = if (FetchWidth > 1) (count > 1.U && lines(read_ptr + 1.U).valid && can_read_1) else false.B

  when(!io.i_stall) { // If not stalled, attempt to read
    when(io.read_en1 && can_read_1) {
      lines_next_valid(read_ptr) := false.B // This will be overridden by flush logic if flush occurs
      num_actually_read := 1.U
      next_read_ptr := read_ptr + 1.U // Advance read_ptr if read occurs

      if (FetchWidth > 1) {
        when(io.read_en2 && can_read_2) {
          lines_next_valid(read_ptr + 1.U) := false.B // Overridden by flush if flush
          num_actually_read := 2.U
          next_read_ptr := read_ptr + 2.U // Advance read_ptr further for second read
        }
      }
    }
  }
  // If io.i_stall is true, num_actually_read remains 0 and next_read_ptr remains read_ptr (due to WireDefault)
  val final_next_count = next_count_intermediate - num_actually_read


  // --- Register Update Logic ---
  when(reset.asBool) {
    write_ptr := 0.U
    read_ptr  := 0.U
    count     := 0.U
    lines.foreach(entry => entry.valid := false.B)
  }.elsewhen(perform_any_flush) {
    // 1. Clear all valid bits initially for flush
    // These direct assignments to lines(i).valid will take precedence over lines_next_valid
    // for the current cycle's update if a flush occurs.
    for (i <- 0 until FifoDepth) { lines(i).valid := false.B }

    // 2. Determine pointers after flush logic
    val rptr_after_flush_decision = WireDefault(0.U(FifoPtrBits.W))
    val wptr_after_flush_decision = WireDefault(0.U(FifoPtrBits.W))

    when(actual_perform_partial_flush) {
      // BI was read in Cycle 0 by normal read logic (if read_en was asserted).
      // `next_read_ptr` (calculated by read logic above) reflects BI being read and pointer advanced.
      // If stalled, next_read_ptr would be the current read_ptr.
      rptr_after_flush_decision := next_read_ptr
      wptr_after_flush_decision := next_read_ptr // New writes start here
    }.otherwise { // actual_perform_full_flush
      rptr_after_flush_decision := 0.U
      wptr_after_flush_decision := 0.U
    }
    read_ptr  := rptr_after_flush_decision
    // write_ptr and count will be set by new writes below

    // 3. Write new instructions in the same flush cycle
    var num_written_during_flush = 0.U(log2Ceil(FetchWidth + 1).W)
    val base_wptr_for_flush_write = wptr_after_flush_decision

    // MODIFIED LOGIC: During flush, assume space is available due to the flush itself.
    // Write up to FetchWidth instructions if provided by PCReg/I-Mem.
    // Assumes io.write_pc, io.write_data1 (and io.write_data2 if FetchWidth > 1)
    // are carrying the new, correct instructions from the redirected path.
    if (FetchWidth >= 1) {
      lines(base_wptr_for_flush_write).addr  := io.write_pc
      lines(base_wptr_for_flush_write).data  := io.write_data1
      lines(base_wptr_for_flush_write).valid := true.B
      num_written_during_flush := 1.U

      if (FetchWidth >= 2) {
        // Ensure not writing past FIFO depth if base_wptr is FifoDepth-1
        // This check is implicitly handled by circular buffer addressing if pointers are correct.
        // However, direct indexing like base_wptr + 1.U needs care if base_wptr can be FifoDepth-1
        // and FetchWidth is 2. The pointer arithmetic handles wrap-around for Regs,
        // but direct Vec access needs consideration if not careful.
        // Assuming FifoDepth is power of 2, ptr + 1.U wraps.
        lines(base_wptr_for_flush_write + 1.U).addr := io.write_pc + InstrBytes.U
        lines(base_wptr_for_flush_write + 1.U).data := io.write_data2
        lines(base_wptr_for_flush_write + 1.U).valid := true.B
        num_written_during_flush := 2.U
      }
    }
    // If FetchWidth is 0 (e.g. misconfiguration), num_written_during_flush remains 0.

    write_ptr := base_wptr_for_flush_write + num_written_during_flush
    count     := num_written_during_flush

  }.otherwise { // Normal operation (no flush)
    write_ptr := next_write_ptr // Calculated by normal write logic
    read_ptr  := next_read_ptr  // Calculated by normal read logic (will be current read_ptr if stalled)
    count     := final_next_count // Calculated by normal write/read logic

    // For normal operations, update valid bits based on lines_next_valid
    // which reflects normal reads and writes.
    for (i <- 0 until FifoDepth) {
      lines(i).valid := lines_next_valid(i)
    }
    // Data/address fields of lines are updated by the normal write logic directly
    // e.g., lines(write_ptr).addr := io.write_pc (this happens in the when(pcreg_attempts_fetch_..._normal) blocks)
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
  // Based on the current state of the count register (which reflects end-of-previous-cycle state)
  io.empty        := count === 0.U
  io.almost_empty := count === 1.U && FetchWidth > 1 // For dual read, 1 left is almost empty
  io.full         := count === FifoDepth.U
  io.almost_full  := count === (FifoDepth - 1).U && FetchWidth > 1 // For dual write, 1 slot left is almost full
}
