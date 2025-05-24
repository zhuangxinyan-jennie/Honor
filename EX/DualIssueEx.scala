package processor

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

object ExceptionCause {
  val INSTR_ILLEGAL     = 2.U  
  val BREAKPOINT       = 3.U   
  val LOAD_MISALIGN    = 4.U   
  val LOAD_FAULT       = 5.U   
  val STORE_MISALIGN   = 6.U   
  val STORE_FAULT      = 7.U   
  val ECALL_M          = 11.U
  val DIVIDE_BY_ZERO   = 12.U  
}

object InstTypeFromDe {
  val J_TYPE = 1.U(4.W)
  val R_TYPE = 2.U(4.W)
  val I_TYPE = 3.U(4.W)
  val S_TYPE = 4.U(4.W)
  val B_TYPE = 5.U(4.W)
  val U_TYPE = 6.U(4.W)
  val FENCE_TYPE = 7.U(4.W)
  val CSR_TYPE = 8.U(4.W)
}

object OpSourceFromDe {
  val alu_op_reg = 0.U(2.W)
  val alu_op_imm = 1.U(2.W)
  val alu_op_pc = 2.U(2.W)
  val alu_op_zero = 3.U(2.W)
}

object OpNameFromDe{
    val ALU_ADD  = 0.U(5.W)  // ADD/ADDI/LOAD/STORE/AUIPC
    val ALU_SUB    = 1.U(5.W)  // SUB/BR
    val ALU_SLL    = 2.U(5.W)  // SLL/SLLI
    val ALU_SLT    = 3.U(5.W)  // signed cmp SLT/SLTI/BLT/BGE
    val ALU_SLTU   = 4.U(5.W)  // unsigned cmp SLTU/SLTIU/BLTU/BGEU
    val ALU_XOR    = 5.U(5.W)  // XOR/XORI
    val ALU_SRL    = 6.U(5.W)  // SRL/SRLI
    val ALU_SRA    = 7.U(5.W)  // SRA/SRAI
    val ALU_OR     = 8.U(5.W)  // OR/ORI
    val ALU_AND    = 9.U(5.W)  // AND/ANDI
    val ALU_COPY   = 10.U(5.W) // transfer data1 LUI
    val ALU_NONE   = 12.U(5.W) // no ALU
    val BR_BEQ     = 13.U(5.W)   // signed br BEQ
    val BR_BNE     = 14.U(5.W)   // BNE
    val BR_BLT     = 15.U(5.W)   // BLT
    val BR_BGE     = 16.U(5.W)   // BGE
    val BR_BLTU    = 17.U(5.W)  // unsigned br BLTU
    val BR_BGEU    = 18.U(5.W)  // BGEU
    val ALU_CSR_W  = 19.U(5.W) // CSR write
    val ALU_CSR_S  = 20.U(5.W) // CSR set
    val ALU_CSR_C  = 21.U(5.W) // CSR clr
     val ALU_MUL   = 22.U(5.W)
    val ALU_MULH   = 23.U(5.W)
    val ALU_MULHSU = 24.U(5.W)
    val ALU_MULHU  = 25.U(5.W)
    val ALU_DIV    = 26.U(5.W)
    val ALU_DIVU   = 27.U(5.W)
    val ALU_REM    = 28.U(5.W)
    val ALU_REMU   = 29.U(5.W)
  
}

class DeToExIO extends Bundle{
  val inst_type = Output(UInt(4.W))
  val rs1 = Output(UInt(5.W))
  val rs2 = Output(UInt(5.W))
  val rs1Val = Output(UInt(32.W))
  val rs2Val = Output(UInt(32.W))
  val rd = Output(UInt(5.W))
  val imm = Output(UInt(32.W))
  val alu_op = Output(UInt(5.W))
  val alu_op1 = Output(UInt(2.W))
  val alu_op2 = Output(UInt(2.W))
  val reg_write = Output(Bool())
  val mem_read = Output(Bool())
  val mem_write = Output(Bool())
  val mem_type = Output(UInt(3.W))
  val mem_unsigned = Output(Bool())
  val exception = Output(Bool())
  
}

