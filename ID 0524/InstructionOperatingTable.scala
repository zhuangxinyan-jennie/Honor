    // 在Parse类中添加如下注释和控制信号设置：

/*  mem_type 访存类型 对应 NONE B H W 这样一来 我就可以直接用mem_type来判断是否访存了 好像就不用mem_read mem_write了 我不知道你更偏好哪种,是要留下还是删掉mem_read mem_write？
指令格式说明： alu_op                 alu_op1       alu_op2                               reg_write    mem_read   mem_write      mem_type       mem_unsigned                     |   rd          imm
指令名 -> (是否使用ALU及其操作名称, ALU操作数1来源, ALU操作数2来源,                        是否写回寄存器, 是否读内存, 是否写内存, 是否访存及访存类型,   无符号标志                                             |  目的寄存器      立即数)










R型指令：
ADD     -> (ALU_ADD,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 + rs2
SUB     -> (ALU_SUB,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 - rs2
SLL     -> (ALU_SLL,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 << rs2
SLT     -> (ALU_SLT,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = (rs1 < rs2)?1:0
SLTU    -> (ALU_SLTU, alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = (rs1 < rs2)?1:0 无符号
AND     -> (ALU_AND,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 & rs2
OR      -> (ALU_OR,   alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 | rs2
XOR     -> (ALU_XOR,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 ^ rs2
SRL     -> (ALU_SRL,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 >>  rs2
SRA     -> (ALU_SRA,  alu_op1_reg, alu_op2_reg, true,  false, false, MEM_NONE, false) // rd = rs1 >>> rs2

I型指令：
ADDI    -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 + imm
SLTI    -> (ALU_SLT,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = (rs1 < imm)?1:0
SLTIU   -> (ALU_SLTU, alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = (rs1 < imm)?1:0 无符号
ANDI    -> (ALU_AND,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 & imm
ORI     -> (ALU_OR,   alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 | imm
XORI    -> (ALU_XOR,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 ^ imm
SLLI    -> (ALU_SLL,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 << imm
SRLI    -> (ALU_SRL,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 >> imm
SRAI    -> (ALU_SRA,  alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = rs1 >>> imm

加载指令：
LB      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  true,  false, MEM_BYTE, false) // rd = sext(M[rs1 + imm][7:0])
LH      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  true,  false, MEM_HALF, false) // rd = sext(M[rs1 + imm][15:0])
LW      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  true,  false, MEM_WORD, false) // rd = M[rs1 + imm][31:0]
LBU     -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  true,  false, MEM_BYTE, true)  // rd = M[rs1 + imm][7:0]
LHU     -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, true,  true,  false, MEM_HALF, true)  // rd = M[rs1 + imm][15:0]

存储指令：ok
SB      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, false, false, true,  MEM_BYTE, false) // M[rs1 + imm][7:0] = rs2[7:0]
SH      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, false, false, true,  MEM_HALF, false) // M[rs1 + imm][15:0] = rs2[15:0]
SW      -> (ALU_ADD,  alu_op1_reg, alu_op2_imm, false, false, true,  MEM_WORD, false) // M[rs1 + imm][31:0] = rs2[31:0]

分支指令：
BEQ     -> (BR_REQ ,  alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 == rs2) pc += imm
BNE     -> (BR_BNE,  alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 != rs2) pc += imm
BLT     -> (BR_BLT,  alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 < rs2)  pc += imm
BGE     -> (BR_BGE,  alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 >= rs2) pc += imm
BLTU    -> (BR_BLTU, alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 < rs2)  pc += imm 无符号
BGEU    -> (BR_BGEU, alu_op1_reg, alu_op2_reg, false, false, false, MEM_NONE, false) // if(rs1 >= rs2) pc += imm 无符号

U型指令：
LUI     -> (ALU_COPY,alu_op1_zero,alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = imm << 12
AUIPC   -> (ALU_ADD,  alu_op1_pc,  alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = pc + (imm << 12)

跳转指令：
JAL     -> (ALU_COPY,alu_op1_pc,  alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = pc + 4; pc += imm
JALR    -> (ALU_COPY,alu_op1_reg, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = pc + 4; pc = (rs1 + imm) & ~1

系统指令：
ECALL   -> (ALU_NONE,  alu_op1_zero,alu_op2_imm, false, false, false, MEM_NONE, false) // 系统调用
EBREAK  -> (ALU_NONE,  alu_op1_zero,alu_op2_imm, false, false, false, MEM_NONE, false) // 断点
FENCE   -> (ALU_NONE,  alu_op1_zero,alu_op2_imm, false, false, false, MEM_NONE, false) // 内存屏障
FENCE_I -> (ALU_NONE,  alu_op1_zero,alu_op2_imm, false, false, false, MEM_NONE, false) // 内存屏障 不确定写的对不对

CSR指令：简单传值，不需要ALU运算是吗？？ 蔡心钰同学负责CSR接收并处理
CSRRW   -> (ALU_CSR_W, alu_op1_reg,  alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = rs1
CSRRS   -> (ALU_CSR_S, alu_op1_reg,  alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = CSRs[csr] | rs1
CSRRC   -> (ALU_CSR_C, alu_op1_reg,  alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = CSRs[csr] & ~rs1
CSRRWI  -> (ALU_CSR_W, alu_op1_zero, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = zimm
CSRRSI  -> (ALU_CSR_S, alu_op1_zero, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = CSRs[csr] | zimm
CSRRCI  -> (ALU_CSR_C, alu_op1_zero, alu_op2_imm, true,  false, false, MEM_NONE, false) // rd = CSRs[csr]; CSRs[csr] = CSRs[csr] & ~zimm
*/
