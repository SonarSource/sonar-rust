plugins {
    id("java")
}

val orchestratorVersion = "6.3.0.4464"

dependencies {
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator-junit5:$orchestratorVersion")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator:$orchestratorVersion")
    testImplementation(libs.junit.platform.suite.api)
    testImplementation("org.sonarsource.sonarqube:sonar-ws:26.7.0.124771")

    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.platform.suite.engine)


    // Force specific versions of transitive dependencies
    constraints {
      implementation("ch.qos.logback:logback-classic:1.5.38") {
        because("CVE-2023-6378 - Deserialization of Untrusted Data")
      }
      implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.22.1") {
        because("CVE-2020-36518 - Out-of-bounds Write")
      }
    }
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
    mavenLocal()
}

sonarqube.isSkipProject = true

tasks.test {
    onlyIf {
        project.hasProperty("e2e")
    }
    useJUnitPlatform()
    systemProperty("pluginVersion", System.getProperty("pluginVersion", null))
    if (project.hasProperty("e2e")) {
        // RustRulesRepositoryApiTest installs the fixture plugin from its build output. Build it (and,
        // for local runs, the base plugin jar the OrchestratorHelper picks up) before the ITs run.
        dependsOn(":custom-rules-plugin:shadowJar")
        if (System.getProperty("pluginVersion").isNullOrEmpty()) {
            dependsOn(":sonar-rust-plugin:shadowJar")
        }
    }
}
