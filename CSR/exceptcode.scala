package CSR

import chisel3._
import chisel3.util._

object exceptcode {
  // Exception codes (matches Verilog defines)
  val INSTRUCTION_ADDRESS_MISALIGNED = 0.U(5.W) // For IF_adel
  val INSTR_ILLEGAL = 2.U(5.W) //非法指令异常，表示 CPU 遇到了无法识别的指令。(ID,EX)
  val LOAD_MISALIGN = 4.U(5.W) //加载地址不对齐异常，访问内存时地址未按数据宽度对齐（如 32 位数据地址不是 4 的倍数）。(IF,EX)
  val BREAKPOINT = 3.U(5.W)  //Breakpoint断点异常（ID）
  val LOAD_FAULT = 5.U(5.W) //加载访问错误异常，访问内存时发生权限或物理地址错误。
  val STORE_MISALIGN = 6.U(5.W) //存储地址不对齐异常，存储数据时地址未对齐。
  val STORE_ACCESS_FAULT   = 7.U(5.W)
  val ECALL = 11.U  //System call系统调用异常（ID）

  // 中断编码（最高位为1）
  val MACHINE_EXTERNAL_INT = "h8000000b".U

}