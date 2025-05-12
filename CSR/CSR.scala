package CSR

import chisel3._
import chisel3.util._

import CSR.exceptcode

class CSR0 extends Bundle{
  val clk = Input(Clock())
  val rst = Input(Bool())

  //流水线控制
  val stallE = Input(Bool())

  //csrrw指令信息
  val csrrw_valid = Input(Bool()) //csrrw操作有效
  val csrrw_addr = Input(UInt(12.W)) //csr地址
  val csrrw_wdata = Input(UInt(32.W)) //写入数据
  val csrrw_rdata = Output(UInt(32.W)) //读取数据
  val csrrw_wr_en = Input(Bool())  //写使能
  val csrrw_rd_en = Input(Bool())  //读使能

  //IF
  val IF_pc = Input(UInt(32.W))
  val IF_adel = Input(Bool()) //指令未对齐 Instruction address misaligned

  //ID阶段异常
  val ID_pc = Input(UInt(32.W))
  val ID_ecall = Input(Bool()) //environment call(system call)
  val ID_ebreak = Input(Bool()) //Breakpoint断点异常（ID）
  val ID_illegal = Input(Bool()) //不合法的指令

  //EX
  val EX_pc = Input(UInt(32.W))
  val EX_cause = Input(UInt(4.W))
  val EX_valid = Input(Bool())
  val EX_inst = Input(UInt(32.W))

  //外部中断
  val ext_int = Input(UInt(6.W))


  //CSR输出信息
  val epc = Output(UInt(32.W)) //异常返回地址
  val trap = Output(Bool())
  val trap_pc = Output(UInt(32.W)) // mtvec跳转地址
  val badva = Output(UInt(32.W)) // mtval值

  //冲刷
  val flush_IF = Output(Bool()) //给IF的冲刷信号
  val flush_ID = Output(Bool()) //给ID的冲刷信号
  val flush_EX = Output(Bool()) //给EX的冲刷信号
  val flush_MEM = Output(Bool()) //给EX的冲刷信号
}

class Csr extends Module {
  val io = IO(new CSR0)
  //CSR寄存器
  val mtime = RegInit(0.U(32.W))
  val mcycle = RegInit(0.U(32.W))    // 0xB00

  // 异常相关CSR寄存器
  val mtvec  = RegInit(0.U(32.W))  // Trap向量基址
  val mepc = RegInit(0.U(32.W))    // 异常程序计数器
  val mcause = RegInit(0.U(32.W))  // 异常原因
  val mtval = RegInit(0.U(32.W))   // 出错值

  //异常状态寄存器
  val trap_flag = RegInit(false.B)

  //每个时钟周期自增
  mcycle := mcycle + 1.U
  when(mtime =/= "hfffffffff".U){
    mtime := mtime+ 1.U
  }

  //读取当前CSR值
  io.csrrw_rdata := 0.U
  when(io.csrrw_rd_en){
    io.csrrw_rdata := MuxLookup(io.csrrw_addr,0.U,Seq(
      "hB00".U -> mcycle,
      "hB01".U -> mtime,
      "h305".U -> mtvec,
      "h341".U -> mepc,
      "h342".U -> mcause,
      "h343".U -> mtval
    ))
  }

  //CSR执行写入
  when(io.csrrw_valid && io.csrrw_wr_en){
    switch(io.csrrw_addr){
      is("hB00".U) {mcycle := io.csrrw_wdata}
      is("hB01".U) {mtime := io.csrrw_wdata}
      is("h305".U) { mtvec  := io.csrrw_wdata }
      is("h341".U) { mepc   := io.csrrw_wdata }
      is("h342".U) { mcause := io.csrrw_wdata }
      is("h343".U) { mtval  := io.csrrw_wdata }
    }
  }

  // 异常处理逻辑
  val exception_occurred = WireInit(false.B)
  val exception_cause = WireInit(0.U(32.W))
  val exception_pc = WireInit(0.U(32.W))
  val exception_mtval = WireInit(0.U(32.W))

