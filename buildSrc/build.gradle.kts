plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.license.report.plugin)
}
