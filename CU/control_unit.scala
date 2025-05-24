import chisel3._
import chisel3.util._

class ControlUnit extends Module {
  val io = IO(new Bundle {
    // Input signals
    val D_master_read_rs   = Input(Bool())
    val D_master_read_rt   = Input(Bool())
    val D_master_rs        = Input(UInt(5.W))
    val D_master_rt        = Input(UInt(5.W))
    val D_master_memtoReg  = Input(Bool())
    val D_master_reg_waddr = Input(UInt(5.W))
    val D_slave_read_rs    = Input(Bool())
    val D_slave_read_rt    = Input(Bool())
    val D_slave_rs         = Input(UInt(5.W))
    val D_slave_rt         = Input(UInt(5.W))
    val E_master_memtoReg  = Input(Bool())
    val E_master_reg_waddr = Input(UInt(5.W))
    val E_slave_memtoReg   = Input(Bool())
    val E_slave_reg_waddr  = Input(UInt(5.W))
    val E_alu_stall        = Input(Bool())
    val E_bj               = Input(Bool())

    // Output signals
    val F_ena_master              = Output(Bool())
    val F_ena_slave               = Output(Bool())
    val D_ena_master              = Output(Bool())
    val D_ena_slave               = Output(Bool())
    val E_ena_master              = Output(Bool())
    val E_ena_slave               = Output(Bool())
    val M_ena_master              = Output(Bool())
    val M_ena_slave               = Output(Bool())
    val W_ena_master              = Output(Bool())
    val W_ena_slave               = Output(Bool())
    val F_flush_master            = Output(Bool())
    val F_flush_slave             = Output(Bool())
    val D_flush_master            = Output(Bool())
    val D_flush_slave             = Output(Bool())
    val E_flush_master            = Output(Bool())
    val E_flush_slave             = Output(Bool())
    val M_flush_master            = Output(Bool())
    val M_flush_slave             = Output(Bool())
    val W_flush_master            = Output(Bool())
    val W_flush_slave             = Output(Bool())
  })

  // 检测Load-Use冒险 (lwstall)
  val loadUseHazard_master = Wire(Bool())
  val loadUseHazard_slave = Wire(Bool())
  val loadUseHazard_inner = Wire(Bool())
  loadUseHazard_master := (io.E_master_memtoReg && (io.E_master_reg_waddr.orR) &&
    ((io.D_master_read_rs && (io.D_master_rs === io.E_master_reg_waddr)) ||
     (io.D_master_read_rt && (io.D_master_rt === io.E_master_reg_waddr)))) ||
    (io.E_slave_memtoReg && (io.E_slave_reg_waddr.orR) &&
    ((io.D_master_read_rs && (io.D_master_rs === io.E_slave_reg_waddr)) ||
     (io.D_master_read_rt && (io.D_master_rt === io.E_slave_reg_waddr))))

  loadUseHazard_slave := (io.E_master_memtoReg && (io.E_master_reg_waddr.orR) &&
    ((io.D_slave_read_rs && (io.D_slave_rs === io.E_master_reg_waddr)) ||
     (io.D_slave_read_rt && (io.D_slave_rt === io.E_master_reg_waddr)))) ||
     (io.E_slave_memtoReg && (io.E_slave_reg_waddr.orR) &&
     ((io.D_slave_read_rs && (io.D_slave_rs === io.E_slave_reg_waddr)) ||
      (io.D_slave_read_rt && (io.D_slave_rt === io.E_slave_reg_waddr))))
  
  loadUseHazard_inner := (io.D_master_memtoReg && (io.D_master_reg_waddr.orR) &&
  ((io.D_slave_read_rs && (io.D_slave_rs === io.D_master_reg_waddr)) ||
   (io.D_slave_read_rt && (io.D_slave_rt === io.D_master_reg_waddr))))


  // 控制冒险信号 (来自分支预测错误)
  val branchHazard = io.E_bj

  // 最长停顿信号 (longest_stall)
  val longestStall = io.E_alu_stall 



  io.F_ena_master := !(loadUseHazard_master || loadUseHazard_slave || loadUseHazard_inner || longestStall)
  io.F_ena_slave := !(loadUseHazard_master || loadUseHazard_slave || loadUseHazard_inner || longestStall)
  io.D_ena_master :=  !(loadUseHazard_master || loadUseHazard_slave || longestStall)                                //loadUseHazard_master或是loadUseHazard_slave D_ena1和D_ena2都要停止 ，loadUseHazard_inner D_ena1继续，D_ena2发射
  io.D_ena_slave := !(loadUseHazard_master || loadUseHazard_slave || longestStall || loadUseHazard_inner)
  io.E_ena_master := !longestStall
  io.E_ena_slave := !longestStall
  io.M_ena_master := !longestStall
  io.M_ena_slave := !longestStall
  io.W_ena_master := true.B
  io.W_ena_slave := true.B

  io.F_flush_master := false.B
  io.F_flush_slave := false.B
  io.D_flush_master := branchHazard || loadUseHazard_inner
  io.D_flush_slave := branchHazard 
  io.E_flush_master := branchHazard || loadUseHazard_master || loadUseHazard_slave 
  io.E_flush_slave := branchHazard || loadUseHazard_inner || loadUseHazard_slave || loadUseHazard_master
  io.M_flush_master := false.B
  io.M_flush_slave := false.B
  io.W_flush_master := false.B
  io.W_flush_slave := false.B

}
