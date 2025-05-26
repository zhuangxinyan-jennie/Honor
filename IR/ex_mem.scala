import chisel3._
import chisel3.util._

class ex_mem_ctrl_sign extends Bundle {
  val mem_en = Bool()               // 内存使能
  val mem_write = Bool()            // 是否写(0:rd)
  val mem_type = UInt(2.W)          // 访存类型，NONE B H W
  val mem_unsigned = Bool()         // 内存无符号访存使能
  val mem_wdata = UInt(32.W)        // 内存写数据
  val mem_addr = UInt(32.W)         // 内存地址
}

class ex_mem extends Module {
  val io = IO(new Bundle {
    val ena = Input(Bool())

    // Master通道输入
    val E_master_ctrl_sign = Input(new ex_mem_ctrl_sign())
    val E_rd1 = Input(UInt(5.W))
    val E_result1 = Input(UInt(32.W))

    // Slave通道输入
    val E_slave_ctrl_sign = Input(new ex_mem_ctrl_sign())
    val E_rd2 = Input(UInt(5.W))
    val E_result2 = Input(UInt(32.W))

    // Master通道输出
    val M_master_ctrl_sign = Output(new ex_mem_ctrl_sign())
    val W_rd1 = Output(UInt(5.W))
    val W_result1 = Output(UInt(32.W))

    // Slave通道输出
    val M_slave_ctrl_sign = Output(new ex_mem_ctrl_sign())
    val W_rd2 = Output(UInt(5.W))
    val W_result2 = Output(UInt(32.W))
  })

  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new ex_mem_ctrl_sign()
    val rd = UInt(5.W)
    val result1 = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new ex_mem_ctrl_sign()
    val rd = UInt(5.W)
    val result2 = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool()) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena) {
    masterReg.ctrl_sign := io.E_master_ctrl_sign
    masterReg.rd := io.E_rd1
    masterReg.result1 := io.E_result1
  }

  // Slave通道寄存器逻辑
  when(reset.asBool()) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena) {
    slaveReg.ctrl_sign := io.E_slave_ctrl_sign
    slaveReg.rd := io.E_rd2
    slaveReg.result2 := io.E_result2
  }

  // 连接Master输出
  io.M_master_ctrl_sign := masterReg.ctrl_sign
  io.W_rd1 := masterReg.rd
  io.W_result1 := masterReg.result1

  // 连接Slave输出
  io.M_slave_ctrl_sign := slaveReg.ctrl_sign
  io.W_rd2 := slaveReg.rd
  io.W_result2 := slaveReg.result2
}