class ExToExceptionIO extends Bundle {
  val valid     = Output(Bool())        // exception valid
  val cause     = Output(UInt(4.W))     // exception cause
  val pc        = Output(UInt(32.W))    // exception PC
  // val inst      = Output(UInt(32.W))    // exception Inst
  val memAddr   = Output(UInt(32.W))    // mem address (for load/store exceptions)
}

class ExceptionToExIO extends Bundle {
  val flush     = Output(Bool())        // flush signal
  val handlerPC = Output(UInt(32.W))    // exception handler PC
}

class ExToCSRIO extends Bundle {
  val readAddr  = Output(UInt(12.W))    // CSR address for read
  val readData  = Input(UInt(32.W))     // CSR data for read
  val writeEn   = Output(Bool())        // CSR write enable
  val writeAddr = Output(UInt(12.W))    // CSR address for write
  val writeData = Output(UInt(32.W))    // CSR data for write
}

class MemAccessIO extends Bundle {      // ex to mem access
  val reqValid  = Output(Bool())        // access requirement valid 
  val reqAddr   = Output(UInt(32.W))    // address and data to store
  val reqData   = Output(UInt(32.W))
  val reqWidth  = Output(UInt(2.W))     // 0=1B, 1=2B, 2=4B
  val reqWrite  = Output(Bool())        // 0 for read, 1 for write
  val memUnsigned = Output(Bool())
  val rspValid  = Input(Bool())
  val rspData   = Input(UInt(32.W))     // data for load
}

class DualIssueExIO extends Bundle {
  // Dual instruction inputs
  val ExIn1 = Flipped(new DeToExIO)
  val ExIn2 = Flipped(new DeToExIO)
  val pc1       = Input(UInt(32.W))
  val pc2       = Input(UInt(32.W))

  // Outputs
  val pc1Out    = Output(UInt(32.W))
  val pc2Out    = Output(UInt(32.W))
  val rd1       = Output(UInt(5.W))// direct connect
  val rd2       = Output(UInt(5.W))
  val result1   = Output(UInt(32.W))
  val result2   = Output(UInt(32.W))
  val valid1    = Output(Bool()) // result valid
  val valid2    = Output(Bool())

  // Exception interfaces
  val exception1 = new ExToExceptionIO
  val exception2 = new ExToExceptionIO
  val exceptionFeedback = Flipped(new ExceptionToExIO)

  // CSR interface
  val csr       = new ExToCSRIO

  // Memory interface
  val mem1      = new MemAccessIO
  val mem2      = new MemAccessIO

  // Branch/jump outputs
  val branch1   = Output(Bool())
  val branchTarget1 = Output(UInt(32.W))
  val branch2   = Output(Bool())
  val branchTarget2 = Output(UInt(32.W))

  // BP
  val bpPredict = Flipped(Valid(UInt(32.W))) // prediction address in
  val bpVerify = Output(Bool())              // result bool of prediction result: true or false
}

class DualIssueEx extends Module {
  val io = IO(new DualIssueExIO)

  // Default outputs
  io.pc1Out :=0.U
  io.pc2Out :=0.U
  io.rd1 := 0.U
  io.rd2 := 0.U
  io.result1 := 0.U
  io.result2 := 0.U
  io.valid1 := false.B
  io.valid2 := false.B
  io.branch1 := false.B
  io.branchTarget1 := 0.U
  io.branch2 := false.B
  io.branchTarget2 := 0.U

  // Exception defaults
  io.exception1.valid := false.B
  io.exception1.cause := 0.U
  io.exception1.pc := 0.U
  // io.exception1.inst := 0.U
  io.exception1.memAddr := 0.U
  
  io.exception2.valid := false.B
  io.exception2.cause := 0.U
  io.exception2.pc := 0.U
  // io.exception2.inst := 0.U
  io.exception2.memAddr := 0.U

