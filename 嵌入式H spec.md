# **RISC-V 五级流水顺序双发射处理器 (Basic Functionality Spec)**

[TOC]

## **1. 引言**

本文档旨在详细描述一款五级流水线、顺序双发射的 RISC-V 处理器。该处理器将实现 RV32I 基本指令集，并满足基本功能要求。

## **2. 设计目标**

*   五级流水线
*   顺序双发 
*   包含RV32I的基本指令集，包含逻辑、移位、算数、分支、访存、特权、自陷
*   实现csrrw指令和mtime、mcycle这两个csr
*   预计实现RV32M
*   实现TAGE分支预测器
*   实现不定周期的访存级，即访存级的延时不被固定为一个周期
*   在实现不定周期访存级的基础上，实现一个简易的dcache

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

该处理器参考CDIM处理器架构(https://github.com/Maxpicca-Li/CDIM/tree/master)，设计了一款双发射五级顺序流水线 CPU，其支持指令 FIFO、分支预测、指令缓存和数据缓存。其中，五级顺序流水线由取指（Instruction Fetch）、译码（Instruction Decode）、执行（Excute）、访存（Memory access），写回（Write Back）五个阶段组成；双发射采用对称双发逻辑以充分保证双发率；指令FIFO 可以隔离取指阶段和后续阶段，以实现高效取指的作用；分支预测采用TAGE分支预测器来提高预测的准确率。处理器的数据通路示意图如下所示，其中红线部分为 MasterPath，蓝线部分为 Slave Path。

### **4.1. 双发策略**

在双发射的 CPU 设计中，双发策略会影响整个数据通路的设计，我们下面介绍我们处理器的双发策略，在issuectrl的代码部分解释了整个处理器的双发策略。

**1．only＿Master 类指令：**即只能放在 Master 发射，且 Slave 不发射指令。对于可能刷新整个流水线的指令（如异常处理有关的指令），如果在 Slave 中检测到这类指令，则暂停 Slave 发射，将其延迟到下一周期的 Master 发射；如果在 Master 中检测到这类指令，也会暂停 Slave 发射，避免不必要的流水线刷新。

**2．only＿in＿Master 类指令：**即只能放在 Master 发射，但Slave可发射指令。对于跳转指令，在一定条件下会跳转到其他地址，并刷新整个流水线。如果在 Slave 中检测到这类指令，则暂停 Slave 发射，将其延迟到下一周期的 Master 发射，同时根据分支预测的结果来判断指令FIFO是否需要清空。同时我们的MEM是单端入口，因此我们也将访存指令也设计成为了这类指令。

### **4.2. 冲突处理**

**2.4.1 数据冲突**
－RAW 冲突：当 Master 或 Slave 需要读取的数据是已经发射且进入后续流水阶段的指令，会产生 RAW 冲突。本设计将需要的数据均前推到 D 阶段，前推后立即进入触发器后再进入 E阶段使用，这样可以避免执行数据的路径过长。其中，若需要读取的数据是当前 E 阶段的访存数据，则需要进行阻塞以等待访存数据在 M 阶段返回。若需要读取的数据是当前 E 阶段的计算结果，或 M 阶段的计算结果，或 M 阶段的访存结果，则通过数据前推模块前推到 D 阶段。
－WAW 冲突：当同周期的 Master 和 Slave 写入寄存器的地址一致时，会产生 WAW 冲突。因为 Slave 是 Master后一条指令，故 RegFile 更新时，会优先写入 Slave 的结果。
－WAR 冲突：由于本设计为顺序流水线，故不存在 WAR 冲突。

**2.4.2 控制冲突**
当遇到跳转指令时，会产生控制冲突。在我们的设计中，为解决控制冲突，我们采用了动态分支预测技术。当预测到将要发生跳转时，我们会立即清空指令FIFO（先进先出队列），并从新的预测地址重新开始取指过程。如果在执行阶段发现预测错误，就向CU控制模块发送请求冲刷流水线的信号进行流水线的冲刷。

（架构的细节在，如还有Load-use冲突在第6部分会详细阐述）

### **4.3. 结构框图**

![image-20250525095508253](C:\Users\music\AppData\Roaming\Typora\typora-user-images\image-20250525095508253.png)

红线部分为Master Path，蓝线部分为Slave Path

## **5. 模块端口定义及功能描述 **

以下列出各主要模块的端口定义及功能描述。方向 `I` 表示输入， `O` 表示输出。

### **5.1. 指令取指模块 (IF - Instruction Fetch)**

#### **pc_reg**

#### **IFIFO**

| 端口名称            | 方向 | 位宽       | 功能描述                                          |
| ------------------- | ---- | ---------- | ------------------------------------------------- |
| **输入信号**        |      |            |                                                   |
| `io.fifo_rst`       | I    | AsyncReset | FIFO 专用异步复位信号                             |
| `io.i_stall`        | I    | Bool       | 流水线暂停信号 (为高时冻结FIFO读指针和计数器减量) |
| `io.read_en1`       | I    | Bool       | 读取端口1使能信号(置1)                            |
| `io.read_en2`       | I    | Bool       | 读取端口2使能信号                                 |
| `io.write_address1` | I    | 32.W       | 写入端口1的指令地址 (PC)                          |
| `io.write_address2` | I    | 32.W       | 写入端口2的指令地址 (PC)                          |
| `io.write_data1`    | I    | 32.W       | 写入端口1的指令数据                               |
| `io.write_data2`    | I    | 32.W       | 写入端口2的指令数据                               |
| **输出信号**        |      |            |                                                   |
| `io.read_data1`     | O    | 32.W       | 读取端口1的指令数据                               |
| `io.read_data2`     | O    | 32.W       | 读取端口2的指令数据                               |
| `io.read_address1`  | O    | 32.W       | 读取端口1的指令地址 (PC)                          |
| `io.read_address2`  | O    | 32.W       | 读取端口2的指令地址 (PC)                          |
| `io.full`           | O    | Bool       | FIFO 满状态信号                                   |
| almost_full         | O    | Bool       | FIFO几乎满(只可读取一条指令)信号                  |

### **5.2. 指令译码模块 (ID - Instruction Decode)**

#### **DualIssueDecode 模块**

| 端口名称                  | 方向 | 位宽 | 功能描述                                                     |
| ------------------------- | ---- | ---- | ------------------------------------------------------------ |
| **输入信号 (Inputs)**     |      |      |                                                              |
| `io.inst1`                | I    | 32.W | 第一条待译码的指令                                           |
| `io.inst2`                | I    | 32.W | 第二条待译码的指令                                           |
| `io.pc1`                  | I    | 32.W | 第一条指令的程序计数器 (PC) 值                               |
| `io.pc2`                  | I    | 32.W | 第二条指令的程序计数器 (PC) 值                               |
| `io.reg1_rs1_data`        | I    | 32.W | 第一条指令的源寄存器1 (rs1) 的数据 (来自寄存器堆)            |
| `io.reg1_rs2_data`        | I    | 32.W | 第一条指令的源寄存器2 (rs2) 的数据 (来自寄存器堆)            |
| `io.reg2_rs1_data`        | I    | 32.W | 第二条指令的源寄存器1 (rs1) 的数据 (来自寄存器堆)            |
| `io.reg2_rs2_data`        | I    | 32.W | 第二条指令的源寄存器2 (rs2) 的数据 (来自寄存器堆)            |
| `io.fifo_empty`           | I    | Bool | 指令FIFO空状态信号(置0)                                      |
| `io.fifo_almost_empty`    | I    | Bool | 指令FIFO几乎为空状态信号(置0)                                |
| **输出信号 (Outputs)**    |      |      |                                                              |
| `io.decode1.inst_type`    | O    | 4.W  | 指令1: 译码后的指令类型                                      |
| `io.decode1.rs1`          | O    | 5.W  | 指令1: 源寄存器1地址                                         |
| `io.decode1.rs2`          | O    | 5.W  | 指令1: 源寄存器2地址                                         |
| `io.decode1.ex_rs1_data`  | O    | 32.W | 指令1: 传往执行阶段的源寄存器1数据 (可能经过前馈选择)        |
| `io.decode1.ex_rs2_data`  | O    | 32.W | 指令1: 传往执行阶段的源寄存器2数据 (可能经过前馈选择)        |
| `io.decode1.rd`           | O    | 5.W  | 指令1: 目标寄存器地址                                        |
| `io.decode1.imm`          | O    | 32.W | 指令1: 立即数                                                |
| `io.decode1.alu_op`       | O    | 5.W  | 指令1: ALU操作类型                                           |
| `io.decode1.alu_op1`      | O    | 2.W  | 指令1: ALU操作数1来源                                        |
| `io.decode1.alu_op2`      | O    | 2.W  | 指令1: ALU操作数2来源                                        |
| `io.decode1.reg_write`    | O    | Bool | 指令1: 是否写回寄存器                                        |
| `io.decode1.mem_read`     | O    | Bool | 指令1: 是否执行内存读取                                      |
| `io.decode1.mem_write`    | O    | Bool | 指令1: 是否执行内存写入                                      |
| `io.decode1.mem_type`     | O    | 3.W  | 指令1: 内存访问宽度类型                                      |
| `io.decode1.mem_unsigned` | O    | Bool | 指令1: 内存加载是否为无符号扩展                              |
| `io.decode1.exception`    | O    | Bool | 指令1: 译码阶段检测到的异常信号                              |
| `io.decode1.pc_out`       | O    | 32.W | 指令1: PC值输出 (传往后续阶段)                               |
| `io.decode2.inst_type`    | O    | 4.W  | 指令2: 译码后的指令类型                                      |
| `io.decode2.rs1`          | O    | 5.W  | 指令2: 源寄存器1地址                                         |
| `io.decode2.rs2`          | O    | 5.W  | 指令2: 源寄存器2地址                                         |
| `io.decode2.ex_rs1_data`  | O    | 32.W | 指令2: 传往执行阶段的源寄存器1数据 (可能经过前馈选择)        |
| `io.decode2.ex_rs2_data`  | O    | 32.W | 指令2: 传往执行阶段的源寄存器2数据 (可能经过前馈选择)        |
| `io.decode2.rd`           | O    | 5.W  | 指令2: 目标寄存器地址                                        |
| `io.decode2.imm`          | O    | 32.W | 指令2: 立即数                                                |
| `io.decode2.alu_op`       | O    | 5.W  | 指令2: ALU操作类型                                           |
| `io.decode2.alu_op1`      | O    | 2.W  | 指令2: ALU操作数1来源                                        |
| `io.decode2.alu_op2`      | O    | 2.W  | 指令2: ALU操作数2来源                                        |
| `io.decode2.reg_write`    | O    | Bool | 指令2: 是否写回寄存器                                        |
| `io.decode2.mem_read`     | O    | Bool | 指令2: 是否执行内存读取                                      |
| `io.decode2.mem_write`    | O    | Bool | 指令2: 是否执行内存写入                                      |
| `io.decode2.mem_type`     | O    | 3.W  | 指令2: 内存访问宽度类型                                      |
| `io.decode2.mem_unsigned` | O    | Bool | 指令2: 内存加载是否为无符号扩展                              |
| `io.decode2.exception`    | O    | Bool | 指令2: 译码阶段检测到的异常信号                              |
| `io.decode2.pc_out`       | O    | 32.W | 指令2: PC值输出 (传往后续阶段)                               |
| `io.slave_ena`            | O    | Bool | 从指令 (第二条指令) 是否有效并被发射                         |
| `io.fifo_push`            | O    | Bool | 是否需要将指令推回指令FIFO (例如因相关性或资源冲突)          |
| `io.fifo_data`            | O    | 32.W | 若 `fifo_push` 有效，此为需要推回FIFO的指令数据 (通常为从指令) |

#### **IssueCtrl 模块端口定义**

| 端口名称                     | 方向 | 位宽 | 功能描述                                                     |
| ---------------------------- | ---- | ---- | ------------------------------------------------------------ |
| **输入信号 (Inputs)**        |      |      |                                                              |
| `io.D_master_ena`            | I    | Bool | 主指令有效使能信号(置1)                                      |
| `io.D_master_reg_wen`        | I    | Bool | 主指令写回寄存器使能信号                                     |
| `io.D_master_reg_waddr`      | I    | 5.W  | 主指令写回寄存器的目标地址                                   |
| `io.D_master_memtoReg`       | I    | Bool | 主指令是否为 Load 类型指令 (数据从内存到寄存器)              |
| `io.D_master_mem_write`      | I    | Bool | 主指令是否为 Store 类型指令 (数据从寄存器到内存)             |
| `io.D_master_is_branch`      | I    | Bool | 主指令是否为分支指令                                         |
| `io.D_master_is_ja`          | I    | Bool | 主指令是否为跳转指令 (JAL, JALR)                             |
| `io.D_master_is_csr`         | I    | Bool | 主指令是否为 CSR (控制状态寄存器) 指令                       |
| `io.D_slave_read_rs1`        | I    | Bool | 从指令是否读取源寄存器 rs1                                   |
| `io.D_slave_read_rs2`        | I    | Bool | 从指令是否读取源寄存器 rs2                                   |
| `io.D_slave_rs1`             | I    | 5.W  | 从指令的源寄存器 rs1 地址                                    |
| `io.D_slave_rs2`             | I    | 5.W  | 从指令的源寄存器 rs2 地址                                    |
| `io.D_slave_reg_waddr`       | I    | 5.W  | 从指令写回寄存器的目标地址                                   |
| `io.D_slave_memtoReg`        | I    | Bool | 从指令是否为 Load 类型指令                                   |
| `io.D_slave_mem_write`       | I    | Bool | 从指令是否为 Store 类型指令                                  |
| `io.D_slave_is_ja`           | I    | Bool | 从指令是否为跳转指令 (JAL, JALR)                             |
| `io.D_slave_may_bring_flush` | I    | Bool | 从指令是否可能导致流水线刷新 (flush)                         |
| `io.D_slave_is_csr`          | I    | Bool | 从指令是否为 CSR 指令                                        |
| `io.fifo_empty`              | I    | Bool | 指令 FIFO (先进先出队列) 是否为空(置0)                       |
| `io.fifo_almost_empty`       | I    | Bool | 指令 FIFO 是否几乎为空(置0)                                  |
| **输出信号 (Outputs)**       |      |      |                                                              |
| `io.D_slave_ena`             | O    | Bool | 从指令发射使能信号 (指示从指令是否可以被发射到下一级流水线)(与read_en2连接) |

#### **BTB 分支预测模块端口定义**

| 端口名称         | 方向 | 位宽 | 功能描述                                   |
| ---------------- | ---- | ---- | ------------------------------------------ |
| **输入信号**     |      |      |                                            |
| io.enaD          | I    | 1    | 译码阶段BTB查询使能                        |
| io.pcD           | I    | 32   | 译码阶段的程序计数器 (PC) 值               |
| io.pcE           | I    | 32   | 执行阶段的程序计数器 (PC) 值 (用于BTB更新) |
| io.pcETarget     | I    | 32   | 执行阶段的实际跳转目标地址 (用于BTB更新)   |
| io.branchE       | I    | 1    | 执行阶段指令是否为分支指令 (用于BTB更新)   |
| io.actualTakeE   | I    | 1    | 执行阶段分支是否实际发生跳转 (用于BTB更新) |
| **输出信号**     |      |      |                                            |
| io.predTakeD     | O    | 1    | 译码阶段预测：若为分支，是否跳转           |
| io.branchTargetD | O    | 32   | 译码阶段预测：若跳转，预测的目标PC值       |

#### **Forward 前馈网络**

| 端口名称                      | 方向 | 位宽         | 功能描述                                                     |
| ----------------------------- | ---- | ------------ | ------------------------------------------------------------ |
| **输入信号**                  |      |              |                                                              |
| `io.id_master_rs_addrs`       | I    | Vec(2, 5.W)  | ID阶段主指令的源寄存器地址                                   |
| `io.id_master_rs_data_rf`     | I    | Vec(2, 32.W) | ID阶段主指令从寄存器堆读出的源操作数数据                     |
| `io.id_slave_rs_addrs`        | I    | Vec(2, 5.W)  | ID阶段从指令的源寄存器地址                                   |
| `io.id_slave_rs_data_rf`      | I    | Vec(2, 32.W) | ID阶段从指令从寄存器堆读出的源操作数数据                     |
| `io.ex_master_alu_valid`      | I    | 1            | EX阶段主路径ALU结果有效且指令写GPR                           |
| `io.ex_master_alu_rd_addr`    | I    | 5.W          | EX阶段主路径指令的目标寄存器地址                             |
| `io.ex_master_alu_data`       | I    | 32.W         | EX阶段主路径ALU结果数据                                      |
| `io.ex_slave_alu_valid`       | I    | 1            | EX阶段从路径ALU结果有效且指令写GPR                           |
| `io.ex_slave_alu_rd_addr`     | I    | 5.W          | EX阶段从路径指令的目标寄存器地址                             |
| `io.ex_slave_alu_data`        | I    | 32.W         | EX阶段从路径ALU结果数据                                      |
| `io.exmem_master_alu_valid`   | I    | 1            | EX/MEM流水线寄存器输出的主路径ALU结果有效且指令写GPR (MEM阶段ALU数据透传) |
| `io.exmem_master_alu_rd_addr` | I    | 5.W          | EX/MEM流水线寄存器输出的主路径指令的目标寄存器地址           |
| `io.exmem_master_alu_data`    | I    | 32.W         | EX/MEM流水线寄存器输出的主路径ALU结果数据                    |
| `io.exmem_slave_alu_valid`    | I    | 1            | EX/MEM流水线寄存器输出的从路径ALU结果有效且指令写GPR (MEM阶段ALU数据透传) |
| `io.exmem_slave_alu_rd_addr`  | I    | 5.W          | EX/MEM流水线寄存器输出的从路径指令的目标寄存器地址           |
| `io.exmem_slave_alu_data`     | I    | 32.W         | EX/MEM流水线寄存器输出的从路径ALU结果数据                    |
| `io.mem_load_valid`           | I    | 1            | MEM阶段Load数据有效且指令写GPR                               |
| `io.mem_load_rd_addr`         | I    | 5.W          | MEM阶段Load指令的目标寄存器地址                              |
| `io.mem_load_data`            | I    | 32.W         | MEM阶段Load出的数据                                          |
| **输出信号**                  |      |              |                                                              |
| `io.final_master_rs_data`     | O    | Vec(2, 32.W) | 经过前馈选择后的主指令最终源操作数数据，送往ID/EX寄存器      |
| `io.final_slave_rs_data`      | O    | Vec(2, 32.W) | 经过前馈选择后的从指令最终源操作数数据，送往ID/EX寄存器      |

### **5.3. 执行模块 (EX - Execute)**

| 端口名称                                | 方向 | 位宽 | 功能描述                                        |
| --------------------------------------- | ---- | ---- | ----------------------------------------------- |
| **输入信号 (Inputs)**                   |      |      |                                                 |
| `io.ExIn1.inst_type`                    | I    | 4.W  | 指令1 (主路径): 来自译码阶段的指令类型          |
| `io.ExIn1.rs1`                          | I    | 5.W  | 指令1: 源寄存器1地址                            |
| `io.ExIn1.rs2`                          | I    | 5.W  | 指令1: 源寄存器2地址                            |
| `io.ExIn1.ex_rs1_data`                  | I    | 32.W | 指令1: 源寄存器1数据 (通常来自前馈或寄存器堆)   |
| `io.ExIn1.ex_rs2_data`                  | I    | 32.W | 指令1: 源寄存器2数据 (通常来自前馈或寄存器堆)   |
| `io.ExIn1.rd`                           | I    | 5.W  | 指令1: 目标寄存器地址                           |
| `io.ExIn1.imm`                          | I    | 32.W | 指令1: 立即数                                   |
| `io.ExIn1.alu_op`                       | I    | 5.W  | 指令1: ALU操作类型                              |
| `io.ExIn1.alu_op1`                      | I    | 2.W  | 指令1: ALU操作数1来源                           |
| `io.ExIn1.alu_op2`                      | I    | 2.W  | 指令1: ALU操作数2来源                           |
| `io.ExIn1.reg_write`                    | I    | Bool | 指令1: 是否写回寄存器                           |
| `io.ExIn1.mem_read`                     | I    | Bool | 指令1: 是否执行内存读取                         |
| `io.ExIn1.mem_write`                    | I    | Bool | 指令1: 是否执行内存写入                         |
| `io.ExIn1.mem_type`                     | I    | 3.W  | 指令1: 内存访问宽度类型                         |
| `io.ExIn1.mem_unsigned`                 | I    | Bool | 指令1: 内存加载是否为无符号扩展                 |
| `io.ExIn1.exception`                    | I    | Bool | 指令1: 译码阶段检测到的异常信号                 |
| `io.ExIn1.pc_out`                       | I    | 32.W | 指令1: 程序计数器 (PC) 值                       |
| `io.ExIn2.inst_type`                    | I    | 4.W  | 指令2 (从路径): 来自译码阶段的指令类型          |
| `io.ExIn2.rs1`                          | I    | 5.W  | 指令2: 源寄存器1地址                            |
| `io.ExIn2.rs2`                          | I    | 5.W  | 指令2: 源寄存器2地址                            |
| `io.ExIn2.ex_rs1_data`                  | I    | 32.W | 指令2: 源寄存器1数据                            |
| `io.ExIn2.ex_rs2_data`                  | I    | 32.W | 指令2: 源寄存器2数据                            |
| `io.ExIn2.rd`                           | I    | 5.W  | 指令2: 目标寄存器地址                           |
| `io.ExIn2.imm`                          | I    | 32.W | 指令2: 立即数                                   |
| `io.ExIn2.alu_op`                       | I    | 5.W  | 指令2: ALU操作类型                              |
| `io.ExIn2.alu_op1`                      | I    | 2.W  | 指令2: ALU操作数1来源                           |
| `io.ExIn2.alu_op2`                      | I    | 2.W  | 指令2: ALU操作数2来源                           |
| `io.ExIn2.reg_write`                    | I    | Bool | 指令2: 是否写回寄存器                           |
| `io.ExIn2.mem_read`                     | I    | Bool | 指令2: 是否执行内存读取                         |
| `io.ExIn2.mem_write`                    | I    | Bool | 指令2: 是否执行内存写入                         |
| `io.ExIn2.mem_type`                     | I    | 3.W  | 指令2: 内存访问宽度类型                         |
| `io.ExIn2.mem_unsigned`                 | I    | Bool | 指令2: 内存加载是否为无符号扩展                 |
| `io.ExIn2.exception`                    | I    | Bool | 指令2: 译码阶段检测到的异常信号                 |
| `io.ExIn2.pc_out`                       | I    | 32.W | 指令2: 程序计数器 (PC) 值                       |
| `io.slave_ena`                          | I    | Bool | 从路径指令有效使能信号                          |
| `io.exceptionFeedback.flush`            | I    | Bool | 异常反馈: 流水线刷新信号                        |
| `io.exceptionFeedback.handlerPC`        | I    | 32.W | 异常反馈: 异常处理程序PC地址                    |
| `io.csr.readData`                       | I    | 32.W | CSR接口: 从CSR读出的数据                        |
| `io.mem1.rspValid`                      | I    | Bool | 内存接口1: 内存响应有效信号 (通常用于Load操作)  |
| `io.mem1.rspData`                       | I    | 32.W | 内存接口1: 从内存加载的数据                     |
| `io.mem2.rspValid`                      | I    | Bool | 内存接口2: 内存响应有效信号 (通常用于Load操作)  |
| `io.mem2.rspData`                       | I    | 32.W | 内存接口2: 从内存加载的数据                     |
| `io.bpPredict.valid`                    | I    | Bool | 分支预测: 预测有效信号                          |
| `io.bpPredict.bits`                     | I    | 32.W | 分支预测: 预测的跳转目标地址                    |
| **输出信号 (Outputs)**                  |      |      |                                                 |
| `io.pc1Out`                             | O    | 32.W | 指令1的PC值输出 (例如传递给异常单元或下一级)    |
| `io.pc2Out`                             | O    | 32.W | 指令2的PC值输出                                 |
| `io.rd1`                                | O    | 5.W  | 指令1: 目标寄存器地址 (直连自输入)              |
| `io.rd2`                                | O    | 5.W  | 指令2: 目标寄存器地址 (直连自输入)              |
| `io.result1`                            | O    | 32.W | 指令1: 执行结果 (ALU结果或Load数据)             |
| `io.result2`                            | O    | 32.W | 指令2: 执行结果 (ALU结果或Load数据)             |
| `io.valid1`                             | O    | Bool | 指令1: 结果有效信号                             |
| `io.valid2`                             | O    | Bool | 指令2: 结果有效信号                             |
| `io.exception1.valid`                   | O    | Bool | 异常接口1: 异常有效                             |
| `io.exception1.cause`                   | O    | 4.W  | 异常接口1: 异常原因                             |
| `io.exception1.pc`                      | O    | 32.W | 异常接口1: 发生异常的PC                         |
| `io.exception1.memAddr`                 | O    | 32.W | 异常接口1: 访存异常的内存地址                   |
| `io.exception2.valid`                   | O    | Bool | 异常接口2: 异常有效                             |
| `io.exception2.cause`                   | O    | 4.W  | 异常接口2: 异常原因                             |
| `io.exception2.pc`                      | O    | 32.W | 异常接口2: 发生异常的PC                         |
| `io.exception2.memAddr`                 | O    | 32.W | 异常接口2: 访存异常的内存地址                   |
| `io.csr.readAddr`                       | O    | 12.W | CSR接口: 读CSR的地址                            |
| `io.csr.writeEn`                        | O    | Bool | CSR接口: 写CSR使能                              |
| `io.csr.writeAddr`                      | O    | 12.W | CSR接口: 写CSR的地址                            |
| `io.csr.writeData`                      | O    | 32.W | CSR接口: 写入CSR的数据                          |
| `io.mem1.reqValid`                      | O    | Bool | 内存接口1: 内存访问请求有效                     |
| `io.mem1.reqAddr`                       | O    | 32.W | 内存接口1: 内存访问地址                         |
| `io.mem1.reqData`                       | O    | 32.W | 内存接口1: 写入内存的数据 (Store操作)           |
| `io.mem1.reqWidth`                      | O    | 2.W  | 内存接口1: 内存访问宽度 (0=1B, 1=2B, 2=4B)      |
| `io.mem1.reqWrite`                      | O    | Bool | 内存接口1: 内存访问类型 (0为读, 1为写)          |
| `io.mem1.memUnsigned`                   | O    | Bool | 内存接口1: 内存加载是否为无符号扩展             |
| `io.mem2.reqValid`                      | O    | Bool | 内存接口2: 内存访问请求有效                     |
| `io.mem2.reqAddr`                       | O    | 32.W | 内存接口2: 内存访问地址                         |
| `io.mem2.reqData`                       | O    | 32.W | 内存接口2: 写入内存的数据 (Store操作)           |
| `io.mem2.reqWidth`                      | O    | 2.W  | 内存接口2: 内存访问宽度 (0=1B, 1=2B, 2=4B)      |
| `io.mem2.reqWrite`                      | O    | Bool | 内存接口2: 内存访问类型 (0为读, 1为写)          |
| `io.mem2.memUnsigned`                   | O    | Bool | 内存接口2: 内存加载是否为无符号扩展             |
| `io.cu.ex_branch_mispredict_detected_i` | O    | Bool | 送往控制单元(CU): 分支预测错误检测信号          |
| `io.cu.ex_master_path_data_ready_i`     | O    | Bool | 送往控制单元(CU): 主路径数据准备就绪信号        |
| `io.cu.ex_slave_path_data_ready_i`      | O    | Bool | 送往控制单元(CU): 从路径数据准备就绪信号        |
| `io.branch1`                            | O    | Bool | 指令1: 是否发生跳转/分支 (结果)                 |
| `io.branchTarget1`                      | O    | 32.W | 指令1: 跳转/分支目标地址 (结果)                 |
| `io.branch2`                            | O    | Bool | 指令2: 是否发生跳转/分支 (结果)                 |
| `io.branchTarget2`                      | O    | 32.W | 指令2: 跳转/分支目标地址 (结果)                 |
| `io.bpVerify`                           | O    | Bool | 分支预测: 预测验证结果 (例如，true表示预测正确) |

### **5.4. 访存模块 (MEM - Memory Access)**

| 端口名称               | 方向 | 位宽 | 功能描述                                      |
| ---------------------- | ---- | ---- | --------------------------------------------- |
| **输入信号 (Inputs)**  |      |      |                                               |
| `io.cpu.mem_en`        | I    | Bool | MEM阶段接收使能信号                           |
| `io.cpu.mem_write`     | I    | Bool | MEM阶段是否进行写操作                         |
| `io.cpu.mem_type`      | I    | 2.W  | 指示MEM阶段的访存类型 (NONE, B, H, W)         |
| `io.cpu.mem_unsigned`  | I    | Bool | 指示MEM阶段是否进行无符号访存                 |
| `io.cpu.mem_wdata`     | I    | 32.W | EX阶段计算出的数据 (用于写入内存)             |
| `io.cpu.mem_addr`      | I    | 32.W | EX阶段计算出的地址                            |
| `io.sram.rdata`        | I    | 32.W | 从SRAM读取的数据                              |
| **输出信号 (Outputs)** |      |      |                                               |
| `io.cpu.mem_rdata`     | O    | 32.W | MEM阶段读取得到的数据                         |
| `io.cpu.mem_ready`     | O    | Bool | 标志MEM阶段工作是否完成，用于不定周期访存控制 |
| `io.sram.en`           | O    | Bool | SRAM使能信号                                  |
| `io.sram.funct3`       | O    | 3.W  | 传递给SRAM的功能码 (可能指示访存类型/宽度)    |
| `io.sram.addr`         | O    | 32.W | 发往SRAM的物理地址                            |
| `io.sram.wdata`        | O    | 32.W | 发往SRAM的写数据                              |
| `io.mem_bypass_data`   | O    | 32.W | MEM阶段传递至EX的数据旁路输出                 |
| `io.mem_bypass_valid`  | O    | Bool | MEM阶段数据旁路有效信号                       |

### **5.5. 寄存器堆 (RF - Register File)**

| 端口名称 | 方向 | 位宽 | 功能描述                                                     |
| -------- | ---- | ---- | ------------------------------------------------------------ |
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

### **5.6. 控制单元模块 (Control Unit - 流水线控制逻辑)**

| 端口名称                             | 方向 | 位宽 | 功能描述                                                     |
| ------------------------------------ | ---- | ---- | ------------------------------------------------------------ |
| **输入信号 (Inputs)**                |      |      |                                                              |
| `io.ex_branch_mispredict_detected_i` | I    | Bool | 来自执行阶段 (Dual IssueEx): 分支预测错误检测信号            |
| `io.ex_master_path_data_ready_i`     | I    | Bool | 来自执行阶段 (Dual IssueEx): 主路径数据准备就绪信号          |
| `io.ex_slave_path_data_ready_i`      | I    | Bool | 来自执行阶段 (Dual IssueEx): 从路径数据准备就绪信号          |
| `io.idex_master_is_load_i`           | I    | Bool | 来自ID/EX流水线寄存器: 主路径指令是否为加载 (Load) 指令      |
| `io.is_master_path_active_in_ex_i`   | I    | Bool | 来自ID/EX流水线寄存器: 执行阶段主路径是否活动 (有有效指令)   |
| `io.is_slave_path_active_in_ex_i`    | I    | Bool | 来自ID/EX流水线寄存器: 执行阶段从路径是否活动 (有有效指令)   |
| **输出信号 (Outputs)**               |      |      |                                                              |
| `io.cu_ififo_flush_o`                | O    | Bool | 送往指令FIFO (InstFifo): 指令FIFO清空信号 (通常因分支预测错误触发) |
| `io.cu_ififo_rd_stall_o`             | O    | Bool | 送往指令FIFO (InstFifo): 指令FIFO读取暂停信号 (用于流水线暂停/反压) |
| `io.cu_idex_stall_o`                 | O    | Bool | 送往ID/EX流水线寄存器: ID/EX阶段暂停信号 (用于流水线暂停/反压) |
| `io.cu_exmem_stall_o`                | O    | Bool | 送往EX/MEM流水线寄存器: EX/MEM阶段暂停信号 (用于流水线暂停/反压) |

### **5.7. CSR 单元模块 (CSR Unit)**

| 端口名称         | 方向 | 位宽 | 功能描述                |
| ---------------- | ---- | ---- | ----------------------- |
| **输入信号**     |      |      |                         |
| `io.csrrw_valid` | I    | Bool | CSR 读写操作有效信号    |
| `io.csrrw_addr`  | I    | 12.W | CSR 寄存器地址          |
| `io.csrrw_wdata` | I    | 32.W | 写入 CSR 寄存器的数据   |
| `io.csrrw_wr_en` | I    | Bool | CSR 写使能信号          |
| `io.csrrw_rd_en` | I    | Bool | CSR 读使能信号          |
| **输出信号**     |      |      |                         |
| `io.csrrw_rdata` | O    | 32.W | 从 CSR 寄存器读取的数据 |

### **5.8. 流水线寄存器 (Pipeline Registers)**

**5.8.1. ID/EX 寄存器组 **

| 端口名称                  | 方向 | 位宽                   | 功能描述                                                    |
| ------------------------- | ---- | ---------------------- | ----------------------------------------------------------- |
| **输入信号 (Inputs)**     |      |                        |                                                             |
| `io.clear`                | I    | Bool                   | 清除信号，用于清空流水线寄存器内容(置0)                     |
| `io.ena`                  | I    | Bool                   | 使能信号，当为高时，流水线寄存器在时钟边沿锁存输入数据      |
| `io.D_master_ctrl_sign`   | I    | id_ex_ctrl_sign Bundle | 来自ID阶段的主指令路径控制信号                              |
| `io.final_master_rs_data` | I    | Vec(2, 32.W)           | 来自ID阶段的主指令路径的最终源寄存器数据 (经过前馈选择后)   |
| `io.D_slave_ctrl_sign`    | I    | id_ex_ctrl_sign Bundle | 来自ID阶段的从指令路径控制信号                              |
| `io.final_slave_rs_data`  | I    | Vec(2, 32.W)           | 来自ID阶段的从指令路径的最终源寄存器数据 (经过前馈选择后)   |
| `io.D_slave_ena`          | I    | Bool                   | 来自ID阶段的从指令路径有效使能信号                          |
| **输出信号 (Outputs)**    |      |                        |                                                             |
| `io.E_master_ctrl_sign`   | O    | id_ex_ctrl_sign Bundle | 输往EX阶段的主指令路径控制信号 (来自流水线寄存器)           |
| `io.final_master_rs_data` | O    | Vec(2, 32.W)           | 输往EX阶段的主指令路径的最终源寄存器数据 (来自流水线寄存器) |
| `io.E_slave_ctrl_sign`    | O    | id_ex_ctrl_sign Bundle | 输往EX阶段的从指令路径控制信号 (来自流水线寄存器)           |
| `io.final_slave_rs_data`  | O    | Vec(2, 32.W)           | 输往EX阶段的从指令路径的最终源寄存器数据 (来自流水线寄存器) |
| `io.E_slave_ena`          | O    | Bool                   | 输往EX阶段的从指令路径有效使能信号 (来自流水线寄存器)       |

**5.8.2. EX/MEM 寄存器组 **

| 端口名称                | 方向 | 位宽                    | 功能描述                                                    |
| ----------------------- | ---- | ----------------------- | ----------------------------------------------------------- |
| **输入信号 (Inputs)**   |      |                         |                                                             |
| `io.ena`                | I    | Bool                    | 使能信号，当为高时，流水线寄存器在时钟边沿锁存输入数据      |
| `io.E_master_ctrl_sign` | I    | ex_mem_ctrl_sign Bundle | 来自EX阶段的主指令路径内存控制信号                          |
| `io.E_rd1`              | I    | 5.W                     | 来自EX阶段的主指令路径的目标寄存器地址 (rd)                 |
| `io.E_result1`          | I    | 32.W                    | 来自EX阶段的主指令路径的计算结果                            |
| `io.E_valid1`           | I    | Bool                    | 来自EX阶段的主指令路径结果有效信号                          |
| `io.E_slave_ctrl_sign`  | I    | ex_mem_ctrl_sign Bundle | 来自EX阶段的从指令路径内存控制信号 (备注：从指令不进行访存) |
| `io.E_rd2`              | I    | 5.W                     | 来自EX阶段的从指令路径的目标寄存器地址 (rd)                 |
| `io.E_result2`          | I    | 32.W                    | 来自EX阶段的从指令路径的计算结果                            |
| `io.E_valid2`           | I    | Bool                    | 来自EX阶段的从指令路径结果有效信号                          |
| **输出信号 (Outputs)**  |      |                         |                                                             |
| `io.M_master_ctrl_sign` | O    | ex_mem_ctrl_sign Bundle | 输往MEM阶段的主指令路径内存控制信号 (来自流水线寄存器)      |
| `io.W_rd1`              | O    | 5.W                     | 输往WB阶段的主指令路径的目标寄存器地址 (来自流水线寄存器)   |
| `io.W_result1`          | O    | 32.W                    | 输往WB阶段的主指令路径的结果数据 (来自流水线寄存器)         |
| `io.W_valid1`           | O    | Bool                    | 输往WB阶段的主指令路径结果有效信号 (来自流水线寄存器)       |
| `io.M_slave_ctrl_sign`  | O    | ex_mem_ctrl_sign Bundle | 输往MEM阶段的从指令路径内存控制信号 (来自流水线寄存器)      |
| `io.W_rd2`              | O    | 5.W                     | 输往WB阶段的从指令路径的目标寄存器地址 (来自流水线寄存器)   |
| `io.W_result2`          | O    | 32.W                    | 输往WB阶段的从指令路径的结果数据 (来自流水线寄存器)         |
| `io.W_valid2`           | O    | Bool                    | 输往WB阶段的从指令路径结果有效信号 (来自流水线寄存器)       |

**5.8.3. MEM/WB 寄存器组 **

| 端口名称                | 方向 | 位宽 | 功能描述                                                     |
| ----------------------- | ---- | ---- | ------------------------------------------------------------ |
| **输入信号 (Inputs)**   |      |      |                                                              |
| `io.M_master_reg_wen`   | I    | Bool | 来自MEM阶段的主指令路径写回使能信号 (访存指令来自mem，非访存指令来自ex_mem.valid1) |
| `io.M_master_reg_waddr` | I    | 5.W  | 来自MEM阶段的主指令路径写回目标寄存器地址 (直接来自ex_mem.W_rd1) |
| `io.M_master_reg_wdata` | I    | 32.W | 来自MEM阶段的主指令路径写回数据 (访存指令来自mem，非访存指令来自ex_mem.result1) |
| `io.M_slave_reg_wen`    | I    | Bool | 来自MEM阶段的从指令路径写回使能信号 (直接来自ex_mem.valid2)  |
| `io.M_slave_reg_waddr`  | I    | 5.W  | 来自MEM阶段的从指令路径写回目标寄存器地址                    |
| `io.M_slave_reg_wdata`  | I    | 32.W | 来自MEM阶段的从指令路径写回数据 (直接来自ex_mem.result2)     |
| **输出信号 (Outputs)**  |      |      |                                                              |
| `io.W_master_reg_wen`   | O    | Bool | 输往WB阶段的主指令路径写回使能信号 (来自流水线寄存器)        |
| `io.W_master_reg_waddr` | O    | 5.W  | 输往WB阶段的主指令路径写回目标寄存器地址 (来自流水线寄存器)  |
| `io.W_master_reg_wdata` | O    | 32.W | 输往WB阶段的主指令路径写回数据 (来自流水线寄存器)            |
| `io.W_slave_reg_wen`    | O    | Bool | 输往WB阶段的从指令路径写回使能信号 (来自流水线寄存器)        |
| `io.W_slave_reg_waddr`  | O    | 5.W  | 输往WB阶段的从指令路径写回目标寄存器地址 (来自流水线寄存器)  |
| `io.W_slave_reg_wdata`  | O    | 32.W | 输往WB阶段的从指令路径写回数据 (来自流水线寄存器)            |

## **6. 处理器架构设计思路详细阐述**

**一、整体架构与数据通路设计流程 (Data Path Design Flow)**

该处理器采用经典的五级流水线结构：取指 (IF)、译码 (ID)、执行 (EX)、访存 (MEM)、写回 (WB)，并在此基础上实现了双发射能力，即理论上每个周期可以处理两条指令（主指令Master和从指令Slave）。

1. **取指阶段 (IF)**:
   - **`PCReg`**: 负责程序计数器（PC）的生成和更新。它可以根据以下信号更新PC：
     - **复位信号**：将PC设置为预定义的复位地址。
     - **分支预测结果**：来自`BTB`模块的预测跳转信号（对应`PCReg.io.D_bj`）和预测的目标地址（对应`PCReg.io.D_bj_target`）。当`BTB`预测发生跳转时，`PCReg`会**立即采纳**预测的目标地址，开始取推测路径上的指令。
     - **执行阶段确定的跳转/分支结果**：来自`DualIssueEx`模块的实际分支是否发生（对应`PCReg.io.E_bj`）和计算出的实际目标地址（对应`PCReg.io.E_bj_target`）。这些信号主要用于**验证分支预测的准确性**。
       - 如果预测正确（例如，预测跳转且实际跳转到相同地址，或预测不跳转且实际不跳转），则流水线正常进行。
       - 如果预测错误（例如，预测不跳转但实际跳转，预测跳转但实际不跳转，或预测的跳转目标地址错误），`DualIssueEx`提供的实际结果将用于**修正PC**，使其指向正确的指令地址。同时，这个预测错误会通知`ControlUnit`来冲刷流水线中已经错误取入的指令，并且`InstFifo`也需要被清空以重新获取正确路径的指令。
   - **指令获取**: `PCReg`产生的`pc_curr`用于从指令存储中获取最多两条指令。
   - **`InstFifo`**: 作为一个指令缓冲队列（16个条目深），接收来自取指单元的指令和PC，平滑IF和ID阶段之间的速度差异。它支持双指令写入和双指令读出，并提供`full`、`empty`、`almost_empty`状态信号。`full`信号用于反压暂停`PCReg`。**其清空逻辑设计为异步清零，以便在分支预测跳转或预测错误需要纠正时能够瞬时响应，将对ID阶段的影响降到最低。**它能够根据取指宽度（通过`F_inst_data_ok1/2`判断是否成功取回1条或2条指令）进行PC的顺序递增（+4或+8）。当指令FIFO (`InstFifo`) 满时，通过`D_fifo_full`信号暂停PC的更新。
2. **译码阶段 (ID)**:
   - **`DualIssueDecode`**: 核心译码与发射控制模块。
     - 从`InstFifo`读取最多两条指令（`inst1`来自`InstFifo.read_data1`，`inst2`来自`InstFifo.read_data2`）及其PC。
     - 内部实例化两个`BaseDecode`单元，分别对主从指令进行RISC-V指令的解析，提取操作码、操作数地址、立即数、以及生成初步的控制信号（如ALU操作类型、内存访问类型、是否写寄存器等）。
     - 实例化`IssueCtrl`单元，根据主从指令的译码信息、潜在的数据冒险（RAW）、结构冲突和指令类型限制，决定从指令是否能够与主指令一起发射 (`slave_ena`)。
     - **从指令发射策略与“推回”机制**：
       - **发射限制**：当主指令（Master）是**分支指令**或**访存相关指令（Load或Store）**时，从指令（Slave）将不被发射（即`DualIssueDecode`模块输出的`slave_ena`信号会为false）。此外，`IssueCtrl`中定义的其他冲突（如数据冲突、CSR/FENCE在从路径等）也会阻止从指令发射。
       - **“推回”的实现**：这里的“推回”并非物理上将指令写回`InstFifo`。当`DualIssueDecode`模块确定从指令不能与主指令一同发射时（`slave_ena`为false），它会通过控制`InstFifo`的读使能来实现：
         - `InstFifo.io.read_en1` 仍然会有效（假设主指令可以发射）。
         - `InstFifo.io.read_en2` 会被置为无效。
         - 这导致`InstFifo`的读指针只前进一个位置（如果之前读取了两条）。在下一个时钟周期，之前作为从指令（`inst2`）从`InstFifo`中读出的那条指令，现在将位于`InstFifo`读端口的第一个位置（即作为新的`InstFifo.read_data1`），从而被重新作为主指令进行评估和译码。这样就实现了“在下一周期作为主指令重新评估”的效果，确保指令按序处理。
   - **`RegisterFile`**: 根据`DualIssueDecode`（源自`BaseDecode`）解析出的主从指令的源寄存器地址（`rs1_addr`, `rs2_addr`），通过其4个读端口异步读取操作数。该模块内部的`bypassRead`函数已经实现了WB->ID的写后读旁路。
   - **`BTB` (分支目标缓冲/预测器)**: 在此阶段（或与ID阶段紧密配合）根据当前`pcD`和指令`instrD`进行分支预测，输出预测是否跳转 (`predTakeD`) 和预测的目标地址 (`branchTargetD`)，这些信号会**立即影响**`PCReg`的下一PC选择。**预测的目标地址（如果预测跳转）也会被传递给后续流水线阶段，最终送入EX阶段的BP FIFO。**
   - **`Forward` (前馈单元)**: 这是一个位于ID阶段的组合逻辑单元。它接收从`RegisterFile`读出的初步操作数值，并结合来自EX阶段的“实时”有效结果（例如，`DualIssueEx.io.valid1` 和 `DualIssueEx.io.result1`，以及对应的目标寄存器地址和写使能）和来自MEM阶段的“实时”有效Load结果（例如，`memModule.io.cpu.mem_ready` 和 `memModule.io.cpu.mem_rdata`，以及对应的目标寄存器地址和写使能），按照优先级（EX从ALU > EX主ALU > EX/MEM从ALU > EX/MEM主ALU > MEM Load > 寄存器堆）选择最终的操作数值。
   - **`id_ex` (ID/EX流水线寄存器)**: 锁存ID阶段的输出，包括：经过`Forward`单元选择后的最终操作数值、PC、立即数、目标寄存器地址、以及从`BaseDecode`传递过来的、**已被修改为RISC-V兼容的**控制信号（例如，ALU操作类型、内存访问类型、写回使能、CSR操作相关信号等）。**如果`BTB`预测跳转，预测的目标地址也会通过此寄存器传递给EX阶段。**
3. **执行阶段 (EX)**:
   - **`DualIssueEx`**: 接收来自`id_ex`流水线寄存器的两条指令的信息。
     - **ALU运算**: 拥有逻辑上并行的两条ALU路径，分别为两条指令计算结果。其内部的`alu`函数目前将所有操作（包括乘除法）实现为组合逻辑。
     - **分支/跳转处理**: 计算分支指令的实际条件和跳转目标地址。将这些信息（实际是否跳转`branchN`，实际目标地址`branchTargetN`）反馈给`BTB`用于验证和更新其预测状态。如果分支预测错误，这些实际结果将用于通知`PCReg`跳转到正确的地址，并触发`ControlUnit`冲刷流水线。
     - **分支预测验证与BP FIFO**: `DualIssueEx`模块内部包含一个分支预测FIFO（`bpFifo`，深度为4的队列）。
       - **预测目标入队**: 从`id_ex`流水线寄存器接收由`BTB`在ID阶段做出的预测跳转目标地址（`io.bpPredict.bits`），并在预测有效时（`io.bpPredict.valid`）将其存入`bpFifo`。
       - **预测验证**: 当EX阶段的指令被解析为分支或跳转类型时，模块会从`bpFifo`中取出（`dequeue`）之前存储的预测目标地址。
       - **比较与反馈**: 将取出的预测目标地址与当前EX阶段计算出的实际跳转目标地址进行比较。比较结果（是否一致）通过`io.bpVerify`信号输出，反馈给`BTB`模块，用于更新其内部的预测器状态和准确性。
     - **访存地址计算与Load数据接收**: 为Load/Store指令计算有效地址。对于Load指令，它会通过`io.memN.req*`端口向MEM阶段发出请求，并**通过其输入端口`io.memN.rspValid`和`io.memN.rspData`从MEM阶段接收返回的数据和有效信号**。这个`rspValid`信号是Load指令在EX阶段完成其数据获取的关键。
     - **结果输出**: 将ALU计算结果（`result1`, `result2`）、访存地址（如果是Load/Store）、Load指令从MEM接收到的数据（通过`io.memN.rspData`传入并赋给`resultN`）、目标寄存器地址、写使能等信息传递给`ex_mem`流水线寄存器。通过`valid1`/`valid2`信号标识对应路径结果的有效性（ALU计算完成或Load数据已通过`io.memN.rspValid`从内存返回）。**如果某条路径的`valid`信号无效（例如，Load指令正在等待其`io.memN.rspValid`信号，或多周期ALU操作尚未完成），`ControlUnit`将根据此状态暂停取指（IF）和译码（ID）阶段，直到EX阶段对应路径的执行完成并输出有效的`valid`信号。**
   - **`Csr` (部分交互)**: `DualIssueEx`通过`csrrw`相关端口与`Csr`模块通信，读写`mtime`和`mcycle`。
4. **访存阶段 (MEM)**:
   - **`ex_mem` (EX/MEM流水线寄存器)**: 锁存来自EX阶段的结果和控制信号，包括ALU结果（`M_master_alu_res`, `M_slave_alu_res`）、访存地址/数据、写回信息等。
   - **`MEM` (数据缓存模块)**:
     - 接收来自`ex_mem`的访存请求（地址、数据、操作类型）。访存设计明确为**单端口访存**，即一个周期`MEM`模块只处理一个CPU请求。由于双发射策略规定当主指令是访存指令时，从指令不发射，因此在正常情况下，EX阶段传递给EX/MEM流水线寄存器的指令中，**最多只有一条是有效的访存指令**。`MEM`模块将处理这条来自主路径的访存请求。
     - 内部实现了一个直接映射、写回策略的数据缓存（16行，每行4字）。
     - 通过状态机处理Cache Hit/Miss、行填充、脏块写回。
     - `io.cpu.mem_ready`信号指示访存操作是否完成，用于流水线暂停。
     - `io.cpu.mem_rdata`提供Load操作读取的数据。
     - 使用`MemAccess`子模块处理对底层SRAM的子字（字节、半字）访问和数据对齐。
   - **数据传递**: 对于Load指令，从`MEM`模块读取的数据 (`memModule.io.cpu.mem_rdata`) 被传递。对于ALU指令，其结果从`ex_mem`寄存器透传（例如，`exMemReg.io.M_master_alu_res`）。
5. **写回阶段 (WB)**:
   - **`mem_wb` (MEM/WB流水线寄存器)**: 锁存来自MEM阶段的结果（Load数据或透传的ALU结果）以及写回所需的目标寄存器地址和写使能（**信号内容需为RISC-V兼容**）。
   - **`RegisterFile`**: 接收来自`mem_wb`的写回数据、目标地址和写使能，通过其2个写端口将结果写入寄存器堆。WAW冲突在此处通过“从指令结果覆盖主指令结果”的逻辑解决。

**二、解决各种冲突的方法 (Conflict Resolution Methods)**

- **数据冒险 (RAW - Read-After-Write)**:
  - **1. WB -> ID 旁路 (Register File Internal Bypass)**:
    - **实现**: `RegisterFile` 模块内部，通过 `bypassRead` 函数实现。
    - **机制**: 当ID阶段的指令（例如指令`I_n`）需要读取某个寄存器（例如`rx`）时，`bypassRead` 函数会检查是否有正在同一周期进行写回（WB）操作的指令（例如指令`I_n-3`，假设5级流水线且无暂停）其目标寄存器也是`rx`。
    - **效果**: 如果匹配（即`RegisterFile.io.wenN`有效且`RegisterFile.io.waN == rx`），`bypassRead` 会直接返回正在被写入的数据（`RegisterFile.io.wdN`），而不是从物理寄存器单元中读取可能尚未更新的旧值。这有效地将WB阶段的结果“旁路”回ID阶段的寄存器读取操作，解决了因写回延迟导致的一个周期RAW冒险。
  - **2. EX -> ID 和 MEM -> ID 前馈 (External Forwarding Unit)**:
    - **实现**: 这是由位于ID阶段的、作为组合逻辑的 `Forward` 单元实现的。
    - **机制**: `Forward` 单元接收ID阶段指令的源寄存器地址，以及从`RegisterFile`初步读出的数据（这个数据已经经过了上述的WB->ID旁路）。然后，它会检查更“新鲜”的数据来源：
      - **来自当前EX阶段的ALU结果**：`Forward`单元查看当前EX阶段的主从两条指令。
        - **对于ALU指令**：其计算结果（`DualIssueEx.io.resultN`）和有效性（`DualIssueEx.io.validN`）在EX内确定。如果该ALU结果有效且指令需要写回通用寄存器（GPR），则此ALU结果可作为EX->ID前馈的数据源。
      - **来自EX/MEM流水线寄存器输出的ALU结果 (MEM阶段ALU数据透传)**: `Forward`单元查看EX/MEM流水线寄存器输出端的主从两条指令的ALU结果（这些是上一个周期EX阶段计算完成的），判断是否是ID阶段所需的数据。
      - **来自MEM阶段的有效Load结果 (当Load指令已在MEM阶段完成)**: `Forward`单元查看当前MEM阶段的Load指令是否已从`MEM`模块获取了有效数据（通过`memModule.io.cpu.mem_ready`判断），并且该Load指令的目标寄存器是否是ID阶段所需。
    - **优先级与选择**: `Forward`单元根据预设的优先级（EX从ALU > EX主ALU > EX/MEM从ALU > EX/MEM主ALU > MEM Load（来自MEM模块的更早的Load） > 寄存器堆（含WB旁路）的值）选择最新的有效数据作为ID阶段指令的最终操作数。
  - **3. Load-Use 冒险暂停 (Stall for Load-Use Hazard)**:
    - **核心暂停规则**: 当一条Load指令（生产者）正在EX阶段处理，但其数据尚未从内存系统通过`DualIssueEx.io.memN.rspValid`有效返回时，如果后续在ID阶段的指令（消费者）需要这个Load的结果，则流水线的前面阶段（IF, ID）必须暂停。EX阶段自身也会因等待该Load数据而无法将此Load指令的结果传递给EX/MEM寄存器。
    - **暂停触发**: `ControlUnit` 模块负责检测此情况。它会监测当前EX阶段的指令是否为Load（通过从ID/EX流水线寄存器传递的控制信号，如`idExReg.io.E_master_mem_read_en_out`），以及`DualIssueEx`模块对应的内存响应有效位（`dualIssueEx.io.memN.rspValid`）是否仍为 `false`。如果EX阶段是Load指令且其`rspValid`为`false`，则表明Load数据未就绪，需要暂停。
    - **暂停的传播与效果**: `ControlUnit`接收到“EX阶段Load数据未就绪”的信号后，会暂停取指（IF）和译码（ID）阶段。EX阶段也会因等待`rspValid`而“卡住”该Load指令，直到数据返回，`DualIssueEx.io.validN`才能真正反映Load数据的有效性。
    - **解除暂停**: 一旦Load指令的数据有效（`dualIssueEx.io.memN.rspValid`变为 `true`），`DualIssueEx.io.validN`将反映此状态，暂停随之解除。此时，`Forward`单元可以将刚在EX阶段变得有效的Load数据（现在通过`DualIssueEx.io.resultN`可获取）前馈给在ID阶段等待的指令，流水线恢复正常执行。
- **写后写冒险 (WAW - Write-After-Write)**:
  - 在 `RegisterFile` 的写回逻辑中解决，从指令的写操作优先于主指令对同一目标寄存器的写操作。
- **写后读冒险 (WAR - Write-After-Read)**:
  - 顺序执行，不会发生
- **结构冲突 (Structural Hazards)**:
  - **访存单元**: `MEM`模块一次只处理一个请求。因此，`IssueCtrl` **必须**阻止两条访存指令（Load或Store）同时从ID阶段发射并期望在同一个周期被MEM模块服务。
  - **ALU单元**: 并行的两条ALU路径 (`DualIssueEx`)。
- **控制冒险 (Control Hazards)**:
  - **分支预测时的发射策略**：当译码阶段的主指令（Master）被识别为分支指令时，从指令（Slave）将不被发射，并保留在`InstFifo`中。只有主指令的分支指令会被送往分支预测单元（`BTB`）进行预测。
  - **`BTB` (分支预测)**: 在取指/译码阶段进行动态分支预测。当`BTB`预测分支跳转时，它会提供预测的跳转目标地址。
  - **预测跳转的即时响应**: 从`BTB`预测跳转到`InstFifo`清空（使IF阶段可以异步清零其已取指但未送入FIFO的内容，或使FIFO本身异步清零）设计为组合逻辑。一旦`BTB`确定预测跳转并给出跳转位置，`PCReg`会立即跳转到预测的目标地址，同时`InstFifo`中的指令被异步清空。ID阶段随后将从`InstFifo`中获取新的指令流。
  - **`DualIssueEx` (分支解析)**: 在执行阶段，分支指令的实际条件和目标地址被最终计算出来。
  - **`ControlUnit` (冲刷与恢复)**:
    - 如果`DualIssueEx`解析出的实际分支结果与`BTB`的预测不符（预测错误），或者即使预测正确但发生了跳转（`E_bj`有效），`ControlUnit`会产生冲刷信号（如`D_flush`, `E_flush`）来清除流水线中错误路径（或已执行的顺序路径）上的指令。`PCReg`会根据`DualIssueEx`的最终解析结果来更新PC。
  - **`PCReg` (PC更新)**: 根据`BTB`的预测（如果跳转，则使用预测目标地址）和`DualIssueEx`的最终解析结果（用于处理预测错误或确认跳转）来更新PC。

## **7. 小组分工** 

1.     取指IF 曹嫣然 

2.     译码ID  庄心妍

3.     执行EX 何凡

4.     访存MEM 刘延昌

5.     级间寄存器  程仁龙

6.     寄存器堆RF及处理器性能测试  张鑫宏

7.     顶层模块的编写 蔡心钰

8.     分支预测  姜佳宇

9.     CSR及顶层模块的编写 白佳瑶

10.     整体架构设计、spec文档撰写、前馈网络及CU的编写 乔际帆

目标：顺序双发射五级流水线

第一周（10周）：阅读文献和资料，熟悉模块功能和目的，构思模块层次

第二、三周（11周-12周）：初步编写模块代码，完成主体内容

第四周（13周）完善详细逻辑，调试代码，进行性能测试，初步对模块集成

第五周（14周）完善模块接口，进行总的仿真测试

五六周完成报告写作
