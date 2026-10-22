plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // LZMA for 7z headers (7-Zip packs the index even when it stores the files).
    implementation("org.tukaani:xz:1.9")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Manual run against real servers; see LiveCheck.kt.
tasks.register<JavaExec>("liveCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.usenetstream.engine.LiveCheckKt")
}