  // CSR defaults
  io.csr.readAddr := 0.U
  io.csr.writeEn := false.B
  io.csr.writeAddr := 0.U
  io.csr.writeData := 0.U

  // Memory defaults
  io.mem1.reqValid := false.B
  io.mem1.reqAddr := 0.U
  io.mem1.reqData := 0.U
  io.mem1.reqWidth := 0.U
  io.mem1.reqWrite := false.B
  io.mem1.memUnsigned := false.B
  
  io.mem2.reqValid := false.B
  io.mem2.reqAddr := 0.U
  io.mem2.reqData := 0.U
  io.mem2.reqWidth := 0.U
  io.mem2.reqWrite := false.B
  io.mem2.memUnsigned := false.B

  // BP
  io.bpVerify := false.B

  // ALU operations
  def alu(op: UInt, op1: UInt, op2: UInt): (UInt, Bool) = {
    val result = WireDefault(0.U(32.W))
    val exception = WireDefault(false.B)
    
    switch(op) {
      is(OpNameFromDe.ALU_ADD)  { result := op1 + op2 }
      is(OpNameFromDe.ALU_SUB)  { result := op1 - op2 }
      is(OpNameFromDe.ALU_SLL)  { result := op1 << op2(4,0) }
      is(OpNameFromDe.ALU_SLT)  { result := Mux(op1.asSInt < op2.asSInt, 1.U, 0.U) }
      is(OpNameFromDe.ALU_SLTU) { result := Mux(op1 < op2, 1.U, 0.U) }
      is(OpNameFromDe.ALU_XOR)  { result := op1 ^ op2 }
      is(OpNameFromDe.ALU_SRL)  { result := op1 >> op2(4,0) }
      is(OpNameFromDe.ALU_SRA)  { result := (op1.asSInt >> op2(4,0)).asUInt }
      is(OpNameFromDe.ALU_OR)   { result := op1 | op2 }
      is(OpNameFromDe.ALU_AND)  { result := op1 & op2 }
      is(OpNameFromDe.ALU_COPY) { result := op2 } // For LUI, JAL, JALR
      is(OpNameFromDe.ALU_MUL) {
        result := (op1 * op2)(31, 0)
      }
      is(OpNameFromDe.ALU_MULH) {
        val product = (op1.asSInt * op2.asSInt).asUInt
        result := product(63, 32)
      }
      is(OpNameFromDe.ALU_MULHSU) {
        val product = (op1.asSInt * op2.asUInt.zext()).asUInt
        result := product(63, 32)
      }
      is(OpNameFromDe.ALU_MULHU) {
        val product = op1 * op2
        result := (product >> 32.U)(31, 0)
      }
      is(OpNameFromDe.ALU_DIV) {
        when(op2 === 0.U) {
          result := Fill(32, 1.U)
          exception := true.B
        }.otherwise {
          result := (op1.asSInt / op2.asSInt).asUInt
        }
      }
      is(OpNameFromDe.ALU_DIVU) {
        when(op2 === 0.U) {
          result := Fill(32, 1.U)
          exception := true.B
        }.otherwise {
          result := op1 / op2
        }
      }
      is(OpNameFromDe.ALU_REM) {
        when(op2 === 0.U) {
          result := op1
          exception := true.B
        }.otherwise {
          result := (op1.asSInt % op2.asSInt).asUInt
        }
      }
      is(OpNameFromDe.ALU_REMU) {
        when(op2 === 0.U) {
          result := op1
          exception := true.B
        }.otherwise {
          result := op1 % op2
        }
      }
    }
    (result, exception)
    }
    // result
  

