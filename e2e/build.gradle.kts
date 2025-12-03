plugins {
    id("java")
}

val orchestratorVersion = "6.0.1.3892"

dependencies {
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator-junit5:$orchestratorVersion")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator:$orchestratorVersion")
    testImplementation("org.junit.platform:junit-platform-suite-api:1.12.0")
    testImplementation("org.sonarsource.sonarqube:sonar-ws:10.7.0.96327")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine:1.12.0")


    // Force specific versions of transitive dependencies
    constraints {
      implementation("ch.qos.logback:logback-classic:1.5.18") {
        because("CVE-2023-6378 - Deserialization of Untrusted Data")
      }
      implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.20.0") {
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
