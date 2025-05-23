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

    
}

class Csr extends Module {
    val io = IO(new CSR0)
    //CSR寄存器
    val mtime = RegInit(0.U(32.W))
    val mcycle = RegInit(0.U(32.W))    // 0xB00
   
   
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
        ))
    }

    //CSR执行写入
    when(io.csrrw_valid && io.csrrw_wr_en){
        switch(io.csrrw_addr){
            is("hB00".U) {mcycle := io.csrrw_wdata}
            is("hB01".U) {mtime := io.csrrw_wdata}
        }
    }

   
}
