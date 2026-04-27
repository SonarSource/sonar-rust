plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

repositories {
    val artifactoryUsername = System.getenv("ARTIFACTORY_PRIVATE_USERNAME") ?: project.findProperty("artifactoryUsername")
    val artifactoryPassword = System.getenv("ARTIFACTORY_PRIVATE_PASSWORD") ?: project.findProperty("artifactoryPassword")
    maven {
        url = uri("https://repox.jfrog.io/repox/sonarsource")
        if (artifactoryUsername is String && artifactoryPassword is String) {
            credentials {
                username = artifactoryUsername
                password = artifactoryPassword
            }
        }
    }
    gradlePluginPortal()
}

dependencies {
    implementation(libs.license.report.plugin)
}
