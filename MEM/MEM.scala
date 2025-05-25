package mem

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

class MEM extends Module {
  val io = IO(new Bundle {

    val cpu = new Bundle {
      val mem_en     = Input(Bool()) //mem阶段接收使能信号
      val mem_write  = Input(Bool())//mem阶段是否进行写操作
      val mem_type   = Input(UInt(2.W))//指示mem阶段的访存类型，NONE B H W
      val mem_unsigned = Input(Bool())//指示mem阶段是否进行无符号访存
      val mem_wdata  = Input(UInt(32.W)) //EX阶段计算出的数据
      val mem_addr   = Input(UInt(32.W)) //EX阶段计算出的地址
      val mem_rdata  = Output(UInt(32.W)) //MEM阶段读取得到的数据
      val mem_ready  = Output(Bool()) //标志mem阶段工作是否完成，用于不定周期访存控制
    }

    val sram = new Bundle {
      //以下与助教提供的mem.scala模块相连
      val rdata    = Input(UInt(32.W))
      val en       = Output(Bool())
      val funct3   = Output(UInt(3.W))
      val addr     = Output(UInt(32.W)) //认定是物理地址，故无需TLB或者地址转换（根据助教提供的mem.scala模块，应该是这样，有问题可与我联系）
      val wdata    = Output(UInt(32.W))
    }

    //传递至EX的数据旁路输出
    val mem_bypass_data  = Output(UInt(32.W))
    val mem_bypass_valid = Output(Bool())

  })

  val memAccess = Module(new MemAccess)

  // 数据缓存参数，采用直接映射方式
  val CACHE_LINES = 16
  val LINE_WORDS  = 4
  val TAG_WIDTH   = 32 - log2Ceil(CACHE_LINES * LINE_WORDS * 4)
  val LINE_BYTES  = LINE_WORDS * 4

  // 缓存生成
  val valid   = RegInit(VecInit(Seq.fill(CACHE_LINES)(false.B)))
  val dirty   = RegInit(VecInit(Seq.fill(CACHE_LINES)(false.B)))
  val tags    = Reg(Vec(CACHE_LINES, UInt(TAG_WIDTH.W)))
  val dataRam = SyncReadMem(CACHE_LINES * LINE_WORDS, UInt(32.W))

  // 状态机定义
  val sIdle :: sCheckCache :: sWriteBack :: sFetchLine :: sStoreAfterMiss :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val addrReg    = Reg(UInt(32.W))
  val opReg      = Reg(UInt(7.W))
  val funct3Reg  = Reg(UInt(3.W))
  val wdataReg   = Reg(UInt(32.W))
  val wordCount  = Reg(UInt(log2Ceil(LINE_WORDS).W))
  val lineAddr   = Reg(UInt(32.W))
  val savedIndex = Reg(UInt(log2Ceil(CACHE_LINES).W))

  // 地址分解（直接提取位域）
  def getIndex(addr: UInt) = addr(7, 4)   // bits [7:4] → 4位Index
  def getTag(addr: UInt)   = addr(31, 8)  // bits [31:8] → 24位Tag
  def getWordOffset(addr: UInt) = addr(3, 2)

  // 使能信号低时保证状态机不运行
  io.cpu.mem_ready := false.B
  io.cpu.mem_rdata := 0.U

  memAccess.io.mem_en           := false.B
  memAccess.io.mem_op           := opReg
  memAccess.io.mem_funct3       := funct3Reg
  memAccess.io.mem_wdata        := wdataReg
  memAccess.io.mem_addr         := addrReg
  memAccess.io.data_sram_rdata  := io.sram.rdata

  io.sram.en    := memAccess.io.data_sram_en
  io.sram.addr  := memAccess.io.data_sram_addr
  io.sram.wdata := memAccess.io.data_sram_wdata
  io.sram.funct3:= MuxCase(0.U, Array(
    (io.cpu.mem_type === 1.U && io.cpu.mem_unsigned === false.B) -> "b000".U,
    (io.cpu.mem_type === 2.U && io.cpu.mem_unsigned === false.B) -> "b001".U,
    (io.cpu.mem_type === 3.U && io.cpu.mem_unsigned === false.B) -> "b010".U,
    (io.cpu.mem_type === 1.U && io.cpu.mem_unsigned === true.B)  -> "b100".U,
    (io.cpu.mem_type === 2.U && io.cpu.mem_unsigned === true.B)  -> "b101".U
  ))

  io.mem_bypass_valid := false.B
  io.mem_bypass_data  := 0.U

