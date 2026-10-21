plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation(libs.license.report.plugin)
}