  // Branch condition evaluation
  def branchEval(op: UInt, rs1Val: UInt, rs2Val: UInt): Bool = {
    val taken = WireDefault(false.B)
    
    switch(op) {
      is(OpNameFromDe.BR_BEQ)  { taken := rs1Val === rs2Val }
      is(OpNameFromDe.BR_BNE)  { taken := rs1Val =/= rs2Val }
      is(OpNameFromDe.BR_BLT)  { taken := rs1Val.asSInt < rs2Val.asSInt }
      is(OpNameFromDe.BR_BGE)  { taken := rs1Val.asSInt >= rs2Val.asSInt }
      is(OpNameFromDe.BR_BLTU) { taken := rs1Val < rs2Val }
      is(OpNameFromDe.BR_BGEU) { taken := rs1Val >= rs2Val }
    }
    taken
  }

  // Output register destinations
  io.rd1 := io.ExIn1.rd
  io.rd2 := io.ExIn2.rd

  // Inst 1
  val op1_1 = MuxLookup(io.ExIn1.alu_op1, 0.U, Seq(
    OpSourceFromDe.alu_op_reg -> io.ExIn1.rs1Val,
    OpSourceFromDe.alu_op_imm -> io.ExIn1.imm,
    OpSourceFromDe.alu_op_pc -> io.pc1,
    OpSourceFromDe.alu_op_zero -> 0.U
  ))
  
  val op2_1 = MuxLookup(io.ExIn1.alu_op2, 0.U, Seq(
    OpSourceFromDe.alu_op_reg -> io.ExIn1.rs2Val,
    OpSourceFromDe.alu_op_imm -> io.ExIn1.imm,
    OpSourceFromDe.alu_op_pc -> io.pc1,
    OpSourceFromDe.alu_op_zero -> 0.U
  ))

 val (aluResult1, aluException1) = alu(io.ExIn1.alu_op, op1_1, op2_1)

  when(aluException1) {
    io.exception1.valid := true.B
    io.exception1.cause := ExceptionCause.DIVIDE_BY_ZERO
    io.exception1.pc := io.pc1
    // io.exception1.inst := io.ExIn1.inst
  }
  
  // branches/jumps for instruction 1
  when(io.ExIn1.inst_type === InstTypeFromDe.B_TYPE) {
    io.branch1 := branchEval(io.ExIn1.alu_op, op1_1, op2_1)
    io.branchTarget1 := aluResult1 // should be io.pc1 + io.ExIn1.imm
  }.elsewhen(io.ExIn1.inst_type === InstTypeFromDe.J_TYPE) {
    io.branch1 := true.B
    io.branchTarget1 := aluResult1
  }

  

  // memory operations for instruction 1
  when(io.ExIn1.mem_read || io.ExIn1.mem_write) {
    io.mem1.reqValid := true.B
    io.mem1.reqAddr := Mux(io.ExIn1.mem_write, aluResult1, io.ExIn1.rd)
    io.mem1.reqData := Mux(io.ExIn1.mem_read, 
                        Mux(io.ExIn1.mem_unsigned, 
                          Mux(io.ExIn1.mem_type(0),
                            aluResult1(7,0),
                            aluResult1(15,0)),
                          Mux(io.ExIn1.mem_type(1),
                            Mux(io.ExIn1.mem_type(0),
                                aluResult1,
                                Cat(Fill(16,aluResult1(15)),aluResult1(15,0))),
                            Cat(Fill(24,aluResult1(7)),aluResult1(7,0)))),
                        Mux(io.ExIn1.mem_write,
                          Mux(io.ExIn1.mem_type(1),
                            Mux(io.ExIn1.mem_type(0),
                              op2_1,
                              op2_1(15,0)),
                            op2_1(7,0)),
                          aluResult1 ))
    io.mem1.reqWidth := io.ExIn1.mem_type
    io.mem1.reqWrite := io.ExIn1.mem_write
    io.mem1.memUnsigned := io.ExIn1.mem_unsigned
    
    // Check for misaligned accesses
    when(io.ExIn1.mem_type === 1.U && aluResult1(0) =/= 0.U ||  // Half-word misaligned
        io.ExIn1.mem_type === 2.U && aluResult1(1,0) =/= 0.U) { // Word misaligned
      io.exception1.valid := true.B
      io.exception1.cause := Mux(io.ExIn1.mem_write, ExceptionCause.STORE_MISALIGN, ExceptionCause.LOAD_MISALIGN)
      io.exception1.pc := io.pc1
      // io.exception2.inst := io.EXIn2.inst
      io.exception1.memAddr := aluResult1
    }
  }

