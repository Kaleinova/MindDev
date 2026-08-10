import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties


version = "0.1"

buildscript {
    repositories {
        mavenCentral()
    }
}

// Mindustry version to depend on.
// Valid values:
// - latest: depend on the latest release of mindustry
// - be: depend on the very latest commit of mindustry
// - v<number>: depend on a specific version
val mindustryVersion = project.property("mindustryVersion") as String
val kotlinVersion = project.property("kotlinVersion") as String
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val modArtifactName = project.name

plugins {
    kotlin("jvm") version "2.3.20"
}

sourceSets.main {
    kotlin.srcDirs("ide/src")
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()

        // Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
        ivy {
            url = uri("https://github.com/")
            patternLayout {
                artifact(
                    when (mindustryVersion) {
                        "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar" // latest stable release
                        "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar" // latest commit (BE)
                        else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar" // specific release
                    }
                )
            }
            metadataSources { artifact() }

            content {
                if (mindustryVersion == "be") {
                    // BE artifact version is always 'latest'
                    includeVersion("Anuken", "MindustryBuilds", "latest")
                } else {
                    includeVersion("Anuken", "Mindustry", mindustryVersion)
                }
            }
        }
    }

    // 公共的 Kotlin 编译选项
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
        options.release.set(8)
    }
}

dependencies {
    compileOnly(if (mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
    implementation(project(":mlogix"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.release.set(8)
}

// 自定义任务类：合并两个 bundles 目录下的 properties 文件
abstract class MergeBundlePropertiesTask : DefaultTask() {

    @get:InputDirectory
    abstract val ideBundleDir: DirectoryProperty

    @get:InputDirectory
    abstract val mlogixBundleDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun merge() {
        val ideDir = ideBundleDir.get().asFile
        val mlogixDir = mlogixBundleDir.get().asFile
        val outputRoot = outputDir.get().asFile

        // 收集所有文件（以相对路径为键）
        val fileMap = mutableMapOf<String, MutableList<File>>()

        fun collectFiles(dir: File) {
            if (!dir.exists()) return
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(dir).path
                fileMap.getOrPut(relative) { mutableListOf() }.add(file)
            }
        }

        collectFiles(ideDir)
        collectFiles(mlogixDir)

        // 处理每个相对路径
        fileMap.forEach { (relativePath, files) ->
            val outputFile = outputRoot.resolve(relativePath)
            outputFile.parentFile.mkdirs()

            when (files.size) {
                1 -> {
                    // 只有一个来源，直接复制
                    files.first().copyTo(outputFile, overwrite = true)
                }
                else -> {
                    // 多个来源（通常是两个），合并 properties
                    require(files.all { it.extension == "properties" }) {
                        "Only .properties files are supported, but found: ${files.map { it.name }}"
                    }
                    val merged = Properties()
                    files.forEach { file ->
                        file.inputStream().use { ins ->
                            val props = Properties()
                            props.load(ins)
                            // 检查重复键并报警告
                            props.keys.forEach { key ->
                                if (merged.containsKey(key)) {
                                    logger.warn(
                                        "Duplicate key '$key' in file '$relativePath' " +
                                                "from source: ${file.parentFile.name}"
                                    )
                                }
                            }
                            merged.putAll(props)
                        }
                    }
                    // 写入合并后的 properties
                    outputFile.writer().use { writer ->
                        merged.store(writer, "Merged bundle.properties")
                    }
                }
            }
        }
    }
}

// 注册任务
tasks.register<MergeBundlePropertiesTask>("mergeBundleProperties") {
    ideBundleDir.set(file("ide/assets/bundles"))
    mlogixBundleDir.set(file("mlogix/assets/bundles"))
    outputDir.set(layout.buildDirectory.dir("mergedBundles"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    archiveFileName.set("${modArtifactName}Desktop.jar")

    dependsOn("mergeBundleProperties")

    from(configurations.runtimeClasspath.map { config -> config.map { if (it.isDirectory) it else zipTree(it) } })

    from(rootDir) {
        include("mod.hjson")
    }

    from(layout.buildDirectory.dir("mergedBundles")) {
        into("assets/bundles/")
    }

    from("ide/assets/") {
        exclude("bundles/")
        into("assets/")
    }
    from("mlogix/assets/") {
        exclude("bundles/")
        into("assets/")
    }
}

val jarAndroid = tasks.register("jarAndroid") {
    dependsOn("jar")

    doLast {
        if (sdkRoot.isNullOrEmpty() || !File(sdkRoot).exists()) {
            throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME is set to your Android SDK directory.")
        }

        val platformRoot = File("$sdkRoot/platforms/").listFiles()
            ?.sorted()
            ?.reversed()
            ?.find { f -> File(f, "android.jar").exists() }
            ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")

        // collect dependencies needed for desugaring
        val dependencies = (configurations.compileClasspath.get().toList() +
                configurations.runtimeClasspath.get().toList() +
                listOf(File(platformRoot, "android.jar")))
            .joinToString(" ") { "--classpath ${it.path}" }

        // dex and desugar files - this requires d8 in your PATH
        val d8 = if (isWindows) "d8.bat" else "d8"

        val process = ProcessBuilder(
            "$d8 $dependencies --min-api 21 --output ${modArtifactName}Android.jar ${modArtifactName}Desktop.jar"
                .split(" ")
        )
            .directory(File("${layout.buildDirectory.get().asFile}/libs"))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.waitFor()
    }
}

val deploy = tasks.register("deploy", Jar::class) {
    dependsOn(jarAndroid)
    dependsOn(tasks.jar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("$modArtifactName.jar")

    from({
        listOf(
            zipTree("${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Desktop.jar"),
            zipTree("${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Android.jar")
        )
    })

    doLast {
        delete(
            "${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Desktop.jar",
            "${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Android.jar"
        )
    }
}
