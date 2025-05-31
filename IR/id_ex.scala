import chisel3._
import chisel3.util._

class id_ex_ctrl_sign extends Bundle {
  val inst_type = UInt(4.W)
  val ex_rs1_data = UInt(32.W)
  val ex_rs2_data = UInt(32.W)
  val rd = UInt(5.W)
  val imm = UInt(32.W)
  val alu_op = UInt(5.W)
  val alu_op1 = UInt(2.W)
  val alu_op2 = UInt(2.W)
  val reg_write = Bool()
  val mem_read = Bool()
  val mem_write = Bool()
  val mem_type = UInt(3.W)
  val mem_unsigned = Bool()
  val exception = Bool()
  val pc_out = UInt(32.W)
}

class id_ex extends Module {
  private val numOperandsFixed: Int = 2

  val io = IO(new Bundle {
    // 控制信号
    val clear = Input(Bool())
    val ena = Input(Bool())
    
    // Master通道输入
    val D_master_ctrl_sign = Input(new id_ex_ctrl_sign())
    val F_final_master_rs_data = Input(Vec(numOperandsFixed, UInt(32.W)))
    
    // Slave通道输入
    val D_slave_ctrl_sign = Input(new id_ex_ctrl_sign())
    val F_final_slave_rs_data  = Input(Vec(numOperandsFixed, UInt(32.W)))
    val D_slave_ena = Input(Bool())

    // Master通道输出
    val E_master_ctrl_sign = Output(new id_ex_ctrl_sign())
    val E_final_master_rs_data = Output(Vec(numOperandsFixed, UInt(32.W)))

    // Slave通道输出
    val E_slave_ctrl_sign = Output(new id_ex_ctrl_sign())
    val E_final_slave_rs_data  = Output(Vec(numOperandsFixed, UInt(32.W)))
    val E_slave_ena = Output(Bool())
  })
  
  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
    val rs_data = Vec(numOperandsFixed, UInt(32.W))
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
    val rs_data = Vec(numOperandsFixed, UInt(32.W))
    val ena = Bool() // Slave通道使能信号
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() || io.clear) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena) {
    masterReg.ctrl_sign := io.D_master_ctrl_sign
    masterReg.rs_data := io.F_final_master_rs_data
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() || io.clear || !io.D_slave_ena) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena) {
    slaveReg.ctrl_sign := io.D_slave_ctrl_sign
    slaveReg.rs_data := io.F_final_slave_rs_data
    slaveReg.ena := io.D_slave_ena
  }

  // 连接寄存器输出到IO
  io.E_master_ctrl_sign := masterReg.ctrl_sign
  io.E_final_master_rs_data := masterReg.rs_data

  io.E_slave_ctrl_sign := slaveReg.ctrl_sign
  io.E_final_slave_rs_data := slaveReg.rs_data
  io.E_slave_ena := slaveReg.ena
}