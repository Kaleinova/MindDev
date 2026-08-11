val mindustryVersion = project.property("mindustryVersion") as String
val kotlinVersion = project.property("kotlinVersion") as String

plugins {
    application
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        kotlin.setSrcDirs(listOf("src"))
    }
    test {
        java.setSrcDirs(listOf("test"))
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

tasks.register<JavaExec>("compile") {
    classpath = sourceSets.main.get().runtimeClasspath + configurations["debugRuntime"]
    mainClass = "mlogix.Main"
    args("c")
    isIgnoreExitValue = true
    errorOutput = System.err
    doLast {
        if (executionResult.get().exitValue != 0) {
            println("程序执行失败，退出码: ${executionResult.get().exitValue}")
        }
    }
}

tasks.register<JavaExec>("compile-debug") {
    classpath = sourceSets.main.get().runtimeClasspath + configurations["debugRuntime"]
    mainClass = "mlogix.Main"
    args("c", "d")
    isIgnoreExitValue = true
    errorOutput = System.err
    doLast {
        if (executionResult.get().exitValue != 0) {
            println("程序执行失败，退出码: ${executionResult.get().exitValue}")
        }
    }
}