# MLGiX 编译器架构与 IR 分层设计

| 项目 | 内容 |
| --- | --- |
| 状态 | 草案 v0.4：分层、递归、闭包、`extern`、effect 保序、`Int`/`Num`、字符串常量拼接已定；第 1 章事实已对源码核验；`pragma` 语法待拍板 |
| 适用范围 | `mlogix/src/compiler/` 及后续的中端/后端 |
| 读者 | 编译器实现者、语言设计者、后续接手的 agent |
| 维护约定 | 本文件中的**分层**、**不变量**、**决策（D1–D20）** 一旦改动，必须同步更新本文件，否则视为设计回归 |
| 相关文档 | [语义分析设计](semantic-analyzer.md)、[语法速通](../grammar/fast-learning.md) |

---

## 0. 文档定位

这是一份**长期参考**文档，回答三类问题：

1. **目标机器到底长什么样**（第 1 节）——所有后端决策的事实来源。游戏版本会变，事实有变就改这一节。
2. **编译器为什么分成这几层**（第 5–6 节）——每层的职责、数据结构、不变量、退出条件。
3. **为什么这么定**（第 8 节）——每个关键子系统的取舍与代价数字，避免后人推翻结论却重复踩坑。

本文档**不是**语法参考，也不描述前端已有的词法/语法/类型推断细节（那些见 `semantic-analyzer.md` 与源码）。

---

## 1. 目标环境事实基线

> 这一节是**事实**，不是设计。凡是与游戏源码不符的地方，以源码为准并回改本节。
> 标注 ⚠️ 的条目来自二手资料或用户口述，尚未逐条对照源码验证。v0.2/v0.3 已核验的条目见附录 D。

### 1.1 执行模型

- 程序是**一维指令数组**，`@counter` 是索引。执行是 `instructions[counter.numval++].run()`——**先自增再执行**，所以指令执行期间 `@counter` 已经指向**下一条**。所有 `set @counter` / 相对寻址类技巧都要按这个偏移算。
- 一行一条指令，指令名 + 若干参数，空格分隔。
- 执行到最后一条后 `@counter` 越界，下一次取指自动重置为 0（**隐式无限循环**）。`end` 等价于把 `@counter` 设为 `length`。
- **标签与注释不是指令**，不占数组位置，不计入指令数。**标签由游戏解析**：`jump` 的目标写成标签名（非整数 token），游戏在导入时换成行号，编译器不必自己换算（见 6.6）。
  - 推论：CFG 的边（标签）是"免费"的，但 `jump` 指令本身要花钱。
  - 推论：文件末尾的裸标签会"踩空"（指向 `length`，行为不可靠），这是**必须检查的合法化规则**。
- 每 tick 执行 `accumulator += edelta() * ipt`，当 `accumulator >= 1` 时执行一条并减 1。
- `accumulator` 上限为 `maxInstructionScale * ipt`（默认 `maxInstructionScale = 5`）。**卡顿后不会补偿性爆发**。
- `wait` 会设置 `yield`，中断当前 tick 的剩余配额，并在后续 tick 累计 `curTime`。

### 1.2 处理器等级与 target profile

| profile | 建筑 | `@ipt` | 链接范围 | 特权 | 指令上限 |
| --- | --- | --- | --- | --- | --- |
| `MICRO` | 微型处理器 | 2 | 10 | ✗ | 1000 |
| `LOGIC` | 逻辑处理器 | 8 | 22 | ✗ | 1000 |
| `HYPER` | 超级处理器 | 25 | 42 | ✗ | 1000 |
| `WORLD` | 世界处理器 | 8（`setrate` 可到 1000） | 全图 | ✓ | 1000 |

- 特权指令（`setrate`/`getblock`/`setblock`/`spawn`/`bullet`/`status`/`setrule`/`message`/`cutscene`/`effect`/`explosion`/`fetch`/`getflag`/`setflag`/`setprop`/`playsound`/`playmusic`/`setmarker`/`makemarker`/`localeprint`/`sync`/`query`/`clientdata`/`weather*`/`spawnwave`）**只能跑在 `WORLD` 上**。
- 推论：语言里只要出现了需要特权的操作，该产物就被"钉"在 `WORLD` 上，需要编译期诊断。
- 推论：`@ipt` 差 500 倍，所以**分区算法的时间成本是 `指令数 / ipt`，不是指令数**。

### 1.3 存储层级

| 存储 | 容量 | 可存什么 | 访问方式 | 跨处理器 |
| --- | --- | --- | --- | --- |
| **变量槽** | 无限（写了名字就有） | number / object / string / null | 直接用名字，1 条指令 | 只能经 `read/write @this "名字"` |
| Memory Cell | 64 槽 | 任意值（number 或 object） | `read`/`write` + **数字索引** | ✓ 共享建筑 |
| Memory Bank | 512 槽 | 任意值（number 或 object） | `read`/`write` + **数字索引** | ✓ 共享建筑 |
| world-cell | 512 槽 | 任意值（number 或 object） | 同上 | ✓ 特权建筑 |
| 处理器变量表 | 无限 | 任意值 | `read`/`write @this "名字"` | ✓ 有 1 tick 帧差 |
| 全局 flag | 无限 | number | `setflag`/`getflag`（仅 WORLD） | ✓ |
| 单位 `flag` | 1 number / 单位 | number | `ucontrol flag` / `sensor @flag` | ✓ 分布式 |
| 建筑字段 | — | number / object | `control` / `setprop` / `sensor` | ✓ |

**关键修正（D9，已对源码确认）**：早期资料称"内存块只能存 double"，**已过时**。实现是 `Object[] objectMemory` + `double[] numberMemory` 两个并行数组 + 一个 `sentinel` 标记——**每个槽位是 tagged union**（要么 number 要么 object），初值为数字 `0`（`MemoryBlock.java:54-66`）。

**容量不要硬编码**：`memoryCapacity` 在 `MemoryBlock` 里默认 **32**，由实例覆盖（cell 64 / bank 512 / world-cell 512）。而且 `sensor mem @memoryCapacity` 运行时可读（`MemoryBlock.sense`），所以 L3 布局层应当在运行时探测容量，而不是把 64/512 写死——模组改容量时也不会错。

这条影响三处：

1. `Array<Unit>` 等对象数组合法，数组不必降级成"只能装数字"。
2. 闭包环境可以装箱进内存块，逃逸闭包在**数据层面**可行（成本转移到生命周期管理）。
3. cell/bank 是**建筑级共享设备**：两个处理器链接同一个 bank 即构成零协议共享内存，这是自动分区的首选通信介质。

### 1.4 指令语义要点（与编译相关）

- **数值全是 double**。位运算强制转 `long` 再转回。`idiv` 是向下取整除法，`mod` 符号随被除数，`emod` 恒非负。
- 三角函数输入/输出都是**度数**。
- `equal`（`==`）有 `1e-6` 容差；`strictEqual`（`===`）无容差且要求类型一致。
- 对象参与数值运算时 `num()` 返回 `1`（非 null）/ `0`（null）。所以对象"真值"可用，但算术无意义。**注意 `null == 0` 为真**，判空只能用 `===`。
- **写入常量变量会被静默忽略**：`set 10 5`、`set @time 0` 都不报错但无效。代码生成绝不能产出这类指令。
- `noop` **占一个指令位**。
- 未知指令会被解析成 `noop` 而非报错 —— 所以拼写错误是**静默**的，编译器应当自己把握这一风险。
- **内存越界**：读返回 **`null`（对象）**，不是 `NaN`（`MemoryBlock.read`，注释明写 "instead of 0"）；写静默忽略。
- **`sensor` 读不到的属性**：数值类返回 `NaN`（`default -> NaN`），对象类返回 `null`。而 NaN 一旦写回变量就会变成 `null`（见 6.1）。
- **每行最多 16 个 token**（含指令名），超出直接报 "Line too long"。
- **分词只按 6 个字符切**：`\n`、空格、`\t`、`#`、`;`、`"`。因此变量名的合法字符集几乎是"除这 6 个以外任意"，比常规标识符宽松得多；`;` 同时充当语句分隔符。
- **`setrate` 只对特权处理器有效**：非特权调用被 clamp 到 `instructionsPerTick`（普通处理器无法提速）；只有 `WORLD` 覆盖了 `maxInstructionsPerTick = 1000`。
- **`jump` 的目标有两种写法**：整数 → 行号；非整数 → 标签名，由游戏解析。标签**独占一行且以 `:` 结尾**，**不能是纯数字**（会被当成行号），最多 **500** 个，重名直接报错。
- **特权指令在非特权处理器上会被静默丢弃**（解析成 `InvalidStatement`），不报错——所以"用了 `spawn` 却编译到普通处理器"必须在编译期拦住。

### 1.5 隐藏状态与副作用

逻辑指令不是纯函数。以下状态会让"看起来等价"的两条指令**语义不同**：

| # | 状态 | 位置 | 写 | 读 | 跨处理器 | 优化禁忌 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `graphicsBuffer`（≤256） | 处理器私有 | `draw *` 追加 | `drawflush` 取走并清空 | ✗ | 命令流顺序敏感（`color`/`stroke`/`translate` 有状态），不可重排/合并 |
| 2 | `textBuffer`（≤400 字符） | 处理器私有 | `print`/`printchar`/`format`/`localeprint` 追加 | `printflush`/`draw print`/`message` 取走并清空 | flush 后可见 | 同缓冲区内可合并；跨 flush 不可重排。**不可读**，所以运行期无法做字符串拼接 |
| 3 | `@counter` | 处理器私有 | `set`/`jump`/`end`/`stop`/`wait` | 取指 | 可被别的处理器远程改 | 控制效果，不能当纯值 |
| 4 | `@unit` + `binds[typeId]` 计数器 | 处理器私有 | `ubind` | `ucontrol`/`uradar` | ✗ | **最危险**：连续两次 `ubind @mono` 绑到不同单位，CSE 会把"轮流控全部"退化成"只控一个" |
| 5 | `memory[]` | 建筑级 | `write`/`setprop` | `read`/`sensor` | ✓ | 读者对编译器不可见，死写不能随便消 |
| 6 | 实体/建筑字段 | 世界状态 | `ucontrol`/`control`/`setprop`/`status` | `sensor`/`uradar`/`ulocate` | ✓ | 有帧差与缓存，非纯函数 |
| 7 | 隐藏缓存与计时器 | 处理器私有 | 指令自身 | 指令自身 | ✗ | `uradar`/`ulocate`/`radar` 有 **30 tick 缓存**（`timer.get(30f)`）；`wait` 有 `curTime`；`@time`/`@tick` 是 volatile |

