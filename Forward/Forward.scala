package Forward

import chisel3._
import chisel3.util._

class ForwardIO extends Bundle {
  private val numOperandsFixed: Int = 2 // 固定为2个操作数

  // --- 输入 ---
  // ID阶段当前指令的源寄存器信息
  // 主指令 (Master)
  val id_master_rs_addrs   = Input(Vec(numOperandsFixed, UInt(5.W))) //注意这里是两个vec，也就是说每个指令有两个操作数
  val id_master_rs_data_rf = Input(Vec(numOperandsFixed, UInt(32.W))) // 从ID阶段拿到的数据，注意，数据不再由ID送给id_ex，而是由ID送给Forward，继而送给id_ex

  // 从指令 (Slave)
  val id_slave_rs_addrs    = Input(Vec(numOperandsFixed, UInt(5.W)))
  val id_slave_rs_data_rf  = Input(Vec(numOperandsFixed, UInt(32.W)))

  // 前馈源:
  // 1. 来自当前EX阶段的有效ALU结果 - 主路径 ("EX -> ID" 前馈)
  val ex_master_alu_valid   = Input(Bool())    // EX主路径ALU结果有效且指令写GPR  id_ex.E_master_ctrl_sign.reg_write && !id_ex.E_master_ctrl_sign.mem_read && !id_ex.E_master_ctrl_sign.mem_write && DualIssueExIO.valid1
  val ex_master_alu_rd_addr = Input(UInt(5.W)) // EX主路径指令的目标寄存器  DualIssueExIO.rd1
  val ex_master_alu_data    = Input(UInt(32.W))  // EX主路径ALU结果数据  DualIssueExIO.result1

  // 2. 来自当前EX阶段的有效ALU结果 - 从路径 ("EX -> ID" 前馈)
  val ex_slave_alu_valid   = Input(Bool())     // EX从路径ALU结果有效且指令写GPR  这些与上同理，换为slave即可
  val ex_slave_alu_rd_addr = Input(UInt(5.W))  // EX从路径指令的目标寄存器
  val ex_slave_alu_data    = Input(UInt(32.W))   // EX从路径ALU结果数据

  // 3. 来自EX/MEM流水线寄存器输出的有效ALU结果 (MEM阶段ALU数据透传) - 主路径
  val exmem_master_alu_valid   = Input(Bool())    // EX/MEM主路径ALU结果有效且指令写GPR  !ex_mem.M_master_ctrl_sign.mem_write && !ex_mem.M_master_ctrl_sign.mem_read && (!(ex_mem.W_rd1===0.U)||!(ex_mem.W_rd2===0.U))
  val exmem_master_alu_rd_addr = Input(UInt(5.W)) // EX/MEM主路径指令的目标寄存器 ex_mem.W_rd1
  val exmem_master_alu_data    = Input(UInt(32.W))  // EX/MEM主路径ALU结果数据  ex_mem的寄存器写数据 ex_mem.M_master_ctrl_sign.mem_wdata

  // 4. 来自EX/MEM流水线寄存器输出的有效ALU结果 (MEM阶段ALU数据透传) - 从路径
  val exmem_slave_alu_valid   = Input(Bool())     // EX/MEM从路径ALU结果有效且指令写GPR
  val exmem_slave_alu_rd_addr = Input(UInt(5.W))  // EX/MEM从路径指令的目标寄存器
  val exmem_slave_alu_data    = Input(UInt(32.W))   // EX/MEM从路径ALU结果数据

  // 5. 来自MEM阶段实际访存的有效Load结果
  val mem_load_valid       = Input(Bool())    // MEM阶段Load数据有效且指令写GPR  MEM.io.cpu.mem_bypass_valid && ex_mem.M_master_ctrl_sign.mem_en && !ex_mem.M_master_ctrl_sign.mem_write
  val mem_load_rd_addr     = Input(UInt(5.W)) // MEM阶段Load指令的目标寄存器  ex_mem.W_rd1
  val mem_load_data        = Input(UInt(32.W))  // MEM阶段Load出的数据  MEM.io.cpu.mem_bypass_data

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
  private val numOperandsFixed: Int = 2