  // CSR operations for instruction 1
  when(io.ExIn1.inst_type === InstTypeFromDe.CSR_TYPE) {
    io.csr.readAddr := io.ExIn1.imm(11,0)
    io.csr.writeEn := true.B
    io.csr.writeAddr := io.ExIn1.imm(11,0)
    
    when(io.ExIn1.alu_op === OpNameFromDe.ALU_CSR_W) {
      io.csr.writeData := op1_1
    }.elsewhen(io.ExIn1.alu_op === OpNameFromDe.ALU_CSR_S) {
      io.csr.writeData := io.csr.readData | op1_1
    }.elsewhen(io.ExIn1.alu_op === OpNameFromDe.ALU_CSR_C) {
      io.csr.writeData := io.csr.readData & ~op1_1
    }
  }

  // memory responses: load
  when(io.mem1.rspValid) {
    io.result1 := io.mem1.rspData
    io.valid1 := true.B
  }.otherwise {
    io.result1 := aluResult1
    io.valid1 := io.ExIn1.reg_write && !io.ExIn1.exception
  }


  // Inst 2
  val op1_2 = MuxLookup(io.ExIn2.alu_op1, 0.U, Seq(
    OpSourceFromDe.alu_op_reg -> io.ExIn2.rs1,
    OpSourceFromDe.alu_op_imm -> io.ExIn2.imm,
    OpSourceFromDe.alu_op_pc -> io.pc2,
    OpSourceFromDe.alu_op_zero -> 0.U
  ))
  
  val op2_2 = MuxLookup(io.ExIn2.alu_op2, 0.U, Seq(
    OpSourceFromDe.alu_op_reg -> io.ExIn2.rs2,
    OpSourceFromDe.alu_op_imm -> io.ExIn2.imm,
    OpSourceFromDe.alu_op_pc -> io.pc2,
    OpSourceFromDe.alu_op_zero -> 0.U
  ))

  val (aluResult2, aluException2) = alu(io.ExIn2.alu_op, op1_2, op2_2)

   when(aluException2) {
    io.exception2.valid := true.B
    io.exception2.cause := ExceptionCause.DIVIDE_BY_ZERO
    io.exception2.pc := io.pc2
    // io.exception2.inst := io.EXIn2.inst
  }
  
  // branches/jumps for instruction 2
  when(io.ExIn2.inst_type === InstTypeFromDe.B_TYPE) {
    io.branch2 := branchEval(io.ExIn2.alu_op, op1_2, op2_2)
    io.branchTarget2 := aluResult2 // should be io.pc2 + io.EXIn2.imm
  }.elsewhen(io.ExIn2.inst_type === InstTypeFromDe.J_TYPE) {
    io.branch2 := true.B
    io.branchTarget2 := aluResult2
  }

  // memory operations for instruction 2
  when(io.ExIn2.mem_read || io.ExIn2.mem_write) {
    io.mem2.reqValid := true.B
    io.mem2.reqAddr := Mux(io.ExIn2.mem_write, aluResult2, io.ExIn2.rd)
    io.mem2.reqData := Mux(io.ExIn2.mem_read, 
                        Mux(io.ExIn2.mem_unsigned, 
                          Mux(io.ExIn2.mem_type(0),
                            aluResult2(7,0),
                            aluResult2(15,0)),
                          Mux(io.ExIn2.mem_type(1),
                            Mux(io.ExIn2.mem_type(0),
                                aluResult2,
                                Cat(Fill(16,aluResult2(15)),aluResult2(15,0))),
                            Cat(Fill(24,aluResult2(7)),aluResult2(7,0)))),
                        Mux(io.ExIn2.mem_write,
                          Mux(io.ExIn2.mem_type(1),
                            Mux(io.ExIn2.mem_type(0),
                              op2_2,
                              op2_2(15,0)),
                            op2_2(7,0)),
                          aluResult2 ))
    io.mem2.reqWidth := io.ExIn2.mem_type
    io.mem2.reqWrite := io.ExIn2.mem_write
    io.mem2.memUnsigned := io.ExIn2.mem_unsigned
    
    // Check for misaligned accesses
    when(io.ExIn2.mem_type === 1.U && aluResult2(0) =/= 0.U ||  // Half-word misaligned
        io.ExIn2.mem_type === 2.U && aluResult2(1,0) =/= 0.U) { // Word misaligned
      io.exception2.valid := true.B
      io.exception2.cause := Mux(io.ExIn2.mem_write, ExceptionCause.STORE_MISALIGN, ExceptionCause.LOAD_MISALIGN)
      io.exception2.pc := io.pc2
      // io.exception2.inst := io.EXIn2.inst
      io.exception2.memAddr := aluResult2
    }
  }