**优化器必须把第 4、7 类建模成"读写同一个隐藏资源"**，否则会产生静默错误代码。

**雷达缓存的粒度（源码确认）**：`RadarI` 的 `timer` / `lastTarget` / `lastSourceBuild` 是**指令实例自己的字段**，不是处理器的字段。所以：

- 同一条 `radar` 指令在 30 tick 内不重算；
- **改 `sort` 参数不会立即生效**——时间没到就复用旧结果；
- `target1/2/3` 在游戏编辑器里是**选择项而非变量**，运行时不可能变，所以它天然不参与"缓存键"。
- 推论：把一条 `radar` 复制成两条**不会**共享缓存，各自的 timer 独立。

### 1.6 执行与同步模型

**两个不同的轴，不要混为一谈。**

**(a) 单客户端内的处理器顺序**：同一 tick 内，多个处理器**按固定顺序**依次执行完各自的配额，所以"同 tick 内 A 写、B 读"的结果取决于执行顺序；跨 tick 无歧义。

**(b) 跨客户端（多人）**：**逻辑在每个客户端上各自独立执行，所有语句都不同步**。"参数相同 → 结果相同"只是确定性，不是同步保证。游戏甚至专门提供了**故意不同步**的内置变量，例如 `@clientName`（当前客户端玩家名，仅 `WORLD` 可用）——`print @clientName` 在每个客户端会打印不同内容。

**对编译器的含义**：

- 编译器**不建模同步**，也不假设"同一变量在所有客户端一致"。这不是代码生成能解决的问题，而是逻辑作者的语义责任。
- 需要一致性的状态，只能由逻辑自己写进**会被同步的建筑**（内存块 / 消息块 / 显示屏），而这些写入本身有延迟——这是独立课题，列入 1.8 的待验项。
- 无头服务器上 `draw` 系列**完全不执行**（`Vars.headless` 短路），所以"客户端看到画面"与"服务端状态"本来就可以不同。
- 分区推论不变：跨分区通信至少 3–6 条指令并引入顺序依赖，**能不分就不分**。

### 1.7 命名空间

以下三者**共享同一个标识符空间**，这是最容易被忽略的坑：

1. 用户定义的变量名（写了就有槽）。
2. 玩家在编辑器里给链接建筑的命名（`conveyor1`、`cell1` 等）。
3. `@` 开头的环境变量与 content（`@counter`、`@copper`、`@this`），只读（`@counter` 例外）。

推论：

- 玩家在游戏里改建筑名 → 编译器**不可能静态知道**，所以外部建筑必须显式声明（见 8.5）。
- 编译器生成的临时变量名必须有一套**不可碰撞的 mangling**（见 7.4）。
- 跨处理器通信靠变量名当键 → **变量名是 ABI**，被导出的名字不能被重命名或合并。
- **`@link` 名优先于用户变量**（同名时链接胜出）。这就是编译器临时变量必须用 `__` 前缀的直接理由。

### 1.8 仍需复核的事实

第 1.1–1.7 的条目已对照 `reference/Mindustry` 逐条核过（核实记录见附录 D）。剩下这些**要么依赖版本/模组，要么必须实测**：

- 模组环境下的 content 数量与逻辑 ID 映射（`logicids.dat`）——会变，编译器不应硬编码。
- 跨客户端同步的**具体边界**：哪些指令的写入会被同步、延迟多少、冲突时谁赢。1.6 只确定了"不同步"这一层，细节需要起服务器实测。
- 建筑被摧毁后，持有其引用的变量读到什么。（单位已确认：字段冻结 + `@dead=1` + `@controlled=0` + `@controller=null`；建筑未验。）
- `@clientName` 这类客户端变量的**完整清单**。
- 内存块在多人模式下的序列化时机与带宽（大 bank 频繁写的影响）。
- `world-cell` 的 `readable`/`writable` 规则是否与 `world-message` 一致。

---

## 2. 语言范围

### 2.1 前端已具备（AST 层）

`fn`、`struct`（含字段默认值与方法）、`enum`（单元/元组/结构体变体）、`match`、`for`、`while`、`break`/`continue`（可带标签）、`use`（模块导入）、泛型、元组、数组字面量、索引、范围、`self` 隐式接收者、**类型注解**（`Expr.Annotation`，形如 `x: Num`、`x: Num?`、`x: A | B`）。

已有 6 个 PassId：`PARSING`、`RESOLUTION`、`DESUGAR`（空壳）、`TYPE_INFERENCE`、`DATAFLOW`（空壳）、`EXHAUSTIVENESS`。

### 2.2 规划中

| 特性 | 状态 | 影响层 |
| --- | --- | --- |
| 函数 | 已解析，未下降 | L2–L4 |
| 结构体 | 已解析，未下降 | L3（SROA / 装箱） |
| 闭包 | 未实现 | L2（转换）、L3（环境布局）、L4（间接调用） |
| 宏 | **comptime 先做**，语法宏后置 | L1 之前的 AST 级 |
| `Array` / `Map` | 未实现，**语言可见** | L3（bank 映射、多 bank 拼接） |
| 字符串一等值 | 未实现，**已定为一等值** | L2/L3（常量池、比较语义） |
| 递归 | 未实现，**C 方案** | L2（SCC）、L3（栈帧） |
| 自动分区 | 未实现，**已定为自动** | L5 |
| 外部链接声明 `extern` | 未实现，**已定为独立关键字** | L1（符号表）+ L3（容量） |

### 2.3 明确不做 / 待定

- **不做**：手写内联汇编块（至少 v1 不做；若要做，需要 L4 的"外部指令"节点与 liveness 屏障）。
- **不做**：GC。内存块的槽位释放依赖逃逸分析与区域规则。

已定（对应第 11 节的 Q）：

- **Q1**：宏先只做 comptime，语法宏后置。
- **Q2**：effect 策略取**保序**，不做依赖图重排。
- **Q3**：`Map` 先只支持小规模、键为数字，用线性扫描。
- **Q5**：语言层区分 `Int` 与 `Num`，底层同为 double（见 6.1）。
- **Q6**：分区后置到 M6，但 `AllocPlan`/`Budget` 预留字段。
- **Q7**：内联阈值先设"函数体 ≤ 20 条且调用点 ≤ 3"，需实测调整。
- **Q8**：变量名与链接名冲突 → 报错，不自动改名。
- **Q9**：外部链接用独立关键字 `extern`，不复用 `use`。
- **R1**：`Int / Int` → `div` 且返回 `Num`；溢出/除零运行时为 `null`（类型系统不跟踪）；`Num → Int` 用 `floor`。
- **R2**：字符串只能**编译期常量拼接**（`@textBuffer` 不可读）。

仍待细化：宏的卫生性与跨文件能力（R3）、编译期指令语法（R4）、分区是否建模客户端差异（R5）。

---

## 3. 设计目标与非目标

### 目标

1. **正确性优先**：任何优化都不能改变可观测行为，尤其是第 1.5 节的隐藏状态。
2. **预算可预测**：任何时刻都能回答"现在多少条指令、还剩多少"。超限报告要能定位到代码。
3. **生成物可读**：输出的 mlog 应当接近人类手写水平，否则无法与手写逻辑协作、无法调试。
4. **分层可测试**：每层都有可 dump 的中间表示与独立的 golden test。
5. **目标可切换**：同一份源码编译到 4 种 profile，不需要改写源码。

### 非目标

- 不追求与 C/Rust 同级的优化深度。目标机太特殊，通用优化（如激进循环变换）收益远小于"少写一条指令"。
- 不做 JIT、不做运行时。
- 不保证跨版本的产物二进制兼容（游戏版本变化会改指令集）。

---

## 4. 核心设计原则

> 这 9 条是后面所有取舍的依据。与直觉冲突的地方都标了理由。

**P1 — 唯一的硬通货是指令条数。**
常规后端优化寄存器压力与延迟；这里变量槽无限且免费，唯一稀缺的是"1000 条"和"每 tick 能执行多少条"。每个 pass 的收益都必须折算成指令条数。

**P2 — 内存槽位是第二稀缺资源，且方向与常规相反。**
常规编译器"把值放进寄存器"是优化；这里把值放进内存块是**用槽位换指令**（且通常换亏）。所以除非为了动态下标、跨处理器共享或逃逸，**默认不把东西搬进内存**。

**P3 — SSA 在这个目标上是免费的。**
变量不限量，SSA 的 φ 不需要寄存器分配来"解决"。因此可以一路保持 SSA 到最后一刻，不要把 out-of-SSA 提前。

**P4 — 变量名是 ABI，不是实现细节。**
跨处理器通信、`read/write @this "名字"` 都以名字为键。命名策略必须与优化解耦，并区分"可重命名"与"已导出"。

**P5 — 副作用默认保序，但 effect 字段从第一天存在。**
effect 是 IR 节点的结构属性，事后补要改所有节点与所有 pass。所以字段先加，优化策略先保守。

**P6 — 标签不花钱，`jump` 花钱。**
基本块布局要最大化 fall-through；不要为了"结构清晰"多插一条 `jump`。

**P7 — 下降必须单调。**
下层不得重新解释上层的语义。MIR 里出现"内存块"、MemIR 里出现"聚合值"、AMIR 里出现"泛型"，都说明分层漏了。

**P8 — 跨处理器是显式的。**
除分区层外，任何层都不应产生通信。通信边的集合是分区层的唯一产物。

**P9 — 每层只做本层能做得最好的事。**
过早下降 = 永久丢失信息。闭包在 MIR 消解、内存在 MemIR 布局、指令在 AMIR 选择，各司其职。

---

## 5. IR 分层总览

### 5.1 总表

**6 个 IR 层 + 3 个横切设施 + 2 个边界（AST / Emit）。**

