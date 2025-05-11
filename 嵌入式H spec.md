# **RISC-V 五级流水顺序双发射处理器 (Basic Functionality Spec)**

[TOC]

## **1. 引言**

本文档旨在详细描述一款五级流水线、顺序双发射的 RISC-V 处理器。该处理器将实现 RV32I 基本指令集，并满足基本功能要求，为后续的扩展和优化提供基础平台。

## **2. 设计目标**

*   包含RV32I的所有指令
*   实现csrrw指令和mtime、mcycle这两个csr
*   实现RV32M
*   实现TAGE分支预测器
*   实现不定周期的访存级，即访存级的延时不被固定为一个周期
*   在实现不定周期访存级的基础上，实现一个简易的dcache
*   五级流水线
*   顺序双发 
*   通过drystone测试

## **3. 设计参数定义**

| 参数名称       | 值                   | 描述                                                         |
| -------------- | -------------------- | ------------------------------------------------------------ |
| **ISA**        | RV32I                | 支持 RISC-V 32 位整数指令集基础规范                          |
| **流水线级数** | 5                    | 五级流水线：取指 (IF)、译码 (ID)、执行 (EX)、访存 (MEM)、写回 (WB) |
| **发射宽度**   | 2                    | 顺序双发射，每个周期最多发射两条指令到执行阶段               |
| **执行单元**   | 至少 2 个 ALU        | 执行阶段至少包含两个算术逻辑单元 (ALU)，可根据需要扩展其他执行单元，如乘除法器等 |
| **分支预测器** | TAGE分支预测器       | TAgged GEometric length predictor                            |
| **CSR 支持**   | csrrw, mtime, mcycle | 支持 `csrrw` 指令和 `mtime`、`mcycle` 控制状态寄存器 (CSR)   |
| **存储器接口** | 不定周期访问         | 支持不定周期访存，存储器接口包含请求和就绪信号               |
| **测试基准**   | Drystone             | 通过 Drystone 基准测试验证处理器功能                         |

## **4. 微架构概述**

