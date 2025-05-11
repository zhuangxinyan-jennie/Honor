import chisel3._
import chisel3.util._

class ControlUnit extends Module {
  val io = IO(new Bundle {
    // Input signals
    val CU_ID_ReadRS1_I = Input(Bool())
    val CU_ID_ReadRS2_I = Input(Bool())
    val CU_ID_RS1_Addr_I = Input(UInt(5.W))
    val CU_ID_RS2_Addr_I = Input(UInt(5.W))
    val CU_EXE_MasterMemToReg_I = Input(Bool())
    val CU_EXE_MasterWriteAddr_I = Input(UInt(5.W))
    val CU_EXE_SlaveMemToReg_I = Input(Bool())
    val CU_EXE_SlaveWriteAddr_I = Input(UInt(5.W))
    val CU_IF_Stall_I = Input(Bool())
    val CU_EXE_ALUStall_I = Input(Bool())
    val CU_MEM_Stall_I = Input(Bool())
    val CU_MEM_Exception_I = Input(Bool())
    val CU_MEM_FlushAll_I = Input(Bool())
    val CU_EXE_Branch_I = Input(Bool())
    val CU_ID_Branch_I = Input(Bool())

    // Output signals
    val CU_IF_ENA_O = Output(Bool())
    val CU_ID_ENA_O = Output(Bool())
    val CU_EXE_ENA_O = Output(Bool())
    val CU_MEM_ENA_O = Output(Bool())
    val CU_WB_ENA_O = Output(Bool())
    val CU_IF_Flush_O = Output(Bool())
    val CU_ID_Flush_O = Output(Bool())
    val CU_EXE_Flush_O = Output(Bool())
    val CU_MEM_Flush_O = Output(Bool())
    val CU_WB_Flush_O = Output(Bool())
    val CU_DelaySlotFlush_O = Output(Bool())
  })

  // 检测Load-Use冒险 (lwstall)
  val loadUseHazard = Wire(Bool())
  loadUseHazard := (io.CU_EXE_MasterMemToReg_I && (io.CU_EXE_MasterWriteAddr_I.orR) &&
    ((io.CU_ID_ReadRS1_I && (io.CU_ID_RS1_Addr_I === io.CU_EXE_MasterWriteAddr_I)) ||
      (io.CU_ID_ReadRS2_I && (io.CU_ID_RS2_Addr_I === io.CU_EXE_MasterWriteAddr_I)))) ||
    (io.CU_EXE_SlaveMemToReg_I && (io.CU_EXE_SlaveWriteAddr_I.orR) &&
      ((io.CU_ID_ReadRS1_I && (io.CU_ID_RS1_Addr_I === io.CU_EXE_SlaveWriteAddr_I)) ||
        (io.CU_ID_ReadRS2_I && (io.CU_ID_RS2_Addr_I === io.CU_EXE_SlaveWriteAddr_I))))

  // 控制冒险信号 (来自分支预测错误)
  val branchHazard = io.CU_EXE_Branch_I || io.CU_ID_Branch_I

  // 最长停顿信号 (longest_stall)
  val longestStall =
    io.CU_EXE_ALUStall_I || io.CU_IF_Stall_I || io.CU_MEM_Stall_I

  // 异常和全局冲刷信号
  val exception = io.CU_MEM_Exception_I
  val flushAll = io.CU_MEM_FlushAll_I

  io.CU_IF_ENA_O := !io.CU_IF_Stall_I // 只受IF阶段停顿影响，不受其他停顿影响
  io.CU_ID_ENA_O := !(loadUseHazard || longestStall)
  io.CU_EXE_ENA_O := !longestStall
  io.CU_MEM_ENA_O := !longestStall
  io.CU_WB_ENA_O := (!longestStall) || exception || flushAll // WB阶段特殊处理

  io.CU_IF_Flush_O := false.B // IF阶段不冲刷
  io.CU_ID_Flush_O := exception || flushAll || io.CU_EXE_Branch_I || io.CU_ID_Branch_I
  io.CU_EXE_Flush_O := exception || flushAll || io.CU_EXE_Branch_I
  io.CU_MEM_Flush_O := exception || flushAll
  io.CU_WB_Flush_O := false.B // WB阶段不冲刷

  // 延迟槽冲刷信号
  io.CU_DelaySlotFlush_O := exception || flushAll
}

