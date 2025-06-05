import chisel3._
import chisel3.util._

class FifoEntry extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32
  val addr  = UInt(PAddrBits.W)
  val data  = UInt(InstrBits.W)
}

class InstFifoIO extends Bundle {
  val PAddrBits = 32
  val InstrBits = 32

  // Write interface from I-Mem (PCReg provides write_pc)
  val write_pc      = Input(UInt(PAddrBits.W)) // Base PC for the write pc_effective_out
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
  val full          = Output(Bool()) // 前两个是给pcreg的，后两个是给id的
  val almost_full   = Output(Bool())
  val empty         = Output(Bool())
  val almost_empty  = Output(Bool())

  // Pipeline stall signal
  val i_stall = Input(Bool()) // Stall from ID or later stages 来自CU的fifo_stall信号

  // Flush command interface
  val flush_command_in            = Input(Bool()) // CU的flush
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

  val lines         = Reg(Vec(FifoDepth, new FifoEntry))
  val lines_valid   = RegInit(VecInit(Seq.fill(FifoDepth)(false.B)))
  val write_ptr     = RegInit(0.U(FifoPtrBits.W))
  val read_ptr      = RegInit(0.U(FifoPtrBits.W))
  val count         = RegInit(0.U(FifoCountBits.W))

  val next_write_ptr_candidate   = WireDefault(write_ptr)
  val next_read_ptr_candidate    = WireDefault(read_ptr)
  val next_count_candidate       = WireDefault(count)
  val next_lines_valid_candidate = WireInit(lines_valid)

  val ex_flush_c0_triggered     = io.flush_command_in && io.flush_due_to_EX_mispredict
  val id_flush_triggered        = io.flush_command_in && io.flush_due_to_ID_predict_taken && !ex_flush_c0_triggered

  val ex_flush_c1_active            = RegNext(ex_flush_c0_triggered, false.B)
  val was_full_at_ex_flush_c0_start = RegNext(io.full && ex_flush_c0_triggered, false.B)

  val hold_normal_writes_condition = io.i_stall && (io.flush_due_to_ID_predict_taken || io.flush_due_to_EX_mispredict)

  val write_ptr_after_normal_write          = WireDefault(write_ptr)
  val count_after_normal_write_intermediate = WireDefault(count)
  val lines_valid_after_normal_write        = WireInit(lines_valid)
  var num_actually_written_normal           = 0.U(log2Ceil(FetchWidth + 1).W)

  when(!hold_normal_writes_condition) {
    // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
    val pcreg_attempts_fetch_two = (FetchWidth > 1).B && !io.full && !io.almost_full
    // Corrected: Convert Scala Boolean (FetchWidth > 1) and (FetchWidth == 1) to Chisel Bool (.B)
    val pcreg_attempts_fetch_one = !io.full && (((FetchWidth > 1).B && io.almost_full) || (FetchWidth == 1).B)

    when(pcreg_attempts_fetch_two) {
      when(count <= (FifoDepth - 2).U) {
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B

        lines(write_ptr + 1.U).addr := io.write_pc + InstrBytes.U
        lines(write_ptr + 1.U).data := io.write_data2
        lines_valid_after_normal_write(write_ptr + 1.U) := true.B

        num_actually_written_normal := 2.U
        write_ptr_after_normal_write := write_ptr + 2.U
      }.elsewhen(count === (FifoDepth - 1).U) {
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B
        num_actually_written_normal := 1.U
        write_ptr_after_normal_write := write_ptr + 1.U
      }
    }.elsewhen(pcreg_attempts_fetch_one) {
      when(count <= (FifoDepth - 1).U) {
        lines(write_ptr).addr  := io.write_pc
        lines(write_ptr).data  := io.write_data1
        lines_valid_after_normal_write(write_ptr) := true.B
        num_actually_written_normal := 1.U
        write_ptr_after_normal_write := write_ptr + 1.U
      }
    }
  }
  count_after_normal_write_intermediate := count + num_actually_written_normal

  val read_ptr_after_normal_read      = WireDefault(read_ptr)
  val count_after_normal_read_write   = WireDefault(count_after_normal_write_intermediate)
  val lines_valid_after_normal_read_write = WireInit(lines_valid_after_normal_write)
  var num_actually_read_normal        = 0.U(log2Ceil(FetchWidth + 1).W)

  val current_can_read_1 = count > 0.U && lines_valid(read_ptr)
  // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
  val current_can_read_2 = (FetchWidth > 1).B && (count > 1.U && lines_valid(read_ptr + 1.U) && current_can_read_1)


