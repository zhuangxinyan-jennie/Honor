import chisel3._
import chisel3.util._

class RegisterFileIO extends Bundle {
  // Master Read
  val ra1_a  = Input(UInt(5.W))
  val rd1_a  = Output(UInt(32.W))
  val ra1_b  = Input(UInt(5.W))
  val rd1_b  = Output(UInt(32.W))
  // Slave Read
  val ra2_a  = Input(UInt(5.W))
  val rd2_a  = Output(UInt(32.W))
  val ra2_b  = Input(UInt(5.W))
  val rd2_b  = Output(UInt(32.W))
  // Master Write
  val wen1   = Input(Bool())
  val wa1    = Input(UInt(5.W))
  val wd1    = Input(UInt(32.W))
  // Slave Write
  val wen2   = Input(Bool())
  val wa2    = Input(UInt(5.W))
  val wd2    = Input(UInt(32.W))
  // Clock 
  val clk    = Input(Clock()) 
}

class RegisterFile extends Module {
  val io = IO(new RegisterFileIO)
  // 32 * 32 bits Register
  val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  // Write：slave cover master
  when(io.wen2 && io.wa2 =/= 0.U) {
    regs(io.wa2) := io.wd2
  }
  when(io.wen1 && io.wa1 =/= 0.U && (!io.wen2 || io.wa1 =/= io.wa2)) {
    regs(io.wa1) := io.wd1
  }

  // x0 always return 0 
  // Slave > Master > old
  def bypassRead(addr: UInt): UInt = {
    MuxCase(
      Mux(addr === 0.U, 0.U, regs(addr)), 
      Seq(
        (io.wen2 && io.wa2 =/= 0.U && io.wa2 === addr) -> io.wd2,
        (io.wen1 && io.wa1 =/= 0.U && io.wa1 === addr && !(io.wen2 && io.wa2 === addr)) -> io.wd1
      )
    )
  }

  // Read
  io.rd1_a := bypassRead(io.ra1_a)
  io.rd1_b := bypassRead(io.ra1_b)
  io.rd2_a := bypassRead(io.ra2_a)
  io.rd2_b := bypassRead(io.ra2_b)
}
