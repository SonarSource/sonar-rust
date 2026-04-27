plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

repositories {
    maven {
        url = uri("https://repox.jfrog.io/repox/sonarsource")
    }
    gradlePluginPortal()
}

dependencies {
    implementation(libs.license.report.plugin)
}
