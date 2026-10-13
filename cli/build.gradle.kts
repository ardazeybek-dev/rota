plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.rota.cli.MainKt"
    applicationName = "rota"
    applicationDefaultJvmArgs = listOf("-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.language=en", "-Duser.country=US")
}

/** A single runnable jar: `java -jar rota.jar solve plan.rota`. */
val fatJar by tasks.registering(Jar::class) {
    archiveFileName = "rota.jar"
    destinationDirectory = layout.buildDirectory.dir("dist")
    manifest { attributes["Main-Class"] = "dev.rota.cli.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}
