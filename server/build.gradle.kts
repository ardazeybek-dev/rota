plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass = "dev.rota.server.ServerKt"
    applicationName = "rota-server"
    applicationDefaultJvmArgs = listOf("-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8")
}

// The web playground ships inside the server jar: static files plus the Kotlin/JS build of :core.
val webAssets by tasks.registering(Sync::class) {
    dependsOn(":core:jsBrowserProductionLibraryDistribution")
    from(rootProject.file("web"))
    from(project(":core").layout.buildDirectory.dir("dist/js/productionLibrary")) {
        into("lib")
        include("*.mjs")
    }
    into(layout.buildDirectory.dir("generated/web/web"))
}

sourceSets.main {
    resources.srcDir(webAssets.map { layout.buildDirectory.dir("generated/web").get() })
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.language=en", "-Duser.country=US")
}

val fatJar by tasks.registering(Jar::class) {
    archiveFileName = "rota-server.jar"
    destinationDirectory = layout.buildDirectory.dir("dist")
    manifest { attributes["Main-Class"] = "dev.rota.server.ServerKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}
