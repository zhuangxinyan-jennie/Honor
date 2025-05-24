import chisel3._
import chisel3.util._

class id_ex_ctrl_sign extends Bundle {
  val inst_type = UInt(4.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
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
  val io = IO(new Bundle {
    // 控制信号
    val clear1 = Input(Bool())
    val clear2 = Input(Bool())
    val ena1 = Input(Bool())
    val ena2 = Input(Bool())
    
    // Master通道输入
    val D_master_ctrl_sign = Input(new id_ex_ctrl_sign())
    val D_master_pred_take = Input(Bool())
    val D_master_branch_target = Input(UInt(32.W))
   
    // Slave通道输入
    val D_slave_ctrl_sign = Input(new id_ex_ctrl_sign())

    // Master通道输出
    val E_master_ctrl_sign = Output(new id_ex_ctrl_sign())
    val E_master_pred_take = Output(Bool())
    val E_master_branch_target = Output(UInt(32.W))

    // Slave通道输出
    val E_slave_ctrl_sign = Output(new id_ex_ctrl_sign())
  })
  
    // 定义Master通道寄存器结构体
  class MasterRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
    val pred_take = Bool()
    val pc = UInt(32.W)
  }
  val masterReg = RegInit(0.U.asTypeOf(new MasterRegBundle))

  // 定义Slave通道寄存器结构体
  class SlaveRegBundle extends Bundle {
    val ctrl_sign = new id_ex_ctrl_sign()
  }
  val slaveReg = RegInit(0.U.asTypeOf(new SlaveRegBundle))

  // Master通道寄存器逻辑
  when(reset.asBool() || io.clear1) {
    masterReg := 0.U.asTypeOf(new MasterRegBundle)
  }.elsewhen(io.ena1) {
    masterReg.ctrl_sign := io.D_master_ctrl_sign
    masterReg.pred_take := io.D_master_pred_take
    masterReg.branch_target := io.D_master_branch_target
  }

  // Slave通道寄存器逻辑
  when(reset.asBool() || io.clear2) {
    slaveReg := 0.U.asTypeOf(new SlaveRegBundle)
  }.elsewhen(io.ena2) {
    slaveReg.ctrl_sign := io.D_slave_ctrl_sign
  }

  // 连接寄存器输出到IO
  io.E_master_ctrl_sign := masterReg.ctrl_sign
  io.E_master_pred_take := masterReg.pred_take
  io.E_master_branch_target := masterReg.branch_target

  io.E_slave_ctrl_sign := slaveReg.ctrl_sign
}