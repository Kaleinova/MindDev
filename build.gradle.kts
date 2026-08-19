import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

buildscript {
    repositories {
        mavenCentral()
    }
}

val mindustryVersion = project.property("mindustryVersion") as String
val kotlinVersion = project.property("kotlinVersion") as String
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val modArtifactName = project.name

plugins {
    kotlin("jvm") version "2.3.20"
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
    implementation(project(":minddev"))
    implementation(project(":mlogix"))
}

// 定义输出目录（在 build 下）
val mergedBundlesRoot = project.layout.buildDirectory.dir("mergedBundles").get()

tasks.register<DefaultTask>("mergeBundleProperties") {
    // 输入声明
    val minddevDir = file("minddev/assets/bundles")
    val mlogixDir = file("mlogix/assets/bundles")
    inputs.dir(minddevDir).withPropertyName("minddevBundleDir")
    inputs.dir(mlogixDir).withPropertyName("mlogixBundleDir")
    outputs.dir(mergedBundlesRoot)

    doLast {
        val fileMap = mutableMapOf<String, MutableList<File>>()
        fun collectFiles(dir: File, base: String = "") {
            if (!dir.exists()) return
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(dir).path
                val key = if (base.isEmpty()) relative else "$base/$relative"
                fileMap.getOrPut(key) { mutableListOf() }.add(file)
            }
        }
        collectFiles(minddevDir)
        collectFiles(mlogixDir)

        fileMap.forEach { (relativePath, files) ->
            // 输出文件路径：使用 mergedBundlesRoot（Directory 类型）
            val outputFile = mergedBundlesRoot.file(relativePath).asFile
            outputFile.parentFile.mkdirs()

            when (files.size) {
                1 -> {
                    files.first().copyTo(outputFile, overwrite = true)
                }

                else -> {
                    require(files.all { it.extension == "properties" }) {
                        "Only .properties files are supported, but found: ${files.map { it.name }}"
                    }
                    val merged = Properties()
                    files.forEach { file ->
                        file.bufferedReader(Charsets.UTF_8).use { reader ->
                            val props = Properties()
                            props.load(reader)
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
                    outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("# Merged ${files[0].name}\n")
                        merged.forEach { (key, value) ->
                            writer.write("$key=$value\n")
                        }
                    }
                }
            }
        }
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    archiveFileName.set("${modArtifactName}Desktop.jar")

    dependsOn("mergeBundleProperties")

    from(configurations.runtimeClasspath.map { config -> config.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("mlogix/Main.class")
    }

    from(rootDir) {
        include("mod.hjson")
    }

    from(mergedBundlesRoot) {
        into("bundles/")
    }

    from("minddev/assets/") {
        exclude("bundles/")
    }
    from("mlogix/assets/") {
        exclude("bundles/")
    }

    doLast {
        val localProperties = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localProperties.load(localPropsFile.inputStream())
        }

        localProperties.getProperty("modsDir")?.let {
            val targetDir = file(it)
            if (!targetDir.exists()) {
                println("Directory not found: $targetDir")
                return@let
            }
            copy {
                from(archiveFile.get())
                into(targetDir)
            }
            println("JAR has been copied to: ${targetDir.absolutePath}")
        }
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