  when(!io.i_stall) {
    when(io.read_en1 && current_can_read_1) {
      lines_valid_after_normal_read_write(read_ptr) := false.B
      num_actually_read_normal := 1.U
      read_ptr_after_normal_read := read_ptr + 1.U

      // Ensure the `if` block for FetchWidth is only generated if `FetchWidth > 1`
      if (FetchWidth > 1) { // This `if` is a Scala `if`, not Chisel `when`
        when(io.read_en2 && current_can_read_2) {
          lines_valid_after_normal_read_write(read_ptr + 1.U) := false.B
          num_actually_read_normal := 2.U
          read_ptr_after_normal_read := read_ptr + 2.U
        }
      }
    }
  }
  count_after_normal_read_write := count_after_normal_write_intermediate - num_actually_read_normal

  next_write_ptr_candidate   := write_ptr_after_normal_write
  next_read_ptr_candidate    := read_ptr_after_normal_read
  next_count_candidate       := count_after_normal_read_write
  next_lines_valid_candidate := lines_valid_after_normal_read_write

  when(reset.asBool) {
    write_ptr := 0.U
    read_ptr  := 0.U
    count     := 0.U
    lines_valid.foreach(_ := false.B)
  }.elsewhen(ex_flush_c1_active) {
    val temp_next_lines_valid = WireInit(lines_valid_after_normal_read_write)

    when(was_full_at_ex_flush_c0_start) {
      temp_next_lines_valid.foreach(_ := false.B)
      next_count_candidate   := 0.U
      next_write_ptr_candidate := next_read_ptr_candidate
    }.otherwise {
      for (i <- 0 until FifoDepth) {
        // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
        val is_newly_written_in_c0 = (i.U === 0.U && count > 0.U) || ((i.U === 1.U).B && (count > 1.U).B && (FetchWidth > 1).B)
        when(!is_newly_written_in_c0) {
          temp_next_lines_valid(i) := false.B
        }
      }
    }
    next_lines_valid_candidate := temp_next_lines_valid
  }.elsewhen(ex_flush_c0_triggered) {
    val rptr_target = 0.U
    val wptr_base   = 0.U
    var num_written_this_flush = 0.U(log2Ceil(FetchWidth + 1).W)

    next_lines_valid_candidate := lines_valid

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
  }.elsewhen(id_flush_triggered) {
    next_lines_valid_candidate.foreach(_ := false.B)

    val rptr_target = 0.U
    val wptr_base   = 0.U
    var num_written_this_flush = 0.U(log2Ceil(FetchWidth + 1).W)

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
  }.otherwise {
    // No specific updates in this block, `next_*_candidate` signals retain their default values.
  }

  write_ptr   := next_write_ptr_candidate
  read_ptr    := next_read_ptr_candidate
  count       := next_count_candidate
  lines_valid := next_lines_valid_candidate

  // Corrected: Ensure comparisons result in Chisel Bool if used in Chisel operations
  val out_can_read_1_normal = count > 0.U && lines_valid(read_ptr) && io.read_en1
  // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
  val out_can_read_2_normal = (FetchWidth > 1).B && (count > 1.U && lines_valid(read_ptr + 1.U) && out_can_read_1_normal && io.read_en2)

  val suppress_read_due_to_ex_flush_c0 = ex_flush_c0_triggered

  val final_out_can_read_1 = out_can_read_1_normal && !suppress_read_due_to_ex_flush_c0
  val final_out_can_read_2 = out_can_read_2_normal && !suppress_read_due_to_ex_flush_c0

  io.read_data1    := Mux(final_out_can_read_1, lines(read_ptr).data, 0.U)
  io.read_address1 := Mux(final_out_can_read_1, lines(read_ptr).addr, 0.U)

  // This `if` block is a Scala `if`, so FetchWidth > 1 evaluates to a Scala Boolean, which is fine here.
  if (FetchWidth > 1) {
    io.read_data2    := Mux(final_out_can_read_2, lines(read_ptr + 1.U).data, 0.U)
    io.read_address2 := Mux(final_out_can_read_2, lines(read_ptr + 1.U).addr, 0.U)
  } else {
    io.read_data2    := 0.U
    io.read_address2 := 0.U
  }

  io.empty        := count === 0.U
  // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
  io.almost_empty := (count === 1.U) && (FetchWidth > 1).B
  io.full         := count === FifoDepth.U
  // Corrected: Convert Scala Boolean (FetchWidth > 1) to Chisel Bool (.B)
  io.almost_full  := (count === (FifoDepth - 1).U) && (FetchWidth > 1).B
}
