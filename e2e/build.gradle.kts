plugins {
    id("java")
}

dependencies {
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator-junit5:5.1.0.2254")
    testImplementation("org.sonarsource.orchestrator:sonar-orchestrator:5.1.0.2254")
    testImplementation("org.junit.platform:junit-platform-suite-api:1.12.0")
    testImplementation("org.sonarsource.sonarqube:sonar-ws:10.7.0.96327")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine:1.12.0")

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
