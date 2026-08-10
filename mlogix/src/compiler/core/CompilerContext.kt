package mlogix.compiler.core

import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.diagnostic.ProblemCollector

/**
 * 编译器上下文：贯穿所有 Pass 共享的状态。
 *
 * 设计原则（对齐 rustc 的查询式上下文）：
 * - 所有状态必须放进此可隔离的上下文中，不使用全局静态变量。
 * - [sourceMap] 是当前正在编译的源文件映射（一个文件构造一次 [CompilerContext] 实现）。
 * - 未来预留：QueryCache（增量编译的记忆化缓存）、宏展开的 DefId 表。
 */
interface CompilerContext {

    /** 诊断收集器：所有 Pass 通过它报告错误/警告 */
    val problems: ProblemCollector

    /** 当前源文件的源码位置映射 */
    val sourceMap: SourceMap

    /** 编译器配置（优化级别、目标平台等） */
    val config: CompilerConfig
}