该处理器参考CDIM处理器架构(https://github.com/Maxpicca-Li/CDIM/tree/master)，设计了一款款双发射五级顺序流水线 CPU，其支持指令 FIFO、分支预测、指令缓存和数据缓存。其中，五级顺序流水线由取指（Instruction Fetch）、译码（Instruction Decode）、执行（Excute）、访存（Memory access），写回（Write Back）五个阶段组成；双发射采用对称双发逻辑以充分保证双发率；指令FIFO 可以隔离取指阶段和后续阶段，以实现高效取指的作用；分支预测采用TAGE分支预测器来提高预测的准确率。处理器的数据通路示意图如下所示，其中红线部分为 MasterPath，蓝线部分为 Slave Path。在对称双发射中，Master 和 Slave 的主要区别在于前者先于后者执行，在处理异常、提交等事宜时会被优先处理。

### **4.1. 双发策略**

在双发射的 CPU 设计中，双发策略会影响整个数据通路的设计，我们下面介绍我们处理器的双发策略，在issuectrl的代码部分解释了整个处理器的双发策略。

**1．only＿Master 类指令：**即只能放在 Master 发射，且 Slave 不发射指令。对于可能刷新整个流水线的指令（如异常处理有关的指令），如果在 Slave 中检测到这类指令，则暂停 Slave 发射，将其延迟到下一周期的 Master 发射；如果在 Master 中检测到这类指令，也会暂停 Slave 发射，避免不必要的流水线刷新。

**2．only＿in＿Master 类指令：**即只能放在 Master 发射，但Slave可发射指令。对于跳转指令，在一定条件下会跳转到其他地址，并刷新整个流水线。和 only＿Master 不同的是，跳转指令会涉及到延迟槽的处理。故如果在 Slave 中检测到这类指令，则暂停 Slave 发射，将其延迟到下一周期的 Master 发射；如果在 Master 中检测到这类指令，若满足 Slave 发射条件，则发射该条指令（延迟槽），若不满足，则在下一周期的 Master 中发射延迟槽数据。

**3．数据冲突**
（a）．RAW 冲突：检查 Master 和 Slave 之间是否存在 CSR，Regfile，(待补充，比如乘除法单元) 等寄存器的 RAW 情况，若存在，则 Slave 不发射。
（b）．Load To Use 冲突：检查 Slave 读到的寄存器是否是当前在 E阶段执行的访存指令的结果，若是，则 Slave 不发射。

**4．数据有效性：**Slave 发射的前提是 Master 发射或当前第二条指令有效。若遇到 Master 暂停发射或指令 FIFO为空的情况下，Slave 不发射。



### **4.2. 冲突处理**

**2.4.1 数据冲突**
－RAW 冲突：当 Master 或 Slave 需要读取的数据是已经发射且进入后续流水阶段的指令，会产生 RAW 冲突。本设计将需要的数据均前推到 D 阶段，前推后立即进入触发器后再进入 E阶段使用，这样可以避免执行数据的路径过长。其中，若需要读取的数据是当前 E 阶段的访存数据，则需要进行阻塞以等待访存数据在 M 阶段返回。若需要读取的数据是当前 E 阶段的计算结果，或 M 阶段的计算结果，或 M 阶段的访存结果，则通过数据前推模块前推到 D 阶段。
－WAW 冲突：当同周期的 Master 和 Slave 写入寄存器的地址一致时，会产生 WAW 冲突。因为 Slave 是 Master后一条指令，故 RegFile 更新时，会优先写入 Slave 的结果。
－WAR 冲突：由于本设计为顺序流水线，故不存在 WAR 冲突。

**2.4.2 控制冲突**
当遇到跳转指令时，会产生控制冲突。我们用延迟槽来解决控制冲突。本设计中，若产生跳转（译码阶段或执行阶段），则根据发射情况判断当前 Path 中的第一条指令是否是延迟槽指令。如果是，则需要判断当时是否有阻塞信号，若没有则直接执行该第一条指令，若有则需要保留该指令到指定 delayslot＿data 寄存器，以备阻塞取消后使用；此外跳转会引起流水线的刷新，但需要保证延迟槽指令能够不被刷新并正常流入下一阶段执行。如果不是，则正常刷新流水线即可。



### 4.3. 结构框图

![image-20250424141106775](C:\Users\music\AppData\Roaming\Typora\typora-user-images\image-20250424141106775.png)

红线部分为Master Path，蓝线部分为Slave Path

## **5. 模块端口定义及功能描述 **

以下列出各主要模块的端口定义及功能描述。方向 `I` 表示输入， `O` 表示输出。

### **5.1. 指令取指模块 (IF - Instruction Fetch)**

#### **pc_reg**

| clk              | I    | 1    | 时钟信号                                          |
| ---------------- | ---- | ---- | ------------------------------------------------- |
| rst              | I    | 1    | 复位信号                                          |
| pc_en            | I    | 1    | PC 寄存器使能信号（正常运行或异常跳转时）         |
| M_except         | I    | 1    | M 阶段是否发生异常或中断需要跳转                  |
| M_except_addr    | I    | 32   | M 阶段异常跳转的目标 PC 地址                      |
| M_flush_all      | I    | 1    | M 阶段是否需要刷新整个流水线                      |
| M_flush_all_addr | I    | 32   | M 阶段刷新流水线时的目标 PC 地址（通常是 PC+4）   |
| E_bj             | I    | 1    | E 阶段是否发生分支/跳转                           |
| E_bj_target      | I    | 32   | E 阶段分支/跳转的目标 PC 地址                     |
| D_bj             | I    | 1    | D 阶段是否发生分支/跳转                           |
| D_bj_target      | I    | 32   | D 阶段分支/跳转的目标 PC 地址                     |
| D_fifo_full      | I    | 1    | 指令 FIFO 是否已满                                |
| F_inst_data_ok1  | I    | 1    | F 阶段从指令存储器获取的第一条指令是否有效        |
| F_inst_data_ok2  | I    | 1    | F 阶段从指令存储器获取的第二条指令是否有效        |
| pc_next          | O    | 32   | 计算出的下一个时钟周期的 PC 值（未考虑跳转/异常） |
| pc_curr          | O    | 32   | 当前时钟周期的 PC 值                              |

#### **IFIFO**

| 端口名称                 | 方向 | 位宽 | 功能描述                                                   |
| ------------------------ | ---- | ---- | ---------------------------------------------------------- |
| clk                      | I    | 1    | 时钟信号                                                   |
| rst                      | I    | 1    | 复位信号                                                   |
| fifo_rst                 | I    | 1    | FIFO 复位信号（通常在 D 阶段刷新时）                       |
| flush_delay_slot         | I    | 1    | 是否需要特殊处理刷新延迟槽指令                             |
| D_ena                    | I    | 1    | D 阶段是否使能（影响 FIFO 读指针）                         |
| i_stall                  | I    | 1    | 来自指令 Cache 的暂停信号（影响 FIFO 写指针）              |
| master_is_branch         | I    | 1    | D 阶段 Master 指令是否是分支/跳转指令                      |
| delay_sel_rst            | I    | 1    | 延迟槽特殊复位/选择逻辑控制信号                            |
| D_delay_rst              | I    | 1    | D 阶段检测到跳转，指示下一条 Master 指令是延迟槽           |
| E_delay_rst              | I    | 1    | E 阶段检测到跳转，指示当前 Master 指令是延迟槽             |
| read_en1                 | I    | 1    | Master 指令读取 FIFO 使能（通常等于 D_ena）                |
| read_en2                 | I    | 1    | Slave 指令读取 FIFO 使能（来自 issue_ctrl）                |
| read_tlb_refill1         | O    | 1    | 从 FIFO 读出的 Master 指令是否关联 TLB refill 异常         |
| read_tlb_refill2         | O    | 1    | 从 FIFO 读出的 Slave 指令是否关联 TLB refill 异常          |
| read_tlb_invalid1        | O    | 1    | 从 FIFO 读出的 Master 指令是否关联 TLB invalid 异常        |
| read_tlb_invalid2        | O    | 1    | 从 FIFO 读出的 Slave 指令是否关联 TLB invalid 异常         |
| read_address1            | O    | 32   | 从 FIFO 读出的 Master 指令的 PC 地址                       |
| read_address2            | O    | 32   | 从 FIFO 读出的 Slave 指令的 PC 地址                        |
| read_data1               | O    | 32   | 从 FIFO 读出的 Master 指令内容                             |
| read_data2               | O    | 32   | 从 FIFO 读出的 Slave 指令内容                              |
| write_en1                | I    | 1    | F 阶段获取的第一条指令有效，允许写入 FIFO                  |
| write_en2                | I    | 1    | F 阶段获取的第二条指令有效，允许写入 FIFO                  |
| write_tlb_refill1        | I    | 1    | 写入 FIFO 的第一条指令是否关联 TLB refill 异常（来自 IF）  |
| write_tlb_refill2        | I    | 1    | 写入 FIFO 的第二条指令是否关联 TLB refill 异常（来自 IF）  |
| write_tlb_invalid1       | I    | 1    | 写入 FIFO 的第一条指令是否关联 TLB invalid 异常（来自 IF） |
| write_tlb_invalid2       | I    | 1    | 写入 FIFO 的第二条指令是否关联 TLB invalid 异常（来自 IF） |
| write_address1           | I    | 32   | 写入 FIFO 的第一条指令的 PC 地址 (F_pc)                    |
| write_address2           | I    | 32   | 写入 FIFO 的第二条指令的 PC 地址 (F_pc + 4)                |
| write_data1              | I    | 32   | 写入 FIFO 的第一条指令内容 (来自 I-Cache)                  |
| write_data2              | I    | 32   | 写入 FIFO 的第二条指令内容 (来自 I-Cache)                  |
| master_is_in_delayslot_o | O    | 1    | 输出，指示当前从 FIFO 读出的 Master 指令是否处于延迟槽     |
| empty                    | O    | 1    | FIFO 是否为空                                              |
| almost_empty             | O    | 1    | FIFO 是否几乎为空（如只剩一条指令）                        |
| full                     | O    | 1    | FIFO 是否已满                                              |

### **5.2. 指令译码模块 (ID - Instruction Decode)**

#### **ID_M和ID_S**

| 端口名称              | 方向 | 位宽      | 功能描述                                        |
| --------------------- | ---- | --------- | ----------------------------------------------- |
| instr                 | I    | 32        | 输入的指令码                                    |
| c0_useable            | I    | 1         | CSR是否可用 (根据 RISCV 架构模式判断)           |
| op                    | O    | 6         | 指令的 opcode 字段                              |
| rs                    | O    | 5         | 指令的 rs 字段 (源寄存器 1 地址)                |
| rt                    | O    | 5         | 指令的 rt 字段 (源寄存器 2 / 目标寄存器地址)    |
| rd                    | O    | 5         | 指令的 rd 字段 (目标寄存器地址)                 |
| funct                 | O    | 6         | R 型指令的 funct 字段                           |
| reg_waddr             | O    | 5         | 该指令需要写入的寄存器地址                      |
| shamt_value           | O    | 32        | 移位指令的移位量 (shamt)                        |
| sign_extend_imm_value | O    | 32        | 指令中的立即数进行符号扩展后的值                |
| is_link_pc8           | O    | 1         | 是否是链接指令 (如 JAL, BLTZAL)，需要保存 PC+8  |
| branch_type           | O    | 4         | 分支指令类型 (BEQ, BNE, BLEZ, etc.)             |
| trap_type             | O    | 4         | 自陷指令类型 (TGE, TNE, etc.)                   |
| cmov_type             | O    | CmovBus   | 条件传送指令类型 (MOVN, MOVZ)                   |
| signsD                | O    | ctrl_sign | 输出的译码控制信号集合                          |
| undefined_inst        | O    | 1         | 是否是未定义指令 (保留指令异常)                 |
| syscall_inst          | O    | 1         | 是否是 SYSCALL 指令                             |
| break_inst            | O    | 1         | 是否是 BREAK 指令                               |
| eret_inst             | O    | 1         | 是否是 ERET 指令                                |
| id_cpu                | O    | 1         | 是否是协处理器不可用异常 (CPUnavailable)        |
| cop0_info_out         | O    | cop0_info | CSR 相关指令的操作信息 (读/写哪个 CSR 寄存器等) |

#### **BP模块**

| 端口名称       | 方向 | 位宽 | 功能描述                                   |
| -------------- | ---- | ---- | ------------------------------------------ |
| clk            | I    | 1    | 时钟信号                                   |
| rst            | I    | 1    | 复位信号                                   |
| enaD           | I    | 1    | D 阶段使能信号                             |
| instrD         | I    | 32   | D 阶段的指令 (用于判断是否是分支指令)      |
| pcD            | I    | 32   | D 阶段的 PC 值                             |
| pc_plus4D      | I    | 32   | D 阶段的 PC + 4                            |
| pcE            | I    | 32   | E 阶段的分支指令 PC 值                     |
| branchE        | I    | 1    | E 阶段是否是分支指令                       |
| actual_takeE   | I    | 1    | E 阶段分支指令的实际跳转结果               |
| branchD        | O    | 1    | 输出，指示 D 阶段指令是否是分支指令        |
| pred_takeD     | O    | 1    | 输出，D 阶段分支预测的结果（预测是否跳转） |
| branch_targetD | O    | 32   | 输出，D 阶段分支预测的目标地址             |

#### **Issue-ctrl**

| 端口名称                | 方向 | 位宽 | 功能描述                                                     |
| ----------------------- | ---- | ---- | ------------------------------------------------------------ |
| D_master_ena            | I    | 1    | 输入，指示 D 阶段 Master 路径是否有效（通常为真）            |
| D_mem_conflict          | I    | 1    | D 阶段检测到的访存结构冲突 (Master 和 Slave 都需要访存)      |
| D_mul_conflict          | I    | 1    | D 阶段检测到的乘法单元结构冲突 (Master 和 Slave 都需要乘法)  |
| D_div_conflict          | I    | 1    | D 阶段检测到的除法单元结构冲突 (Master 和 Slave 都需要除法)  |
| E_master_memtoReg       | I    | 1    | E 阶段 Master 指令是否 Load (用于检测 Load-Use 冲突)         |
| E_master_reg_waddr      | I    | 5    | E 阶段 Master 指令写回地址 (用于 Load-Use)                   |
| E_slave_memtoReg        | I    | 1    | E 阶段 Slave 指令是否 Load (用于 Load-Use)                   |
| E_slave_reg_waddr       | I    | 5    | E 阶段 Slave 指令写回地址 (用于 Load-Use)                    |
| D_master_reg_wen        | I    | 1    | D 阶段 Master 指令是否写寄存器 (用于 RAW 检测)               |
| D_master_reg_waddr      | I    | 5    | D 阶段 Master 指令写寄存器地址 (用于 RAW 检测)               |
| D_slave_read_rs         | I    | 1    | D 阶段 Slave 指令是否读 rs                                   |
| D_slave_read_rt         | I    | 1    | D 阶段 Slave 指令是否读 rt                                   |
| D_slave_rs              | I    | 5    | D 阶段 Slave 指令的 rs 地址                                  |
| D_slave_rt              | I    | 5    | D 阶段 Slave 指令的 rt 地址                                  |
| D_master_hilowrite      | I    | 1    | D 阶段 Master 指令是否写 Hi/Lo 寄存器                        |
| D_slave_hiloread        | I    | 1    | D 阶段 Slave 指令是否读 Hi/Lo 寄存器                         |
| D_slave_hilowrite       | I    | 1    | D 阶段 Slave 指令是否写 Hi/Lo 寄存器                         |
| D_master_CSRwrite       | I    | 1    | D 阶段 Master 指令是否写 CSR 寄存器                          |
| D_slave_CSRread         | I    | 1    | D 阶段 Slave 指令是否读 CSR 寄存器                           |
| D_master_is_branch      | I    | 1    | D 阶段 Master 指令是否是分支/跳转指令 (用于判断 Slave 是否在延迟槽) |
| D_master_only_one_issue | I    | 1    | D 阶段 Master 指令是否是只能单发射的指令 (如 SYSCALL, ERET)  |
| D_slave_only_one_issue  | I    | 1    | D 阶段 Slave 指令是否是只能单发射的指令 (如果出现在 Slave 路径) |
| D_slave_may_bring_flush | I    | 1    | D 阶段 Slave 指令是否可能导致刷新 (通常指延迟槽中的跳转指令) |
| fifo_empty              | I    | 1    | 指令 FIFO 是否为空                                           |
| fifo_almost_empty       | I    | 1    | 指令 FIFO 是否几乎为空                                       |
| D_slave_is_in_delayslot | O    | 1    | 输出，指示 D 阶段 Slave 指令是否被判断为处于延迟槽           |
| D_slave_ena             | O    | 1    | 输出，控制 D 阶段 Slave 路径是否使能并发射指令到 E 阶段      |

### **5.3. 执行模块 (EX - Execute)**

### **5.4. 访存模块 (MEM - Memory Access)**

| 端口名称         | 方向 | 位宽       | 功能描述                                              |
| ---------------- | ---- | ---------- | ----------------------------------------------------- |
| mem_en           | I    | 1          | M 阶段是否有访存操作                                  |
| mem_op           | I    | 6          | M 阶段访存操作类型                                    |
| mem_wdata        | I    | 32         | M 阶段需要写入 Cache 的数据                           |
| mem_addr         | I    | 32         | M 阶段访存的物理地址或虚拟地址                        |
| mem_rdata        | O    | 32         | 处理后的内存读取结果 (根据 Load 类型选择字节/半字/字) |
| data_sram_rdata  | I    | 32         | 从数据 Cache 接口实际读回的数据                       |
| data_sram_en     | O    | 1          | 输出到数据 Cache，指示是否进行访问                    |
| data_sram_rlen   | O    | 2          | 输出到数据 Cache，读操作长度                          |
| data_sram_wen    | O    | 4          | 输出到数据 Cache，写操作字节使能                      |
| data_sram_addr   | O    | 32         | 输出到数据 Cache 的访问地址                           |
| data_sram_wdata  | O    | 32         | 输出到数据 Cache 的写入数据                           |
| M_master_mem_sel | I    | 1          | M 阶段是否选中 Master 路径访存                        |
| M_slave_mem_sel  | I    | 1          | M 阶段是否选中 Slave 路径访存                         |
| M_master_except  | I    | except_bus | M 阶段 Master 路径异常信息 (用于判断访存是否有效)     |
| M_slave_except   | I    | except_bus | M 阶段 Slave 路径异常信息 (用于判断访存是否有效)      |

### **5.5. 寄存器堆 (RF - Register File)**

| 端口名称 | 方向 | 位宽 | 功能描述                                                     |
| -------- | ---- | ---- | ------------------------------------------------------------ |
| clk      | I    | 1    | 时钟信号                                                     |
| rst      | I    | 1    | 复位信号                                                     |
| ra1_a    | I    | 5    | Master 路径读端口 1A 地址 (通常接 D_master_rs)               |
| rd1_a    | O    | 32   | Master 路径读端口 1A 数据输出                                |
| ra1_b    | I    | 5    | Master 路径读端口 1B 地址 (通常接 D_master_rt)               |
| rd1_b    | O    | 32   | Master 路径读端口 1B 数据输出                                |
| wen1     | I    | 1    | Master 路径写端口 1 使能 (来自 W 阶段，需检查 W_ena 和 W_master_except) |
| wa1      | I    | 5    | Master 路径写端口 1 地址 (来自 W 阶段)                       |
| wd1      | I    | 32   | Master 路径写端口 1 数据 (来自 W 阶段)                       |
| ra2_a    | I    | 5    | Slave 路径读端口 2A 地址 (通常接 D_slave_rs)                 |
| rd2_a    | O    | 32   | Slave 路径读端口 2A 数据输出                                 |
| ra2_b    | I    | 5    | Slave 路径读端口 2B 地址 (通常接 D_slave_rt)                 |
| rd2_b    | O    | 32   | Slave 路径读端口 2B 数据输出                                 |
| wen2     | I    | 1    | Slave 路径写端口 2 使能 (来自 W 阶段，需检查 W_ena 和异常)   |
| wa2      | I    | 5    | Slave 路径写端口 2 地址 (来自 W 阶段)                        |
| wd2      | I    | 32   | Slave 路径写端口 2 数据 (来自 W 阶段)                        |

### **5.6. 控制单元模块 (CU - Control Unit - 流水线控制逻辑)**

| 端口名称           | 方向 | 位宽 | 功能描述                                            |
| ------------------ | ---- | ---- | --------------------------------------------------- |
| D_master_read_rs   | I    | 1    | D 阶段 Master 指令是否读取 rs 寄存器                |
| D_master_read_rt   | I    | 1    | D 阶段 Master 指令是否读取 rt 寄存器                |
| D_master_rs        | I    | 5    | D 阶段 Master 指令的 rs 寄存器地址                  |
| D_master_rt        | I    | 5    | D 阶段 Master 指令的 rt 寄存器地址                  |
| E_master_memtoReg  | I    | 1    | E 阶段 Master 指令是否是访存写回寄存器（Load 指令） |
| E_master_reg_waddr | I    | 5    | E 阶段 Master 指令的写回寄存器地址                  |
| E_slave_memtoReg   | I    | 1    | E 阶段 Slave 指令是否是访存写回寄存器（Load 指令）  |
| E_slave_reg_waddr  | I    | 5    | E 阶段 Slave 指令的写回寄存器地址                   |
| i_stall            | I    | 1    | 来自指令 Cache 或 FIFO 的暂停请求                   |
| E_alu_stall        | I    | 1    | 来自 ALU（乘除法等）的暂停请求                      |
| d_stall            | I    | 1    | 来自数据 Cache 或 MEM 阶段的暂停请求                |
| M_except           | I    | 1    | M 阶段是否发生异常或中断需要跳转 (来自 CSR)         |
| M_flush_all        | I    | 1    | M 阶段是否需要刷新整个流水线 (如 Cache fence)       |
| E_bj               | I    | 1    | E 阶段是否发生分支/跳转（预测错误或延迟跳转）       |
| D_bj               | I    | 1    | D 阶段是否发生分支/跳转（预测跳转或无冲突跳转）     |
| F_ena              | O    | 1    | F 阶段使能信号，控制 F 阶段是否运行                 |
| D_ena              | O    | 1    | D 阶段使能信号，控制 D 阶段是否运行                 |
| E_ena              | O    | 1    | E 阶段使能信号，控制 E 阶段是否运行                 |
| M_ena              | O    | 1    | M 阶段使能信号，控制 M 阶段是否运行                 |
| W_ena              | O    | 1    | W 阶段使能信号，控制 W 阶段是否运行                 |
| F_flush            | O    | 1    | F 阶段清空信号                                      |
| D_flush            | O    | 1    | D 阶段清空信号                                      |
| E_flush            | O    | 1    | E 阶段清空信号                                      |
| M_flush            | O    | 1    | M 阶段清空信号                                      |
| W_flush            | O    | 1    | W 阶段清空信号                                      |
| delay_slot_flush   | O    | 1    | 是否需要特殊处理刷新延迟槽指令                      |

### **5.7. CSR 单元模块 (CSR Unit)**

| 端口名称      | 方向   | 位宽 | 功能描述                |
| :------------ | :----- | :--- | :---------------------- |
| clk           | I      | 1    | 时钟信号                |
| resetn        | I      | 1    | 复位信号                |
| csr_addr      | I      | 12   | CSR寄存器地址输入       |
| csr_wdata     | I      | 32   | CSR写数据输入           |
| csr_openable  | I      | 1    | CSR操作使能信号         |
| csr_rdata     | O      | 32   | CSR读数据输出           |
| mtc0_wen      | input  | 1    | MTC0写使能信号          |
| mfc0_ren      | input  | 1    | MFC0读使能信号          |
| csr_sel       | input  | 3    | 寄存器选择域            |
| csr_rd        | input  | 5    | 寄存器编号              |
| f_adel        | input  | 1    | 取指地址错异常          |
| f_badvaddr    | input  | 32   | 取指阶段的错误PC地址    |
| d_ri          | input  | 1    | 保留指令异常            |
| d_syscall     | input  | 1    | 系统调用异常            |
| d_break       | input  | 1    | Break指令异常           |
| d_eret        | input  | 1    | ERET指令标识            |
| e_ov          | input  | 1    | 算术溢出异常            |
| e_tr          | input  | 1    | 自陷异常                |
| m_tlbl        | input  | 1    | TLB加载异常             |
| m_tlbs        | input  | 1    | TLB存储异常             |
| m_adel        | input  | 1    | 加载地址错异常          |
| m_ades        | input  | 1    | 存储地址错异常          |
| m_mod         | input  | 1    | TLB修改异常             |
| m_badvaddr    | input  | 32   | 访存阶段的错误地址      |
| hw_int        | input  | 6    | 硬件中断线输入          |
| timer_int     | input  | 1    | 计时器中断输入          |
| in_delay_slot | input  | 1    | 当前指令在延迟槽标识    |
| current_pc    | input  | 32   | 当前PC值（用于EPC记录） |
| has_exception | output | 1    | 异常发生标志            |
| ex_vector     | output | 32   | 异常向量地址            |
| csr_eret      | output | 1    | ERET执行标志            |
| entry_hi      | output | 32   | TLB查询/写入的高位地址  |
| entry_lo0     | output | 32   | TLB项低位0              |
| entry_lo1     | output | 32   | TLB项低位1              |
| tlbp_index    | input  | 32   | TLB探测结果索引         |

### **5.8. 流水线寄存器 (Pipeline Registers)**

流水线寄存器用于连接相邻的流水线阶段，传递指令和控制信息。它们是实现流水线的关键组件，需要包含时钟和复位信号，以及可能的清空逻辑（由 Flush 信号控制）。

**5.8.1. ID/EX 寄存器组 (ID_EX_Reg)**

| 端口名称                 | 方向 | 位宽       | 功能描述                                                     |
| ------------------------ | ---- | ---------- | ------------------------------------------------------------ |
| clk                      | I    | 1          | 时钟信号                                                     |
| rst                      | I    | 1          | 复位信号                                                     |
| clear1                   | I    | 1          | Master 路径寄存器清空信号 (来自 M 阶段异常/刷新或 E 阶段非延迟槽刷新) |
| clear2                   | I    | 1          | Slave 路径寄存器清空信号 (来自 M 阶段异常/刷新或 E 阶段刷新) |
| ena1                     | I    | 1          | Master 路径寄存器使能 (E_ena)                                |
| ena2                     | I    | 1          | Slave 路径寄存器使能 (D_slave_ena)                           |
| D_master_ctrl_sign       | I    | ctrl_sign  | D 阶段 Master 路径控制信号                                   |
| D_master_except          | I    | except_bus | D 阶段 Master 路径异常信号                                   |
| D_master_cop0_info       | I    | cop0_info  | D 阶段 Master 路径 CSR 操作信息                              |
| D_master_is_link_pc8     | I    | 1          | D 阶段 Master 指令是否需要链接 PC+8                          |
| D_master_is_in_delayslot | I    | 1          | D 阶段 Master 指令是否在延迟槽                               |
| D_master_is_branch       | I    | 1          | D 阶段 Master 指令是否是分支指令                             |
| D_master_pred_take       | I    | 1          | D 阶段 Master 分支预测是否跳转                               |
| D_master_jump_conflict   | I    | 1          | D 阶段 Master 跳转指令是否有冲突                             |
| D_master_branch_type     | I    | 4          | D 阶段 Master 分支类型                                       |
| D_master_trap_type       | I    | 4          | D 阶段 Master 自陷类型                                       |
| D_master_rs              | I    | 5          | D 阶段 Master rs 地址                                        |
| D_master_rt              | I    | 5          | D 阶段 Master rt 地址                                        |
| D_master_reg_waddr       | I    | 5          | D 阶段 Master 写寄存器地址                                   |
| D_master_op              | I    | 6          | D 阶段 Master opcode                                         |
| D_master_cmov_type       | I    | CmovBus    | D 阶段 Master 条件传送类型                                   |
| D_master_pc              | I    | 32         | D 阶段 Master PC 值                                          |
| D_master_inst            | I    | 32         | D 阶段 Master 指令                                           |
| D_master_rs_value        | I    | 32         | D 阶段 Master 前推后的 rs 值                                 |
| D_master_rt_value        | I    | 32         | D 阶段 Master 前推后的 rt 值                                 |
| D_master_imm_value       | I    | 32         | D 阶段 Master 立即数值                                       |
| D_master_shamt_value     | I    | 32         | D 阶段 Master 移位量值                                       |
| D_master_pc_plus4        | I    | 32         | D 阶段 Master PC+4 值                                        |
| D_master_branch_target   | I    | 32         | D 阶段 Master 分支目标地址                                   |
| D_slave_ctrl_sign        | I    | ctrl_sign  | D 阶段 Slave 路径控制信号                                    |
| D_slave_except           | I    | except_bus | D 阶段 Slave 路径异常信号                                    |
| D_slave_cop0_info        | I    | cop0_info  | D 阶段 Slave 路径 CSR 操作信息                               |
| D_slave_is_in_delayslot  | I    | 1          | D 阶段 Slave 指令是否在延迟槽                                |
| D_slave_rs               | I    | 5          | D 阶段 Slave rs 地址                                         |
| D_slave_rt               | I    | 5          | D 阶段 Slave rt 地址                                         |
| D_slave_reg_waddr        | I    | 5          | D 阶段 Slave 写寄存器地址                                    |
| D_slave_op               | I    | 6          | D 阶段 Slave opcode                                          |
| D_slave_cmov_type        | I    | CmovBus    | D 阶段 Slave 条件传送类型                                    |
| D_slave_pc               | I    | 32         | D 阶段 Slave PC 值                                           |
| D_slave_inst             | I    | 32         | D 阶段 Slave 指令                                            |
| D_slave_rs_value         | I    | 32         | D 阶段 Slave 前推后的 rs 值                                  |
| D_slave_rt_value         | I    | 32         | D 阶段 Slave 前推后的 rt 值                                  |
| D_slave_imm_value        | I    | 32         | D 阶段 Slave 立即数值                                        |
| D_slave_shamt_value      | I    | 32         | D 阶段 Slave 移位量值                                        |
| E_master_ctrl_sign       | O    | ctrl_sign  | E 阶段 Master 路径控制信号                                   |
| E_master_except          | O    | except_bus | E 阶段 Master 路径异常信号 (临时，未合并 ALU/MEM 异常)       |
| E_master_cop0_info       | O    | cop0_info  | E 阶段 Master 路径 CSR 操作信息                              |
| E_master_is_link_pc8     | O    | 1          | E 阶段 Master 指令是否需要链接 PC+8                          |
| E_master_is_in_delayslot | O    | 1          | E 阶段 Master 指令是否在延迟槽                               |
| E_master_is_branch       | O    | 1          | E 阶段 Master 指令是否是分支指令                             |
| E_master_pred_take       | O    | 1          | E 阶段 Master 分支预测是否跳转                               |
| E_master_jump_conflict   | O    | 1          | E 阶段 Master 跳转指令是否有冲突 (临时)                      |
| E_master_branch_type     | O    | 4          | E 阶段 Master 分支类型                                       |
| E_master_trap_type       | O    | 4          | E 阶段 Master 自陷类型                                       |
| E_master_rs              | O    | 5          | E 阶段 Master rs 地址                                        |
| E_master_rt              | O    | 5          | E 阶段 Master rt 地址                                        |
| E_master_reg_waddr       | O    | 5          | E 阶段 Master 写寄存器地址                                   |
| E_master_op              | O    | 6          | E 阶段 Master opcode                                         |
| E_master_cmov_type       | O    | CmovBus    | E 阶段 Master 条件传送类型                                   |
| E_master_pc              | O    | 32         | E 阶段 Master PC 值                                          |
| E_master_inst            | O    | 32         | E 阶段 Master 指令                                           |
| E_master_rs_value        | O    | 32         | E 阶段 Master rs 值                                          |
| E_master_rt_value        | O    | 32         | E 阶段 Master rt 值                                          |
| E_master_imm_value       | O    | 32         | E 阶段 Master 立即数值                                       |
| E_master_shamt_value     | O    | 32         | E 阶段 Master 移位量值                                       |
| E_master_pc_plus4        | O    | 32         | E 阶段 Master PC+4 值                                        |
| E_master_branch_target   | O    | 32         | E 阶段 Master 分支目标地址                                   |
| E_slave_ctrl_sign        | O    | ctrl_sign  | E 阶段 Slave 路径控制信号                                    |
| E_slave_except           | O    | except_bus | E 阶段 Slave 路径异常信号 (临时)                             |
| E_slave_cop0_info        | O    | cop0_info  | E 阶段 Slave 路径 CSR 操作信息                               |
| E_slave_is_in_delayslot  | O    | 1          | E 阶段 Slave 指令是否在延迟槽                                |
| E_slave_rs               | O    | 5          | E 阶段 Slave rs 地址                                         |
| E_slave_rt               | O    | 5          | E 阶段 Slave rt 地址                                         |
| E_slave_reg_waddr        | O    | 5          | E 阶段 Slave 写寄存器地址                                    |
| E_slave_op               | O    | 6          | E 阶段 Slave opcode                                          |
| E_slave_cmov_type        | O    | CmovBus    | E 阶段 Slave 条件传送类型                                    |
| E_slave_pc               | O    | 32         | E 阶段 Slave PC 值                                           |
| E_slave_inst             | O    | 32         | E 阶段 Slave 指令                                            |
| E_slave_rs_value         | O    | 32         | E 阶段 Slave rs 值                                           |
| E_slave_rt_value         | O    | 32         | E 阶段 Slave rt 值                                           |
| E_slave_imm_value        | O    | 32         | E 阶段 Slave 立即数值                                        |
| E_slave_shamt_value      | O    | 32         | E 阶段 Slave 移位量值                                        |

**5.8.2. EX/MEM 寄存器组 (EX_MEM_Reg)**

| 端口名称                  | 方向 | 位宽       | 功能描述                                             |
| ------------------------- | ---- | ---------- | ---------------------------------------------------- |
| clk                       | I    | 1          | 时钟信号                                             |
| rst                       | I    | 1          | 复位信号                                             |
| clear1                    | I    | 1          | Master 路径寄存器清空信号 (M_flush)                  |
| clear2                    | I    | 1          | Slave 路径寄存器清空信号 (M_flush)                   |
| ena1                      | I    | 1          | Master 路径寄存器使能 (M_ena)                        |
| ena2                      | I    | 1          | Slave 路径寄存器使能 (M_ena)                         |
| E_mem_en                  | I    | 1          | E 阶段是否有访存操作                                 |
| E_mem_ren                 | I    | 1          | E 阶段是否读内存                                     |
| E_mem_wen                 | I    | 1          | E 阶段是否写内存                                     |
| E_mem_op                  | I    | 6          | E 阶段访存操作类型                                   |
| E_mem_addr                | I    | 32         | E 阶段访存物理地址 (?) 或虚拟地址                    |
| E_mem_wdata               | I    | 32         | E 阶段写内存数据                                     |
| E_mem_va                  | I    | 32         | E 阶段访存虚拟地址                                   |
| M_mem_en                  | O    | 1          | M 阶段是否有访存操作                                 |
| M_mem_ren                 | O    | 1          | M 阶段是否读内存                                     |
| M_mem_wen                 | O    | 1          | M 阶段是否写内存                                     |
| M_mem_op                  | O    | 6          | M 阶段访存操作类型                                   |
| M_mem_addr                | O    | 32         | M 阶段访存物理地址 (?) 或虚拟地址                    |
| M_mem_wdata               | O    | 32         | M 阶段写内存数据                                     |
| M_mem_va                  | O    | 32         | M 阶段访存虚拟地址                                   |
| E_master_ctrl_sign        | I    | ctrl_sign  | E 阶段 Master 路径控制信号                           |
| E_master_except           | I    | except_bus | E 阶段 Master 路径最终异常信号 (合并了 ALU/MEM 异常) |
| E_master_reg_wen          | I    | 1          | E 阶段 Master 指令是否写寄存器                       |
| E_master_mem_sel          | I    | 1          | E 阶段是否选中 Master 访存                           |
| E_master_is_in_delayslot  | I    | 1          | E 阶段 Master 指令是否在延迟槽                       |
| E_master_reg_waddr        | I    | 5          | E 阶段 Master 写寄存器地址                           |
| E_master_inst             | I    | 32         | E 阶段 Master 指令                                   |
| E_master_alu_res          | I    | 32         | E 阶段 Master ALU 结果                               |
| E_master_pc               | I    | 32         | E 阶段 Master PC 值                                  |
| E_slave_ctrl_sign         | I    | ctrl_sign  | E 阶段 Slave 路径控制信号                            |
| E_slave_except            | I    | except_bus | E 阶段 Slave 路径最终异常信号                        |
| E_slave_reg_wen           | I    | 1          | E 阶段 Slave 指令是否写寄存器                        |
| E_slave_mem_sel           | I    | 1          | E 阶段是否选中 Slave 访存                            |
| E_slave_is_in_delayslot   | I    | 1          | E 阶段 Slave 指令是否在延迟槽                        |
| E_slave_reg_waddr         | I    | 5          | E 阶段 Slave 写寄存器地址                            |
| E_slave_pc                | I    | 32         | E 阶段 Slave PC 值                                   |
| E_slave_inst              | I    | 32         | E 阶段 Slave 指令                                    |
| E_slave_alu_res           | I    | 32         | E 阶段 Slave ALU 结果                                |
| M_master_ctrl_sign        | O    | ctrl_sign  | M 阶段 Master 路径控制信号                           |
| M_master_except           | O    | except_bus | M 阶段 Master 路径异常信号 (临时，未合并 TLB 异常)   |
| M_master_reg_wen          | O    | 1          | M 阶段 Master 指令是否写寄存器                       |
| M_master_mem_sel          | O    | 1          | M 阶段是否选中 Master 访存                           |
| M_master_is_in_delayslot  | O    | 1          | M 阶段 Master 指令是否在延迟槽                       |
| M_master_reg_waddr        | O    | 5          | M 阶段 Master 写寄存器地址                           |
| M_master_inst             | O    | 32         | M 阶段 Master 指令                                   |
| M_master_alu_res          | O    | 32         | M 阶段 Master ALU 结果                               |
| M_master_pc               | O    | 32         | M 阶段 Master PC 值                                  |
| M_slave_ctrl_sign         | O    | ctrl_sign  | M 阶段 Slave 路径控制信号                            |
| M_slave_except            | O    | except_bus | M 阶段 Slave 路径异常信号 (临时)                     |
| M_slave_reg_wen           | O    | 1          | M 阶段 Slave 指令是否写寄存器                        |
| M_slave_mem_sel           | O    | 1          | M 阶段是否选中 Slave 访存                            |
| M_slave_is_in_delayslot   | O    | 1          | M 阶段 Slave 指令是否在延迟槽                        |
| M_slave_reg_waddr         | O    | 5          | M 阶段 Slave 写寄存器地址                            |
| M_slave_pc                | O    | 32         | M 阶段 Slave PC 值                                   |
| M_slave_inst              | O    | 32         | M 阶段 Slave 指令                                    |
| M_slave_alu_res           | O    | 32         | M 阶段 Slave ALU 结果                                |
| E_master_debug_CSR_count  | I    | 32         | E 阶段 Master CSR Count 调试值                       |
| E_master_debug_CSR_random | I    | 32         | E 阶段 Master CSR Random 调试值                      |
| E_master_debug_CSR_cause  | I    | 32         | E 阶段 Master CSR Cause 调试值                       |
| M_master_debug_CSR_count  | O    | 32         | M 阶段 Master CSR Count 调试值                       |
| M_master_debug_CSR_random | O    | 32         | M 阶段 Master CSR Random 调试值                      |
| M_master_debug_CSR_cause  | O    | 32         | M 阶段 Master CSR Cause 调试值                       |

**5.8.3. MEM/WB 寄存器组 (MEM_WB_Reg)**

| 端口名称                  | 方向 | 位宽       | 功能描述                                         |
| ------------------------- | ---- | ---------- | ------------------------------------------------ |
| clk                       | I    | 1          | 时钟信号                                         |
| rst                       | I    | 1          | 复位信号                                         |
| clear1                    | I    | 1          | Master 路径寄存器清空信号 (W_flush)              |
| clear2                    | I    | 1          | Slave 路径寄存器清空信号 (W_flush)               |
| ena1                      | I    | 1          | Master 路径寄存器使能 (W_ena)                    |
| ena2                      | I    | 1          | Slave 路径寄存器使能 (W_ena)                     |
| M_master_except           | I    | except_bus | M 阶段 Master 路径异常信号                       |
| M_master_reg_wen          | I    | 1          | M 阶段 Master 指令是否写寄存器                   |
| M_master_reg_waddr        | I    | 5          | M 阶段 Master 写寄存器地址                       |
| M_master_inst             | I    | 32         | M 阶段 Master 指令                               |
| M_master_pc               | I    | 32         | M 阶段 Master PC 值                              |
| M_master_reg_wdata        | I    | 32         | M 阶段 Master 写寄存器数据 (访存结果或 ALU 结果) |
| M_slave_except            | I    | except_bus | M 阶段 Slave 路径异常信号                        |
| M_slave_reg_wen           | I    | 1          | M 阶段 Slave 指令是否写寄存器                    |
| M_slave_reg_waddr         | I    | 5          | M 阶段 Slave 写寄存器地址                        |
| M_slave_inst              | I    | 32         | M 阶段 Slave 指令                                |
| M_slave_pc                | I    | 32         | M 阶段 Slave PC 值                               |
| M_slave_reg_wdata         | I    | 32         | M 阶段 Slave 写寄存器数据 (访存结果或 ALU 结果)  |
| W_master_except           | O    | except_bus | W 阶段 Master 路径异常信号                       |
| W_master_reg_wen          | O    | 1          | W 阶段 Master 指令是否写寄存器                   |
| W_master_reg_waddr        | O    | 5          | W 阶段 Master 写寄存器地址                       |
| W_master_inst             | O    | 32         | W 阶段 Master 指令                               |
| W_master_pc               | O    | 32         | W 阶段 Master PC 值                              |
| W_master_reg_wdata        | O    | 32         | W 阶段 Master 写寄存器数据                       |
| W_slave_except            | O    | except_bus | W 阶段 Slave 路径异常信号                        |
| W_slave_reg_wen           | O    | 1          | W 阶段 Slave 指令是否写寄存器                    |
| W_slave_reg_waddr         | O    | 5          | W 阶段 Slave 写寄存器地址                        |
| W_slave_inst              | O    | 32         | W 阶段 Slave 指令                                |
| W_slave_pc                | O    | 32         | W 阶段 Slave PC 值                               |
| W_slave_reg_wdata         | O    | 32         | W 阶段 Slave 写寄存器数据                        |
| M_master_debug_CSR_count  | I    | 32         | M 阶段 Master CSR Count 调试值                   |
| M_master_debug_CSR_random | I    | 32         | M 阶段 Master CSR Random 调试值                  |
| M_master_debug_CSR_cause  | I    | 32         | M 阶段 Master CSR Cause 调试值                   |
| M_master_debug_int        | I    | 1          | M 阶段 Master 是否发生中断异常                   |
| W_master_debug_CSR_count  | O    | 32         | W 阶段 Master CSR Count 调试值                   |
| W_master_debug_CSR_random | O    | 32         | W 阶段 Master CSR Random 调试值                  |
| W_master_debug_CSR_cause  | O    | 32         | W 阶段 Master CSR Cause 调试值                   |
| W_master_debug_int        | O    | 1          | W 阶段 Master 是否发生中断异常                   |

## **6. 模块内部功能描述**

### **IF部分**

一、双发射基础架构

1、双端口指令Cache：数据行为64bit（2条32位指令），支持同时获取两条指令

2、指令FIFO：作为取指与后续阶段的缓冲区，深度为16条目

3、双读双写机制：支持同时写入/读取两条指令

 

二、关键组件

1、pc_reg

根据双发射情况动态计算下一条PC值：

双指令就绪(F_inst_data_ok1 & F_inst_data_ok2)：PC+8

单指令就绪(F_inst_data_ok1)：PC+4

 

2、指令Cache

64bit数据行：对应两条32位指令

双返回信号：

inst_data_ok1：第一条指令有效

inst_data_ok2：第二条指令有效

边界处理：当PC指向Cache行末尾时，只返回1条有效指令

 

3、指令FIFO

双写入机制：

 if(write_en1) lines[write_pointer] <= write_line1;

 if(write_en2) lines[write_pointer+1] <= write_line2;

双读取机制：

 read_line1 = lines[read_pointer];

 read_line2 = lines[read_pointer + 1];

状态管理：

full：阻止继续取指

empty：阻止后续发射

almost_empty：只有1条指令时可发射master

 

三、双发射控制逻辑

1、正常双发射条件：

FIFO中有≥2条指令(data_count ≥ 2)

无结构冲突/数据冲突

无异常/分支跳转

 

2、发射判断逻辑

在issueCtrl模块中实现

if(almost_empty) begin

  // 只能发射master

  read_en1 = 1; read_en2 = 0;

end else begin

  // 可双发射

  read_en1 = 1; read_en2 = 1;

end

 

3、延迟槽特殊处理

当检测到分支指令时：

 当前发射的第一条指令作为延迟槽指令

 设置master_is_in_delayslot_o标志

 通过delayslot_line缓存延迟槽指令

 

4、异常处理机制

优先级设计：

  复位 > 异常 > 冲刷 > 分支跳转 > 正常取指

  if (rst) pc_next = 32'hbfc00000;

  else if (M_except) pc_next = M_except_addr;

  else if (M_flush_all) pc_next = M_flush_all_addr;

  else if (E_bj) pc_next = E_bj_target;

  ...

精确异常：

  异常发生时记录准确PC

  通过EPC寄存器保存异常现场



CPU每个时钟周期：

1、从指令Cache获取最多两条指令

2、将指令存入FIFO缓冲区

3、根据流水线状态决定发射1条或2条指令

4、正确处理各种异常和分支情况



### **ID部分**

***\*Issue_ctrl模块:\****

这是一个双发射处理器中的发射控制模块，用于判断是否可以同时发射两条指令。

***\*主要负责\****：

· 检测各类冲突

· 控制指令发射

· 管理分支预测

· 确保指令执行的正确性

 

***\*输入信号分类\****

\- 基本控制信号：

\- 结构冲突相关

\- 数据相关（LOAD相关）

\- WAR相关

 

***\*冲突检测逻辑\****

\- FIFO状态检测：当FIFO为空或者即将为空时，禁止双发射（由IF阶段同学完成判断）

\- 结构冲突检测：

l 检测内存访问冲突

lw  $t0, 0($t1)    # 主指令（访存）

sw  $t2, 4($t3)    # 从指令（访存）

l 检测乘除法器冲突

mult $t0, $t1     # 主指令（使用乘法器）

div  $t2, $t3     # 从指令（使用除法器）

乘除法器资源冲突，不能同时发射

\- LOAD相关检测：

l 检测从指令是否依赖于前面LOAD指令的结果

lw  $t0, 0($t1)    # 主指令（Load）

add $t2, $t0, $t3   # 从指令（使用Load的结果）

\- RAW冲突检测：

l 寄存器RAW

add $t0, $t1, $t2   # 主指令

sub $t3, $t0, $t4   # 从指令（使用了主指令的结果）

l HILO寄存器RAW（待定）

mult $t0, $t1     # 主指令（写HILO）

mfhi $t2       # 从指令（读HI）

从指令需要等待HILO写入完成，不能同时发射

***\*特殊情况处理：\****

\- 分支指令处理

主指令、从指令是否分支/跳转

\- CSR指令处理（单发射）

主从指令是否为CSR指令

\- FENCE指令处理（单发射）

主从指令是否是FENCE指令

***\*最终的发射逻辑\****

// 发射控制逻辑

val D_slave_ena = !(

  !D_master_valid |     // 主指令无效

  fifo_disable |       // FIFO状态限制

  struct_conflict |     // 结构冲突

  load_stall |        // Load相关

  war_reg |         // 寄存器WAR

  branch_limit |       // 分支/跳转限制

  csr_limit |        // CSR限制

  fence_limit        // FENCE限制

);

 

需要特殊考虑的地方：

（1）：X0寄存器始终为0

（2）：立即数不需要读RS2

（3）：后续加入32M指令集需要拓展部分端口（还没协商好）

 

### **EX部分**

4 FU；基础ALU、读写的FU、FPU、机动的一个FU（用于其他指令或者增加并行）。正常ALU和接口已完成，FPU已设计完成了浮点的乘法、累加、转换



### **MEM部分**

**MEM****模块设计思路整理**

**一、模块职责**

MEM模块主要实现访存控制（包括异常处理的模块，可以包括访存仲裁模块?），与写回数据的选择。

我的设计中，考虑分为如下模块：master与slave的仲裁处理(待定)，异常检测与处理（主要是地址对齐

检查），指令数据的处理（分为load与非load模块），转发与旁路，流水线控制（阻塞与冲刷信号）。

**二、关键子模块设计**

**1.** **访存仲裁单元**

• 功能：处理双发射路径（Master/Slave）的访存冲突。

• 处理方式：

若Master和Slave同时请求访存（如均需使用Cache），优先处理Master请求，阻塞Slave。(一般情况下

不会发生，发生时进行处理)

**2.** **地址对齐检查（重要的异常发生类型）**

• 逻辑：根据操作类型（ Load / Store ）检查地址是否对齐。

• Load 指令：地址需对齐访问粒度（如LW需4字节对齐）。

• Store 指令：地址需对齐且可写权限（如SW需4字节对齐）。

• 异常触发：若未对齐，生成Load或Store异常，在 MEM 异常处理模块进一步处理。

**3.** **异常处理**

• 信号主要包括： 异常发生信号 ， 异常类型信号 （必要时可包括 异常地址信号 ）输出。

• 逻辑：阻止当前访存操作，将访存使能信号置0，输出异常处理信号（为 O 信号，交给异常处理模

块）。

**4.** **指令数据的处理**

• 信号主要包括： 数据 ， 地址 ， 写使能 输出

• 逻辑：根据操作类型进行处理（ Load / 非Load ）• Load 指令：来自存储的数据

• 非Load 指令：来自 EX 的计算结果，透传至下一阶段



### **BP部分**

1.分支预测

PC 先输入未经预测的当前 PC 值（（由于双发射所以是 PC 和 PC+4），由本模块预测后得到这

两条 pc 是否跳转，以及如果跳转，跳转目标 pc 是多少。其中 BP_Jump_Enable_Out_O 为 2

位，00 说明两个 pc 都预测 NT，10 说明 pc 预测 T，pc+4 预测 NT，01 同理，11 冗余。

2.预测学习

本模块需要来自 EX 模块（若分支预测的运算由其他模块实现，则来自相应模块）的输出结

果，来写入本模块内部的寄存器作为分支预测的依据



![image-20250424115941404](C:\Users\music\AppData\Roaming\Typora\typora-user-images\image-20250424115941404.png)

## **7. 小组分工** 

1.     取指IF 曹嫣然 

2.     译码ID  庄心妍

3.     执行EX 何凡

4.     访存MEM 刘延昌

5.     级间寄存器  程仁龙

6.     寄存器堆RF  张鑫宏

7.     CU模块 蔡心钰

8.     分支预测  姜佳宇

9.     CSR及异常处理 白佳瑶

10. 整体架构设计以及spec文档撰写 乔际帆
