package parse
import chisel3._
import chisel3.util._
import name._
import scala.language.reflectiveCalls

// 基础译码模块
class BaseDecode extends Module {
    // 定义访存类型常量
    val MEM_NONE = 0.U(3.W) //不访存 NONE
    val MEM_BYTE = 1.U(3.W) //字节8 B
    val MEM_HALF = 2.U(3.W) //字节16 H
    val MEM_WORD = 3.U(3.W) //字32 W

    //指令类型常量赋值
    val J_TYPE = 1.U(4.W)
    val R_TYPE = 2.U(4.W)
    val I_TYPE = 3.U(4.W)
    val S_TYPE = 4.U(4.W)
    val B_TYPE = 5.U(4.W)
    val U_TYPE = 6.U(4.W)
    val FENCE_TYPE = 7.U(4.W)
    val CSR_TYPE = 8.U(4.W)

    //操作数来源
    val alu_op1_reg = 0.U(2.W)// 从寄存器读取
    val alu_op2_reg = 1.U(2.W)// 从寄存器读取
    val alu_op1_pc = 2.U(2.W)// 从PC读取
    val alu_op1_zero = 3.U(2.W)// 固定为0
    val alu_op2_imm = 1.U(2.W)// 从立即数读取

    // ALU操作类型常量
    val ALU_ADD  = 0.U(5.W)  // 加法：ADD/ADDI/LOAD/STORE/AUIPC
    val ALU_SUB  = 1.U(5.W)  // 减法：SUB/分支比较
    val ALU_SLL  = 2.U(5.W)  // 逻辑左移：SLL/SLLI
    val ALU_SLT  = 3.U(5.W)  // 有符号小于：SLT/SLTI/BLT/BGE
    val ALU_SLTU = 4.U(5.W)  // 无符号小于：SLTU/SLTIU/BLTU/BGEU
    val ALU_XOR  = 5.U(5.W)  // 异或：XOR/XORI
    val ALU_SRL  = 6.U(5.W)  // 逻辑右移：SRL/SRLI
    val ALU_SRA  = 7.U(5.W)  // 算术右移：SRA/SRAI
    val ALU_OR   = 8.U(5.W)  // 或：OR/ORI
    val ALU_AND  = 9.U(5.W)  // 与：AND/ANDI
    val ALU_COPY = 10.U(5.W) // 直接传递操作数1：LUI
    val ALU_NONE = 12.U(5.W) // 不需要ALU运算
    val BR_BEQ = 13.U(5.W)   // 分支跳转
    val BR_BNE = 14.U(5.W)   // 分支跳转
    val BR_BLT = 15.U(5.W)   // 分支跳转
    val BR_BGE = 16.U(5.W)   // 分支跳转
    val BR_BLTU = 17.U(5.W)  // 分支跳转
    val BR_BGEU = 18.U(5.W)  // 分支跳转
    val ALU_CSR_W = 19.U(5.W) // CSR写入
    val ALU_CSR_S = 20.U(5.W) // CSR设置
    val ALU_CSR_C = 21.U(5.W) // CSR清除

    val io = IO(new Bundle {
        val instr =      Input(UInt(32.W)) // 输入的指令
        val inst_type = Output(UInt(4.W)) // 指令类型  //object里完成了定义
        val rs1 =       Output(UInt(5.W)) // 源寄存器1 取出的值 
        val rs2 =       Output(UInt(5.W)) // 源寄存器2 取出的值 
        val rd =        Output(UInt(5.W)) // 目的寄存器 
        val imm =       Output(UInt(32.W)) // 立即数

        val alu_op = Output(UInt(5.W))//指令名称 位宽为什么是5 我乱设置的 够就好了对吗？有空间上限吗？
        val alu_op1 = Output(UInt(2.W)) //执行阶段ALU的操作数1来源 
        val alu_op2 = Output(UInt(2.W)) //执行阶段ALU的操作数2来源 （如果是立即数我会把具体值给到的EX，也就是放进输出）
        val reg_write = Output(Bool()) //是否写回寄存器 
        val mem_read = Output(Bool()) //是否读取内存 LOAD
        val mem_write = Output(Bool()) //是否写入内存 STORE

        //输出的访存操作类型控制信号，例如 Load, Store, None, 以及类型 L/S - B/H/W位宽取决于编码
        val mem_type = Output(UInt(3.W)) // 访存类型，对应 NONE B H W 
        val mem_unsigned = Output(Bool()) //是否为无符号加载 LBU LHU
        val exception = Output(Bool()) // 指令非法标志
    })

