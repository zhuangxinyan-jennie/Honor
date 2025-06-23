// --- 文件: mem.scala (最终·修改版) ---

package mycpu

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import firrtl.annotations.MemoryLoadFileType

class if_mem extends Bundle {
  val instAddr = Output(UInt(64.W))
  val valid    = Output(Bool())
  val flush    = Output(Bool())
  val ready_for_pc = Input(Bool())
}

class mem_id(val instWidth: Int) extends Bundle {
  val inst  = Output(Vec(instWidth, UInt(32.W)))
  val valid = Output(Bool())
  val pc    = Output(UInt(64.W))
  val ready = Input(Bool())
}

// =============================================================================
// == 接口修改: 添加 is_load 信号
// =============================================================================
class lsu_mem_c extends Bundle {
  val atomFlag  = Output(Bool())
  val dataAddr  = Output(UInt(64.W))
  val writeEn   = Output(Bool())
  val writeData = Output(UInt(64.W))
  val func3     = Output(UInt(3.W))
  val is_load   = Output(Bool()) // << --- [关键修改] 新增信号，用于明确指示加载操作
}

class mem_lsu_c extends Bundle {
  val data = Output(UInt(64.W))
}


class mem(memDepth: Int, instWidth: Int) extends Module {
  val io = IO(new Bundle {
    val if_mem  = Flipped(new if_mem())
    val ex_mem  = Flipped(new lsu_mem_c)
    val mem_id  = new mem_id(instWidth)
    val mem_lsu = new mem_lsu_c
  })

  val memInside = SyncReadMem(memDepth, Vec(4, UInt(8.W)))


  // =============================================================================
  val debugData = Wire(Vec(4, UInt(8.W)))
  debugData := memInside.read(0x8001.U)
  dontTouch(debugData)
  // =============================================================================


  // =============================================================================
  // == 指令读取逻辑 (Instruction Fetch) - 保持不变
  // =============================================================================

  val fetch_addr_reg = Reg(UInt(64.W))
  val s_idle :: s_wait :: s_valid :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val has_just_reset = RegNext(reset.asBool, true.B)

  io.if_mem.ready_for_pc := false.B
  io.mem_id.valid := false.B
  io.mem_id.pc    := fetch_addr_reg

  switch(state) {
    is(s_idle) {
      io.if_mem.ready_for_pc := !has_just_reset
      when(io.if_mem.valid) {
        fetch_addr_reg := io.if_mem.instAddr
        state := s_wait
      }
    }
    is(s_wait) {
      io.if_mem.ready_for_pc := false.B
      io.mem_id.valid := false.B
      state := s_valid
    }
    is(s_valid) {
      io.if_mem.ready_for_pc := false.B
      io.mem_id.valid := true.B
      when(io.mem_id.ready) {
        state := s_idle
      }
    }
  }

  when(io.if_mem.flush) {
    state := s_idle
  }

  for (i <- 0 until instWidth) {
    val readAddress = (fetch_addr_reg >> 2.U) + i.U
    val readDataVec = memInside.read(readAddress)
    io.mem_id.inst(i) := Cat(readDataVec(3), readDataVec(2), readDataVec(1), readDataVec(0))
  }

  // =============================================================================
  // == 数据读取逻辑 (Data Load) - 【重要修改】
  // =============================================================================
  val dataReadAddr   = io.ex_mem.dataAddr >> 2.U 
  val dataReadAddrP1 = dataReadAddr + 1.U

  // --- [关键修改] 将无条件的并行读改为有条件的读 ---
  // 1. 先将加载数据的容器定义为Wire，并提供默认值以防生成意料之外的锁存器
  val loadedDataWord0 = Wire(Vec(4, UInt(8.W)))
  val loadedDataWord1 = Wire(Vec(4, UInt(8.W)))
  loadedDataWord0 := DontCare // Chisel推荐的做法
  loadedDataWord1 := DontCare

  // 2. 只有在LSU明确指示为加载指令时，才执行实际的内存读取操作
  when(io.ex_mem.is_load) {
    loadedDataWord0 := memInside.read(dataReadAddr)
    loadedDataWord1 := memInside.read(dataReadAddrP1)
  }

  // 3. 拼接逻辑保持不变，它现在使用条件读取后的Wire
  io.mem_lsu.data := Cat(
    loadedDataWord1(3), loadedDataWord1(2), loadedDataWord1(1), loadedDataWord1(0),
    loadedDataWord0(3), loadedDataWord0(2), loadedDataWord0(1), loadedDataWord0(0)
  )

