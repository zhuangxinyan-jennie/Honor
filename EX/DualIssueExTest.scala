package processor

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import processor.DualIssueEx
import processor.ExceptionCause._
import processor.InstTypeFromDe._
import processor.OpSourceFromDe._
import processor.OpNameFromDe._
// import processor.DeToExIO._
// import processor.ExToExceptionIO._
// import processor.ExceptionToExIO._
// import processor.ExToCSRIO._
// import processor.MemAccessIO._
// import processor.DualIssueExIO._

class DualIssueExTest extends AnyFlatSpec with ChiselScalatestTester {

  val defaultConfig = () => new DualIssueEx

  implicit class TestHarness(c: DualIssueEx) {
    def pokeExIn1(aluOp: UInt, op1: UInt, op2: UInt): Unit = {
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.R_TYPE)
      c.io.ExIn1.alu_op.poke(aluOp)
      c.io.ExIn1.alu_op1.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn1.alu_op2.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn1.ex_rs1_data.poke(op1)
      c.io.ExIn1.ex_rs2_data.poke(op2)
      c.io.ExIn1.reg_write.poke(true.B)
    }

    def pokeExIn2(aluOp: UInt, op1: UInt, op2: UInt): Unit = {
      c.io.ExIn2.inst_type.poke(InstTypeFromDe.R_TYPE)
      c.io.ExIn2.alu_op.poke(aluOp)
      c.io.ExIn2.alu_op1.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn2.alu_op2.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn2.ex_rs1_data.poke(op1)
      c.io.ExIn2.ex_rs2_data.poke(op2)
      c.io.ExIn2.reg_write.poke(true.B)
    }

    def expectResult1(expected: UInt): Unit = {
      c.io.result1.expect(expected)
    }

