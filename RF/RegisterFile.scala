import chisel3._
import chisel3.util._

class RegisterFileIO extends Bundle {
  // Master Read
  val ra1_a  = Input(UInt(5.W))
  val rd1_a  = Output(SInt(32.W))
  val ra1_b  = Input(UInt(5.W))
  val rd1_b  = Output(SInt(32.W))
  // Slave Read
  val ra2_a  = Input(UInt(5.W))
  val rd2_a  = Output(SInt(32.W))
  val ra2_b  = Input(UInt(5.W))
  val rd2_b  = Output(SInt(32.W))
  // Master Write
  val wen1   = Input(Bool())
  val wa1    = Input(UInt(5.W))
  val wd1    = Input(SInt(32.W))
  // Slave Write
  val wen2   = Input(Bool())
  val wa2    = Input(UInt(5.W))
  val wd2    = Input(SInt(32.W))
  // Reset
  val rst    = Input(Bool())
  // Clock (conflict with ChiselTest)
  // val clk    = Input(Clock()) 
}

class RegisterFile extends Module {
  val io = IO(new RegisterFileIO)
  // 32 * 32 bits Register
  val regs = RegInit(VecInit(Seq.fill(32)(0.S(32.W))))

  // Reset
  when(io.rst) {
    regs := VecInit(Seq.fill(32)(0.S(32.W)))
} .otherwise {
  // Write：slave cover master
  when(io.wen2 && io.wa2 =/= 0.U) {
    regs(io.wa2) := io.wd2
  }
  when(io.wen1 && io.wa1 =/= 0.U && (!io.wen2 || io.wa1 =/= io.wa2)) {
    regs(io.wa1) := io.wd1
  }
}
  // x0 always return 0 
  def readReg(addr: UInt): SInt = Mux(addr === 0.U, 0.S, regs(addr))

  // Read
  io.rd1_a := readReg(io.ra1_a)
  io.rd1_b := readReg(io.ra1_b)
  io.rd2_a := readReg(io.ra2_a)
  io.rd2_b := readReg(io.ra2_b)
}
