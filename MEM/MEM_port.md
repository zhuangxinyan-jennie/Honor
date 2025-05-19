### **5.4. 访存模块 (MEM - Memory Access)**

| 端口名称         | 方向 | 位宽       | 功能描述                                                   |
| ---------------- | ---- | ---------- | -----------------------------------------------------    |
| cpu.mem_en           | I    | 1          | M 阶段是否有访存操作                                  |
| cpu.mem_op           | I    | 7          | M 阶段访存操作类型                                    |
| cpu.mem_funct3       | I    | 3          | M 阶段访存操作码                                      |
| cpu.mem_wdata        | I    | 32         | M 阶段需要写入 Cache 的数据                           |
| cpu.mem_addr         | I    | 32         | M 阶段访存的物理地址                                  |
| cpu.mem_ready        | O    | 1          | M阶段工作是否完成，用于不定周期访存控制                 |
| cpu.mem_rdata        | O    | 32         | 处理后的内存读取结果 (根据 Load 类型选择字节/半字/字)   |
| sram.rdata           | I    | 32         | 从助教提供模块接口实际读回的数据                       |
| sram.en              | O    | 1          | 输出到助教提供模块，指示是否进行访问                    |
| sram.funct3          | O    | 3          | 输出到助教提供模块，指示操作码                         |
| sram.addr            | O    | 32         | 输出到助教提供模块的访问地址                           |
| sram.wdata           | O    | 32         | 输出到助教提供模块的写入数据                           |
| M_master_mem_sel     | I    | 1          | M 阶段是否选中 Master 路径访存                        |
| M_slave_mem_sel      | I    | 1          | M 阶段是否选中 Slave 路径访存                         |
| mem_bypass_data      | O    | 32         | 输出给EX阶段的前馈数据                                |
| mem_bypass_valid     | O    | 1          | 输出给EX阶段标志前馈数据能否使用的使能信号              |
| flush                | I    | 1          | 接收到的来自CU(or 异常处理模块?)的冲刷信号             |

