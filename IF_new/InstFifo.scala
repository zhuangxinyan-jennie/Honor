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
  val PAddrBits   = 32
  val InstrBits   = 32
  val FifoDepth   = 16
  val FetchWidth  = 2
  val InstrBytes  = 4

  val FifoPtrBits   = log2Ceil(FifoDepth)
  val FifoCountBits = log2Ceil(FifoDepth) + 1

  val io = IO(new InstFifoIO)

  val lines       = Reg(Vec(FifoDepth, new FifoEntry))
  val write_ptr   = RegInit(0.U(FifoPtrBits.W))
  val read_ptr    = RegInit(0.U(FifoPtrBits.W))
  val count       = RegInit(0.U(FifoCountBits.W))

  // --- Intermediate Wires for Next State Calculation ---
  val next_write_ptr = WireDefault(write_ptr)
  val next_read_ptr  = WireDefault(read_ptr)
  val next_count_intermediate = WireDefault(count)

  val lines_next_valid = Wire(Vec(FifoDepth, Bool()))
  for(i <- 0 until FifoDepth) { lines_next_valid(i) := lines(i).valid }

  // --- Flush Decision ---
  val actual_perform_full_flush = io.flush_command_in && io.flush_due_to_EX_mispredict
  val actual_perform_partial_flush = io.flush_command_in && io.flush_due_to_ID_predict_taken && !io.flush_due_to_EX_mispredict
  val perform_any_flush = actual_perform_full_flush || actual_perform_partial_flush

  // --- Condition to hold write pointer during normal operations (as per user request) ---
  // Hold normal writes if stalled AND a flush condition (ID predict taken OR EX mispredict) is active.
  val hold_normal_writes_condition = io.i_stall && (io.flush_due_to_ID_predict_taken || io.flush_due_to_EX_mispredict)

  // --- Write Logic (Normal Operation) ---
  val pcreg_attempts_fetch_two_normal = FetchWidth > 1 && !io.full && !io.almost_full
  val pcreg_attempts_fetch_one_normal = !io.full && ((FetchWidth > 1 && io.almost_full) || FetchWidth == 1)
  var num_actually_written_normal = 0.U(log2Ceil(FetchWidth + 1).W)

  // This entire block for normal writes is conditional now
  when(!hold_normal_writes_condition) {
    when(pcreg_attempts_fetch_two_normal) {
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
  }
  // If hold_normal_writes_condition is true:
  // - num_actually_written_normal remains 0 (its initialization).
  // - next_write_ptr remains write_ptr (from WireDefault).
  // - lines(X).addr/data assignments above are skipped, so those Regs will hold their values.
  // - lines_next_valid(X) are not set to true.B by this block.

  next_count_intermediate := count + num_actually_written_normal

  // --- Read Logic ---
  var num_actually_read = 0.U(log2Ceil(FetchWidth + 1).W)
  val can_read_1 = count > 0.U && lines(read_ptr).valid
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
  }.elsewhen(perform_any_flush) {
    for (i <- 0 until FifoDepth) { lines(i).valid := false.B }

    val rptr_after_flush_decision = WireDefault(0.U(FifoPtrBits.W))
    val wptr_after_flush_decision = WireDefault(0.U(FifoPtrBits.W))

    when(actual_perform_partial_flush) {
      rptr_after_flush_decision := next_read_ptr // next_read_ptr already reflects stall if active
      wptr_after_flush_decision := next_read_ptr
    }.otherwise { // actual_perform_full_flush
      rptr_after_flush_decision := 0.U
      wptr_after_flush_decision := 0.U
    }
    read_ptr  := rptr_after_flush_decision

    var num_written_during_flush = 0.U(log2Ceil(FetchWidth + 1).W)
    val base_wptr_for_flush_write = wptr_after_flush_decision

    if (FetchWidth >= 1) {
      lines(base_wptr_for_flush_write).addr  := io.write_pc
      lines(base_wptr_for_flush_write).data  := io.write_data1
      lines(base_wptr_for_flush_write).valid := true.B
      num_written_during_flush := 1.U

      if (FetchWidth >= 2) {
        lines(base_wptr_for_flush_write + 1.U).addr := io.write_pc + InstrBytes.U
        lines(base_wptr_for_flush_write + 1.U).data := io.write_data2
        lines(base_wptr_for_flush_write + 1.U).valid := true.B
        num_written_during_flush := 2.U
      }
    }
    write_ptr := base_wptr_for_flush_write + num_written_during_flush
    count     := num_written_during_flush

  }.otherwise { // Normal operation (no flush)
    write_ptr := next_write_ptr // Will hold if hold_normal_writes_condition was true
    read_ptr  := next_read_ptr  // Will hold if io.i_stall was true
    count     := final_next_count

    // The lines(X).addr and lines(X).data registers hold their values if the
    // assignments within the when(!hold_normal_writes_condition) block were skipped.
    // Update valid bits based on lines_next_valid, which correctly reflects
    // defaults, normal writes (if not held), and normal reads.
    for (i <- 0 until FifoDepth) {
      lines(i).valid := lines_next_valid(i)
    }
  }

  // --- Combinational Outputs for Read Data ---
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
  io.empty        := count === 0.U
  io.almost_empty := count === 1.U && FetchWidth > 1
  io.full         := count === FifoDepth.U
  io.almost_full  := count === (FifoDepth - 1).U && FetchWidth > 1
}
