plugins {
    id("java")
}

val orchestratorVersion = "6.1.0.3962"

dependencies {
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator-junit5:$orchestratorVersion")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator:$orchestratorVersion")
    testImplementation("org.junit.platform:junit-platform-suite-api:6.0.3")
    testImplementation("org.sonarsource.sonarqube:sonar-ws:10.7.0.96327")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine:6.0.3")


    // Force specific versions of transitive dependencies
    constraints {
      implementation("ch.qos.logback:logback-classic:1.5.32") {
        because("CVE-2023-6378 - Deserialization of Untrusted Data")
      }
      implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.21.1") {
        because("CVE-2020-36518 - Out-of-bounds Write")
      }
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

sonarqube.isSkipProject = true

tasks.test {
    onlyIf {
        project.hasProperty("e2e")
    }
    useJUnitPlatform()
    systemProperty("pluginVersion", System.getProperty("pluginVersion", null))
}
