import chisel3._
import chisel3.util._

class ex_mem_ctrl_sign extends Bundle {
  val mem_en = Bool()           // 内存使能
  val mem_write = Bool()        // 内存写回寄存器
  val mem_type = UInt(2.W)        // 内存写使能
  val mem_unsigned = Bool()        // 内存无符号访存使能
  val mem_wdata = UInt(32.W)      // 内存写数据
  val mem_addr = UInt(32.W)       // 内存地址
}

class ex_mem extends Module {
  val io = IO(new Bundle {
    val clear1 = Input(Bool())
    val clear2 = Input(Bool())
    val ena1 = Input(Bool())
    val ena2 = Input(Bool())

    // Master通道输入
    val E_master_ctrl_sign = Input(new ex_mem_ctrl_sign())
    val E_master_inst = Input(UInt(32.W))
    val E_master_pc = Input(UInt(32.W))
    
    // Slave通道输入
    val E_slave_ctrl_sign = Input(new ex_mem_ctrl_sign())
    val E_slave_pc = Input(UInt(32.W))
    val E_slave_inst = Input(UInt(32.W))

    // Master通道输出
    val M_master_ctrl_sign = Output(new ex_mem_ctrl_sign())
    val M_master_inst = Output(UInt(32.W))
    val M_master_pc = Output(UInt(32.W))

    // Slave通道输出
    val M_slave_ctrl_sign = Output(new ex_mem_ctrl_sign())
    val M_slave_pc = Output(UInt(32.W))
    val M_slave_inst = Output(UInt(32.W))
  })

  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new ex_mem_ctrl_sign()
    val inst = UInt(32.W)
    val pc = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new ex_mem_ctrl_sign()
    val inst = UInt(32.W)
    val pc = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() || io.clear1) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena1) {
    masterReg.ctrl_sign := io.E_master_ctrl_sign
    masterReg.inst := io.E_master_inst
    masterReg.pc := io.E_master_pc
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() || io.clear2) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena2) {
    slaveReg.ctrl_sign := io.E_slave_ctrl_sign
    slaveReg.inst := io.E_slave_inst
    slaveReg.pc := io.E_slave_pc
  }

  // 连接Master输出
  io.M_master_ctrl_sign := masterReg.ctrl_sign
  io.M_master_inst := masterReg.inst
  io.M_master_pc := masterReg.pc

  // 连接Slave输出
  io.M_slave_ctrl_sign := slaveReg.ctrl_sign
  io.M_slave_inst := slaveReg.inst
  io.M_slave_pc := slaveReg.pc
}