package issue_ctrl
import chisel3._
import chisel3.util._
import parse._
import name._
import scala.language.reflectiveCalls

class IssueCtrl extends Module {
    val io = IO(new Bundle {
        // 主指令使能信号
        val D_master_ena = Input(Bool())           // 主指令使能
        val D_master_reg_wen = Input(Bool())       // 主指令写回寄存器使能
        val D_master_reg_waddr = Input(UInt(5.W))  // 主指令写回寄存器地址
        val D_master_memtoReg = Input(Bool())      // 主指令写回数据来源
        val D_master_is_branch = Input(Bool())     // 主指令是否为分支指令
        val D_master_is_ja = Input(Bool())         //主指令是否是跳转指令  JAL JALR
        // val D_master_only_one_issue = Input(Bool())// 主指令是否只执行一次
        // val D_master_csr_write = Input(Bool())     // 主指令写CSR使能

        // 从指令输入信号
        val D_slave_read_rs1 = Input(Bool())       // 从指令读取rs1
        val D_slave_read_rs2 = Input(Bool())       // 从指令读取rs2
        val D_slave_rs1 = Input(UInt(5.W))         // 从指令rs1地址
        val D_slave_rs2 = Input(UInt(5.W))         // 从指令rs2地址
        // val D_slave_only_one_issue = Input(Bool()) // 从指令是否只执行一次
        val D_slave_is_ja = Input(Bool())          // 从指令是否是跳转指令  JAL JALR
        val D_slave_may_bring_flush = Input(Bool())// 从指令是否可能带来flush
        // val D_slave_csr_read = Input(Bool())       // 从指令读取CSR
        val D_slave_is_csr = Input(Bool())         // 从指令是否是CSR指令
        val D_slave_is_fence12 = Input(Bool())      // 从指令是否是fence或fence.i指令

        // 执行阶段输入信号
        val E_master_memtoReg = Input(Bool())      // 主指令执行后写回数据来源
        val E_master_reg_waddr = Input(UInt(5.W))  // 主指令执行后写回寄存器地址
        val E_slave_memtoReg = Input(Bool())       // 从指令执行后写回数据来源
        val E_slave_reg_waddr = Input(UInt(5.W))   // 从指令执行后写回寄存器地址

        // FIFO状态信号
        val fifo_empty = Input(Bool())             // FIFO空
        val fifo_almost_empty = Input(Bool())      // FIFO几乎空

        // 输出信号
        val D_slave_is_in_delayslot = Output(Bool()) // 从指令是否在延迟槽
        val D_slave_ena = Output(Bool())             // 从指令使能
    })

    // 延迟槽判断
    io.D_slave_is_in_delayslot := io.D_master_is_branch & io.D_slave_ena

    // 输出信号
    val load_stall = Wire(Bool())      // Load指令停顿
    val fifo_disable = Wire(Bool())    // FIFO禁用
    val struct_conflict = Wire(Bool()) // 结构冲突
    val war_reg = Wire(Bool())         // 写回寄存器冲突
    val war_csr = Wire(Bool())         // 写CSR冲突
    val data_conflict = Wire(Bool())   // 数据冲突

    // FIFO禁用 当FIFO为空或几乎为空时，禁用从指令的发射
    fifo_disable := io.fifo_empty | io.fifo_almost_empty

    // Load指令停顿  当主指令是Load指令时，从指令需要读取主指令写回的寄存器
    // lw x1, 0(x2)    # 主指令：从内存加载数据到x1
    // add x3, x1, x4  # 从指令：需要读取x1的值进行加法运算  写后读
    load_stall := io.E_master_memtoReg & ((io.D_slave_read_rs1 & io.D_slave_rs1 === io.E_master_reg_waddr) | 
                                         (io.D_slave_read_rs2 & io.D_slave_rs2 === io.E_master_reg_waddr))
 
    // 结构冲突  当主指令和从指令都需要访问内存时，可能会导致结构冲突
   // lw x1, 0(x2)    # 主指令：从内存加载数据到x1 
   // lw x3, 4(x4)    # 从指令：从内存加载数据到x3 
    struct_conflict := io.D_master_memtoReg && io.E_slave_memtoReg  // 结构冲突

    // 写回寄存器冲突 
    //add x1, x2, x3    # 主指令：将x2+x3的结果写入x1 
    //add x4, x1, x5    # 从指令：需要读取x1的值 写后读
    war_reg := io.D_master_reg_wen & ((io.D_slave_read_rs1 & io.D_slave_rs1 === io.D_master_reg_waddr) | 
                                      (io.D_slave_read_rs2 & io.D_slave_rs2 === io.D_master_reg_waddr))

    //主从指令都是JAL/JALR指令时，禁止发射
    ja_conflict := io.D_master_is_ja & io.D_slave_is_ja

    // // 写CSR冲突
    // war_csr := io.D_master_csr_write & io.D_slave_csr_read

    // CSR独占周期
    csr_conflict := io.D_slave_is_csr

    // fence或fence.i指令独占周期
    fence_conflict := io.D_slave_is_fence12 

    // 数据冲突
    data_conflict := war_reg | war_csr | load_stall

    // 从指令使能
    io.D_slave_ena := !(!io.D_master_ena | 
                        fifo_disable | 
                        // io.D_master_only_one_issue | 
                        // io.D_slave_only_one_issue | 
                        io.D_slave_may_bring_flush | 
                        struct_conflict |   //结构冲突
                        data_conflict |      //数据冲突
                        ja_conflict |        //JAL/JALR冲突
                        csr_conflict |       //CSR独占周期
                        fence_conflict)      //fence或fence.i指令独占周期

    /* 
    数据冲突 
        - RAW
            - 写回寄存器冲突
            - 写CSR冲突
        - Load停顿
            - 指令Load
    结构冲突
        - 结构冲突
    指令使能
        - 指令使能
        - FIFO状态
    指令使能
        - 指令使能
            - ECALL/EBREAK指令
            - CSR指令
    */
}