```
源文件
  │
  ▼  PARSING
[L0 AST] ──────────────── 语法层，名字是字符串
  │  RESOLUTION
  ▼
[L1 HIR] ──────────────── 语义层：脱糖、DefId、具体类型、单态化
  │  MIR_BUILD
  ▼
[L2 MIR] ──────────────── 中端：CFG + SSA + effect，优化主战场
  │  LAYOUT
  ▼
[L3 MemIR] ────────────── 存储层：聚合 → 地址，分配方案落地
  │  ISEL
  ▼
[L4 AMIR] ─────────────── 机器层：mlog 指令 + 无限命名寄存器 + 标签
  │  PARTITION
  ▼
[L5 PIR] ──────────────── 分区层：多处理器切分 + 通信协议
  │  ASM_LAYOUT
  ▼
[L6 ASM IR] ───────────── 汇编层：标签分配，预算校验
  │  EMIT
  ▼
mlog 文本 / 蓝图
```

| 层 | 名称 | 输入 → 输出 | 核心职责 | 关键不变量 | 唯一性（为什么必须独立） |
| --- | --- | --- | --- | --- | --- |
| L0 | AST | 源 → 语法树 | 语法、宏展开宿主、`Span` | 未解析，名字是字符串 | 语法糖的宿主；宏在这里工作 |
| L1 | HIR | AST → 程序 | 脱糖、解析、类型标注、单态化 | 无语法糖、无泛型、名字全 `DefId`、每个表达式有具体类型 | **语言语义的终点线** |
| L2 | MIR | HIR → 函数 | CFG + SSA + effect、优化、调用形态、闭包转换 | SSA、指令带 effect、无目标机概念 | **优化的唯一主战场**（下层已丢失信息） |
| L3 | MemIR | MIR → 程序 + 分配方案 | 聚合 → 显式地址；变量/内存/常量/栈/跳转表布局 | 每个值有唯一存储位置、容量已校验 | **"用内存换指令"的唯一决策点** |
| L4 | AMIR | MemIR → 函数 | 指令选择、寻址、out-of-SSA、命名、peephole | 每条指令 1:1 可打印为一条 mlog | **目标机知识的唯一边界** |
| L5 | PIR | AMIR 全程序 → 分区程序 | 跨处理器切分、通信、共享内存、时间均衡 | 每分区 ≤1000、跨分区只有显式边 | **唯一跨分区的全局视角** |
| L6 | ASM IR | PIR → 指令序列 | 标签分配与校验、fall-through、预算校验、数据段地址 | 与最终文本一一对应；控制流用标签 | **唯一知道最终文本形态的地方** |

**最小可行子集**：L1 + L2 + L4 + L6 + Emit 即可跑通"函数 + 结构体 + 数组（单 bank）"。L3 可以先作为 L4 的一个子阶段存在，L5 最后做。但**目录与类型名一开始就按 6 层划分**，避免后期大搬家。

### 5.2 下降契约（legalization contract）

每一层对下一层的承诺，建议写成测试：

1. **L2 之前不得出现目标机概念**：不许出现 `cell`、`@counter`、`op` 之类的词。MIR 里出现"内存块"即为设计泄漏。
2. **L3 之前不得出现具体地址/槽号**。槽位分配只发生在 L3。
3. **L4 之后不得出现聚合值**。结构体/元组/枚举载荷必须是标量组或内存记录。
4. **L5 之后不得改变单分区语义**。分区层只做"搬运 + 插通信"，不重新优化函数体（否则收益无法归因、bug 无法定位）。
5. **L6 之后不得改变指令顺序**。标签本身不怕重排，但**数据段里的地址**（跳转表）会因重排失配。

---

## 6. 各层详述

### 6.0 L0 — AST（语法层）

- 现有 `Expr`/`Stmt`，全部带 `Span`，未解析。
- **宏与 comptime 在这一层之前或在这一层上工作**（AST → AST），不产生新的 IR 层。
- 一旦进入 L1，宏就应当已经展开完毕。

### 6.1 L1 — HIR（High-level IR，语义层）

**职责**

- 把语法糖全部消掉：`for`/`while`（→ `Loop` + `Break`/`Continue`）、方法调用糖（→ 显式 `self` 实参）、运算符糖、字符串插值、`use` 导入。
- `match` 保留为**原语 `Switch`**（不降成 if 链），因为 L4 需要它来决定是否生成跳转表。
- 名字全部替换为 `DefId`；所有表达式带具体类型。
- **单态化**（泛型 → 具体类型）。
- 产出 whole-program 的 `HirProgram`（不是单文件 `Stmt`）。

**不变量**

- 无泛型、无隐式接收者、无语法糖。
- 类型全部是具体类型（无 `Var`）。
- 每个聚合值（struct/tuple/enum 载荷）都是**一等值**，可以自由复制（logic 是值语义）。

**关键数据结构（示意）**

```kotlin
sealed class HirType {
    object Num : HirType()                       // double
    object Int : HirType()                       // 逻辑整数，底层同为 double（D13）
    object Str : HirType()
    data class Obj(val contentKind: ContentKind) : HirType()
    data class Struct(val defId: DefId, val fields: Seq<HirType>) : HirType()
    data class Enum(val defId: DefId, val tagType: HirType, val payload: HirType?) : HirType()
    data class Tuple(val elements: Seq<HirType>) : HirType()
    data class Func(val params: Seq<HirType>, val ret: HirType, val captures: Seq<HirType>) : HirType()
    data class Array(val element: HirType, val size: Int?) : HirType()   // size = null 表示动态
}
```

**`Int` 的语义（D13 / D18）**：`Int` 与 `Num` 在 HIR 是两个类型，但**底层都是 double**——区分是纯语言层的，不产生运行时表示差异。

已定：

- 整数字面量与浮点字面量在**词法层就已区分**（`TokenType.INT` / `TokenType.NUM`）；
- `Int → Num` 隐式（零成本）；
- **`Num → Int` 用 `floor`**（不是 `round`），必须显式转换，不做静默截断；
- **`Int / Int` 用 `div`，结果类型是 `Num`**——不是 `idiv`、也不是 `Int`，因为除零或溢出时结果是 `null`，`Int` 装不下；
- **溢出与非法结果在运行时变成 `null`**。这是**运行时性质，类型系统不跟踪**。

**对优化的直接约束**：既然 `Num` 在运行时可能是 `null`，就不能做"数学上成立但吃掉 null 情形"的折叠：

- **恒等式全部非法**：`x + 0 → x`、`x * 1 → x`、`x / 1 → x` 都不成立——`x` 为 null 时原式得 `0`，而 `x` 本身是 null，`===` 能看出差别；
- **会引入非有限中间值的化简非法**：`x / x → 1`、`0 / x → 0`（`x = 0` 时结果为 null）；
- 反过来 `x - x → 0`、`x * 0 → 0` 是**合法**的（null 参与也确实得 0）；
- 常量折叠必须按 `div` 的真实语义算，不能先折成代数恒等式。

**`null` 怎么处理（D18 的配套）**：`Num` 在静态类型上**不可为 null**，因为这是运行时性质、类型系统不跟踪。

**根源（源码确认）**：`LVar.setnum` 在写回时检查 `Double.isNaN || isInfinite`，命中就把变量变成 **`null`（对象）**（`LVar.java` 的 `setnum` / `invalid`）。所以：

- **不止 `div`**：`0/0`、`x/0`、浮点溢出（`1e308 * 10`）、`sqrt(-1)`、`log(-1)`——**任何产生非有限 double 的运算，结果都会变成 null**；
- `num()` 读取 null 时返回 **0**，于是 **`null == 0` 为真**（`==` 走数值路径时两边都取 `num()`）。要区分 null 和 0，必须用 **`===`**（`strictEqual` 比较 `isobj` 标志）；
- 因此语言层的判空必须用 `===` 或专门的 `isNull`，`x == 0` 不能当判空。

**语言策略**：

- 不引入 `Num?`（会给所有算式加负担，收益极低），只提供兜底运算 `a ?? fallback`，降到 `select`（1 条指令）；
- 编译器**不做"Num 一定有限"的假设**去优化（见上文的折叠约束）。

```kotlin
class HirFunction(
    val defId: DefId,
    val params: Seq<DefId>,
    val ret: HirType,
    val body: HirBlock,
    val captures: Seq<Capture>,       // 仅在它是闭包体时非空
    val isClosureBody: Boolean,
)

class HirProgram(
    val functions: Seq<HirFunction>,
    val structs: Seq<StructDecl>,
    val enums: Seq<EnumDecl>,
    val links: Seq<LinkDecl>,         // 来自 extern 声明
    val comptimeValues: ObjectMap<String, ConstValue>,
)
```

**退出条件**：`HirProgram` 通过类型检查，`EXHAUSTIVENESS` 通过，宏已展开，泛型已消除。

### 6.2 L2 — MIR（中端）

**职责**

1. **CFG 构建 + SSA 化**：`Loop`/`Switch` → 基本块 + 终结符 + φ。
2. **调用形态决策**（见 8.1）：对每个调用点标记 `INLINE` / `DIRECT` / `FRAME` / `INDIRECT`。
3. **闭包转换**（见 8.2）：`Func` 类型的值 → `(funcId, envRecord)` 的显式表示。
4. **机器无关优化**（在 effect 约束下）：常量折叠与传播、DCE、CSE、LICM、内联、代数化简、死 φ 消除、if 转换。
5. **逃逸分析**：判定哪些聚合值/闭包不逃逸（供 L3 决定存储归属）。

**不变量**

- SSA：每个值只定义一次，使用点有支配关系。
- 每条指令带 `Effects`（见 7.1）。
- **不许出现**：`cell`、`bank`、`@counter`、具体 mlog 操作码、具体地址。
- 内存语义只用抽象原语表达：`Alloc(size)`、`Load(place)`、`Store(place, v)`、`Index(base, i)`。

**关键数据结构（示意）**

