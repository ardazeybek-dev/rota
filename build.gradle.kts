plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Static web playground (GitHub Pages): the web/ folder plus the Kotlin/JS build of :core.
val site by tasks.registering(Sync::class) {
    group = "build"
    description = "Assembles the static playground into build/site"
    dependsOn(":core:jsBrowserProductionLibraryDistribution")
    from(file("web"))
    from(project(":core").layout.buildDirectory.dir("dist/js/productionLibrary")) {
        into("lib")
        include("*.mjs")
    }
    into(layout.buildDirectory.dir("site"))
}
