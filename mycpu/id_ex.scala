package mycpu
import chisel3._
import chisel3.util._

class id_ex_ctrl_sign extends Bundle {
  val inst_type = UInt(4.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
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

  // 为 Bundle 添加一个初始化方法，用于创建全零/默认值的实例
  def defaults(): id_ex_ctrl_sign = {
    val default = Wire(new id_ex_ctrl_sign())
    default.inst_type := 0.U
    default.rs1 := 0.U
    default.rs2 := 0.U
    default.ex_rs1_data := 0.U
    default.ex_rs2_data := 0.U
    default.rd := 0.U
    default.imm := 0.U
    default.alu_op := 0.U
    default.alu_op1 := 0.U
    default.alu_op2 := 0.U
    default.reg_write := false.B
    default.mem_read := false.B
    default.mem_write := false.B
    default.mem_type := 0.U
    default.mem_unsigned := false.B
    default.exception := false.B
    default.pc_out := 0.U
    default
  }
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
    val D_master_is_BJ = Input(Bool())
    
    // Slave通道输入
    val D_slave_ctrl_sign = Input(new id_ex_ctrl_sign())
    val F_final_slave_rs_data  = Input(Vec(numOperandsFixed, UInt(32.W)))
    val D_slave_ena = Input(Bool()) // 译码阶段从指令是否使能

    // Master通道输出
    val E_master_ctrl_sign = Output(new id_ex_ctrl_sign())
    val E_final_master_rs_data = Output(Vec(numOperandsFixed, UInt(32.W)))
    val E_master_is_BJ = Output(Bool())

    // Slave通道输出
    val E_slave_ctrl_sign = Output(new id_ex_ctrl_sign())
    val E_final_slave_rs_data  = Output(Vec(numOperandsFixed, UInt(32.W)))
    val E_slave_ena = Output(Bool())
  })
  
  // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
    val rs_data = Vec(numOperandsFixed, UInt(32.W))
    val D_BJ = Bool()

    // 为MasterRegBundle添加一个初始化方法
    def defaults(): MasterRegBundle = {
      val default = Wire(new MasterRegBundle())
      default.ctrl_sign := (new id_ex_ctrl_sign()).defaults() // 使用id_ex_ctrl_sign的defaults
      for (i <- 0 until numOperandsFixed) {
        default.rs_data(i) := 0.U(32.W)
      }
      default.D_BJ := false.B
      default
    }
  }
  // 使用显式默认值初始化MasterReg
  val masterReg = RegInit((new MasterRegBundle()).defaults()) 

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
    val rs_data = Vec(numOperandsFixed, UInt(32.W))
    val ena = Bool() // Slave通道使能信号

    // 为SlaveRegBundle添加一个初始化方法
    def defaults(): SlaveRegBundle = {
      val default = Wire(new SlaveRegBundle())
      default.ctrl_sign := (new id_ex_ctrl_sign()).defaults()
      for (i <- 0 until numOperandsFixed) {
        default.rs_data(i) := 0.U(32.W)
      }
      default.ena := false.B
      default
    }
  }
  // 使用显式默认值初始化SlaveReg
  val slaveReg = RegInit((new SlaveRegBundle()).defaults())

  // Master通道寄存器逻辑
  when(reset.asBool() || io.clear) {
    masterReg := (new MasterRegBundle()).defaults() // 复位或清空时也使用显式默认值
  }.elsewhen(io.ena) {
    masterReg.ctrl_sign := io.D_master_ctrl_sign
    masterReg.rs_data := io.F_final_master_rs_data
    masterReg.D_BJ := io.D_master_is_BJ
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() || io.clear || !io.D_slave_ena) {
    slaveReg := (new SlaveRegBundle()).defaults() // 复位、清空或从指令不使能时也使用显式默认值
  }.elsewhen(io.ena && io.D_slave_ena) { // 只有在ena和D_slave_ena都为true时才更新
    slaveReg.ctrl_sign := io.D_slave_ctrl_sign
    slaveReg.rs_data := io.F_final_slave_rs_data
    slaveReg.ena := io.D_slave_ena // 明确赋值为true.B
  }.elsewhen(io.ena && !io.D_slave_ena) { // 当io.ena为true但D_slave_ena为false时，清空slaveReg
    slaveReg := (new SlaveRegBundle()).defaults()
  }


  // 连接寄存器输出到IO
  io.E_master_ctrl_sign := masterReg.ctrl_sign
  io.E_final_master_rs_data := masterReg.rs_data
  io.E_master_is_BJ := masterReg.D_BJ

  io.E_slave_ctrl_sign := slaveReg.ctrl_sign
  io.E_final_slave_rs_data := slaveReg.rs_data
  io.E_slave_ena := slaveReg.ena // E_slave_ena 明确连接到 slaveReg.ena
}
