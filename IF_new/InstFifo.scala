import chisel3._
import chisel3.util._

class FifoEntry extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32
  val addr  = UInt(PAddrBits.W)
  val data  = UInt(InstrBits.W)
  // val valid = Bool() // valid is now a separate Reg Vec
}

class InstFifoIO extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32

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
  val PAddrBits   = 32
  val InstrBits   = 32
  val FifoDepth   = 16
  val FetchWidth  = 2 // Assuming FetchWidth is 2 as in original code for almost_full/empty
  val InstrBytes  = 4

  val FifoPtrBits   = log2Ceil(FifoDepth)
  val FifoCountBits = log2Ceil(FifoDepth) + 1

  val io = IO(new InstFifoIO)

  val lines         = Reg(Vec(FifoDepth, new FifoEntry)) // Data and Addr
  val lines_valid   = RegInit(VecInit(Seq.fill(FifoDepth)(false.B))) // Separate Valid Regs
  val write_ptr     = RegInit(0.U(FifoPtrBits.W))
  val read_ptr      = RegInit(0.U(FifoPtrBits.W))
  val count         = RegInit(0.U(FifoCountBits.W))

  // --- Intermediate Wires for Next State Calculation ---
  val next_write_ptr_candidate = WireDefault(write_ptr)
  val next_read_ptr_candidate  = WireDefault(read_ptr)
  val next_count_candidate     = WireDefault(count)
  val next_lines_valid_candidate = WireInit(lines_valid) // Start with current valid state

  // --- Flush Decision ---
  val ex_flush_c0_triggered   = io.flush_command_in && io.flush_due_to_EX_mispredict
  val id_flush_triggered      = io.flush_command_in && io.flush_due_to_ID_predict_taken &&!ex_flush_c0_triggered
  // val perform_any_flush_c0    = ex_flush_c0_triggered |
