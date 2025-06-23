package mycpu
import chisel3._
import chisel3.util._

class ControlUnitIO extends Bundle {
  // === Inputs ===
  val ex_branch_mispredict_detected_i = Input(Bool())
  val ex_master_path_data_ready_i     = Input(Bool())
  val ex_slave_path_data_ready_i      = Input(Bool())
  val idex_master_is_load_i           = Input(Bool())
  val is_master_path_active_in_ex_i = Input(Bool())
  val is_slave_path_active_in_ex_i  = Input(Bool())
  val BP_predTakeD                    = Input(Bool())
  val E_master_is_BJ                  = Input(Bool())
  val cpu_mem_ready                   = Input(Bool())
  val if_fifo_empty_i = Input(Bool())

  // === Outputs ===
  val cu_ififo_flush_o    = Output(Bool())
  val cu_ififo_rd_stall_o = Output(Bool())
  val cu_idex_stall_o     = Output(Bool())
  val cu_exmem_stall_o    = Output(Bool())
}

class ControlUnit extends Module {
  val io = IO(new ControlUnitIO())

  io.cu_ififo_flush_o := (io.E_master_is_BJ && io.ex_branch_mispredict_detected_i) || io.BP_predTakeD

  val master_path_requires_stall = io.is_master_path_active_in_ex_i && !io.ex_master_path_data_ready_i
  val slave_path_requires_stall  = io.is_slave_path_active_in_ex_i  && !io.ex_slave_path_data_ready_i
  val load_use_hazard_stall = io.idex_master_is_load_i && (!io.cpu_mem_ready)
  
  val overall_stall_condition = master_path_requires_stall || slave_path_requires_stall || load_use_hazard_stall

  io.cu_ififo_rd_stall_o := overall_stall_condition

  io.cu_idex_stall_o := overall_stall_condition || io.if_fifo_empty_i

  val is_master_load = io.idex_master_is_load_i
  io.cu_exmem_stall_o := (master_path_requires_stall && !is_master_load) || slave_path_requires_stall
}