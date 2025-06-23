package mycpu
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

  val write_pc    = Input(UInt(PAddrBits.W))
  val write_data1 = Input(UInt(InstrBits.W))
  val write_data2 = Input(UInt(InstrBits.W))
  val write_en      = Input(Bool())

  val read_en1      = Input(Bool())
  val read_en2      = Input(Bool())
  val read_data1    = Output(UInt(InstrBits.W))
  val read_address1 = Output(UInt(PAddrBits.W))
  val read_data2    = Output(UInt(InstrBits.W))
  val read_address2 = Output(UInt(PAddrBits.W))

  val full          = Output(Bool())
  val almost_full   = Output(Bool())
  val empty         = Output(Bool())
  val almost_empty  = Output(Bool())

  val i_stall = Input(Bool())

  val flush_command_in            = Input(Bool())
  val flush_due_to_ID_predict_taken = Input(Bool())
  val flush_due_to_EX_mispredict    = Input(Bool())

  val fifo_is_settling_flush = Output(Bool())

  val ready_for_mem = Output(Bool())
}


class InstFifo extends Module {
  val PAddrBits   = 32
  val InstrBits   = 32
  val FifoDepth   = 16
  val FetchWidth  = 2
  val InstrBytes  = 4
  val NOP         = "h00000013".U(32.W) // 定义 RISC-V 的 NOP (addi x0, x0, 0) 指令

  val FifoPtrBits   = log2Ceil(FifoDepth)
  val FifoCountBits = log2Ceil(FifoDepth) + 1

  val io = IO(new InstFifoIO)

  // --- 状态寄存器 ---
  val lines       = Reg(Vec(FifoDepth, new FifoEntry))
  val lines_valid = RegInit(VecInit(Seq.fill(FifoDepth)(false.B)))
  val write_ptr   = RegInit(0.U(FifoPtrBits.W))
  val read_ptr    = RegInit(0.U(FifoPtrBits.W))
  val count       = RegInit(0.U(FifoCountBits.W))
  
  // --- 读写决策逻辑 (组合逻辑) ---
  val is_full       = count === FifoDepth.U
  val will_write    = WireDefault(false.B)
  val will_read     = WireDefault(false.B)
  val num_to_write  = WireDefault(0.U(2.W))
  val num_to_read   = WireDefault(0.U(2.W))

  // 写入条件: 上游valid有效, 且本模块ready (没有stall且没有满)
  when(io.write_en && io.ready_for_mem) {
    val can_write_one  = count < FifoDepth.U
    val can_write_two  = count < (FifoDepth - 1).U

    when((FetchWidth > 1).B && can_write_two) {
      will_write := true.B
      num_to_write := 2.U
    } .elsewhen(can_write_one) {
      will_write := true.B
      num_to_write := 1.U
    }
  }

  // 读取条件: 下游允许读取, 且本模块没有stall且没有空
  when(io.read_en1 && !io.i_stall && (count > 0.U)) {
    will_read := true.B
    num_to_read := 1.U
    if (FetchWidth > 1) {
      when(io.read_en2 && (count > 1.U)) {
        num_to_read := 2.U
      }
    }
  }
  
  // =============================================================================
  // == 核心状态机：统一管理所有状态更新
  // =============================================================================

  val is_flushing = io.flush_command_in

  // 最高优先级：冲刷或复位
  when(is_flushing || reset.asBool) {
    write_ptr   := 0.U
    read_ptr    := 0.U
    count       := 0.U
    lines_valid.foreach(_ := false.B)
  }
  // 正常操作
  .otherwise {
    // 指针和计数器更新
    when(will_write) { write_ptr := write_ptr + num_to_write }
    when(will_read)  { read_ptr  := read_ptr + num_to_read }
    when(will_write || will_read) { count := count + num_to_write - num_to_read }

    // 数据和 valid 位更新
    when(will_read) {
      lines_valid(read_ptr) := false.B
      if (FetchWidth > 1) {
        when(num_to_read === 2.U) {
          lines_valid(read_ptr + 1.U) := false.B
        }
      }
    }
    
    when(will_write) {
      lines(write_ptr).data := io.write_data1
      lines(write_ptr).addr := io.write_pc
      lines_valid(write_ptr) := true.B
      if (FetchWidth > 1) {
        when(num_to_write === 2.U) {
          lines(write_ptr + 1.U).data := io.write_data2
          lines(write_ptr + 1.U).addr := io.write_pc + InstrBytes.U
          lines_valid(write_ptr + 1.U) := true.B
        }
      }
    }
  }

  // =============================================================================
  // == 输出逻辑
  // =============================================================================
  
  io.full         := is_full
  io.almost_full  := count >= (FifoDepth - 1).U
  io.empty        := count === 0.U
  io.almost_empty := count <= 1.U

  // --- MODIFIED ---: 当读指针指向的条目无效时，输出 NOP 指令
  val is_entry1_valid = lines_valid(read_ptr)
  io.read_data1    := Mux(is_entry1_valid, lines(read_ptr).data, NOP)
  io.read_address1 := Mux(is_entry1_valid, lines(read_ptr).addr, 0.U)

  if (FetchWidth > 1) {
    val is_entry2_valid = lines_valid(read_ptr + 1.U)
    io.read_data2    := Mux(is_entry2_valid, lines(read_ptr + 1.U).data, NOP)
    io.read_address2 := Mux(is_entry2_valid, lines(read_ptr + 1.U).addr, 0.U)
  } else {
    io.read_data2    := NOP
    io.read_address2 := 0.U
  }

  io.fifo_is_settling_flush := false.B

  // --- NEW ---: 驱动 ready 信号。当 FIFO 没有被下游暂停 且 没有满时，可以接收新数据
  io.ready_for_mem := !io.i_stall && !is_full
}