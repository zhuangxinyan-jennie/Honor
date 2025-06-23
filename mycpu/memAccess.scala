package mycpu

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

    // 输出端口
    val mem_rdata          = Output(UInt(32.W))
    val data_sram_en       = Output(Bool())
    val data_sram_wen      = Output(UInt(4.W))
    val data_sram_addr     = Output(UInt(32.W))
    val data_sram_wdata    = Output(UInt(32.W))
  })


  io.data_sram_en := io.mem_en //逻辑判断，是否进行读取
  io.data_sram_addr := io.mem_addr //默认为物理地址

  // 初始化默认值
  io.mem_rdata := 0.U
  io.data_sram_wen := 0.U
  io.data_sram_wdata := 0.U


  val mem_opfunc = Wire(UInt(10.W))
  mem_opfunc := Cat(io.mem_op, io.mem_funct3)

  switch(mem_opfunc) {
    // lw: opcode=0000011, funct3=010 -> "b0000011010"
    is("b0000011010".U) {
      io.data_sram_wen := 0.U
      io.mem_rdata := io.data_sram_rdata
    }

    // lh: opcode=0000011, funct3=001 -> "b0000011001"
    is("b0000011001".U) {
      io.data_sram_wen := 0.U
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(Fill(16, io.data_sram_rdata(15)), io.data_sram_rdata(15, 0)),
        2.U -> Cat(Fill(16, io.data_sram_rdata(31)), io.data_sram_rdata(31, 16))
      ))
    }

    // lhu: opcode=0000011, funct3=101 -> "b0000011101"
    is("b0000011101".U) {
      io.data_sram_wen := 0.U
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(0.U(16.W), io.data_sram_rdata(15, 0)),
        2.U -> Cat(0.U(16.W), io.data_sram_rdata(31, 16))
      ))
    }

    // lb: opcode=0000011, funct3=000 -> "b0000011000"
    is("b0000011000".U) {
      io.data_sram_wen := 0.U
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
      io.mem_rdata := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> Cat(0.U(24.W), io.data_sram_rdata(7, 0)),
        1.U -> Cat(0.U(24.W), io.data_sram_rdata(15, 8)),
        2.U -> Cat(0.U(24.W), io.data_sram_rdata(23, 16)),
        3.U -> Cat(0.U(24.W), io.data_sram_rdata(31, 24))
      ))
    }

    // sw: opcode=0100011, funct3=010 -> "b0100011010"
    is("b0100011010".U) {
      io.data_sram_wen := Mux(io.mem_addr(1,0) === 0.U, 0xF.U, 0.U)
      io.data_sram_wdata := io.mem_wdata
    }

    // sh: opcode=0100011, funct3=001 -> "b0100011001"
    is("b0100011001".U) {
      io.data_sram_wen := MuxLookup(io.mem_addr(1,0), 0.U, Seq(
        0.U -> 0x3.U,
        2.U -> 0xC.U
      ))
      io.data_sram_wdata := Cat(io.mem_wdata(15,0), io.mem_wdata(15,0))
    }

    // sb: opcode=0100011, funct3=000 -> "b0100011000"
    is("b0100011000".U) {
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



































// package mycpu

// import chisel3._
// import chisel3.util._


// // 修正后的MemAccess模块
// class MemAccessFixed extends Module {
//   val io = IO(new Bundle {
//     val mem_en             = Input(Bool())
//     val mem_op             = Input(UInt(7.W))
//     val mem_funct3         = Input(UInt(3.W))
//     val mem_wdata          = Input(UInt(32.W))
//     val mem_addr           = Input(UInt(32.W))
//     val data_sram_rdata    = Input(UInt(32.W))
//     val mem_rdata          = Output(UInt(32.W))
//     val data_sram_en       = Output(Bool())
//     val data_sram_wen      = Output(UInt(4.W))
//     val data_sram_addr     = Output(UInt(32.W))
//     val data_sram_wdata    = Output(UInt(32.W))
//   })

//   io.data_sram_en := io.mem_en
//   io.data_sram_addr := io.mem_addr & ~3.U  // 字对齐的地址

//   // 默认值
//   io.mem_rdata := 0.U
//   io.data_sram_wen := 0.U
//   io.data_sram_wdata := 0.U

//   val byteOffset = io.mem_addr(1, 0)
//   val isLoad = io.mem_op === "b0000011".U
//   val isStore = io.mem_op === "b0100011".U

//   when(isLoad) {
//     io.data_sram_wen := 0.U
    
//     // 处理各种Load指令
//     switch(io.mem_funct3) {
//       is("b000".U) { // LB
//         val byteData = MuxLookup(byteOffset, io.data_sram_rdata(7, 0), Seq(
//           0.U -> io.data_sram_rdata(7, 0),
//           1.U -> io.data_sram_rdata(15, 8),
//           2.U -> io.data_sram_rdata(23, 16),
//           3.U -> io.data_sram_rdata(31, 24)
//         ))
//         io.mem_rdata := Cat(Fill(24, byteData(7)), byteData)
//       }
//       is("b001".U) { // LH
//         val halfData = MuxLookup(byteOffset, io.data_sram_rdata(15, 0), Seq(
//           0.U -> io.data_sram_rdata(15, 0),
//           1.U -> io.data_sram_rdata(23, 8),
//           2.U -> io.data_sram_rdata(31, 16),
//           3.U -> Cat(io.data_sram_rdata(7, 0), io.data_sram_rdata(31, 24))  // 跨字
//         ))
//         io.mem_rdata := Cat(Fill(16, halfData(15)), halfData)
//       }
//       is("b010".U) { // LW
//         io.mem_rdata := MuxLookup(byteOffset, io.data_sram_rdata, Seq(
//           0.U -> io.data_sram_rdata,
//           1.U -> Cat(io.data_sram_rdata(7, 0), io.data_sram_rdata(31, 8)),
//           2.U -> Cat(io.data_sram_rdata(15, 0), io.data_sram_rdata(31, 16)),
//           3.U -> Cat(io.data_sram_rdata(23, 0), io.data_sram_rdata(31, 24))
//         ))
//       }
//       is("b100".U) { // LBU
//         io.mem_rdata := MuxLookup(byteOffset, Cat(0.U(24.W), io.data_sram_rdata(7, 0)), Seq(
//           0.U -> Cat(0.U(24.W), io.data_sram_rdata(7, 0)),
//           1.U -> Cat(0.U(24.W), io.data_sram_rdata(15, 8)),
//           2.U -> Cat(0.U(24.W), io.data_sram_rdata(23, 16)),
//           3.U -> Cat(0.U(24.W), io.data_sram_rdata(31, 24))
//         ))
//       }
//       is("b101".U) { // LHU
//         val halfData = MuxLookup(byteOffset, io.data_sram_rdata(15, 0), Seq(
//           0.U -> io.data_sram_rdata(15, 0),
//           1.U -> io.data_sram_rdata(23, 8),
//           2.U -> io.data_sram_rdata(31, 16),
//           3.U -> Cat(io.data_sram_rdata(7, 0), io.data_sram_rdata(31, 24))  // 跨字
//         ))
//         io.mem_rdata := Cat(0.U(16.W), halfData)
//       }
//     }
//   }.elsewhen(isStore) {
//     // 处理各种Store指令
//     switch(io.mem_funct3) {
//       is("b000".U) { // SB
//         val writeData = Fill(4, io.mem_wdata(7, 0))
//         io.data_sram_wdata := writeData
//         io.data_sram_wen := MuxLookup(byteOffset, "b0001".U, Seq(
//           0.U -> "b0001".U,
//           1.U -> "b0010".U,
//           2.U -> "b0100".U,
//           3.U -> "b1000".U
//         ))
//       }
//       is("b001".U) { // SH
//         val writeData = Fill(2, io.mem_wdata(15, 0))
//         io.data_sram_wdata := writeData
//         io.data_sram_wen := MuxLookup(byteOffset, "b0011".U, Seq(
//           0.U -> "b0011".U,
//           1.U -> "b0110".U,
//           2.U -> "b1100".U,
//           3.U -> "b1000".U  // 只写高字节，低字节在下一个字
//         ))
//       }
//       is("b010".U) { // SW
//         io.data_sram_wdata := io.mem_wdata
//         io.data_sram_wen := MuxLookup(byteOffset, "b1111".U, Seq(
//           0.U -> "b1111".U,
//           1.U -> "b1110".U,  // 写3个字节，最低字节在下一个字
//           2.U -> "b1100".U,  // 写2个字节，低2字节在下一个字
//           3.U -> "b1000".U   // 写1个字节，低3字节在下一个字
//         ))
//       }
//     }
//   }
// }