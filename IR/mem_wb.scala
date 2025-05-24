import chisel3._
import chisel3.util._

// 根据 defines.vh 定义对应的 Chisel Bundles
/*class ctrl_sign extends Bundle {
  val aluop = UInt(8.W)         // ALU操作码，指示ALU执行的具体操作
  val read_rs = Bool()          // 读取源寄存器rs
  val read_rt = Bool()          // 读取源寄存器rt
  val reg_write = Bool()        // 寄存器写使能
  val mem_en = Bool()           // 内存使能
  val mem_write_reg = Bool()    // 内存写回寄存器
  val mem_read = Bool()         // 内存读使能
  val mem_write = Bool()        // 内存写使能
  val may_bring_flush = Bool()  // 可能引发刷新
  val only_one_issue = Bool()   // 仅允许一个指令发射
  val icache_fence = Bool()     // 指令缓存隔离
  val dcache_fence = Bool()     // 数据缓存隔离
}
*/

class mem_wb extends Module {
  val io = IO(new Bundle {
    val clear1 = Input(Bool())
    val clear2 = Input(Bool())
    val ena1 = Input(Bool())
    val ena2 = Input(Bool())

    // Master通道信号
    // val M_master_ctrl_sign = Input(new ctrl_sign())
    val M_master_reg_wen = Input(Bool())
    val M_master_reg_waddr = Input(UInt(5.W))
    val M_master_inst = Input(UInt(32.W))
    val M_master_pc = Input(UInt(32.W))
    val M_master_reg_wdata = Input(UInt(32.W))
    
    // val W_master_ctrl_sign = Output(new ctrl_sign())
    val W_master_reg_wen = Output(Bool())
    val W_master_reg_waddr = Output(UInt(5.W))
    val W_master_inst = Output(UInt(32.W))
    val W_master_pc = Output(UInt(32.W))
    val W_master_reg_wdata = Output(UInt(32.W))

    // Slave通道信号
    // val M_slave_ctrl_sign = Input(new ctrl_sign())
    val M_slave_reg_wen = Input(Bool())
    val M_slave_reg_waddr = Input(UInt(5.W))
    val M_slave_inst = Input(UInt(32.W))
    val M_slave_pc = Input(UInt(32.W))
    val M_slave_reg_wdata = Input(UInt(32.W))
    
    // val W_slave_ctrl_sign = Output(new ctrl_sign())
    val W_slave_reg_wen = Output(Bool())
    val W_slave_reg_waddr = Output(UInt(5.W))
    val W_slave_inst = Output(UInt(32.W))
    val W_slave_pc = Output(UInt(32.W))
    val W_slave_reg_wdata = Output(UInt(32.W))
  })

  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    // val ctrl_sign = new ctrl_sign()
    val reg_wen = Bool()
    val reg_waddr = UInt(5.W)
    val inst = UInt(32.W)
    val pc = UInt(32.W)
    val reg_wdata = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    // val ctrl_sign = new ctrl_sign()
    val reg_wen = Bool()
    val reg_waddr = UInt(5.W)
    val inst = UInt(32.W)
    val pc = UInt(32.W)
    val reg_wdata = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() | io.clear1) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena1) {
    // masterReg.ctrl_sign := io.M_master_ctrl_sign
    masterReg.reg_wen := io.M_master_reg_wen
    masterReg.reg_waddr := io.M_master_reg_waddr
    masterReg.inst := io.M_master_inst
    masterReg.pc := io.M_master_pc
    masterReg.reg_wdata := io.M_master_reg_wdata
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() | io.clear2) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena2) {
    // slaveReg.ctrl_sign := io.M_slave_ctrl_sign
    slaveReg.reg_wen := io.M_slave_reg_wen
    slaveReg.reg_waddr := io.M_slave_reg_waddr
    slaveReg.inst := io.M_slave_inst
    slaveReg.pc := io.M_slave_pc
    slaveReg.reg_wdata := io.M_slave_reg_wdata
  }

  // 连接输出
  // io.W_master_ctrl_sign := masterReg.ctrl_sign
  io.W_master_reg_wen := masterReg.reg_wen
  io.W_master_reg_waddr := masterReg.reg_waddr
  io.W_master_inst := masterReg.inst
  io.W_master_pc := masterReg.pc
  io.W_master_reg_wdata := masterReg.reg_wdata

  // io.W_slave_ctrl_sign := slaveReg.ctrl_sign
  io.W_slave_reg_wen := slaveReg.reg_wen
  io.W_slave_reg_waddr := slaveReg.reg_waddr
  io.W_slave_inst := slaveReg.inst
  io.W_slave_pc := slaveReg.pc
  io.W_slave_reg_wdata := slaveReg.reg_wdata
}