import chisel3._
import chisel3.stage.ChiselStage
import issue_ctrl._
import parse._

object GenerateVerilog extends App {
    // 生成IssueCtrl模块的Verilog
    (new ChiselStage).emitVerilog(new IssueCtrl, Array(
        "--target-dir", "generated",
        "--target:fpga"
    ))

    // 生成DualIssueDecode模块的Verilog
    (new ChiselStage).emitVerilog(new DualIssueDecode, Array(
        "--target-dir", "generated",
        "--target:fpga"
    ))

    // 生成Parse模块的Verilog
    (new ChiselStage).emitVerilog(new BaseDecode, Array(
        "--target-dir", "generated",
        "--emission-options", "disableMemRandomization,disableRegisterRandomization"
    ))
} 