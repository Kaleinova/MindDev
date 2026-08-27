package mlogix.compiler.core

import arc.files.Fi
import arc.func.Cons
import arc.struct.ObjectMap
import arc.struct.Seq
import java.io.IOException

class SourceMap(/* 项目根目录 */val projectPath: Fi?) {
    private val sourceFileMap = ObjectMap<Fi, SourceFile>()

    /* 以此通过索引获取sourceMap */
    private val sourceFileList = Seq<SourceFile>()

    /**
     * 加载文件并创建 SourceFile
     */
    @Throws(IOException::class)
    fun loadSourceMap(filePath: Fi): SourceFile {
        val sourceFile = SourceFile(filePath, sourceFileList.size, projectPath)
        sourceFileMap.put(filePath, sourceFile)
        sourceFileList.add(sourceFile)
        return sourceFile
    }

    /**
     * 从字符串创建 SourceFile
     */
    @Throws(IOException::class)
    fun loadSourceMap(source: String): SourceFile {
        val sourceFile = SourceFile(source, sourceFileList.size)
        sourceFileList.add(sourceFile)
        return sourceFile
    }

    /**
     * 获取文件的 SourceFile
     */
    fun getSourceFile(filePath: Fi): SourceFile? {
        return sourceFileMap[filePath]
    }

    /**
     * 通过索引获取sourceMap
     */
    fun getSourceFile(index: Int): SourceFile? {
        return sourceFileList.get(index)
    }

    @Throws(IOException::class)
    fun walk(cons: Cons<Fi>) {
        projectPath?.findAll { f -> f.extension().equals("mlx") }?.forEach { f -> cons.get(f) }
    }
}