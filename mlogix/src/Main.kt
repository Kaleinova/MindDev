package mlogix

import arc.files.Fi
import arc.util.I18NBundle
import mlogix.compiler.Compiler
import mlogix.util.I18N
import mlogix.util.Log

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("未传入参数")
            return
        }

        if (args.size >= 2) {
            when (args[1]) {
                "d" -> Log.setLevel(Log.LogType.DEBUG)
            }
        }

        when (args[0]) {
            "c" -> compile()
        }
    }

    fun compile() {
        val projectDirectory = Fi.get(System.getProperty("user.dir"))
        initI18N(projectDirectory)

        // 获取当前工作目录

        val compiler = Compiler(projectDirectory)
        val result = compiler.compile()
    }

    private fun initI18N(dir: Fi) {
        I18N.bundle = I18NBundle.createBundle(dir.child("assets/bundles/bundle"))
    }
}