    // 初始化所有输出信号的默认值
    io.inst_type := 0.U
    io.rs1 := 0.U
    io.rs2 := 0.U
    io.rd := 0.U
    io.imm := 0.U
    io.alu_op := ALU_NONE
    io.alu_op1 := alu_op1_zero
    io.alu_op2 := alu_op2_imm
    io.reg_write := false.B
    io.mem_read := false.B
    io.mem_write := false.B
    io.mem_type := MEM_NONE
    io.mem_unsigned := false.B
    io.exception := false.B

    //提取指令类型需要的位
    val opcode = io.instr(6, 0)//
    val funct3 = io.instr(14, 12)//  ?
    val funct7 = io.instr(31, 25)//  ?

//提取源寄存器
    io.rs1 := io.instr(19, 15)
    io.rs2 := io.instr(24, 20)
    // io.rd := io.inst(11, 7) 

  //---------------------------------------------------------------------------------------------------
  // inst_type 指令类型
  //---------------------------------------------------------------------------------------------------
    io.inst_type := MuxCase(0.U, Array(
        //J
        (opcode === "b1101111".U) -> J_TYPE,
        //R
        (opcode === "b0110011".U) -> R_TYPE,
        //I
        (opcode === "b0000011".U || opcode === "b0010011".U || opcode === "b1100111".U ) -> I_TYPE,
        //S
        (opcode === "b0100011".U) -> S_TYPE,
        //B
        (opcode === "b1100011".U) -> B_TYPE,
        //U
        (opcode === "b0110111".U || opcode === "b0010111".U) -> U_TYPE,
        //FENCE
        (opcode === "b0001111".U) -> FENCE_TYPE,
        //CSR
        (opcode === "b1110011".U) -> CSR_TYPE,
    ))

  //---------------------------------------------------------------------------------------------------
  // mem_type 访存类型   mem_unsigned 无符号访存
  //---------------------------------------------------------------------------------------------------

    switch(io.inst_type) {
        is(I_TYPE) {
            io.mem_type := MuxCase(MEM_NONE, Array(
                (opcode === "b0000011".U && funct3 === "b000".U) -> MEM_BYTE,  // LB
                (opcode === "b0000011".U && funct3 === "b001".U) -> MEM_HALF,  // LH
                (opcode === "b0000011".U && funct3 === "b010".U) -> MEM_WORD,  // LW
                (opcode === "b0000011".U && funct3 === "b100".U) -> MEM_BYTE,  // LBU
                (opcode === "b0000011".U && funct3 === "b101".U) -> MEM_HALF   // LHU
            ))
            io.mem_unsigned := (opcode === "b0000011".U) && (funct3 === "b100".U || funct3 === "b101".U)  // LBU 和 LHU 是无符号加载
        }
        is(S_TYPE) {
            io.mem_type := MuxCase(MEM_NONE, Array(
                (funct3 === "b000".U) -> MEM_BYTE,  // SB
                (funct3 === "b001".U) -> MEM_HALF,  // SH
                (funct3 === "b010".U) -> MEM_WORD   // SW
            ))
            io.mem_unsigned := false.B  // 存储指令不需要考虑符号
        }
    }

    //提取源寄存器
    io.rs1 := io.instr(19, 15)
    io.rs2 := io.instr(24, 20)
    // io.rd := io.inst(11, 7) //底下已经写好了目的寄存器地址的分类179行

  //---------------------------------------------------------------------------------------------------
  // imm 立即数
  //---------------------------------------------------------------------------------------------------
    io.imm := MuxCase(0.U, Array(
        (io.inst_type === I_TYPE) -> Cat(Fill(20, io.inst(31)), io.inst(31, 20)),
        (io.inst_type === S_TYPE) -> Cat(Fill(20, io.inst(31)), io.inst(31, 25), io.inst(11, 7)),
        (io.inst_type === B_TYPE) -> Cat(Fill(19, io.inst(31)), io.inst(7), io.inst(30, 25), io.inst(11, 8), 0.U(1.W)),
        (io.inst_type === U_TYPE) -> Cat(io.inst(31, 12), Fill(12, 0.U(1.W))),//立即数放在高12位
        (io.inst_type === J_TYPE) -> Cat(Fill(11, io.inst(31)), io.inst(31), io.inst(19, 12), io.inst(20), io.inst(30, 21), 0.U(1.W)),
        //R型指令没有立即数
        (io.inst_type === R_TYPE) -> 0.U(32.W),
        //CSR指令，立即数在rs1位置（我不确定）
        (io.inst_type === CSR_TYPE) -> Cat(Fill(27, 0.U(1.W)), io.inst(19, 15)),
        //FENCE指令没有立即数
        (io.inst_type === FENCE_TYPE) -> 0.U(32.W),
    ))
    