  // CSR operations for instruction 2
  when(io.ExIn2.inst_type === InstTypeFromDe.CSR_TYPE) {
    io.csr.readAddr := io.ExIn2.imm(11,0)
    io.csr.writeEn := true.B
    io.csr.writeAddr := io.ExIn2.imm(11,0)
    
    when(io.ExIn2.alu_op === OpNameFromDe.ALU_CSR_W) {
      io.csr.writeData := op1_2
    }.elsewhen(io.ExIn2.alu_op === OpNameFromDe.ALU_CSR_S) {
      io.csr.writeData := io.csr.readData | op1_2
    }.elsewhen(io.ExIn2.alu_op === OpNameFromDe.ALU_CSR_C) {
      io.csr.writeData := io.csr.readData & ~op1_2
    }
  }

  when(io.mem2.rspValid) {
    io.result2 := io.mem2.rspData
    io.valid2 := true.B
  }.otherwise {
    io.result2 := aluResult2
    io.valid2 := io.ExIn2.reg_write && !io.ExIn2.exception
  }


  // exceptions
  when(io.exceptionFeedback.flush) {
    // Flush pipeline on exception
    io.valid1 := false.B
    io.valid2 := false.B
    io.branch1 := false.B
    io.branch2 := false.B
    io.mem1.reqValid := false.B
    io.mem2.reqValid := false.B
  }

  // BP
  // FIFO of depth 4
  val bpFifo = Module(new Queue(UInt(32.W), entries = 4))
  bpFifo.io.enq.valid := io.bpPredict.valid
  bpFifo.io.enq.bits := io.bpPredict.bits
  bpFifo.io.deq.ready := false.B
  val verifyTarget = WireDefault(false.B)
  
  // varify inst 1
  val verify1 = io.ExIn1.inst_type === InstTypeFromDe.B_TYPE || 
               io.ExIn1.inst_type === InstTypeFromDe.J_TYPE
        
  when(verify1 && bpFifo.io.deq.valid) {
    val predTarget = bpFifo.io.deq.bits
    val realTarget = Mux(io.ExIn1.inst_type === InstTypeFromDe.B_TYPE, 
                        io.branchTarget1,
                        aluResult1)
    verifyTarget := predTarget === realTarget
    bpFifo.io.deq.ready := true.B
  }

  // varify of inst 2
  val verify2 = io.ExIn2.inst_type === InstTypeFromDe.B_TYPE || 
               io.ExIn2.inst_type === InstTypeFromDe.J_TYPE
  when(verify2 && bpFifo.io.deq.valid) {
    val predTarget = bpFifo.io.deq.bits
    val realTarget = Mux(io.ExIn2.inst_type === InstTypeFromDe.B_TYPE, 
                        io.branchTarget2,
                        aluResult2)
    verifyTarget := predTarget === realTarget
    bpFifo.io.deq.ready := true.B
  }

  // default
  when(!verify1 && !verify2) {
    bpFifo.io.deq.ready := false.B
    verifyTarget := false.B
  }

  io.bpVerify := verifyTarget
}

