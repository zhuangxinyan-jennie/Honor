package Forward

import chisel3._
import chisel3.util._

/**
 * ForwardIO (Forward Module IO Bundle)
 * Forward模块的输入输出接口
 * 固定为双操作数，双发射设计
 */
class ForwardIO extends Bundle {
  private val numOperandsFixed: Int = 2 // 固定为2个操作数

  // --- 输入 ---
  // ID阶段当前指令的源寄存器信息
  // 主指令 (Master)
  val id_master_rs_addrs   = Input(Vec(numOperandsFixed, UInt(5.W)))
  val id_master_rs_data_rf = Input(Vec(numOperandsFixed, UInt(32.W))) // 从寄存器堆读出的数据 (已包含WB->ID旁路)

  // 从指令 (Slave)
  val id_slave_rs_addrs    = Input(Vec(numOperandsFixed, UInt(5.W)))
  val id_slave_rs_data_rf  = Input(Vec(numOperandsFixed, UInt(32.W)))

  // 前馈源: 来自EX阶段的有效结果 - 主路径
  val ex_master_valid   = Input(Bool())
  val ex_master_rd_addr = Input(UInt(5.W))
  val ex_master_data    = Input(UInt(32.W))

  // 前馈源: 来自EX阶段的有效结果 - 从路径
  val ex_slave_valid   = Input(Bool())
  val ex_slave_rd_addr = Input(UInt(5.W))
  val ex_slave_data    = Input(UInt(32.W))

  // 前馈源: 来自MEM阶段的有效结果
  val mem_valid       = Input(Bool())
  val mem_rd_addr     = Input(UInt(5.W))
  val mem_data        = Input(UInt(32.W))

  // --- 输出 ---
  // 经过前馈选择后的最终操作数值，送往ID/EX寄存器
  val final_master_rs_data = Output(Vec(numOperandsFixed, UInt(32.W)))
  val final_slave_rs_data  = Output(Vec(numOperandsFixed, UInt(32.W)))
}

/**
 * Forward (Forwarding Unit Module)
 * 实现ID阶段的组合逻辑前馈网络
 * 固定为双操作数，双发射设计
 */
class Forward extends Module {
  val io = IO(new ForwardIO())
  private val numOperandsFixed: Int = 2 // 固定为2个操作数

  // 辅助函数，为单个操作数选择前馈数据
  // 优先级顺序: EX_Slave -> EX_Master -> MEM -> RegisterFile_Data (默认)
  def generateForwardedData(
                             current_id_rs_addr: UInt,         // 当前ID阶段指令的某个源寄存器地址
                             current_id_rs_data_rf: UInt,    // 从寄存器堆为该地址读出的数据 (已包含WB->ID旁路)
                             // EX主路径前馈源
                             ex_m_valid: Bool,
                             ex_m_rd_addr: UInt,
                             ex_m_data: UInt,
                             // EX从路径前馈源
                             ex_s_valid: Bool,
                             ex_s_rd_addr: UInt,
                             ex_s_data: UInt,
                             // MEM路径前馈源
                             mem_s_valid: Bool,
                             mem_s_rd_addr: UInt,
                             mem_s_data: UInt
                           ): UInt = {

    // 条件：源有效(valid)，目标地址匹配(rd_addr)，且目标不是x0 (零寄存器)
    val ex_master_can_forward = ex_m_valid &&
      (ex_m_rd_addr === current_id_rs_addr) &&
      (current_id_rs_addr =/= 0.U)

    val ex_slave_can_forward =  ex_s_valid &&
      (ex_s_rd_addr === current_id_rs_addr) &&
      (current_id_rs_addr =/= 0.U)

    val mem_can_forward = mem_s_valid &&
      (mem_s_rd_addr === current_id_rs_addr) &&
      (current_id_rs_addr =/= 0.U)

    // 使用MuxCase实现带优先级的选择
    // 将EX从路径的优先级调整到EX主路径之前
    MuxCase(current_id_rs_data_rf, Seq( // 默认值是从寄存器堆读取的数据
      ex_slave_can_forward  -> ex_s_data,       // 优先: EX从路径
      ex_master_can_forward -> ex_m_data,       // 其次: EX主路径
      mem_can_forward       -> mem_s_data        // 最后: MEM路径
    ))
  }

  // 为主指令的每个源操作数生成最终数据
  for (i <- 0 until numOperandsFixed) {
    io.final_master_rs_data(i) := generateForwardedData(
      io.id_master_rs_addrs(i),
      io.id_master_rs_data_rf(i),
      io.ex_master_valid,
      io.ex_master_rd_addr,
      io.ex_master_data,
      io.ex_slave_valid,
      io.ex_slave_rd_addr,
      io.ex_slave_data,
      io.mem_valid,
      io.mem_rd_addr,
      io.mem_data
    )
  }

  // 为从指令的每个源操作数生成最终数据
  for (i <- 0 until numOperandsFixed) {
    io.final_slave_rs_data(i) := generateForwardedData(
      io.id_slave_rs_addrs(i),
      io.id_slave_rs_data_rf(i),
      io.ex_master_valid,
      io.ex_master_rd_addr,
      io.ex_master_data,
      io.ex_slave_valid,
      io.ex_slave_rd_addr,
      io.ex_slave_data,
      io.mem_valid,
      io.mem_rd_addr,
      io.mem_data
    )
  }
}