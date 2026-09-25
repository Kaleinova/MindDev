val mindustryVersion = project.property("mindustryVersion") as String

plugins {
    application
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src"))
    }
    test {
        kotlin.setSrcDirs(listOf("test"))
    }
}

configurations {
    create("mindustryBase") {
        isCanBeResolved = false
        isCanBeConsumed = false
    }

    create("debugRuntime") {
        extendsFrom(configurations["mindustryBase"])
        isCanBeResolved = true
        isCanBeConsumed = false
    }

    compileOnly.get().extendsFrom(configurations["mindustryBase"])

    testImplementation.get().extendsFrom(configurations["mindustryBase"])
}

dependencies {
    val dependency =
        if (mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion"
    add("mindustryBase", dependency)

    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// 配置可执行 JAR 的主类
application {
    mainClass.set("minddev.mlogix.Main")
}

/**
 * CLI 调试任务：以 JavaExec 直接跑 `mlogix.Main`。
 *
 * Gradle 的 JavaExec 是 JVM 启动器（与源码语言无关），跑 Kotlin 的 `main` 完全正常。
 * `debugRuntime` 只在这里参与 classpath：IDE 里跑并不需要它，所以不污染 `runtimeOnly`。
 */
fun registerMainTask(taskName: String, vararg arguments: String) {
    tasks.register<JavaExec>(taskName) {
        group = "mlogix"
        description = "以 ${arguments.joinToString(" ")} 作为参数运行 mlogix.Main"
        classpath = sourceSets.main.get().runtimeClasspath + configurations["debugRuntime"]
        mainClass = "mlogix.Main"
        args(*arguments)
        isIgnoreExitValue = true
        errorOutput = System.err
        doLast {
            if (executionResult.get().exitValue != 0) {
                println("程序执行失败，退出码: ${executionResult.get().exitValue}")
            }
        }
    }
}

registerMainTask("compile", "c")                       // 编译（普通模式）
registerMainTask("compile-debug", "c", "d")            // 编译 + debug 输出（AST 等）
registerMainTask("tokenize-debug", "t", "d")           // 只看 token