    //目的寄存器地址
    switch(io.inst_type){
        is(I_TYPE){
            io.rd := io.inst(11, 7)
        }
        is(R_TYPE){
            io.rd := io.inst(11, 7)
        }
        is(U_TYPE){
            io.rd := io.inst(11, 7)
        }
        is(J_TYPE){
            io.rd := io.inst(11, 7)
        }
        is(S_TYPE){
            io.rd := 0.U(5.W)  //store指令没有目的寄存器
        }
        is(B_TYPE){
            io.rd := 0.U(5.W)  //分支跳转指令没有目的寄存器
        }
        is(CSR_TYPE){
            io.rd := io.inst(11,7)
        }
        is(FENCE_TYPE){
            io.rd := 0.U(5.W)
        }
        is(0.U) {
            io.rd := 0.U(5.W)
        }
    }
 
  //------------------------------------------------------------------------------------------------------------------------------------------------------------
  // alu_op1 操作数1来源  alu_op2 操作数2来源  reg_write 是否写回寄存器 mem_read 是否读取内存 mem_write 是否写入内存 mem_type 访存类型 mem_unsigned 无符号访存
  //------------------------------------------------------------------------------------------------------------------------------------------------------------
    switch(io.inst_type) {
        is(I_TYPE){  
            io.alu_op1 := alu_op1_reg    // 第一个操作数从寄存器读取
            io.alu_op2 := alu_op2_imm    // 第二个操作数从立即数读取
            io.reg_write := true.B       // 写回寄存器
            io.mem_read := (opcode === "b0000011".U)  // 只有Load指令需要读取内存
            io.mem_write := false.B      // 不写入内存
            io.mem_unsigned := (opcode === "b0000011".U) && (funct3 === "b100".U || funct3 === "b101".U)  // LBU 和 LHU 是无符号加载
        }
        is(R_TYPE){
            io.alu_op1 := alu_op1_reg        // 第一个操作数从寄存器读取    
            io.alu_op2 := alu_op2_reg        // 第二个操作数从寄存器读取
            io.reg_write := true.B           // 写回寄存器
            io.mem_read := false.B           // 不读取内存
            io.mem_write := false.B          // 不写入内存
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }
        is(B_TYPE){                         // 分支跳转
            io.alu_op1 := alu_op1_reg        // 第一个操作数从寄存器读取
            io.alu_op2 := alu_op2_reg        // 第二个操作数从寄存器读取
            io.reg_write := false.B          // 不写回寄存器
            io.mem_read := false.B           // 不读取内存
            io.mem_write := false.B          // 不写入内存
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }
        is(U_TYPE){
            io.alu_op1 := Mux(opcode === "b0110111".U, alu_op1_zero, alu_op1_pc)   // LUI用zero，AUIPC用PC
            io.alu_op2 := alu_op2_imm    // 第二个操作数从立即数读取
            io.reg_write := true.B           // 都需要写回寄存器
            io.mem_read := false.B           // 不读取内存
            io.mem_write := false.B          // 不写入内存
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }
        is(J_TYPE){                         // JAL
            io.alu_op1 := alu_op1_pc         // 第一个操作数从PC读取
            io.alu_op2 := alu_op2_imm        // 第二个操作数从立即数读取
            io.reg_write := true.B           // 写回寄存器
            io.mem_read := false.B           // 不读取内存
            io.mem_write := false.B          // 不写入内存
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }
        is(S_TYPE){
            io.alu_op1 := alu_op1_reg        // 第一个操作数从寄存器读取
            io.alu_op2 := alu_op2_imm        // 第二个操作数从立即数读取
            io.reg_write := false.B          // 不写回寄存器
            io.mem_read := false.B           // 不读取内存
            io.mem_write := true.B           // 写入内存
            // io.mem_unsigned := false.B       // 存储指令不需要考虑符号
        }
        is(FENCE_TYPE){
            io.alu_op1 := alu_op1_zero       // FENCE指令不需要ALU运算
            io.alu_op2 := alu_op2_imm        // FENCE指令不需要ALU运算
            io.reg_write := false.B          // 不需要写回寄存器
            io.mem_read := false.B           // 不需要读内存
            io.mem_write := false.B          // 不需要写内存
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }     // 不需要写内存
        is(CSR_TYPE){
            io.alu_op1 := Mux(funct3(1,0) === "b01".U, alu_op1_reg, alu_op1_zero)  // CSRRW/CSRRWI用reg，其他用zero
            io.alu_op2 := alu_op2_imm
            io.reg_write := true.B           // 写回rd
            io.mem_read := false.B
            io.mem_write := false.B
            io.mem_type := MEM_NONE          // 不访存
            io.mem_unsigned := false.B       // 非访存指令，设置为false
        }
        is(0.U) {
            io.rd := 0.U(5.W)
        }
    }

