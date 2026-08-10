val mindustryVersion = project.property("mindustryVersion") as String

sourceSets.main {
    kotlin.srcDirs("src")
}

dependencies {
    compileOnly(if (mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
    implementation(project(":mlogix"))
}