```kotlin
class MirFunction(
    val defId: DefId,
    val params: Seq<MirValue>,
    val blocks: Seq<MirBlock>,
    val callForm: CallForm,          // NORMAL / FRAME_BASED
    val sccId: Int,                  // 所属递归 SCC，-1 表示非递归
)

class MirBlock(
    val id: BlockId,
    val phis: Seq<Phi>,
    val insts: Seq<MirInst>,
    val terminator: MirTerm,
)

sealed class MirInst {
    val effects: Effects
    data class Const(...) : MirInst()
    data class BinOp(op: MirOp, a: MirValue, b: MirValue) : MirInst()
    data class Compare(op: CmpOp, a: MirValue, b: MirValue) : MirInst()
    data class Select(cond: MirValue, a: MirValue, b: MirValue) : MirInst()
    data class Alloc(size: MirValue, elem: HirType) : MirInst()      // 抽象分配
    data class Load(place: Place) : MirInst()
    data class Store(place: Place, value: MirValue) : MirInst()
    data class FieldGet(base: MirValue, fieldIndex: Int) : MirInst()
    data class MakeStruct(defId: DefId, operands: Seq[MirValue]) : MirInst()
    data class MakeClosure(funcId: Int, captures: Seq[MirValue]) : MirInst()
    data class Call(target: Callee, args: Seq[MirValue], form: CallForm) : MirInst()
}

sealed class MirTerm {
    data class Goto(target: BlockId) : MirTerm()
    data class Branch(cond: MirValue, ifTrue: BlockId, ifFalse: BlockId) : MirTerm()
    data class Switch(scrutinee: MirValue, cases: Seq<Pair<Long, BlockId>>, default: BlockId) : MirTerm()
    data class Return(value: MirValue?) : MirTerm()
    object Unreachable : MirTerm()
}
```

**为什么必须独立**：这是唯一还保留"语言级信息"（聚合值、闭包、类型）且同时具备 CFG 的层。再往下聚合被拆散、闭包被消解，优化就做不了。

### 6.3 L3 — MemIR（存储布局层）

**职责：把"值"变成"位置"。** 这是本文档里最特殊的一层，因为目标机的存储层级与常规机器完全不同（1.3 节）。

1. **聚合值降级**：
   - 小结构体/元组 → **SROA 标量化**（每个字段一个 logic 变量）。判据：字段数 × 访问频次 + 是否被取址 + 是否进数组。
   - 大聚合 / 需要动态下标 / 跨处理器共享 → **装进内存块**，值变成"基址指针"。
   - 枚举 → `(tag, payload...)` 的标量组（tag 是数字；带载荷的变体需要固定布局）。
2. **数组与 Map 布局**：`Array<T>` → 单 cell（≤64）/ 单 bank（≤512）/ 多 bank 拼接；`Map<K,V>` → 具体表示（待定）。
3. **闭包环境布局**：记录 = `[funcId, capture0, capture1, ...]`，放静态记录（不逃逸）或动态分配（逃逸）。
4. **栈帧布局**：为每个递归 SCC 计算帧大小与偏移（见 8.1）。
5. **常量池 / 字符串表 / 跳转表**：决定放变量、放内存块还是内联。
6. **变量表生成**：给每个 SSA 值分配变量名，并标注 `kind`（`TEMP` / `USER` / `ABI_EXPORTED` / `LINK`）。

**核心产出：`AllocPlan`（必须可 dump）**

```kotlin
class AllocPlan(
    val variables: VariableTable,                 // 名字 → kind / 来源 DefId / 是否导出
    val cellChains: Seq<CellChain>,               // 多 bank 拼接的连续空间
    val aggregates: ObjectMap<DefId, StorageKind>,// SCALAR / CELL / BANK_CHAIN / REMOTE
    val constPool: ConstPool,                     // 常量 → 槽位或内联
    val stringTable: StringTable,
    val jumpTables: Seq<JumpTable>,               // 含 L6 才能解析的重定位项
    val frames: Seq<FrameLayout>,                 // 每个递归 SCC 一份
    val stackRegion: Region?,                     // 共享栈区（所有递归 SCC 共用）
    val exports: Seq<ExportedSymbol>,             // 跨处理器 ABI
)
```

**关键算法**

- **SROA**：结构体标量化，注意"是否被取址/进数组/跨处理器"三个逃逸条件。
- **槽位装箱**：多 bank 拼接的容量计算、对齐、碎片。
- **生命周期/区域分析**：决定闭包环境与临时数组能否复用同一块槽位。
- **静态容量校验**：每个 `Array` 的长度 × 元素大小是否放得下声明的内存；不足则编译期报错。

**不变量**

- 每个值有唯一存储位置。
- 所有容量已校验，超过声明的外部内存即报错。
- 不许出现"聚合值"这种抽象：要么是标量组，要么是地址。
- 不许出现具体 mlog 指令。

**为什么必须独立**：`AllocPlan` 是**全局的**（一个数组跨多少 bank、哪个闭包环境复用哪块），而指令选择是**逐函数**的。混在一起会让"换一种布局"变成"重写整个后端"，而且无法做"同一 MIR 的多套布局对比"。

### 6.4 L4 — AMIR（抽象机 IR）

**职责**：把 MemIR 降到一台"理想化的 mlog 机器"上——无限命名寄存器、定长内存、标签、具体操作码。

1. **指令选择**：MIR 算子 → mlog 指令（`op` 系列多数 1:1；`Switch` → 比较链或跳转表；数组访问 → 显式地址计算 + `read`/`write`）。
2. **寻址**：`cell` 没有"基址 + 偏移"寻址，`arr[i]` 必须显式算地址。**常量下标可以省掉乘法**，这是 L3 必须把"索引是否编译期常量"传下来的原因。
3. **out-of-SSA**：φ → move，消解循环中的并行复制。
4. **命名策略**：给变量起最终名字，区分可重命名与 `ABI_EXPORTED`。
5. **peephole**：模式匹配级优化（`set a b` 后紧跟只用一次的 `a` → 直接传播；`op mul x y 1` → 删除）。
6. **fall-through 布局**：重排基本块以消除不必要的 `jump`（P6）。

**不变量**

- 每条 AMIR 指令可 1:1 打印成一条 mlog 文本行。
- 控制流用标签表示，行号未知。
- 无聚合值、无泛型、无闭包抽象。
- 允许存在**重定位项**（`SymbolRef`），供 L6 解析（仅跳转表使用）。

**为什么必须独立**：这是目标机知识的**唯一边界**。所有"mlog 长什么样"的知识收敛在这里，前端与中端完全不需要知道。

### 6.5 L5 — PIR（分区层）

**职责**：因为选择了"自动拆分到多处理器"，这层必须存在，且是**唯一跨分区**的层。

1. **分区**：把 AMIR 函数/基本块分配到多个处理器。
2. **通信插入**：在跨分区调用/共享访问处插入协议（见 8.4）。
3. **共享内存分配**：决定哪些数据放共享 bank、哪些走变量名协议。
4. **时间均衡**：目标函数是 `max(指令数_i / ipt_i)`，不是指令数均分。
5. **同步**：显式屏障/握手，绝不假设共享内存一致。
6. **预算校验**：每个分区 ≤ 1000。

**不变量**

- 每个分区 ≤ 1000 条指令。
- 分区之间只有显式通信边。
- 处理器私有状态（`@unit`、`ubind` 计数器、缓冲区）**不跨分区共享**；被搬走的函数若依赖它们，必须在协议里显式传递。

**为什么必须独立**：分区是全局优化（换一种切法，所有分区的指令数都变），与单函数后端正交。混进 L4 会让"同一函数在不同分区下有不同的代码"变成不可维护的状态。

### 6.6 L6 — ASM IR（线性汇编）

**职责**：把控制流固定成文本，把预算算准。**不做标签换行号**——这一步交给游戏。

1. **标签分配与校验**：生成标签名；查重；查"纯数字"（会被误当成行号）；查 `maxJumps = 500` 上限；查**踩空**（标签后必须至少跟一条指令，否则解析成 `length`）。
2. **数据段地址（唯一的数字来源）**：只有跳转表需要数字，且由编译器在发射时自己算（三个方案见 8.2）。控制流本身从不出现数字目标。
3. **预算校验**：`≤ maxInstructions`（1000）。超限必须报出**根因**（哪个函数、哪次内联、哪个数组访问展开贡献了多少条），而不是只报一个数字。
4. **变量名 mangling 落地**：处理与 `@` 环境变量、`@link` 名、关键字的碰撞。
5. **fall-through 的最终确认**：基本块顺序在写文本前定死。

**不变量**

- 与最终文本一一对应。
- **控制流目标一律是标签**，不是数字；只有数据段可能出现数字地址。
- 每条指令的 `Effects` 保留（供调试与后续验证）。

### 6.7 L7 — Emit

- 输出 mlog 文本（一行一条，`#` 注释可选，导入游戏后注释会被清除）。
- 输出 **source map**：`mlog 行 → 源 Span`（见 7.3）。
- 可选输出：分层 dump（`-dump=hir,mir,mem,amir,pir,asm`）、预算报告、分配报告。
- 不做任何决策，只做格式化。

---

## 7. 横切设施

### 7.1 Effect 模型

挂在 `MirInst` 上，一路保留到 L6。

```kotlin
class Effects(
    val reads: EffectSet,      // 读了什么资源
    val writes: EffectSet,     // 写了什么资源
    val kind: EffectKind,      // PURE / VOLATILE / BUFFER / WORLD / CONTROL / HIDDEN
    val orderSensitive: Boolean // 是否对相邻同类指令的顺序敏感（draw/print）
)
```

资源枚举（对应 1.5 节的 7 类）：

```
COUNTER, UNIT, VARIABLE(name), MEMORY(bankId), ENTITY, GLOBAL_TAG,
GRAPHICS_BUFFER, TEXT_BUFFER, RADAR_CACHE, BIND_COUNTER, WAIT_TIMER, NETWORK
```

**两条策略（D10 已定为 A：保序）**

| 策略 | 判定方式 | 收益 | 风险 |
| --- | --- | --- | --- |
| **A. 严格保序**（选定） | pass 顺序遍历，遇非 `PURE` 即切断 CSE/DCE 窗口 | 实现半天，拿到 ~80% 收益 | 无法跨副作用做 LICM |
| **B. Effect 依赖图** | 按 `(reads, writes)` 判可交换性 | 跨副作用重排/合并 | 漏一个隐藏状态就产生静默错误 |

**已定的落地方式（Q2 = 保序）**：IR 第一天就带 `Effects` 字段（结构属性，事后补代价极高），但优化只开策略 A；策略 B 不排期，只保留接口。下列 4 个回归用例在写 effect 分类表时就要建立：

