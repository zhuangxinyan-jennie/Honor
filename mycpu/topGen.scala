package mycpu
import chisel3._
import chisel3.stage.ChiselStage


object TopGen extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Top(), Array("--target-dir", "generated"))
}