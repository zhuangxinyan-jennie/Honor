import chisel3._
import chisel3.util._

//信号均为高电平有效
class ControlUnitIO extends Bundle {
  // === Inputs ===
  val ex_branch_mispredict_detected_i = Input(Bool()) // from Dual IssueEx
  val ex_master_path_data_ready_i   = Input(Bool()) // from Dual IssueEx
  val ex_slave_path_data_ready_i    = Input(Bool()) // from Dual IssueEx
  val idex_master_is_load_i         = Input(Bool()) // from ID/EX  E_master_ctrl_sign.mem_read||E_master_ctrl_sign.mem_write
  val is_master_path_active_in_ex_i = Input(Bool()) // from ID/EX  !(E_master_ctrl_sign.inst_type===0.U)
  val is_slave_path_active_in_ex_i  = Input(Bool()) // from ID/EX  !(E_slave_ctrl_sign.inst_type===0.U)
  val BP_predTakeD                  = Input(Bool()) // from BP  BTB.io.predTakeD

  // === Outputs ===
  val cu_ififo_flush_o     = Output(Bool()) // to InstFifo  InstFifoIO.fifo_rst
  val cu_ififo_rd_stall_o  = Output(Bool()) // to InstFifo  InstFifoIO.i_stall
  val cu_idex_stall_o      = Output(Bool()) // to ID/EX  id_ex.ena   id_ex的clear信号始终置为0(无效)
  val cu_exmem_stall_o     = Output(Bool()) // to EX/MEM  ex_mem.en
}

class ControlUnit extends Module {
  val io = IO(new ControlUnitIO())

  io.cu_ififo_flush_o := io.ex_branch_mispredict_detected_i || io.BP_predTakeD

  val master_path_requires_stall = io.is_master_path_active_in_ex_i && !io.ex_master_path_data_ready_i
  val slave_path_requires_stall  = io.is_slave_path_active_in_ex_i  && !io.ex_slave_path_data_ready_i

  val overall_stall_condition = master_path_requires_stall || slave_path_requires_stall

  io.cu_ififo_rd_stall_o := overall_stall_condition
  io.cu_idex_stall_o     := overall_stall_condition

  val is_master_load = io.idex_master_is_load_i
  io.cu_exmem_stall_o := (master_path_requires_stall && !is_master_load) || slave_path_requires_stall
}