    def expectResult2(expected: UInt): Unit = {
      c.io.result2.expect(expected)
    }
  }


  "ALU" should "handle basic operations: ADD and SUB" in {
    test(defaultConfig()) { c =>
      // ADD
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_ADD,
        op1 = 5.U,
        op2 = 3.U
      )

      // SUB
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_SUB,
        op1 = 10.U,
        op2 = 4.U
      )

      c.clock.step()
      c.expectResult1(8.U)
      c.expectResult2(6.U)
    }
  }

  "ALU" should "handle basic operations: SLL and SLT" in {
    test(defaultConfig()) { c =>
      // SLL
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_SLL,
        op1 = 5.U,
        op2 = 3.U
      )

      // SLT
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_SLT,
        op1 = 3.U,
        op2 = 4.U
      )

      c.clock.step()
      c.expectResult1(40.U)
      c.expectResult2(1.U)
    }
  }

  "ALU" should "handle basic operations: AND and XOR" in {
    test(defaultConfig()) { c =>
      // SLL
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_AND,
        op1 = 5.U,
        op2 = 3.U
      )

      // SLT
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_XOR,
        op1 = 3.U,
        op2 = 4.U
      )

      c.clock.step()
      c.expectResult1(1.U)
      c.expectResult2(7.U)
    }
  }

  "ALU" should "handle basic operations: SRL and SRA" in {
    test(defaultConfig()) { c =>
      // SLL
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_SRL,
        op1 = 5.U,
        op2 = 2.U
      )

      // SLT
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_SRA,
        op1 = 16.U,
        op2 = 4.U
      )

      c.clock.step()
      c.expectResult1(1.U)
      c.expectResult2(1.U)
    }
  }

  "ALU" should "handle basic operations: MUL & DIV" in {
    test(defaultConfig()) { c =>
      // MUL
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_MUL,
        op1 = 5.U,
        op2 = 2.U
      )

      // DIV
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_DIV,
        op1 = 16.U,
        op2 = 4.U
      )

      c.clock.step()
      c.expectResult1(10.U)
      c.expectResult2(4.U)
    }
  }

  "ALU" should "handle high multiplication operations: MULH, MULHSU" in {
    test(defaultConfig()) { c =>
      val MIN_INT = BigInt("80000000",16)
      val MAX_UINT = BigInt("FFFFFFFF",16)
      val MINUS_ONE = BigInt("FFFFFFFF",16)
      // MULH (signed * signed)
      c.pokeExIn1(
        aluOp = OpNameFromDe.ALU_MULH,
        op1 = MIN_INT.asUInt(32.W),  // -2147483648
        op2 = MIN_INT.asUInt(32.W)    // -2147483648
      )

      // MULHSU (signed * unsigned)
      c.pokeExIn2(
        aluOp = OpNameFromDe.ALU_MULHSU,
        op1 = MINUS_ONE.asUInt(32.W),   // -1
        op2 = MAX_UINT.asUInt(32.W)    // 4294967295
      )

      c.clock.step()

      // MULH: (-2^31) * (-2^31) = 2^62 → 0x40000000
      c.expectResult1(BigInt("40000000", 16).U)

      // MULHSU: (-1) * 4294967295 = -4294967295 → 0xffffffff
      c.expectResult2(BigInt("ffffffff", 16).U)
    }
  }

  "ALU" should "handle logical operations" in {
    test(defaultConfig()) { c =>
      val andOp1 = BigInt("FF00FF00", 16).U(32.W)
      val andOp2 = BigInt("0F0F0F0F", 16).U(32.W)
      val orOp1 = BigInt("00FF00FF", 16).U(32.W)
      val orOp2 = BigInt("F0F0F0F0", 16).U(32.W)
      // AND
      c.pokeExIn1(OpNameFromDe.ALU_AND, andOp1, andOp2)
      // OR
      c.pokeExIn2(OpNameFromDe.ALU_OR,  orOp1, orOp2)
      c.clock.step()
      c.expectResult1(BigInt("0F000F00",16).U(32.W)) // 0xFF00FF00 & 0x0F0F0F0F
      c.expectResult2(BigInt("F0FFF0FF", 16).U(32.W)) // 0x00FF00FF | 0xF0F0F0F0
    }
  }

  "Shift operations" should "work correctly" in {
    test(defaultConfig()) { c =>
      val sraIn = BigInt("F0000000",16).U(32.W)
      // SLL (shift left logical)
      c.pokeExIn1(OpNameFromDe.ALU_SLL, 0x0000000F.U, 4.U)
      // SRA (shift right arithmetic)
      c.pokeExIn2(OpNameFromDe.ALU_SRA, sraIn, 4.U)
      c.clock.step()
      c.expectResult1("h000000F0".U(32.W)) // 0xF << 4 = 0xF0
      c.expectResult2("hFF000000".U(32.W)) // sign mantained
    }
  }

  "Branch instructions" should "evaluate conditions" in {
    test(defaultConfig()) { c =>
      val pcIn = BigInt("FECA1100",16)
      val expectedTarget = (pcIn + 0x100) & 0xFFFFFFFFL
      // BLT (signed less than)
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.B_TYPE)
      c.io.ExIn1.alu_op.poke(OpNameFromDe.BR_BLT)
      c.io.ExIn1.pc_out.poke(pcIn)
      c.io.ExIn1.ex_rs1_data.poke(3.U)                   // X[rs1] = 3
      c.io.ExIn1.ex_rs2_data.poke(5.U)                  // X[rs2] = 5
      c.io.ExIn1.imm.poke(0x100.U)              // branch offset

      c.clock.step()
      c.io.branch1.expect(true.B)
      c.io.branchTarget1.expect(expectedTarget.U(32.W))
    }
  }

  "Memory load operations" should "generate correct addresses" in {
    test(defaultConfig()) { c =>
      // Load word
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.I_TYPE)
      c.io.ExIn1.alu_op.poke(OpNameFromDe.ALU_ADD)
      c.io.ExIn1.alu_op1.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn1.alu_op2.poke(OpSourceFromDe.alu_op_imm)
      c.io.ExIn1.rd.poke(9.U)
      c.io.ExIn1.ex_rs1_data.poke(0x1000.U)             // x[rs1]
      c.io.ExIn1.ex_rs2_data.poke(0x12345678.U)
      c.io.ExIn1.imm.poke(0x20.U)              // offset
      c.io.ExIn1.mem_write.poke(false.B)
      c.io.ExIn1.mem_read.poke(true.B)
      c.io.ExIn1.mem_type.poke(3.U)            // 4-byte
      c.io.mem1.rspValid.poke(true.B)
      c.io.mem1.rspData.poke(0x1abcdeff.U)

      c.clock.step()
      c.io.result1.expect(0x1abcdeff.U)       // load from mem data
      c.io.valid1.expect(true.B)
    }
  }

  "Memory store operations" should "generate correct addresses" in {
    test(defaultConfig()) { c =>
      // Store word
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.S_TYPE)
      c.io.ExIn1.alu_op.poke(OpNameFromDe.ALU_ADD)
      c.io.ExIn1.alu_op1.poke(OpSourceFromDe.alu_op_reg)
      c.io.ExIn1.alu_op2.poke(OpSourceFromDe.alu_op_imm)
      c.io.ExIn1.ex_rs1_data.poke(0x1000.U)             // base address
      c.io.ExIn1.ex_rs2_data.poke(0x12345678.U)
      c.io.ExIn1.imm.poke(0x20.U)              // offset
      c.io.ExIn1.mem_write.poke(true.B)
      c.io.ExIn1.mem_read.poke(false.B)
      c.io.ExIn1.mem_type.poke(3.U)            // 4-byte

      c.clock.step()
      c.io.mem1.reqAddr.expect(0x1020.U)       // 0x1000 + 0x20
      c.io.mem1.reqData.expect(0x12345678.U)
      c.io.mem1.reqWrite.expect(true.B)
    }
  }

  "CSR operations" should "write correctly" in {
    test(defaultConfig()) { c =>
      // CSR write immediate
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.CSR_TYPE)
      c.io.ExIn1.alu_op.poke(OpNameFromDe.ALU_CSR_W)
      c.io.ExIn1.imm.poke(0x300.U)             // CSR address
      c.io.ExIn1.ex_rs1_data.poke(0x1234.U)             // write value

      c.clock.step()
      c.io.csr.writeEn.expect(true.B)
      c.io.csr.writeAddr.expect(0x300.U)
      c.io.csr.writeData.expect(0x1234.U)
    }
  }

  "Exceptions" should "trigger on misaligned access" in {
    test(defaultConfig()) { c =>
      // Misaligned word load
      c.io.ExIn1.inst_type.poke(InstTypeFromDe.I_TYPE)        // Load instruction
      c.io.ExIn1.alu_op.poke(OpNameFromDe.ALU_ADD)
      c.io.ExIn1.ex_rs1_data.poke(0x1001.U)             // Unaligned address
      c.io.ExIn1.imm.poke(0.U)
      c.io.ExIn1.mem_read.poke(true.B)
      c.io.ExIn1.mem_type.poke(3.U)             // 4-byte

      c.clock.step()
      c.io.exception1.valid.expect(true.B)
      c.io.exception1.cause.expect(ExceptionCause.LOAD_MISALIGN)
      c.io.exception1.memAddr.expect(0x1001.U)
    }
  }

  "Dual issue" should "handle parallel execution" in {
    test(defaultConfig()) { c =>
      // Instruction 1: ADD
      c.pokeExIn1(OpNameFromDe.ALU_ADD, 5.U, 3.U)
      // Instruction 2: XOR
      c.pokeExIn2(OpNameFromDe.ALU_XOR, 0x00FF.U, 0xFF00.U)

      c.clock.step()
      c.expectResult1(8.U)                     // 5 + 3
      c.expectResult2(0xFFFF.U)                // 0x00FF ^ 0xFF00
    }
  }

  "Division exceptions" should "trigger on divide by zero" in {
    test(defaultConfig()) { c =>
      // DIV with zero divisor
      c.pokeExIn1(OpNameFromDe.ALU_DIV, 100.U, 0.U)
      c.clock.step()
      c.io.exception1.valid.expect(true.B)
      c.io.exception1.cause.expect(ExceptionCause.DIVIDE_BY_ZERO)
    }
  }

  // "Memory" should "handle load/store operations" in {
  //   test(defaultConfig()) { c =>
  //     // Store
  //     c.io.ExIn1.pokeMem(
  //       memWrite = true.B,
  //       addr = 0x1000.U,
  //       data = 0x12345678.U,
  //       width = 2.U // 32-bit
  //     )

  //     // Load
  //     c.io.EXIn2.pokeMem(
  //       memRead = true.B,
  //       addr = 0x1000.U,
  //       width = 2.U
  //     )

  //     c.clock.step()
  //     c.io.mem1.rspValid.poke(true.B)
  //     c.io.mem1.rspData.poke(0x12345678.U)

  //     c.clock.step()
  //     c.io.result2.expect(0x12345678.U)
  //   }
  // }

  // // BP
  // "Branch Prediction" should "verify prediction results" in {
  //   test(defaultConfig()) { c =>
  //     // BP predict
  //     c.io.bpPredict.valid.poke(true.B)
  //     c.io.bpPredict.bits.poke(0x2000.U)

  //     // prediction inst
  //     c.io.ExIn1.pokeBranch(
  //       pc = 0x1000.U,
  //       target = 0x2000.U,
  //       taken = true.B
  //     )

  //     c.clock.step()
  //     c.io.bpVerify.expect(true.B)
  //   }
  // }

  // // exception
  // "Exceptions" should "trigger correctly" in {
  //   test(defaultConfig()) { c =>
  //     // divide by zero
  //     c.io.ExIn1.pokeALU(
  //       aluOp = OpNameFromDe.ALU_DIV,
  //       op1 = 10.U, op2 = 0.U
  //     )

  //     c.clock.step()
  //     c.io.exception1.valid.expect(true.B)
  //     c.io.exception1.cause.expect(ExceptionCause.DIVIDE_BY_ZERO)
  //   }
  // }

  // // RV32M
  // "RV32M" should "handle multiply/divide" in {
  //   test(defaultConfig()) { c =>
  //     // unsigned mul h
  //     c.io.ExIn1.pokeALU(
  //       aluOp = OpNameFromDe.ALU_MULHU,
  //       op1 = "hFFFFFFFF".U, op2 = "hFFFFFFFF".U,
  //       expected = "hFFFFFFFE".U
  //     )

  //     // signed div
  //     c.io.EXIn2.pokeALU(
  //       aluOp = OpNameFromDe.ALU_DIV,
  //       op1 = (-50).S(32.W).asUInt, op2 = 5.U,
  //       expected = (-10).S(32.W).asUInt
  //     )

  //     c.clock.step()
  //   }
  // }

  // // CSR
  // "CSR" should "handle read/write" in {
  //   test(defaultConfig()) { c =>
  //     // CSR write
  //     c.io.ExIn1.pokeCSR(
  //       csrOp = OpNameFromDe.ALU_CSR_W,
  //       addr = 0x300.U,
  //       data = 0x1234.U
  //     )

  //     // CSR read
  //     c.io.csr.readData.poke(0x1234.U)
  //     c.clock.step()
  //     c.io.csr.writeData.expect(0x1234.U)
  //   }
  // }

  // // dual issue conflict
  // "Dual Issue" should "handle parallel execution" in {
  //   test(defaultConfig()) { c =>
  //     // add
  //     c.io.ExIn1.pokeTestALU(
  //       aluOp = OpNameFromDe.ALU_ADD,
  //       op1 = 5.U, op2 = 3.U,
  //       expected = 8.U
  //     )

  //     // AND
  //     c.io.EXIn2.pokeTestALU(
  //       aluOp = OpNameFromDe.ALU_AND,
  //       op1 = 0xF.U, op2 = 0xA.U,
  //       expected = 0xA.U
  //     )

  //     c.clock.step()
  //   }
  // }


  // implicit class TestHarness(c: DualIssueEx) {
  //   def pokeTestALU(aluOp: UInt, op1: UInt, op2: UInt, expected: UInt): Unit = {
  //     c.io.ExIn1.inst_type.poke(InstTypeFromDe.R_TYPE)
  //     c.io.ExIn1.alu_op.poke(aluOp)
  //     c.io.ExIn1.alu_op1.poke(OpSourceFromDe.alu_op_reg)
  //     c.io.ExIn1.alu_op2.poke(OpSourceFromDe.alu_op_reg)
  //     c.io.ExIn1.rs1.poke(op1)
  //     c.io.ExIn1.rs2.poke(op2)
  //     c.io.ExIn1.reg_write.poke(true.B)
  //   }

  //   def pokeMem(memWrite: Bool, addr: UInt, data: UInt = 0.U, width: UInt): Unit = {
  //     c.io.ExIn1.inst_type.poke(if (memWrite.litToBoolean) InstTypeFromDe.S_TYPE else InstTypeFromDe.I_TYPE)
  //     c.io.ExIn1.alu_op.poke(OpNameFromDe.ALU_ADD)
  //     c.io.ExIn1.alu_op1.poke(OpSourceFromDe.alu_op_reg)
  //     c.io.ExIn1.alu_op2.poke(OpSourceFromDe.alu_op_imm)
  //     c.io.ExIn1.rs1.poke(addr)
  //     c.io.ExIn1.imm.poke(0.U)
  //     c.io.ExIn1.mem_write.poke(memWrite)
  //     c.io.ExIn1.mem_read.poke(!memWrite)
  //     c.io.ExIn1.mem_type.poke(width)
  //     if (memWrite.litToBoolean) c.io.ExIn1.rs2.poke(data)
  //   }

  //   def pokeBranch(pc: UInt, target: UInt, taken: Bool): Unit = {
  //     c.io.pc1.poke(pc)
  //     c.io.ExIn1.inst_type.poke(InstTypeFromDe.B_TYPE)
  //     c.io.ExIn1.alu_op.poke(OpNameFromDe.BR_BEQ)
  //     c.io.ExIn1.imm.poke(target - pc)
  //   }

  //   def pokeCSR(csrOp: UInt, addr: UInt, data: UInt): Unit = {
  //     c.io.ExIn1.inst_type.poke(InstTypeFromDe.CSR_TYPE)
  //     c.io.ExIn1.alu_op.poke(csrOp)
  //     c.io.ExIn1.imm.poke(addr)
  //     c.io.ExIn1.rs1.poke(data)
  //   }
  // }
}
