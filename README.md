![image](https://github.com/user-attachments/assets/3d8aecf7-0601-4aa5-8034-13cb047a73f3)
**一、整体架构与数据通路设计流程 (Data Path Design Flow)**

该处理器采用经典的五级流水线结构：取指 (IF)、译码 (ID)、执行 (EX)、访存 (MEM)、写回 (WB)，并在此基础上实现了双发射能力，即理论上每个周期可以处理两条指令（主指令Master和从指令Slave）。

1. **取指阶段 (IF)**:
   - **`PCReg`**: 负责程序计数器（PC）的生成和更新。它可以根据以下信号更新PC：
     - **复位信号**：将PC设置为预定义的复位地址。
     - **分支预测结果**：来自`BTB`模块的预测跳转信号（对应`PCReg.io.D_bj`）和预测的目标地址（对应`PCReg.io.D_bj_target`）。当`BTB`预测发生跳转时，`PCReg`会**立即采纳**预测的目标地址，开始取推测路径上的指令。
     - **执行阶段确定的跳转/分支结果**：来自`DualIssueEx`模块的实际分支是否发生（对应`PCReg.io.E_bj`）和计算出的实际目标地址（对应`PCReg.io.E_bj_target`）。这些信号主要用于**验证分支预测的准确性**。
       - 如果预测正确（例如，预测跳转且实际跳转到相同地址，或预测不跳转且实际不跳转），则流水线正常进行。
       - 如果预测错误（例如，预测不跳转但实际跳转，预测跳转但实际不跳转，或预测的跳转目标地址错误），`DualIssueEx`提供的实际结果将用于**修正PC**，使其指向正确的指令地址。同时，这个预测错误会通知`ControlUnit`来冲刷`InstFifo`。
   - **指令获取**: `PCReg`产生的`pc_curr`用于从指令存储中获取最多两条指令。
   - **`InstFifo`**: 作为一个指令缓冲队列（16个条目深），接收来自取指单元的指令和PC，平滑IF和ID阶段之间的速度差异。它支持双指令写入和双指令读出，并提供`full`、`empty`、`almost_empty`状态信号。`full`信号用于反压暂停`PCReg`。**其清空逻辑设计为异步清零，以便在分支预测跳转或预测错误需要纠正时能够瞬时响应，将对ID阶段的影响降到最低。**它能够根据取指宽度（通过`F_inst_data_ok1/2`判断是否成功取回1条或2条指令）进行PC的顺序递增（+4或+8）。当指令FIFO (`InstFifo`) 满时，通过`D_fifo_full`信号暂停PC的更新。
2. **译码阶段 (ID)**:
   - **`DualIssueDecode`**: 核心译码与发射控制模块。
     - 从`InstFifo`读取最多两条指令（`inst1`来自`InstFifo.read_data1`，`inst2`来自`InstFifo.read_data2`）及其PC。
     - 内部实例化两个`BaseDecode`单元，分别对主从指令进行RISC-V指令的解析，提取操作码、操作数地址、立即数、以及生成初步的控制信号（如ALU操作类型、内存访问类型、是否写寄存器等）。
     - 实例化`IssueCtrl`单元，根据主从指令的译码信息、潜在的数据冒险（RAW）、结构冲突和指令类型限制，决定从指令是否能够与主指令一起发射 (`slave_ena`)。
     - **从指令发射策略与“推回”机制**：
       - **发射限制**：当主指令（Master）是**分支指令**或**访存相关指令（Load或Store）**或**主从指令有数据依赖**时，从指令（Slave）将不被发射（即`DualIssueDecode`模块输出的`slave_ena`信号会为false）。此外，`IssueCtrl`中定义的其他冲突（如数据冲突、CSR/FENCE在从路径等）也会阻止从指令发射。
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
    - 如果`DualIssueEx`解析出的实际分支结果与`BTB`的预测不符（预测错误），`ControlUnit`会产生冲刷信号来冲刷FIFO中的指令。`PCReg`会根据`DualIssueEx`的最终解析结果来更新PC。
  - **`PCReg` (PC更新)**: 根据`BTB`的预测（如果跳转，则使用预测目标地址）和`DualIssueEx`的最终解析结果（用于处理预测错误或确认跳转）来更新PC。