1. 连续两次 `ubind @mono` 不被合并（否则批量控制退化成单控）。
2. `wait 0.1` 不被 DCE（否则节奏错乱）。
3. 循环内 `sensor @time` 不被 LICM 提出（volatile）。
4. `draw color` 与 `draw rect` 不被重排（否则画错）。

### 7.2 代价账本（Cost Ledger）

常规编译器不在每层维护指令数，因为指令数由后端决定。但这里**内联改指令数、SROA 改指令数、内存 vs 变量的选择改指令数、分区改指令数**，所以必须贯穿。

```kotlin
class Budget(
    val limit: Int = 1000,
    var current: Int = 0,
    val history: Seq<BudgetEntry>,   // 每个 pass 的 before/after + 归因
)
```

- 挂在 `CompilerContext` 上，每个 pass 报告 `before/after`。
- 超限时能回答："`inline(f)` +180 条，`layout(A)` +40 条，`partition` +12 条通信"。
- 现有的 `Compiler.PhaseTimer` 是天然的挂载点（时间 + 条数一起报）。

### 7.3 Source Map 与诊断

现有 `SourceMap` / `Span` 解决"源文件 ↔ AST"，还缺"**生成行 ↔ 源 Span**"。

- 在 L6 生成：每条 ASM 指令记录其来源 `Span`（合并自 L1 一路传下来的来源信息）。
- 用途：1000 超限定位、运行时报错定位、调试器（未来的 LSP/编辑器预览）。
- 要求：**注释不占指令**，所以行号映射不能依赖注释。

### 7.4 命名与 ABI

**mangling 规则（建议）**

| 类别 | 前缀 | 可重命名 | 例 |
| --- | --- | --- | --- |
| 用户变量 | 原名 | 否（保可读性） | `hp` |
| 编译器临时 | `__t` | 是 | `__t17` |
| 闭包环境 | `__e` | 是 | `__e3` |
| 递归帧指针 | `__sp` | 否（ABI） | `__sp` |
| 跨处理器导出 | `__x` | **否** | `__x_done` |
| 外部链接 | 玩家名 | **否** | `conveyor1` |
| 标签 | `__L` | 是（但**不能是纯数字**） | `__L12` |

规则：

1. 所有编译器生成名以 `__` 开头，最小化与用户名的碰撞。
2. L6 必须做**碰撞检测**：若用户变量名与链接名同名、或与 `__` 前缀名冲突，报错而不是静默覆盖。
3. `ABI_EXPORTED` 的变量**不得被 out-of-SSA 合并或复制传播消掉**（它们的名字被别的处理器引用）。
4. 绝不生成 `set @time ...` 这类写只读常量的指令（会被静默忽略）。

---

## 8. 关键子系统

### 8.0 三套语法域（先定边界，避免歧义）

| 语法 | 作用域 | 何时求值 | 备注 |
| --- | --- | --- | --- |
| `:` | **类型**（`x: Num`） | 编译期 | 现有 `Expr.Annotation` 就是它，是**类型注解**，不是装饰器 |
| `@` | **游戏内容 / 环境变量**（`@counter`、`@copper`） | **运行期** | Lexer 目前还没有 `@` token；一旦引入就归这个域 |
| `pragma` | **编译期指令**（栈深度、目标 profile） | 编译期 | 新增关键字，只影响代码生成 |
| `#` | 注释 | — | Lexer 已占用 |

**`@` 不能再被复用为编译期指令**，理由有两条：

1. 它必须是运行期取值（`@counter` 读出来是活的数字），而 `@depth(200)` 是编译期常量——同一 sigil 承载两种求值时机，读代码的人无法分辨。
2. 这个项目已经踩过一次同类坑：颜色字面量之所以写成 `0%RRGGBB` 而不是 `%RRGGBB`，就是因为裸 `%` 被取模占了。**不要重复这个错误。**

详见 8.7。

### 8.1 调用协议与递归（决策 D1：C 方案）

**三种协议**

| 协议 | 调用点开销 | 函数体开销 | 可递归 | 适用 |
| --- | --- | --- | --- | --- |
| `INLINE` | 0 | 函数体 × 调用点数 | — | 小函数、单调用点、热路径 |
| `DIRECT`（无栈） | 2 条（`op add __ra_k @counter 1` + `jump f`；返回 `set @counter __ra_k`） | 0 膨胀 | ✗ | 非递归函数 |
| `FRAME`（栈帧） | ~3 + n 条 | **×3~5 膨胀** | ✓ | 递归 SCC 内的函数 |
| `INDIRECT`（闭包） | ~5–6 条（读 funcId + 查跳转表 + 保存返回地址 + `set @counter`） | 0 膨胀 | 取决于目标 | 闭包调用 |

**返回地址怎么来（关键细节）**：`set` 的源操作数**不是标签**——只有 `jump` 的目标可以是标签。所以 `set __ra_k ret_k` 是**无效写法**（`ret_k` 会被当成一个变量名读，值是 0）。正确做法是靠 `@counter` 自定位：

```
i:    op add __ra_k @counter 1     # 执行期 @counter 已 = i+1，所以 __ra_k = i+2
i+1:  jump f                        # 进函数体
i+2:  <返回点>                      # 函数体末尾用 set @counter __ra_k 回到这里
```

`@counter` 是 `putVar` 注册的**普通变量**（与执行器内部 counter 是同一个 LVar），所以能当 `op` 的操作数读活值。这个技巧让调用协议**完全不依赖行号**，也对手工编辑 mlog 免疫。代价是每个调用点需要一个独立的 `__ra_k`（共享一个会在嵌套调用时被覆盖）。

**为什么 FRAME 是 ×3~5 而不是 +几条**：logic 没有寄存器保存/恢复。递归重入会覆盖同名变量，所以帧内**所有局部变量与临时值都必须放到内存块**，每次访问变成"算地址 + `read`/`write`"。一条 `op add` 会变成 4–6 条。一个 30 条的函数会变成 90–150 条。**在 1000 条上限下这是决定性的**，所以绝不能让所有函数为"可能存在"的递归买单。

**SCC 判定**

1. 在 L2 构建调用图（含闭包调用边，保守连到所有可能的被调者）。
2. Tarjan 求 SCC；`size > 1` 或存在自环的 SCC = **递归 SCC**。
3. 递归 SCC 内的函数标记 `FRAME_BASED`；其余 `DIRECT` 或 `INLINE`。
4. 跨 SCC 的调用：调用者若在 `FRAME` 里，自己已在内存中，安全；调用者若在 `DIRECT` 里，它的变量是全局命名的，被调者不会碰它们（被调者只用帧 + 自己的唯一命名临时），所以也安全。**唯一共享的寄存器是 `__sp`**——返回地址按调用点分开（见上），所以不会互相覆盖。

**帧布局**

```
slot 0        : 返回地址
slot 1..n     : 参数
slot n+1..    : 局部变量与临时值（SSA 值一一对应）
```

**共享栈区**：所有递归 SCC 共用一个 `__sp` 与一段连续栈区（而不是每个 SCC 各一块），避免碎片、简化 sp 管理。代价是不同帧大小混排，但地址计算仍是 `__sp + offset`。

**深度与外部内存（决策 D12）**

开发者声明需要的递归深度（语法见 8.7，当前建议 `pragma stackDepth = 200`），编译器反推需要多少外部内存：

```
帧大小 F      = 1（返回地址） + 参数数 + 局部/临时槽数（按 SSA 值数对齐）
所需槽数 S    = D × F
所需 bank 数  = ceil(S / 512)
所需 cell 数  = ceil(S / 64)
```

反向也支持：开发者声明 `stack = 2 bank`，编译器算出 `D_max = floor(1024 / F)` 并**在编译期报告给用户**。

- 默认行为：**静态计算 + 编译期告警**（不插运行时检查）。
- 可选：`pragma stackDepthChecked = true` → 插 2 条运行时检查（`op greaterThanEq __t __sp __limit` + `jump overflow`），换"不会静默踩坏内存"。
- 若声明的内存不足 → **编译期报错**，并给出"需要 X 个 bank / 你声明了 Y 个"的明确信息。

**尾调用**：`DIRECT` 协议下尾调用只需 `jump`，**天然支持无限深尾递归**，不需要占栈。所以"深度"这个问题对尾递归不存在——这也是 C 方案的一个免费红利。

### 8.2 闭包（决策 D2：B 默认 + A 优化）

**表示**

闭包值 = **内存块中的一条记录**，逻辑变量持有它的基址：

```
record := [funcId, env0, env1, ...]
```

为什么是"记录 + 指针"而不是"两个逻辑值"：logic 变量只能存一个值，`(funcId, env)` 二元组要么打包成数字（脆弱），要么进内存。选后者。

**创建**

| 场景 | 成本 | 说明 |
| --- | --- | --- |
| 不逃逸 + 循环外（`A` 优化） | 0 | 内联/拆解，完全消失 |
| 不逃逸 + 循环内 | n+1 | 用**静态记录**（每个创建点固定一块），零分配 |
| 逃逸 | n+2~n+3 | 动态分配一条记录，需要区域/生命周期规则 |

**捕获语义（必须定死）**：**按值快照**。logic 变量是值语义，闭包创建时把捕获值拷进记录。需要引用语义时，必须**显式捕获内存块基址**（两种不同的捕获语法），而不是靠隐式规则——否则用户永远猜不到闭包看到的是哪个时间点的值。

**调用**

1. 从记录读 `funcId`（1 条）。
2. 取函数入口地址：查表（2–3 条）或走分派链（见下）。
3. 保存返回地址 + 跳过去（2 条）。

**间接调用怎么拿到"地址"（重要分层细节）**

`jump` 支持标签，但 `set @counter` 只吃**数字**——所以间接调用必须有办法把 `funcId` 变成可跳转的目标。三种做法：