  // 辅助函数，为单个操作数选择前馈数据
  // 优先级顺序:
  // 1. EX_Slave_ALU (当前周期的EX从路径ALU结果)
  // 2. EX_Master_ALU (当前周期的EX主路径ALU结果)
  // 3. EXMEM_Slave_ALU (来自EX/MEM寄存器，上周期的EX从路径结果)
  // 4. EXMEM_Master_ALU (来自EX/MEM寄存器，上周期的EX主路径结果)
  // 5. MEM_Load_Data (来自内存系统的Load结果，当前周期在MEM阶段有效)
  // 6. RegisterFile_Data (默认，已包含WB->ID旁路)
  def generateForwardedData(
                             current_id_rs_addr: UInt, // 当前ID阶段指令的某个源寄存器地址
                             current_id_rs_data_rf: UInt, // 从寄存器堆为该地址读出的数据 (已包含WB->ID旁路)
                             // 当前EX阶段ALU结果
                             ex_m_alu_valid_in: Bool, ex_m_alu_rd_addr_in: UInt, ex_m_alu_data_in: UInt,
                             ex_s_alu_valid_in: Bool, ex_s_alu_rd_addr_in: UInt, ex_s_alu_data_in: UInt,
                             // EX/MEM寄存器输出的ALU结果 (MEM阶段ALU透传)
                             exmem_m_alu_valid_in: Bool, exmem_m_alu_rd_addr_in: UInt, exmem_m_alu_data_in: UInt,
                             exmem_s_alu_valid_in: Bool, exmem_s_alu_rd_addr_in: UInt, exmem_s_alu_data_in: UInt,
                             // MEM阶段Load结果
                             mem_ld_valid_in: Bool, mem_ld_rd_addr_in: UInt, mem_ld_data_in: UInt
                           ): UInt = {

    // 条件：源有效(valid)，目标地址匹配(rd_addr)，且目标不是x0 (零寄存器)
    val ex_master_alu_can_fwd = ex_m_alu_valid_in && (ex_m_alu_rd_addr_in === current_id_rs_addr) && (current_id_rs_addr =/= 0.U)
    val ex_slave_alu_can_fwd = ex_s_alu_valid_in && (ex_s_alu_rd_addr_in === current_id_rs_addr) && (current_id_rs_addr =/= 0.U)

    val exmem_master_alu_can_fwd = exmem_m_alu_valid_in && (exmem_m_alu_rd_addr_in === current_id_rs_addr) && (current_id_rs_addr =/= 0.U)
    val exmem_slave_alu_can_fwd = exmem_s_alu_valid_in && (exmem_s_alu_rd_addr_in === current_id_rs_addr) && (current_id_rs_addr =/= 0.U)

    val mem_load_can_fwd = mem_ld_valid_in && (mem_ld_rd_addr_in === current_id_rs_addr) && (current_id_rs_addr =/= 0.U)

    MuxCase(current_id_rs_data_rf, Seq(
      ex_slave_alu_can_fwd -> ex_s_alu_data_in, // 优先级 1: 当前EX从路径ALU结果
      ex_master_alu_can_fwd -> ex_m_alu_data_in, // 优先级 2: 当前EX主路径ALU结果
      exmem_slave_alu_can_fwd -> exmem_s_alu_data_in, // 优先级 3: EX/MEM从路径ALU结果
      exmem_master_alu_can_fwd -> exmem_m_alu_data_in, // 优先级 4: EX/MEM主路径ALU结果
      mem_load_can_fwd -> mem_ld_data_in // 优先级 5: MEM Load结果
    ))
  }

  // 为主指令的每个源操作数生成最终数据
  for (i <- 0 until numOperandsFixed) {
    io.final_master_rs_data(i) := generateForwardedData(
      io.id_master_rs_addrs(i),
      io.id_master_rs_data_rf(i),
      io.ex_master_alu_valid, io.ex_master_alu_rd_addr, io.ex_master_alu_data,
      io.ex_slave_alu_valid, io.ex_slave_alu_rd_addr, io.ex_slave_alu_data,
      io.exmem_master_alu_valid, io.exmem_master_alu_rd_addr, io.exmem_master_alu_data,
      io.exmem_slave_alu_valid, io.exmem_slave_alu_rd_addr, io.exmem_slave_alu_data,
      io.mem_load_valid, io.mem_load_rd_addr, io.mem_load_data
    )
  }

  // 为从指令的每个源操作数生成最终数据
  for (i <- 0 until numOperandsFixed) {
    io.final_slave_rs_data(i) := generateForwardedData(
      io.id_slave_rs_addrs(i),
      io.id_slave_rs_data_rf(i),
      io.ex_master_alu_valid, io.ex_master_alu_rd_addr, io.ex_master_alu_data,
      io.ex_slave_alu_valid, io.ex_slave_alu_rd_addr, io.ex_slave_alu_data,
      io.exmem_master_alu_valid, io.exmem_master_alu_rd_addr, io.exmem_master_alu_data,
      io.exmem_slave_alu_valid, io.exmem_slave_alu_rd_addr, io.exmem_slave_alu_data,
      io.mem_load_valid, io.mem_load_rd_addr, io.mem_load_data
    )
  }
}