  // =============================================================================
  // == 数据写入逻辑 (Data Store) - 保持不变, 其本身就是由 writeEn 条件保护的
  // =============================================================================
  val dataWriteAddr   = io.ex_mem.dataAddr >> 2.U
  val dataWriteAddrP1 = dataWriteAddr + 1.U
  val dataWriteAddrP2 = dataWriteAddr + 2.U
  val memWriteVec  = Wire(Vec(4, UInt(8.W)))
  val memWriteVec1 = Wire(Vec(4, UInt(8.W)))
  for (i <- 0 to 3) {
    memWriteVec(i)  := io.ex_mem.writeData(i * 8 + 7, i * 8)
    memWriteVec1(i) := io.ex_mem.writeData((i + 4) * 8 + 7, (i + 4) * 8)
  }
  switch(io.ex_mem.dataAddr(1, 0)) {
    is(1.U) {
      for (i <- 0 to 2) {
        memWriteVec(i + 1)  := io.ex_mem.writeData(i * 8 + 7, i * 8)
        memWriteVec1(i + 1) := io.ex_mem.writeData((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 0) {
        memWriteVec(i)  := io.ex_mem.writeData((i + 3) * 8 + 7, (i + 3) * 8)
        memWriteVec1(i) := io.ex_mem.writeData((i + 7) * 8 + 7, (i + 7) * 8)
      }
    }
    is(2.U) {
      for (i <- 0 to 1) {
        memWriteVec(i + 2)  := io.ex_mem.writeData(i * 8 + 7, i * 8)
        memWriteVec1(i + 2) := io.ex_mem.writeData((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 1) {
        memWriteVec(i)  := io.ex_mem.writeData((i + 2) * 8 + 7, (i + 2) * 8)
        memWriteVec1(i) := io.ex_mem.writeData((i + 6) * 8 + 7, (i + 6) * 8)
      }
    }
    is(3.U) {
      for (i <- 0 to 0) {
        memWriteVec(i + 3)  := io.ex_mem.writeData(i * 8 + 7, i * 8)
        memWriteVec1(i + 3) := io.ex_mem.writeData((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 2) {
        memWriteVec(i)  := io.ex_mem.writeData((i + 1) * 8 + 7, (i + 1) * 8)
        memWriteVec1(i) := io.ex_mem.writeData((i + 5) * 8 + 7, (i + 5) * 8)
      }
    }
  }
  val memChoose0 = Wire(Vec(4, Bool()))
  val memChoose1 = Wire(Vec(4, Bool()))
  for (i <- 0 to 3) {
    memChoose0(i) := false.B
    memChoose1(i) := false.B
  }
  switch(io.ex_mem.func3) {
    is(0.U) { memChoose0(io.ex_mem.dataAddr(1, 0)) := true.B }
    is(1.U) {
      when(io.ex_mem.dataAddr(1, 0) =/= 3.U) {
        memChoose0(io.ex_mem.dataAddr(1, 0))       := true.B
        memChoose0(io.ex_mem.dataAddr(1, 0) + 1.U) := true.B
      }.otherwise {
        memChoose0(3) := true.B
        memChoose1(0) := true.B
      }
    }
    is(2.U) {
      memChoose0(0) := Mux(io.ex_mem.dataAddr(1, 0) === 0.U, true.B, false.B)
      memChoose0(1) := Mux(io.ex_mem.dataAddr(1, 0) <= 1.U, true.B, false.B)
      memChoose0(2) := Mux(io.ex_mem.dataAddr(1, 0) <= 2.U, true.B, false.B)
      memChoose0(3) := Mux(io.ex_mem.dataAddr(1, 0) <= 3.U, true.B, false.B)
      memChoose1(0) := Mux(io.ex_mem.dataAddr(1, 0) >= 1.U, true.B, false.B)
      memChoose1(1) := Mux(io.ex_mem.dataAddr(1, 0) >= 2.U, true.B, false.B)
      memChoose1(2) := Mux(io.ex_mem.dataAddr(1, 0) >= 3.U, true.B, false.B)
    }
    is(3.U) {
      memChoose0(0) := Mux(io.ex_mem.dataAddr(1, 0) === 0.U, true.B, false.B)
      memChoose0(1) := Mux(io.ex_mem.dataAddr(1, 0) <= 1.U, true.B, false.B)
      memChoose0(2) := Mux(io.ex_mem.dataAddr(1, 0) <= 2.U, true.B, false.B)
      memChoose0(3) := Mux(io.ex_mem.dataAddr(1, 0) <= 3.U, true.B, false.B)
      memChoose1(0) := Mux(io.ex_mem.dataAddr(1, 0) >= 1.U, true.B, false.B)
      memChoose1(1) := Mux(io.ex_mem.dataAddr(1, 0) >= 2.U, true.B, false.B)
      memChoose1(2) := Mux(io.ex_mem.dataAddr(1, 0) >= 3.U, true.B, false.B)
    }
  }
  when(io.ex_mem.writeEn) {
    when(io.ex_mem.func3 === 3.U) {
      memInside.write(dataWriteAddr, memWriteVec, memChoose0)
      memInside.write(dataWriteAddrP1, memWriteVec, memChoose1)
      memInside.write(dataWriteAddrP1, memWriteVec1, memChoose0)
      memInside.write(dataWriteAddrP2, memWriteVec1, memChoose1)
    }.otherwise {
      memInside.write(dataWriteAddr, memWriteVec, memChoose0)
      memInside.write(dataWriteAddrP1, memWriteVec, memChoose1)
    }
  }

  // =============================================================================
  // == 初始化与调试 - 保持不变
  // =============================================================================
  loadMemoryFromFile(memInside, "/home/chisellab/Desktop/DualIssueCPU_mem/src/test/scala/dhrystone.data", MemoryLoadFileType.Hex)
  
  when(io.ex_mem.writeEn
    && io.ex_mem.dataAddr === "h10001ff1".U
    && io.ex_mem.func3 === 0.U) {
    printf("%c", io.ex_mem.writeData(7, 0))
  }
}