| 方案 | 一次性成本 | 每次调用 | 手工改 mlog 后仍正确 | 备注 |
| --- | --- | --- | --- | --- |
| **A. 发射期算地址**（默认） | 0 | 查表 2–3 + 跳转 2 | ✗ 表项会失配 | 只有数据段用数字，控制流仍是标签；最简单最省 |
| **B. 共享分派链** | 2N 条（N = 闭包数） | 2 条（跳分派器） | ✓ | 全标签、零数字；N 大时初始化段膨胀 |
| **C. 运行时自建表** | 2N+3 条 | 同 A | ✓ 自校准 | 启动时用 `@counter` 记录各函数入口；注意执行期 `@counter` 已指向**下一条**，要减 1 |

**默认选 A**：编译器在发射时本来就知道每个标签的最终行号，没必要让运行时再算一遍。只有"生成物会被手工编辑"的诉求变强时，才值得为 B/C 付初始化成本。

> 选 A 后，**跳转表项是唯一需要"重定位"的东西**：L3/L4 里它们是符号引用，L6 发射时算成行号。这也正是 6.6 只保留"数据段地址"这一项数字工作的原因。

**跨处理器限制（v1）**：闭包对象**禁止跨分区**。因为 `funcId` 是各处理器自己编译期的编号，跨处理器不通用。若将来要支持，需要在分区时建立"跨处理器函数编号表"，成本高、收益低。

### 8.3 存储分配与布局（决策 D6）

**语言暴露 `Array` / `Map`，编译器映射到 cell/bank。**

| 类型 | 大小 | 存储 |
| --- | --- | --- |
| `Array<T>`，长度 ≤ 64 | 小 | 单 Cell |
| 长度 ≤ 512 | 中 | 单 Bank |
| 长度 > 512 | 大 | **多 bank 拼接**（容量 = bank 数 × 512） |
| 长度是编译期常量且很小 | 极小 | **标量化成一组变量**（零地址计算） |
| `Map<K,V>` | 待定 | 待定（开放寻址 / 线性扫描） |

**多 bank 拼接**：`bankChain = [b0, b1, ...]`，`read(bankChain, i)` 需要先算 `bankIndex = i / 512`、`offset = i % 512`，再用分支选择 bank。**每次动态下标访问多 2–4 条指令**，所以布局层要把"索引是否常量"传下去，常量下标直接落到具体 bank、零额外开销。

**SROA vs 装箱的判据**（按优先级）：

1. 被取址 / 进数组 / 跨处理器 → 必须装箱。
2. 字段数 ≤ 阈值（建议 4）且访问次数 > 1 → 标量化。
3. 字段数多或只在构造/解构处整体使用 → 装箱。

**方向性提醒（P2）**：把值搬进内存**省的是变量（本来免费），费的是指令**。所以默认不搬。这个判断会决定 90% 的程序能否塞进 1000 条。

### 8.4 分区与通信（决策 D4）

**输入**：AMIR 全程序、`target profile` 列表（来自 `extern` 声明）、调用图、链接拓扑。

**目标函数**：`min max_i(指令数_i / ipt_i)`，约束 `指令数_i ≤ 1000`，加上通信成本。

**为什么不能按指令数均分**：`ipt` 从 2 到 1000 差 500 倍。500 条指令放 `MICRO` 要 250 tick，放 `WORLD` 只要 0.5 tick。不均衡直接变成玩家可见的帧差 bug。

**算法**

1. 以调用图 SCC 为原子单位（一个函数和它必然同置的被调者不可拆）。
2. 贪心/多路划分（Kernighan-Lin 变体），权重 = `指令数 / ipt`，割边成本 = 通信指令数。
3. 后处理：合并"通信成本 > 拆分收益"的分区（**能不分就不分**）。
4. 校验：每分区 ≤ 1000；特权指令只在 `WORLD`。

**四种通信介质**

| 介质 | 成本 | 延迟 | 适合 |
| --- | --- | --- | --- |
| **共享 cell/bank**（两处理器链接同一建筑） | 1–2 条 `read`/`write` | 同 tick（看执行顺序） | 大数据、数组、共享状态 —— **首选** |
| `read/write @this "名字"` | 2–3 条 | 1 tick 帧差 | 少量标量、控制信号、对象引用 |
| 全局 flag（仅 `WORLD`） | 1–2 条 | 同 tick | `WORLD` 之间的轻量信号 |
| 单位 `flag` | 1–2 条 | 长期 | 单位级元数据 |

**远程调用协议（粗略）**

```
调用方：写参数到共享内存/对方变量 → 置 request=1 → 轮询 done
被调方：轮询 request → 读参数 → 执行 → 写结果 → 置 done=1 → request=0
```

一次远程调用约 6–10 条指令 + 若干 tick 延迟。**所以只能粗粒度切分**（调用整段例程，不能每次循环迭代都远程调用）。这反过来约束了分区算法：只在"粗边界"（函数调用级）切开。

### 8.5 外部链接声明（决策 D3：`extern` 关键字）

`use` 保持只做模块导入（`use a.b`、`use a.*`、`use a.**`、`use a.{x,y}`）。外部链接改用**独立关键字 `extern`**（Q9 已定）：

```mlx
use a.b.*                           # 模块导入

extern conveyor1                    # 外部链接：按玩家起的名字引用
extern bank1: Bank                  # 外部链接：内存建筑（容量运行时可读）
extern cpu2: Processor<Hyper>       # 外部链接：参与分区的处理器
extern display1: Display            # 外部链接：显示屏
```

规则：

1. `extern X` / `extern X: Type` 中的 `X` 是**玩家在游戏里给建筑起的名字**，编译期作为不透明符号，运行期由游戏解析成 `@link` 名字。
2. 名字**不可重命名、不可优化掉**；与用户变量名同在一个命名空间且**链接优先**（见 1.7），所以必须做碰撞检测（见 7.4）。
3. `Processor<T>` 声明参与分区；未声明的处理器不参与。
4. **容量不在编译期写死**：`Bank`/`Cell` 只是"这是个内存建筑"的类型约束，真实容量在运行期用 `sensor mem @memoryCapacity` 读取（默认 32，cell 64 / bank 512 / world-cell 512）。L3 的静态校验按"最大可能容量"给**警告**，运行期再做兜底检查。
5. 未声明的链接在代码中被引用 → **编译期报错**（而不是运行期静默变 `null`）。
6. `use` 与 `extern` 不会解析冲突：前者是路径形式（`.`/`*`/`{}`），后者是"标识符 + 可选类型注解"。

### 8.6 栈深度与外部内存估算（决策 D12）

这是 D1 与 D6 的交汇点，值得单独列。

**用户视角**

```mlx
extern bank1: Bank
extern bank2: Bank
pragma stackDepth = 200   # 我预计最深递归 200 层
```

**编译器输出**

```
栈帧：f 需要 4 槽，g 需要 6 槽，共享栈区最大帧 6 槽
1000 槽可用（2 bank）→ 最大深度 = 1000 / 6 = 166
⚠️ 声明的深度 200 超过可用容量 166，需要 3 个 bank
```

**反向用法**：只声明内存、不声明深度 → 编译器算出 `D_max` 并**作为一条编译期信息报告**（不是错误，除非开了 `pragma stackDepthChecked`）。

### 8.7 编译期指令（`pragma`）

**问题**：`@depth(200)` 这类写法不能用——`@` 必须留给运行期的游戏内容与环境变量（见 8.0）。可选方案：

| 方案 | 例 | 问题 |
| --- | --- | --- |
| `@depth(200)` | 拟作属性 | **`@` 与游戏内容冲突**，且编译期/运行期混在一个 sigil 里 |
| `#[depth(200)]` | Rust 风格 | **`#` 是行注释**，词法层就死了 |
| `set depth 200` | 复用关键字 | `set` 已是赋值语句关键字（`Stmt.SetVar`） |
| 只给 CLI / 配置文件 | `--stack-depth 200` | 丢失"配置随代码走"的能力，评审时看不见 |
| **`pragma name = value`** | 新关键字 | 无冲突；可扩展；可 grep |

**建议（待确认，见 11.2 R4）**：新增一个顶层语句 `pragma`。

```mlx
pragma stackDepth = 200          # 递归深度
pragma stackDepthChecked = true  # 是否插运行时溢出检查
pragma target = WORLD            # 目标 profile（可被 CLI 覆盖）
```

规则：

1. **文件级、与位置无关**（`RESOLUTION` 阶段收集），不参与作用域链。
2. 值必须是编译期常量（字面量或常量表达式），不接受变量。
3. **未知 pragma 名 → 报错**并给候选名（不要静默忽略，logic 的静默失败已经够多了）。
4. 同名重复 → 后出现者覆盖，并给 warning。
5. 只影响**代码生成**，不影响语义；因此可以安全地被 CLI 覆盖。
6. **v1 只做全局 pragma，不做声明级属性**（`#[inline]` 之类）。等到确实需要逐函数调优时再设计，避免现在就冻结属性语法。

**与 `Expr.Annotation` 的关系**：`Expr.Annotation` 是**类型注解**（`x: Num` 里的 `: Num`，以及 `x: T?` / `x: A | B` 的联合与可空），属于 8.0 表里的"类型"域，与 `pragma` 无关。文档里不再用"注解"一词指代编译期指令。

---

## 9. 与现有代码的接缝（决策 D8）

### 9.1 PassId 顺序

```
PARSING
  → [MACRO_EXPAND / COMPTIME]        # 待定，AST 级
  → RESOLUTION                        # 现状：AST 上做
  → DESUGAR            (重做)         # AST → HirProgram
  → TYPE_INFERENCE     (迁移)         # 改为在 HIR 上跑
  → EXHAUSTIVENESS     (迁移)         # 在 HIR 上跑
  → MONOMORPHIZE       (新)           # 泛型消解
  → MIR_BUILD          (新)           # CFG + SSA + effect
  → MIR_OPT            (新)           # 优化流水
  → DATAFLOW / BORROWCK (迁移)        # 从 HIR 挪到 MIR（需要 CFG）
  → LAYOUT             (新)           # MemIR 构建 + AllocPlan
  → ISEL               (新)           # AMIR
  → AMIR_OPT           (新)           # peephole + fall-through
  → PARTITION          (新)           # PIR
  → ASM_LAYOUT         (新)           # L6
  → EMIT               (新)
```

**注意现有顺序的问题**：现在的 `PassId` 里 `DATAFLOW` 在 `EXHAUSTIVENESS` 之后但在同层。确定性赋值与借用检查都**需要 CFG**，应该挪到 MIR 上；`EXHAUSTIVENESS` 只需要类型信息，留在 HIR。

