package mlogix.compiler.support

import arc.files.Fi
import arc.util.I18NBundle.createBundle
import mlogix.util.I18N

/**
 * 测试用 I18N 初始化。
 *
 * 原来的 9 个测试类各自写一遍 `@BeforeAll` + `Fi.get(user.dir)`，既重复又依赖
 * Gradle 的工作目录（IDE 里单独跑某个类时可能找不到 bundle）。这里集中处理：
 * - 只初始化一次；
 * - 同时兼容「工作目录是仓库根」和「工作目录是 mlogix 模块目录」两种运行方式。
 */
object TestI18n {
    private var installed = false

    fun install() {
        if (installed) return

        val projectDirectory = Fi.get(System.getProperty("user.dir"))
        val bundleDirectory = when {
            projectDirectory.child("assets/bundles/bundle.properties").exists() ->
                projectDirectory.child("assets/bundles/bundle")

            projectDirectory.child("mlogix/assets/bundles/bundle.properties").exists() ->
                projectDirectory.child("mlogix/assets/bundles/bundle")

            else -> error(
                "找不到 assets/bundles/bundle.properties；当前工作目录: " +
                    projectDirectory.absolutePath()
            )
        }

        I18N.bundle = createBundle(bundleDirectory)
        installed = true
    }
}
