import chisel3._
import chisel3.util._

class mem_wb extends Module {
  val io = IO(new Bundle {

    // Master通道信号
    val M_master_reg_wen = Input(Bool())
    val M_master_reg_waddr = Input(UInt(5.W))  //直接来自ex_mem
    val M_master_reg_wdata = Input(UInt(32.W))
    
    val W_master_reg_wen = Output(Bool())
    val W_master_reg_waddr = Output(UInt(5.W))
    val W_master_reg_wdata = Output(UInt(32.W))

    // Slave通道信号
    val M_slave_reg_wen = Input(Bool())
    val M_slave_reg_waddr = Input(UInt(5.W))
    val M_slave_reg_wdata = Input(UInt(32.W))

    val W_slave_reg_wen = Output(Bool())
    val W_slave_reg_waddr = Output(UInt(5.W))
    val W_slave_reg_wdata = Output(UInt(32.W))
  })

  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val reg_wen = Bool()
    val reg_waddr = UInt(5.W)
    val reg_wdata = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val reg_wen = Bool()
    val reg_waddr = UInt(5.W)
    val reg_wdata = UInt(32.W)
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.otherwise {
    masterReg.reg_wen := io.M_master_reg_wen
    masterReg.reg_waddr := io.M_master_reg_waddr
    masterReg.reg_wdata := io.M_master_reg_wdata
  }

  // Slave通道寄存器逻辑
  when(reset.asBool) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.otherwise {
    slaveReg.reg_wen := io.M_slave_reg_wen
    slaveReg.reg_waddr := io.M_slave_reg_waddr
    slaveReg.reg_wdata := io.M_slave_reg_wdata
  }

  // 连接输出
  io.W_master_reg_wen := masterReg.reg_wen
  io.W_master_reg_waddr := masterReg.reg_waddr
  io.W_master_reg_wdata := masterReg.reg_wdata

  io.W_slave_reg_wen := slaveReg.reg_wen
  io.W_slave_reg_waddr := slaveReg.reg_waddr
  io.W_slave_reg_wdata := slaveReg.reg_wdata
}