### 9.2 目录划分

```
src/compiler/
├── ast/                    # 现状保留（语法层）
├── core/                   # 现状保留（token/type/symbol/span/pass）
├── ir/
│   ├── hir/                # L1 HirProgram / HirFunction / HirType
│   ├── mir/                # L2 MirFunction / MirBlock / MirInst / Effects
│   ├── mem/                # L3 MemProgram / AllocPlan / CellChain / FrameLayout
│   ├── amir/               # L4 AmirFunction / AmirInst / Label / SymbolRef
│   ├── pir/                # L5 PartitionPlan / ProcessorUnit / CommEdge
│   └── asm/                # L6 AsmUnit / AsmInstr / LineMap
├── passes/                 # 各 pass（现有沿用，新增按层命名）
├── emit/                   # L7 文本输出 + source map
└── pipeline/               # 现有 Pipeline / PhaseTimer
```

**IR 是数据结构，pass 是变换**，两者分目录（现有 `ast/` 与 `passes/` 的分法正确，继续沿用）。

### 9.3 Pipeline 升级

现状：`pipeline.run(sourceFile, context)` → `Stmt`，按文件循环。

问题：L2 之后需要**全程序视角**（调用图、SCC、跨文件内联、分区）。

建议：

```kotlin
// 阶段 1：逐文件
val units: Seq<HirProgram> = files.map { parseAndDesugar(it) }

// 阶段 2：合并为全程序
val program: HirProgram = merge(units)

// 阶段 3：全程序下降
val mir: MirProgram = mirBuild(program)
...
```

`Pipeline<I, O>` 的 `then` 已经是泛型的，能承载任意类型变化；真正要改的是 `Compiler.compile()` 的"按文件循环"结构。

### 9.4 配置项扩展

现有 `CompilerConfig(mode, optimize, target)` 需要扩展：

```kotlin
data class CompilerConfig(
    val mode: CompilationMode = CompilationMode.ALL,
    val optimize: Int = 0,
    val profile: TargetProfile = TargetProfile.LOGIC,   // 默认目标
    val stackDepth: Int? = null,                        // pragma stackDepth 的等价物
    val maxInstructions: Int = 1000,
    val autoPartition: Boolean = true,
    val budgetReport: Boolean = false,
    val dumpLayers: Set<LayerKind> = emptySet(),        // hir/mir/mem/amir/pir/asm
    val emitSourceMap: Boolean = true,
)
```

---

## 10. 里程碑

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **M0** | HIR 定义 + AST→HIR + 类型推断迁移 | 现有测试全绿，`-dump=hir` 可读 |
| **M1** | MIR 构建（CFG/SSA）+ ISEL（`op`/`jump`/`set`）+ Emit | 纯函数程序（无聚合、无闭包）产出可运行 mlog |
| **M2** | MemIR：结构体 SROA、数组→单 bank、常量池、预算账本、source map | 超限能定位到函数；结构体程序正确 |
| **M3** | 调用协议 `DIRECT` + 尾调用 + 闭包 B（非逃逸静态记录） | 高阶函数、闭包传参正确 |
| **M4** | 递归 SCC + 栈帧 + 深度/外部内存估算 | 递归程序正确；深度报告准确 |
| **M5** | 多 bank 拼接、逃逸闭包、生命周期 | 大数组、逃逸闭包不泄漏槽位 |
| **M6** | PIR 分区 + 通信 | 两处理器协同程序正确；时间均衡 |
| **M7** | 宏 / comptime | 待定 |

**建议的并行度**：M0–M1 单人主线；M2 的 source map 与预算账本可以独立开发（不依赖 M1 完成）。

---

## 11. 决策与待定

### 11.1 已定（本轮）

| # | 问题 | 结论 |
| --- | --- | --- |
| Q1 | 宏模型 | 先只做 comptime（覆盖"编译期运算"需求），语法宏后置 |
| Q2 | effect 优化策略 | **保序**，不做依赖图重排 |
| Q3 | `Map` 表示 | 先只支持小规模、键为数字，线性扫描 |
| Q4 | 字符串操作集 | 先只支持 `print`/比较/拼接（拼接机制待细化） |
| Q5 | 整数 vs 浮点 | 语言层**区分** `Int` 与 `Num`，底层都是 double |
| Q6 | 分区是否 v1 就做 | 后置到 M6，但 `AllocPlan`/`Budget` 预留字段 |
| Q7 | 内联阈值 | 先设"函数体 ≤ 20 条且调用点 ≤ 3"，需实测调整 |
| Q8 | 命名冲突 | 报错，不自动改名 |
| Q9 | 外部链接语法 | 独立关键字 `extern`，不复用 `use` |
| R1 | `Int` 的除法/溢出/转换 | `Int / Int` → `div`，返回 `Num`；溢出/除零运行时为 `null`（不跟踪）；`Num → Int` 用 `floor` |
| R2 | 字符串拼接 | **仅编译期常量拼接**——`@textBuffer` 不可读，运行期没有拼接能力 |

### 11.2 仍待定

| # | 问题 | 为什么还没定 | 阻塞谁 |
| --- | --- | --- | --- |
| R3 | 宏的卫生性与跨文件能力 | comptime 先做，但宏能否生成新标识符、能否跨文件 | L1 之前 |
| R4 | 编译期指令的语法形态 | 已排除 `@`（游戏内容）与 `#`（注释）；建议顶层 `pragma name = value`，待你拍板 | L1 |
| R5 | 分区层是否建模"客户端差异" | 1.6 已明确逻辑不同步；但分区后的跨处理器通信是否要区分客户端，未定 | L5 |

---

## 12. 术语表

| 术语 | 含义 |
| --- | --- |
| **profile** | 目标处理器等级（`MICRO`/`LOGIC`/`HYPER`/`WORLD`），决定 `@ipt` 与可用指令 |
| **ipt** | instructions per tick，每 tick 可执行的指令数 |
| **SCC** | 强连通分量（调用图上的），用于识别递归 |
| **帧（frame）** | 递归函数在内存块中的一块连续槽位 |
| **静态记录** | 不逃逸闭包在编译期为每个创建点固定分配的一块记录，零运行时分配 |
| **跳转表** | 存放函数入口地址的内存数组，用于间接调用；地址是 L6 的重定位项 |
| **重定位（relocation）** | 把数据段里的符号解析成最终行号；只用于跳转表，控制流本身不重定位 |
| **踩空** | 跳转目标指向 `length`（末尾裸标签），`@counter` 越界被重置 |
| **预算账本** | 贯穿各层的指令条数计数器 |
| **下降（lowering）** | 从高抽象层变换到低抽象层 |
| **合法化（legalization）** | 把下层不支持的构造改写成下层支持的构造 |
| **逃逸（escape）** | 值超出了它的创建作用域（存进变量、进数组、跨 tick、跨处理器） |

---

## 附录 A：指令效果分类表

> 用于 L2 的 `Effects` 初值。`P` = Pure，`R` = Read，`W` = Write，`B` = Buffer append，`F` = Flush，`C` = Control，`H` = Hidden state。

| 指令 | 分类 | 备注 |
| --- | --- | --- |
| `op` / `select` / `lookup` / `packcolor` / `unpackcolor` / `noop` | P | `noop` 仍占 1 条 |
| `set` | P | 目标为 `@counter` 时改判 `C`；目标为导出变量时加 `W(VARIABLE)` |
| `jump` / `end` / `stop` | C | |
| `wait` | C + H(WAIT_TIMER) | 不可 DCE |
| `read` / `write`（cell/bank） | R/W(MEMORY) | 越界：读 `null`、写忽略 |
| `read` / `write`（`@this` 变量名） | R/W(VARIABLE) | 字符串键，跨处理器 |
| `sensor` | R(ENTITY) | `@time`/`@tick`/`@counter` 为 volatile；读不到时返回 NaN，写回变量后变 null |
| `radar` / `uradar` / `ulocate` | R(ENTITY) + H(RADAR_CACHE) | 30 tick 缓存，指令实例级 |
| `ubind` | W(UNIT) + H(BIND_COUNTER) | **绝不 CSE** |
| `ucontrol` * | W(ENTITY) | 同时刷新控制计时器 |
| `getlink` | R(LINKS) | |
| `control` / `setprop` / `status` | W(ENTITY) | |
| `draw` * | B(GRAPHICS_BUFFER) | 顺序敏感 |
| `drawflush` | F(GRAPHICS_BUFFER) + W(ENTITY) | |
| `print` / `printchar` / `format` / `localeprint` | B(TEXT_BUFFER) | 同缓冲区可合并；缓冲区不可读 |
| `printflush` | F(TEXT_BUFFER) + W(ENTITY) | |
| `message` | F(TEXT_BUFFER) + W(GLOBAL) | 仅 WORLD |
| `setflag` / `getflag` | W/R(GLOBAL_TAG) | 仅 WORLD |
| `spawn` / `bullet` / `effect` / `explosion` / `setblock` / `setrule` / `cutscene` / `playsound` / `playmusic` / `setmarker` / `makemarker` / `weatherset` / `spawnwave` | W(WORLD) | 仅 WORLD |
| `getblock` / `weathersense` | R(WORLD) | 仅 WORLD |
| `fetch` / `query` | R(WORLD) | 仅 WORLD |
| `setrate` | W(COUNTER) + W(WORLD) | 仅 WORLD，改 `@ipt` |
| `sync` / `clientdata` | W(NETWORK) | 仅 WORLD |

---

## 附录 B：存储层级速查

```
┌─────────────────────────────────────────────────────────────┐
│ 变量槽（无限）                                              │
│   number(double) / object / string / null                   │
│   名字 = 标识符；跨处理器需 read/write @this "名字"         │
├─────────────────────────────────────────────────────────────┤
│ Memory Cell  64 槽   ┐                                      │
│ Memory Bank 512 槽   ├─ 建筑级，可被多个处理器链接=共享内存 │
│   可存任意值（D9）；数字索引访问；越界读 null / 写忽略      │
├─────────────────────────────────────────────────────────────┤
│ 全局 flag（仅 WORLD）  number                               │
│ 单位 flag              1 number / 单位                      │
├─────────────────────────────────────────────────────────────┤
│ 处理器私有：@counter / @unit / graphicsBuffer(256) /        │
│             textBuffer(400) / radar缓存(30 tick) / wait 计时│
└─────────────────────────────────────────────────────────────┘
```