| id_flush_triggered // Not directly used in this structure

  val ex_flush_c1_active            = RegNext(ex_flush_c0_triggered, false.B)
  val was_full_at_ex_flush_c0_start = RegNext(io.full && ex_flush_c0_triggered, false.B) // For bubble logic in C1 of EX flush

  // --- Condition to hold write pointer during normal operations (prevents PCReg's normal stream) ---
  val hold_normal_writes_condition = io.i_stall && (io.flush_due_to_ID_predict_taken |
| io.flush_due_to_EX_mispredict)

  // --- Calculate effects of NORMAL Write operations ---
  val write_ptr_after_normal_write = WireDefault(write_ptr)
  val count_after_normal_write_intermediate = WireDefault(count) // count after write, before read
  val lines_valid_after_normal_write = WireInit(lines_valid)    // valid after write, before read
  var num_actually_written_normal = 0.U(log2Ceil(FetchWidth + 1).W)

  when(!hold_normal_writes_condition) {
    val pcreg_attempts_fetch_two = FetchWidth > 1 &&!io.full &&!io.almost_full // Based on current full/almost_full
    val pcreg_attempts_fetch_one =!io.full && ((FetchWidth > 1 && io.almost_full) |
| FetchWidth == 1)

    when(pcreg_attempts_fetch_two) { // PCReg wants to write 2
      when(count <= (FifoDepth - 2).U) { // Enough space for 2
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B

        lines(write_ptr + 1.U).addr := io.write_pc + InstrBytes.U
        lines(write_ptr + 1.U).data := io.write_data2
        lines_valid_after_normal_write(write_ptr + 1.U) := true.B

        num_actually_written_normal := 2.U
        write_ptr_after_normal_write := write_ptr + 2.U
      }.elsewhen(count === (FifoDepth - 1).U) { // Enough space for 1, PCReg wanted 2
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B
        num_actually_written_normal := 1.U
        write_ptr_after_normal_write := write_ptr + 1.U
      } // else, not enough space for even 1, num_actually_written_normal remains 0
    }.elsewhen(pcreg_attempts_fetch_one) { // PCReg wants to write 1
      when(count <= (FifoDepth - 1).U) { // Enough space for 1
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B
        num_actually_written_normal := 1.U
        write_ptr_after_normal_write := write_ptr + 1.U
      } // else, not enough space, num_actually_written_normal remains 0
    }
  }
  count_after_normal_write_intermediate := count + num_actually_written_normal

  // --- Calculate effects of NORMAL Read operations ---
  val read_ptr_after_normal_read = WireDefault(read_ptr)
  val count_after_normal_read_write = WireDefault(count_after_normal_write_intermediate)
  val lines_valid_after_normal_read_write = WireInit(lines_valid_after_normal_write)
  var num_actually_read_normal = 0.U(log2Ceil(FetchWidth + 1).W)

  val current_can_read_1 = count > 0.U && lines_valid(read_ptr)
  val current_can_read_2 = if (FetchWidth > 1) (count > 1.U && lines_valid(read_ptr + 1.U) && current_can_read_1) else false.B

  when(!io.i_stall) {
    when(io.read_en1 && current_can_read_1) {
      lines_valid_after_normal_read_write(read_ptr) := false.B
      num_actually_read_normal := 1.U
      read_ptr_after_normal_read := read_ptr + 1.U

      if (FetchWidth > 1) {
        when(io.read_en2 && current_can_read_2) {
          lines_valid_after_normal_read_write(read_ptr + 1.U) := false.B
          num_actually_read_normal := 2.U
          read_ptr_after_normal_read := read_ptr + 2.U
        }
      }
    }
  }
  count_after_normal_read_write := count_after_normal_write_intermediate - num_actually_read_normal

  // --- Default next state candidates are from normal operations ---
  next_write_ptr_candidate := write_ptr_after_normal_write
  next_read_ptr_candidate  := read_ptr_after_normal_read
  next_count_candidate     := count_after_normal_read_write
  next_lines_valid_candidate := lines_valid_after_normal_read_write


  // --- Register Update Logic ---
  when(reset.asBool) {
    write_ptr := 0.U
    read_ptr  := 0.U
    count     := 0.U
    lines_valid.foreach(_ := false.B)
  }.elsewhen(ex_flush_c1_active) { // CYCLE 1 of EX Mispredict Flush
    // This is Cycle 1. State of (write_ptr, read_ptr, count, lines_valid) is from C0 EX flush.
    // Normal read/write can occur on the data potentially written in C0.
    // Default candidates are already calculated based on this C0 state.
    // Apply C1 flush specific invalidations.

    val temp_next_lines_valid = WireInit(lines_valid_after_normal_read_write) // Start with normal C1 op effects

    when(was_full_at_ex_flush_c0_start) { // FIFO was full at C0 EX flush start -> Bubble in C1
      temp_next_lines_valid.foreach(_ := false.B)
      next_count_candidate     := 0.U
      // Pointers can be left as per normal ops on an empty FIFO or reset.
      // If count is 0, read_ptr and write_ptr don't matter as much for emptiness.
      // Let normal read/write determine pointers if they operate on a now-empty FIFO.
      // If normal read/write logic assumes count > 0 for pointer updates, this might need care.
      // For simplicity, if bubble, count is 0. Pointers might be 0 or as per normal ops.
      // Let's ensure pointers are consistent with an empty FIFO if bubble.
      next_read_ptr_candidate  := read_ptr // Or 0.U. Current read_ptr is 0 from C0.
      next_write_ptr_candidate := write_ptr // Or 0.U. Current write_ptr is num_new_c0 from C0.
                                          // If count is 0, wptr should ideally equal rptr.
                                          // If bubble makes count 0, let's make wptr = rptr (which is 0)
      next_write_ptr_candidate := next_read_ptr_candidate // To be consistent with count = 0
    }.otherwise { // Normal C1 of EX flush (FIFO was not full at C0 start)
      // Invalidate entries that were NOT part of the C0 flush write.
      // C0 write was at physical  and [1] if two. Current read_ptr is 0. Current count is num_written_in_C0.
      for (i <- 0 until FifoDepth) {
        val is_newly_written_in_c0 = (i.U === 0.U && count > 0.U) |
| (i.U === 1.U && count > 1.U)
        when(!is_newly_written_in_c0) {
          temp_next_lines_valid(i) := false.B
        }
        // If is_newly_written_in_c0, its validity is already set by lines_valid_after_normal_read_write
      }
    }
    next_lines_valid_candidate := temp_next_lines_valid
    // next_write_ptr_candidate, next_read_ptr_candidate, next_count_candidate already set by normal ops or bubble logic.

  }.elsewhen(ex_flush_c0_triggered) { // CYCLE 0 of EX Mispredict Flush
    // Old entries' valid bits remain as they are for this cycle (propagate from current lines_valid).
    // New instructions written to the beginning.
    val rptr_target = 0.U
    val wptr_base   = 0.U
    var num_written_this_flush = 0.U(log2Ceil(FetchWidth + 1).W)

    // Initialize next_lines_valid with current valid state (old entries remain valid for C0)
    next_lines_valid_candidate := lines_valid

    // Write new instructions (these are not affected by hold_normal_writes_condition)
    if (FetchWidth >= 1) {
      lines(wptr_base).addr  := io.write_pc
      lines(wptr_base).data  := io.write_data1
      next_lines_valid_candidate(wptr_base) := true.B // New instruction is valid
      num_written_this_flush := 1.U

      if (FetchWidth >= 2) {
        lines(wptr_base + 1.U).addr := io.write_pc + InstrBytes.U
        lines(wptr_base + 1.U).data := io.write_data2
        next_lines_valid_candidate(wptr_base + 1.U) := true.B // New instruction is valid
        num_written_this_flush := 2.U
      }
    }

    next_write_ptr_candidate := wptr_base + num_written_this_flush
    next_read_ptr_candidate  := rptr_target
    next_count_candidate     := num_written_this_flush
    // next_lines_valid_candidate has new entries marked valid, old entries retain current validity for C0.

  }.elsewhen(id_flush_triggered) { // ID Predict Taken Flush (Partial Flush) - Single Cycle
    // New instructions to physical head (0). Others invalid this cycle.
    next_lines_valid_candidate.foreach(_ := false.B) // Invalidate all first

    val rptr_target = 0.U
    val wptr_base   = 0.U
    var num_written_this_flush = 0.U(log2Ceil(FetchWidth + 1).W)

    // Write new instructions (not affected by hold_normal_writes_condition)
    if (FetchWidth >= 1) {
      lines(wptr_base).addr  := io.write_pc
      lines(wptr_base).data  := io.write_data1
      next_lines_valid_candidate(wptr_base) := true.B
      num_written_this_flush := 1.U

      if (FetchWidth >= 2) {
        lines(wptr_base + 1.U).addr := io.write_pc + InstrBytes.U
        lines(wptr_base + 1.U).data := io.write_data2
        next_lines_valid_candidate(wptr_base + 1.U) := true.B
        num_written_this_flush := 2.U
      }
    }

    next_write_ptr_candidate := wptr_base + num_written_this_flush
    next_read_ptr_candidate  := rptr_target
    next_count_candidate     := num_written_this_flush
    // next_lines_valid_candidate has new entries valid, others false for this cycle.

  }.otherwise { // Normal operation (no reset, no flush C0, no flush C1 active)
    // Candidates are already set from normal read/write logic at the top.
    // No changes needed here for next_write_ptr_candidate etc.
  }

  // --- Update Registers from Candidates ---
  write_ptr   := next_write_ptr_candidate
  read_ptr    := next_read_ptr_candidate
  count       := next_count_candidate
  lines_valid := next_lines_valid_candidate


  // --- Combinational Outputs for Read Data ---
  // Use current state (Regs) for outputs
  val out_can_read_1 = count > 0.U && lines_valid(read_ptr)
  val out_can_read_2 = if (FetchWidth > 1) (count > 1.U && lines_valid(read_ptr + 1.U) && out_can_read_1) else false.B

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
  // Use current state (Regs) for outputs
  io.empty        := count === 0.U
  io.almost_empty := count === 1.U && FetchWidth > 1
  io.full         := count === FifoDepth.U
  io.almost_full  := count === (FifoDepth - 1).U && FetchWidth > 1
}