  //---------------------------------------------------------------------------------------------------
  // alu_op 操作名称
  //---------------------------------------------------------------------------------------------------
    io.alu_op := MuxCase(ALU_NONE, Array(
        // R型指令
        ((io.inst_type === R_TYPE) && (funct3 === "b000".U) && (funct7 === "b0000000".U)) -> ALU_ADD,  // ADD
        ((io.inst_type === R_TYPE) && (funct3 === "b000".U) && (funct7 === "b0100000".U)) -> ALU_SUB,  // SUB
        ((io.inst_type === R_TYPE) && (funct3 === "b001".U)) -> ALU_SLL,  // SLL
        ((io.inst_type === R_TYPE) && (funct3 === "b010".U)) -> ALU_SLT,  // SLT
        ((io.inst_type === R_TYPE) && (funct3 === "b011".U)) -> ALU_SLTU, // SLTU
        ((io.inst_type === R_TYPE) && (funct3 === "b100".U)) -> ALU_XOR,  // XOR
        ((io.inst_type === R_TYPE) && (funct3 === "b101".U) && (funct7 === "b0000000".U)) -> ALU_SRL,  // SRL
        ((io.inst_type === R_TYPE) && (funct3 === "b101".U) && (funct7 === "b0100000".U)) -> ALU_SRA,  // SRA
        ((io.inst_type === R_TYPE) && (funct3 === "b110".U)) -> ALU_OR,   // OR
        ((io.inst_type === R_TYPE) && (funct3 === "b111".U)) -> ALU_AND,  // AND

        // I型指令
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b000".U)) -> ALU_ADD,  // ADDI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b010".U)) -> ALU_SLT,  // SLTI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b011".U)) -> ALU_SLTU, // SLTIU
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b100".U)) -> ALU_XOR,  // XORI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b110".U)) -> ALU_OR,   // ORI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b111".U)) -> ALU_AND,  // ANDI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b001".U)) -> ALU_SLL,  // SLLI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b101".U) && (funct7 === "b0000000".U)) -> ALU_SRL, // SRLI
        ((io.inst_type === I_TYPE) && (opcode === "b0010011".U) && (funct3 === "b101".U) && (funct7 === "b0100000".U)) -> ALU_SRA, // SRAI
        
        // Load指令
        ((io.inst_type === I_TYPE) && (opcode === "b0000011".U)) -> ALU_ADD, // 所有Load指令都用ADD计算地址
        
        // Store指令
        (io.inst_type === S_TYPE) -> ALU_ADD, // 所有Store指令都用ADD计算地址
        
        // Branch指令
        ((io.inst_type === B_TYPE) && (funct3 === "b000".U)) -> BR_BEQ,  // BEQ
        ((io.inst_type === B_TYPE) && (funct3 === "b001".U)) -> BR_BNE,  // BNE
        ((io.inst_type === B_TYPE) && (funct3 === "b100".U)) -> BR_BLT,  // BLT
        ((io.inst_type === B_TYPE) && (funct3 === "b101".U)) -> BR_BGE,  // BGE
        ((io.inst_type === B_TYPE) && (funct3 === "b110".U)) -> BR_BLTU, // BLTU
        ((io.inst_type === B_TYPE) && (funct3 === "b111".U)) -> BR_BGEU, // BGEU
        
        // U型指令
        ((io.inst_type === U_TYPE) && (opcode === "b0110111".U)) -> ALU_COPY, // LUI
        ((io.inst_type === U_TYPE) && (opcode === "b0010111".U)) -> ALU_ADD,  // AUIPC
        
        // J型指令
        (io.inst_type === J_TYPE) -> ALU_COPY, // JAL
        ((io.inst_type === I_TYPE) && (opcode === "b1100111".U)) -> ALU_COPY, // JALR
        
        // CSR指令
        ((io.inst_type === CSR_TYPE) && (funct3(1,0) === "b01".U)) -> ALU_CSR_W, // CSRRW/CSRRWI
        ((io.inst_type === CSR_TYPE) && (funct3(1,0) === "b10".U)) -> ALU_CSR_S, // CSRRS/CSRRSI
        ((io.inst_type === CSR_TYPE) && (funct3(1,0) === "b11".U)) -> ALU_CSR_C  // CSRRC/CSRRCI

        // Fence指令

    ))
    io.exception := io.inst_type === 0.U
}
}