> 上图未画 `world-cell`（512 槽，特权），以及 `world-message` / `world-switch`——它们与普通版本同构，只是 `privileged = true`。

---

## 附录 C：决策记录

| ID | 决策 | 状态 | 依据 |
| --- | --- | --- | --- |
| D1 | 递归用 C 方案（SCC 按需栈帧），非递归函数用无栈调用点特化 | ✅ 已定 | 栈式递归使函数体 ×3~5，1000 条上限不可接受 |
| D2 | 闭包 B 默认（记录 + 跳转表），A 作为逃逸分析优化 | ✅ 已定 | 表达力与成本平衡；跨处理器闭包 v1 禁止 |
| D3 | `extern X[: Type]` 作外部链接声明 | ✅ 已定 | 不复用 `use`，避免与模块导入混淆 |
| D4 | 超 1000 时自动分拆到多处理器 + `read/write` 通信 | ✅ 已定 | 用户明确选择 |
| D5 | 4 档 target profile，编译期能力检查 | ✅ 已定 | `ipt` 差 500 倍，被动适配不可行 |
| D6 | 语言暴露 `Array`/`Map`，映射 cell/bank + 多 bank 拼接 | ✅ 已定 | 用户明确选择 |
| D7 | 字符串是一等值 | ✅ 已定 | 用户明确选择 |
| D8 | DESUGAR 重做为 AST → HIR | ✅ 已定 | 分层干净；AST 只作语法层 |
| D9 | cell/bank 可存任意值（含对象） | ✅ 已定（已对源码确认） | `objectMemory` + `numberMemory` + `sentinel` 做 tagged union |
| D10 | IR 带 effect 字段；优化只做**保序** | ✅ 已定 | 字段后补代价极高；依赖图重排不排期 |
| D11 | 宏：comptime 先做，语法宏后置 | 🟡 部分已定 | 项目前期；宏模型仍待 R3 |
| D12 | 开发者声明递归深度 → 编译器折算所需外部内存 | ✅ 已定 | 用户明确选择 |
| D13 | 语言层区分 `Int` 与 `Num`，底层同为 double | ✅ 已定 | 语义清晰、零运行时代价；降级细则见 D18 |
| D14 | 逻辑在各客户端独立执行，编译器不建模同步 | ✅ 已定 | 游戏语义如此；同步是逻辑作者的责任 |
| D15 | 雷达缓存 30 tick，且缓存挂在**指令实例**上 | ✅ 已定（源码确认） | `timer.get(30f)`；影响 effect 粒度与 CSE |
| D16 | 跳转目标用**标签**，由游戏解析；编译器不产出数字跳转目标 | ✅ 已定 | `LParser` 原生支持标签，省掉全局重定位 |
| D17 | 返回地址用 `@counter` 自定位（`op add __ra @counter 1`），不用行号 | ✅ 已定 | `set` 不吃标签；自定位同时让产物抗手工编辑 |
| D18 | `Int / Int` → `div` 返回 `Num`；溢出/除零运行时为 `null`；`Num → Int` 用 `floor` | ✅ 已定 | 用户明确；`Int` 装不下 null，故返回 `Num` |
| D19 | 字符串仅支持编译期常量拼接 | ✅ 已定 | `@textBuffer` 不可读，运行期无法取回 |
| D20 | 编译期指令用顶层 `pragma name = value` | 🟡 建议待确认 | `@` 归游戏内容、`#` 是注释、`set` 已占用 |

---

## 附录 D：源码核实记录（v0.2 起）

> 全部对照 `reference/Mindustry`。**标注"修正"的条目改掉了更早版本的原文。**

| 事实 | 结论 | 位置 |
| --- | --- | --- |
| 四档 `ipt` | 2 / 8 / 25 / 8 | `content/Blocks.java:6898,6905,6916,7003` |
| `WORLD` 的 `maxInstructionsPerTick` | **1000**（所以 `setrate` 上限确是 1000） | `Blocks.java:7007` |
| 非特权 `setrate` | 被 clamp 到 `instructionsPerTick`，**不能提速** | `logic/LExecutor.java:1224` |
| `maxInstructions` | 1000 | `LExecutor.java:40` |
| `maxInstructionScale` | 5，且 `accumulator` 被截断 | `LogicBlock.java:45,557` |
| 链接范围默认值 | `8 * 10`（logic/hyper 覆盖为 `8*22`/`8*42`，world 为 `MAX`) | `LogicBlock.java:49` |
| `memoryCapacity` 默认 | **32**；实例覆盖 cell 64 / bank 512 / world-cell 512 | `MemoryBlock.java:17`、`Blocks.java:6924,6930,7016` |
| 内存槽位模型 | `objectMemory` + `numberMemory` + `sentinel` = **tagged union**，初值 0 | `MemoryBlock.java:54-66` |
| 越界读 | 返回 **`null`（对象）**（修正：原写 NaN） | `MemoryBlock.java:90-104` |
| 越界写 | 静默忽略 | `MemoryBlock.java:112-114` |
| 写只读常量 | `set` 与 `select` 都静默跳过 | `LExecutor.java:845,898` |
| 雷达缓存 | **30 tick**（修正：原写 40），缓存在 `RadarI` 实例字段上 | `LExecutor.java` RadarI |
| `graphicsBuffer` / `displayBuffer` / `textBuffer` | 256 / 1024 / 400 | `LExecutor.java:43-45` |
| 消息建筑文本 | `maxTextLength = 400`、`maxNewlines = 24` | `MessageBlock.java:29-30` |
| `draw` 参数打包 | `packSign` = 低 9 位 + 符号位 → **-511..511** | `LExecutor.java:1027` |
| 每行 token 上限 | **16**，超出报 "Line too long" | `LParser.java:10,127` |
| 分词分界符 | `\n`、空格、`\t`、`#`、`;`、`"`，其余字符都可进 token | `LParser.java:100` |
| 单位死亡后的读取 | 字段冻结，`@dead=1`、`@controlled=0`、`@controller=null`；`sensor` 未知属性返回 `NaN` | `UnitComp.java:280,299,312` |
| `LVar` 表示 | `isobj` 区分；字符串就是 `objval` 里的 `String` | `LVar.java:12-14` |
| `@link` 与用户变量同名 | **链接优先** | 用户确认 + `LAssembler.putVar/putConst` |
| `@counter` 时序 | `instructions[counter.numval++].run()` → **先自增再执行**，执行期 `@counter` 已指向下一条 | `LExecutor.java:118` |
| `@counter` 可当操作数 | 由 `putVar` 注册为普通变量（与 `executor.counter` 同一 LVar），不是常量 | `LAssembler.java:22`、`LExecutor.java:135` |
| 标签解析 | 单 token 且以 `:` 结尾；重名报错；上限 **`maxJumps = 500`** | `LParser.java:11,157-165` |
| `jump` 目标 | 能 `parseInt` → 行号；否则 → 标签名，交给后期解析 | `LParser.java:170-173` |
| 非特权处理器上的特权指令 | **静默替换为 `InvalidStatement`**，不报错 | `LParser.java:190-192` |
| 非有限数的归宿 | `setnum` 遇 `isNaN \|\| isInfinite` → 写回 **`null`（对象）**；因此除零/溢出/`sqrt(-1)` 全都变 null | `LVar.java` `setnum` / `invalid` |
| `null` 与 `0` | `num()` 把 null 读成 0 → **`null == 0` 为真**；只有 `===` 能区分 | `LVar.java` `num` / `setnum` |

### v0.2 修正（对 v0.1）

1. 雷达缓存 **40 tick → 30 tick**，粒度由"每处理器"改为"每条指令实例"。
2. 内存越界读 **NaN → `null`**。
3. 存储层级补齐 **`world-cell`**（并注明 `world-message`/`world-switch`）。
4. 容量**不再硬编码**：默认 32、实例覆盖、运行时可 `sensor @memoryCapacity`。
5. 外部链接声明 `use X: Type` → **`extern X[: Type]`**（Q9）。
6. 新增 **`Int` / `Num` 语言层区分**（D13）。
7. 1.6 由"多处理器与帧差"改写为**"执行与同步模型"**（D14，客户端独立执行）。
8. 1.4 补充 token 上限、分词字符集、`setrate` 的真实约束。

### v0.3 修正（对 v0.2）

1. **L6 不再做"标签 → 行号"**（D16）：`jump` 直接写标签，由游戏在导入时解析。L6 只剩标签分配/校验、预算、数据段地址。
2. **8.1 调用协议改为 `@counter` 自定位**（D17）：原文 `set __ra_k <ret_k>` 是**错的**——`set` 吃不到标签。正确写法是 `op add __ra_k @counter 1`。
3. **8.2 间接调用给出 A/B/C 三方案**，默认 A（发射期算地址），并说明各自的编辑鲁棒性与成本。
4. 1.1 / 1.4 补充：`@counter` 先自增再执行；标签必须独占一行、不能是纯数字、上限 500；**非特权处理器上的特权指令被静默丢弃**。
5. 术语表「重定位」缩小为"只用于数据段"。

### v0.4 修正（对 v0.3）

1. **`div` 不是唯一来源**：`LVar.setnum` 把任何 NaN/Inf 都写成 null，所以溢出、`sqrt(-1)`、`log(-1)` 同样变 null（附录 D 补两条）。
2. 由 `num()` 把 null 读成 0 推出 **`null == 0` 为真**，判空必须用 `===`。
3. **修正 6.1 的折叠规则**：`x - x → 0`、`x * 0 → 0` 其实合法；真正非法的是恒等式（`x + 0 → x` 等）与 `x / x → 1`。
4. 新增 **8.0 三套语法域**，明确 `:` / `@` / `pragma` / `#` 分属不同域。
5. 新增 **8.7 编译期指令（`pragma`）**，替换原先的 `@depth` 写法（D20）。
6. `Expr.Annotation` 明确为**类型/联合/可空标注**（`x: T?`、`x: A | B`），不是装饰器。
7. 字符串限制为**编译期常量拼接**（D19）。