  // 异常优先级（从高到低）
  exception_occurred := false.B
  exception_cause    := 0.U
  exception_pc       := 0.U
  exception_mtval    := 0.U

  //1.中断（最高优先级）、
  when(io.ext_int =/= 0.U){
    exception_occurred := true.B
    exception_cause := exceptcode.MACHINE_EXTERNAL_INT
    exception_pc :=io.ID_pc //中断发生在ID阶段
  }

    //2.地址相关异常
    //IF阶段地址不对其
    .elsewhen(io.IF_adel){
      exception_occurred :=true.B
      exception_cause := exceptcode.INSTRUCTION_ADDRESS_MISALIGNED
      exception_pc := io.IF_pc
      exception_pc := io.IF_pc
    }
    //EX阶段地址异常
    // EX阶段传递的地址异常
    .elsewhen(io.EX_valid && !io.stallE) {
      when(io.EX_cause === exceptcode.LOAD_MISALIGN) {
        exception_occurred := true.B
        exception_cause    := exceptcode.LOAD_MISALIGN
        exception_pc       := io.EX_pc
        exception_mtval    := io.EX_inst // 实际应连接访存地址
      }.elsewhen(io.EX_cause === exceptcode.STORE_MISALIGN) {
        exception_occurred := true.B
        exception_cause    := exceptcode.STORE_MISALIGN
        exception_pc       := io.EX_pc
        exception_mtval    := io.EX_inst
      }
    }

    //3.系统调用与断点（内陷）
    .elsewhen(io.ID_ecall){
      exception_occurred := true.B
      exception_cause := exceptcode.ECALL
      exception_pc := io.ID_pc
    }.elsewhen(io.ID_ebreak){
    exception_occurred := true.B
    exception_cause := exceptcode.BREAKPOINT
    exception_pc := io.ID_pc
  }

    //4.其他异常
    .elsewhen(io.ID_illegal){
      exception_occurred := true.B
      exception_cause := exceptcode.INSTR_ILLEGAL
      exception_pc := io.ID_pc
    }
    .elsewhen(io.EX_valid && !io.stallE) {
      exception_occurred := true.B
      exception_pc       := io.EX_pc

      switch(io.EX_cause) {
        // 指令相关异常
        is(exceptcode.INSTRUCTION_ADDRESS_MISALIGNED) {
          exception_cause := exceptcode.INSTRUCTION_ADDRESS_MISALIGNED
          exception_mtval := io.IF_pc // 来自IF阶段的错误地址
        }
        is(exceptcode.INSTR_ILLEGAL) {
          exception_cause := exceptcode.INSTR_ILLEGAL
          exception_mtval := io.EX_inst // 非法指令编码
        }
        is(exceptcode.BREAKPOINT) {
          exception_cause := exceptcode.BREAKPOINT
          exception_mtval := io.EX_inst // 非法指令编码
        }
        //访存异常
        is(exceptcode.LOAD_FAULT) {
          exception_cause := exceptcode.LOAD_FAULT
          exception_mtval := io.EX_inst // 错误物理地址
        }
        is(exceptcode.STORE_ACCESS_FAULT) {
          exception_cause := exceptcode.STORE_ACCESS_FAULT
          exception_mtval := io.EX_inst // 错误物理地址
        }

        //
        is(exceptcode.ECALL) { //
          exception_cause := exceptcode.ECALL
          exception_mtval := 0.U
        }
      }
    }

  //更新CSR寄存器
  when(exception_occurred && !trap_flag){
    mepc :=exception_pc
    mcause :=exception_cause
    mtval :=exception_mtval
    trap_flag := true.B

  }
  //输出信号
  io.epc := mepc
  io.trap := exception_occurred && !trap_flag
  io.badva := mtval

  // 流水线冲刷（包含MEM阶段）
  io.flush_IF  := io.trap
  io.flush_ID  := io.trap
  io.flush_EX  := io.trap
  io.flush_MEM := io.trap // MEM阶段冲刷

  //异常返回
  when(io.csrrw_valid && io.csrrw_addr === "h341".U && io.csrrw_wr_en){
    trap_flag := false.B
  }
}