  // 状态机
  switch(state) {
    is(sIdle) {
      io.cpu.mem_ready := false.B
      when(io.cpu.mem_en) {
        addrReg := io.cpu.mem_addr
        opReg   := MuxCase(0.U, Array(
          (io.cpu.mem_write === false.B) -> "b0000011".U,
          (io.cpu.mem_write === true.B) -> "b0100011".U
        ))
        funct3Reg := MuxCase(0.U, Array(
          (io.cpu.mem_type === 1.U && io.cpu.mem_unsigned === false.B) -> "b000".U,
          (io.cpu.mem_type === 2.U && io.cpu.mem_unsigned === false.B) -> "b001".U,
          (io.cpu.mem_type === 3.U && io.cpu.mem_unsigned === false.B) -> "b010".U,
          (io.cpu.mem_type === 1.U && io.cpu.mem_unsigned === true.B)  -> "b100".U,
          (io.cpu.mem_type === 2.U && io.cpu.mem_unsigned === true.B)  -> "b101".U
        ))
        wdataReg := io.cpu.mem_wdata
        state := sCheckCache
      }
    }

    is(sCheckCache) {
      val index = getIndex(addrReg)
      val tag   = getTag(addrReg)
      val hit   = valid(index) && (tags(index) === tag) //组合逻辑判断是否命中
      val wordOffset = getWordOffset(addrReg)
      val ramAddr = (index << log2Ceil(LINE_WORDS)).asUInt() + wordOffset

      //添加MEM至EX阶段关于load指令的数据通路
      when(hit && io.cpu.mem_en && (opReg === "b0000011".U)) {
        io.mem_bypass_valid := true.B
        io.mem_bypass_data := MuxLookup(funct3Reg, 0.U, Seq(
          "b000".U -> Cat(Fill(24, dataRam.read(ramAddr)(7)), dataRam.read(ramAddr)(7,0)),
          "b001".U -> Cat(Fill(16, dataRam.read(ramAddr)(15)), dataRam.read(ramAddr)(15,0)),
          "b010".U -> dataRam.read(ramAddr),
          "b100".U -> Cat(0.U(24.W), dataRam.read(ramAddr)(7,0)),
          "b101".U -> Cat(0.U(16.W), dataRam.read(ramAddr)(15,0))
        ))
      }.otherwise {
        io.mem_bypass_valid := false.B
        io.mem_bypass_data := DontCare
      }

      when(hit) {
        when(opReg === "b0000011".U) { // Load
          memAccess.io.mem_en := true.B
          memAccess.io.mem_op := opReg
          memAccess.io.mem_funct3 := funct3Reg
          memAccess.io.mem_addr := addrReg
          memAccess.io.data_sram_rdata := dataRam.read(ramAddr) // 输入缓存数据

          // 返回处理后的结果
          io.cpu.mem_rdata := memAccess.io.mem_rdata
          io.cpu.mem_ready := true.B
          state := sIdle
        }.otherwise { // Store
          memAccess.io.mem_en := true.B
          memAccess.io.mem_op := opReg
          memAccess.io.mem_funct3 := funct3Reg
          memAccess.io.mem_addr := addrReg
          memAccess.io.mem_wdata := wdataReg

          // 读取旧数据并合并
          val oldData = dataRam.read(ramAddr)
          val mask = (Fill(8, memAccess.io.data_sram_wen(0)) ##
            Fill(8, memAccess.io.data_sram_wen(1)) ##
            Fill(8, memAccess.io.data_sram_wen(2)) ##
            Fill(8, memAccess.io.data_sram_wen(3)))
          val newData = (oldData & (~mask).asUInt()) | (memAccess.io.data_sram_wdata & mask.asUInt())

          // 写入合并后的数据
          dataRam.write(ramAddr, newData)
          dirty(index) := true.B
          io.cpu.mem_ready := true.B
          state := sIdle
        }
      }.otherwise { // Miss
        lineAddr := (addrReg >> log2Ceil(LINE_BYTES)) << log2Ceil(LINE_BYTES)
        savedIndex := index
        wordCount := 0.U
        state := Mux(dirty(index), sWriteBack, sFetchLine)
      }
    }

    is(sWriteBack) {
      memAccess.io.mem_en := true.B
      memAccess.io.mem_op := "b0100011".U // Store操作
      memAccess.io.mem_addr := lineAddr + (wordCount << 2)
      memAccess.io.mem_wdata := dataRam.read((savedIndex << log2Ceil(LINE_WORDS)).asUInt() + wordCount)

      wordCount := wordCount + 1.U
      when(wordCount === (LINE_WORDS-1).U) {
        dirty(savedIndex) := false.B // 清除脏位
        state := sFetchLine     // 进入加载新行状态
      }
    }

    is(sFetchLine) {
      memAccess.io.mem_en := true.B
      memAccess.io.mem_op := "b0000011".U // Load word
      memAccess.io.mem_addr := lineAddr + (wordCount << 2)

      val index = getIndex(lineAddr)
      dataRam.write((savedIndex << log2Ceil(LINE_WORDS)).asUInt() + wordCount, io.sram.rdata)
      wordCount := wordCount + 1.U
      when(wordCount === (LINE_WORDS-1).U) {
        valid(savedIndex) := true.B
        tags(savedIndex)  := getTag(lineAddr)
        dirty(savedIndex) := false.B
        state := Mux(opReg === "b0100011".U, sStoreAfterMiss, sCheckCache)
      }
    }

    is(sStoreAfterMiss) {
      memAccess.io.mem_en := true.B
      memAccess.io.mem_op := opReg
      memAccess.io.mem_funct3 := funct3Reg
      memAccess.io.mem_addr := addrReg
      memAccess.io.mem_wdata := wdataReg

      // 读取缓存旧数据
      val wordOffset = getWordOffset(addrReg)
      val ramAddr = (savedIndex << log2Ceil(LINE_WORDS)).asUInt() + wordOffset
      val oldData = dataRam.read(ramAddr)
      // 生成合并后的新数据
      val mask = Wire(UInt(32.W))
      mask := (Fill(8, memAccess.io.data_sram_wen(0)) ##
        Fill(8, memAccess.io.data_sram_wen(1)) ##
        Fill(8, memAccess.io.data_sram_wen(2)) ##
        Fill(8, memAccess.io.data_sram_wen(3)))
      val newData = (oldData & (~mask).asUInt()) | (memAccess.io.data_sram_wdata & mask.asUInt())

      // 写入合并后的数据
      dataRam.write(ramAddr, newData)
      dirty(savedIndex) := true.B
      io.cpu.mem_ready := true.B
      state := sIdle
    }
  }
}

