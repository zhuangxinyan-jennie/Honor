import chisel3._
import chisel3.util._


class MemAccess extends Module {
  val io = IO(new Bundle {
    // 输入端口
    val mem_en             = Input(Bool())
    val mem_op             = Input(UInt(7.W))
    val mem_funct3         = Input(UInt(3.W))
    val mem_wdata          = Input(UInt(32.W))
    val mem_addr           = Input(UInt(32.W))
    val data_sram_rdata    = Input(UInt(32.W))
    val M_master_mem_sel   = Input(Bool())
    val M_slave_mem_sel    = Input(Bool())
    val M_master_except    = Input(new ExceptBus)
    val M_slave_except     = Input(new ExceptBus)
    
    // 输出端口
    val mem_rdata          = Output(UInt(32.W))
    val data_sram_en       = Output(Bool())
    val data_sram_rlen     = Output(UInt(2.W))
    val data_sram_wen      = Output(UInt(4.W))
    val data_sram_addr     = Output(UInt(32.W))
    val data_sram_wdata    = Output(UInt(32.W))
  })

  
  io.data_sram_en := io.mem_en && (
    (io.M_master_mem_sel && ~(|io.M_master_except)) || 
    (io.M_slave_mem_sel && ~(|io.M_master_except) && ~(|io.M_slave_except))) //逻辑判断，是否进行读取
  io.data_sram_addr := io.mem_addr //默认为物理地址
  
  // 初始化默认值
  io.mem_rdata := 0.U
  io.data_sram_rlen := 0.U
  io.data_sram_wen := 0.U
  io.data_sram_wdata := 0.U
  

  val mem_opfunc = Wire(UInt(10.W))
  mem_opfunc := Cat(io.mem_op, io.mem_funct3)
  
  switch(mem_opfunc) {
    // lw: opcode=0000011, funct3=010 -> "b0000011010"
    is("b0000011010".U) { 
      io.data_sram_wen := 0.U
      io.data_sram_rlen := 2.U 
      io.mem_rdata := io.data_sram_rdata 
    }

    // lh: opcode=0000011, funct3=001 -> "b0000011001"
    is("b0000011001".U) { 
      io.data_sram_wen := 0.U
      io.data_sram_rlen := 1.U 
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(Fill(16, io.data_sram_rdata(15)), io.data_sram_rdata(15, 0)),
        2.U -> Cat(Fill(16, io.data_sram_rdata(31)), io.data_sram_rdata(31, 16))
      ))
    }

    // lhu: opcode=0000011, funct3=101 -> "b0000011101"
    is("b0000011101".U) { 
      io.data_sram_wen := 0.U
      io.data_sram_rlen := 1.U
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(0.U(16.W), io.data_sram_rdata(15, 0)),
        2.U -> Cat(0.U(16.W), io.data_sram_rdata(31, 16))
      ))
    }

    // lb: opcode=0000011, funct3=000 -> "b0000011000"
    is("b0000011000".U) { 
      io.data_sram_wen := 0.U
      io.data_sram_rlen := 0.U 
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(Fill(24, io.data_sram_rdata(7)),  io.data_sram_rdata(7, 0)),
        1.U -> Cat(Fill(24, io.data_sram_rdata(15)), io.data_sram_rdata(15, 8)),
        2.U -> Cat(Fill(24, io.data_sram_rdata(23)), io.data_sram_rdata(23, 16)),
        3.U -> Cat(Fill(24, io.data_sram_rdata(31)), io.data_sram_rdata(31, 24))
      ))
    }

    // lbu: opcode=0000011, funct3=100 -> "b0000011100"
    is("b0000011100".U) { 
      io.data_sram_wen := 0.U
      io.data_sram_rlen := 0.U
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(0.U(24.W), io.data_sram_rdata(7, 0)),
        1.U -> Cat(0.U(24.W), io.data_sram_rdata(15, 8)),
        2.U -> Cat(0.U(24.W), io.data_sram_rdata(23, 16)),
        3.U -> Cat(0.U(24.W), io.data_sram_rdata(31, 24))
      ))
    }

    // sw: opcode=0100011, funct3=010 -> "b0100011010"
    is("b0100011010".U) { 
      io.data_sram_rlen := 2.U 
      io.data_sram_wen := Mux(io.mem_addr(1,0) === 0.U, 0xF.U, 0.U)
      io.data_sram_wdata := io.mem_wdata 
    }

    // sh: opcode=0100011, funct3=001 -> "b0100011001"
    is("b0100011001".U) { 
      io.data_sram_rlen := 1.U 
      io.data_sram_wen := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> 0x3.U, 
        2.U -> 0xC.U  
      ))
      io.data_sram_wdata := Cat(io.mem_wdata(15,0), io.mem_wdata(15,0))
    }

    // sb: opcode=0100011, funct3=000 -> "b0100011000"
    is("b0100011000".U) { 
      io.data_sram_rlen := 0.U 
      io.data_sram_wen := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> 0x1.U, 
        1.U -> 0x2.U, 
        2.U -> 0x4.U, 
        3.U -> 0x8.U  
      ))
      io.data_sram_wdata := Cat(
        io.mem_wdata(7,0), io.mem_wdata(7,0), 
        io.mem_wdata(7,0), io.mem_wdata(7,0)
      )
    }
  }
}
