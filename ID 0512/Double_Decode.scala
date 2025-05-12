import chisel3._
import chisel3.util._
import parse._
import name._
import issue_ctrl._
import scala.language.reflectiveCalls

// 双发射译码模块
class DualIssueDecode extends Module {
    val io = IO(new Bundle {
        val inst1 = Input(UInt(32.W))  // 第一条指令
        val inst2 = Input(UInt(32.W))  // 第二条指令
        val decode1 = Output(new Bundle {
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
        })
        val decode2 = Output(new Bundle {
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
        })
        // 发射控制相关信号
        val fifo_empty = Input(Bool())
        val fifo_almost_empty = Input(Bool())
        val slave_ena = Output(Bool())  // 从指令是否使能
        val slave_in_delayslot = Output(Bool())  // 从指令是否在延迟槽
        // FIFO 控制信号
        val fifo_push = Output(Bool())  // 是否将指令推回 FIFO
        val fifo_data = Output(UInt(32.W))  // 推回 FIFO 的指令
    })

    // 实例化译码模块
    val decode1 = Module(new BaseDecode)
    val decode2 = Module(new BaseDecode)
    val issue_ctrl = Module(new IssueCtrl)

    // 连接译码模块的输入
    decode1.io.inst := io.inst1
    decode2.io.inst := io.inst2

    // 连接发射控制模块的输入
    issue_ctrl.io.D_master_ena := true.B  // 主指令始终使能
    issue_ctrl.io.D_master_reg_wen := decode1.io.reg_write
    issue_ctrl.io.D_master_reg_waddr := decode1.io.rd
    issue_ctrl.io.D_master_memtoReg := decode1.io.mem_read
    issue_ctrl.io.D_master_is_branch := decode1.io.inst_type === 5.U  // B_TYPE
    issue_ctrl.io.D_master_only_one_issue := decode1.io.inst_type === 8.U  // CSR_TYPE
    issue_ctrl.io.D_master_csr_write := decode1.io.inst_type === 8.U && decode1.io.reg_write

    issue_ctrl.io.D_slave_read_rs1 := decode2.io.rs1 =/= 0.U
    issue_ctrl.io.D_slave_read_rs2 := decode2.io.rs2 =/= 0.U
    issue_ctrl.io.D_slave_rs1 := decode2.io.rs1
    issue_ctrl.io.D_slave_rs2 := decode2.io.rs2
    issue_ctrl.io.D_slave_only_one_issue := decode2.io.inst_type === 8.U  // CSR_TYPE
    issue_ctrl.io.D_slave_may_bring_flush := decode2.io.inst_type === 5.U  // B_TYPE
    issue_ctrl.io.D_slave_csr_read := decode2.io.inst_type === 8.U

    issue_ctrl.io.E_master_memtoReg := decode1.io.mem_read
    issue_ctrl.io.E_master_reg_waddr := decode1.io.rd
    issue_ctrl.io.E_slave_memtoReg := decode2.io.mem_read
    issue_ctrl.io.E_slave_reg_waddr := decode2.io.rd

    issue_ctrl.io.fifo_empty := io.fifo_empty
    issue_ctrl.io.fifo_almost_empty := io.fifo_almost_empty

    // 连接发射控制模块的输出
    io.slave_ena := issue_ctrl.io.D_slave_ena
    io.slave_in_delayslot := issue_ctrl.io.D_slave_is_in_delayslot

    // FIFO 控制逻辑
    // 当从指令不使能且FIFO不空时，将指令推回FIFO
    io.fifo_push := !io.slave_ena && !io.fifo_empty
    io.fifo_data := io.inst2

    // 连接译码模块的输出
    io.decode1.inst_type := decode1.io.inst_type
    io.decode1.rs1 := decode1.io.rs1
    io.decode1.rs2 := decode1.io.rs2
    io.decode1.rd := decode1.io.rd
    io.decode1.imm := decode1.io.imm
    io.decode1.alu_op := decode1.io.alu_op
    io.decode1.alu_op1 := decode1.io.alu_op1
    io.decode1.alu_op2 := decode1.io.alu_op2
    io.decode1.reg_write := decode1.io.reg_write
    io.decode1.mem_read := decode1.io.mem_read
    io.decode1.mem_write := decode1.io.mem_write
    io.decode1.mem_type := decode1.io.mem_type
    io.decode1.mem_unsigned := decode1.io.mem_unsigned
    io.decode1.exception := decode1.io.exception

    // 从指令使能时，选择从译码模块的输出，否则选择0
    io.decode2.inst_type := Mux(io.slave_ena, decode2.io.inst_type, 0.U)
    io.decode2.rs1 := Mux(io.slave_ena, decode2.io.rs1, 0.U)
    io.decode2.rs2 := Mux(io.slave_ena, decode2.io.rs2, 0.U)
    io.decode2.rd := Mux(io.slave_ena, decode2.io.rd, 0.U)
    io.decode2.imm := Mux(io.slave_ena, decode2.io.imm, 0.U)
    io.decode2.alu_op := Mux(io.slave_ena, decode2.io.alu_op, 0.U)
    io.decode2.alu_op1 := Mux(io.slave_ena, decode2.io.alu_op1, 0.U)
    io.decode2.alu_op2 := Mux(io.slave_ena, decode2.io.alu_op2, 0.U)
    io.decode2.reg_write := Mux(io.slave_ena, decode2.io.reg_write, false.B)
    io.decode2.mem_read := Mux(io.slave_ena, decode2.io.mem_read, false.B)
    io.decode2.mem_write := Mux(io.slave_ena, decode2.io.mem_write, false.B)
    io.decode2.mem_type := Mux(io.slave_ena, decode2.io.mem_type, 0.U)
    io.decode2.mem_unsigned := Mux(io.slave_ena, decode2.io.mem_unsigned, false.B)
    io.decode2.exception := Mux(io.slave_ena, decode2.io.exception, false.B)
}
