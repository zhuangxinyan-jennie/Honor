package DualIssueDecodeTest

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.experimental.BundleLiterals._
import parse._
import issue_ctrl._
import Ｄouble_Decode.DualIssueDecode

class DualIssueDecodeTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "DualIssueDecode"

  // 测试用例1：测试两条独立的R型指令
  it should "decode two independent R-type instructions correctly" in {
    test(new DualIssueDecode) { dut =>
      // add x1, x2, x3
      // add x24, x6, x7
      dut.io.inst1.poke(Integer.parseInt("00000000001100010000000010110011", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000011100110000110000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.reg1_rs1_data.poke(2.U)    // x2的值
      dut.io.reg1_rs2_data.poke(3.U)    // x3的值
      dut.io.reg2_rs1_data.poke(6.U)    // x6的值
      dut.io.reg2_rs2_data.poke(7.U)    // x7的值
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查第一条指令的译码结果
      dut.io.decode1.inst_type.expect(2.U)  // R_TYPE
      dut.io.decode1.rs1.expect(2.U)        // x2
      dut.io.decode1.rs2.expect(3.U)        // x3
      dut.io.decode1.rd.expect(1.U)         // x1
      dut.io.decode1.alu_op.expect(0.U)     // ALU_ADD

      // 检查第二条指令的译码结果
      dut.io.decode2.inst_type.expect(2.U)  // R_TYPE
      dut.io.decode2.rs1.expect(6.U)        // x6
      dut.io.decode2.rs2.expect(7.U)        // x7
      dut.io.decode2.rd.expect(24.U)        // x24

      // 检查发射控制
      dut.io.slave_ena.expect(true.B)       // 可以双发射
    }
  }

  // 测试用例2：测试数据相关的R型指令
  it should "handle RAW dependency correctly" in {
    test(new DualIssueDecode) { dut =>
      // add x1, x2, x3
      // add x4, x1, x5  // 使用第一条指令的结果
      dut.io.inst1.poke(Integer.parseInt("00000000001100010000000010110011", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000010100001000001000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.reg1_rs1_data.poke(2.U)
      dut.io.reg1_rs2_data.poke(3.U)
      dut.io.reg2_rs1_data.poke(1.U)
      dut.io.reg2_rs2_data.poke(5.U)
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查发射控制
      dut.io.slave_ena.expect(false.B)  // 不能双发射，有RAW相关
    }
  }

  // 测试用例3：测试Load指令和普通指令
  it should "handle load instruction correctly" in {
    test(new DualIssueDecode) { dut =>
      // lw x1, 0(x2)
      // add x4, x5, x6  // 无关的计算指令
      dut.io.inst1.poke(Integer.parseInt("00000000000000010010000010000011", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000011000101000001000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.reg1_rs1_data.poke(2.U)
      dut.io.reg1_rs2_data.poke(0.U)
      dut.io.reg2_rs1_data.poke(5.U)
      dut.io.reg2_rs2_data.poke(6.U)
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查Load指令译码
      dut.io.decode1.inst_type.expect(3.U)  // I_TYPE
      dut.io.decode1.mem_read.expect(true.B)

      // 检查发射控制
      dut.io.slave_ena.expect(false.B)  // Load指令不能双发射
    }
  }

  // 测试用例4：测试Store指令
  it should "handle store instruction correctly" in {
    test(new DualIssueDecode) { dut =>
      // sw x1, 0(x2)
      // add x4, x5, x6
      dut.io.inst1.poke(Integer.parseInt("00000000000100010010000000100011", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000011000101000001000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.reg1_rs1_data.poke(2.U)
      dut.io.reg1_rs2_data.poke(1.U)
      dut.io.reg2_rs1_data.poke(5.U)
      dut.io.reg2_rs2_data.poke(6.U)
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查Store指令译码
      dut.io.decode1.inst_type.expect(4.U)  // S_TYPE
      dut.io.decode1.mem_write.expect(true.B)

      // 检查发射控制
      dut.io.slave_ena.expect(false.B)  // Store指令不能双发射
    }
  }

  // 测试用例5：测试跳转指令
  it should "handle jump instruction correctly" in {
    test(new DualIssueDecode) { dut =>
      // jal x1, offset
      // add x4, x5, x6
      dut.io.inst1.poke(Integer.parseInt("00000000100000000000000011101111", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000011000101000001000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查跳转指令译码
      dut.io.decode1.inst_type.expect(1.U)  // J_TYPE

      // 检查发射控制
      dut.io.slave_ena.expect(false.B)  // 跳转指令不能双发射
    }
  }

  // 测试用例6：测试CSR指令
  it should "handle CSR instruction correctly" in {
    test(new DualIssueDecode) { dut =>
      // csrrw x1, mstatus, x2
      // add x4, x5, x6
      dut.io.inst1.poke(Integer.parseInt("00110000000000010001000011110011", 2).U)
      dut.io.inst2.poke(Integer.parseInt("00000000011000101000001000110011", 2).U)
      dut.io.pc1.poke(0.U)
      dut.io.pc2.poke(4.U)
      dut.io.fifo_empty.poke(false.B)
      dut.io.fifo_almost_empty.poke(false.B)

      dut.clock.step(1)

      // 检查CSR指令译码
      dut.io.decode1.inst_type.expect(8.U)  // CSR_TYPE

      // 检查发射控制
      dut.io.slave_ena.expect(false.B)  // CSR指令不能双发射
    }
  }
}
