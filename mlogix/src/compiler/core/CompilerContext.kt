package mlogix.compiler.core

import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.diagnostic.DiagCollector

/**
 * 编译器上下文：贯穿所有 Pass 共享的状态。
 *
 * 设计原则（对齐 rustc 的查询式上下文）：
 * - 所有状态必须放进此可隔离的上下文中，不使用全局静态变量。
 * - [sourceFile] 是当前正在编译的源文件映射（一个文件构造一次 [CompilerContext] 实现）。
 * - 未来预留：QueryCache（增量编译的记忆化缓存）、宏展开的 DefId 表。
 */
interface CompilerContext {

    /** 诊断收集器：所有 Pass 通过它报告错误/警告 */
    val problems: DiagCollector

    /** 当前源文件的源码位置映射 */
    val sourceFile: SourceFile

    /** 编译器配置（优化级别、目标平台等） */
    val config: CompilerConfig
}

