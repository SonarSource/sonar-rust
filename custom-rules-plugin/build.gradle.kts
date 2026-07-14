plugins {
  id("java")
  id("com.gradleup.shadow") version "9.5.1"
}

// Test-fixture plugin: a minimal SonarQube plugin that implements the
// org.sonar.plugins.rust.api.RustRulesRepository extension point exposed by sonar-rust, used by the
// e2e module to exercise that API independently of any other plugin. Not published or released.

val sonarApiVersion = "13.8.0.4399"

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
  mavenCentral()
}

dependencies {
  compileOnly("org.sonarsource.api.plugin:sonar-plugin-api:$sonarApiVersion")
  // For the RustRulesRepository API only. At runtime SonarQube exposes that api package from the
  // installed base plugin, so it is not bundled into this fixture's jar (compileOnly).
  compileOnly(project(":sonar-rust-plugin"))
}

java {
  toolchain {
    // Build and run on Java 21, but keep compiling to Java 17 bytecode.
    languageVersion = JavaLanguageVersion.of(21)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(17)
}

sonarqube.isSkipProject = true

tasks.jar {
  enabled = false
  dependsOn(tasks.shadowJar)
  manifest {
    attributes(
      mapOf(
        "Plugin-Key" to "customrust",
        "Plugin-Name" to "Custom Rust Rules (test fixture)",
        "Plugin-Version" to project.version,
        "Plugin-Class" to "org.sonarsource.rust.custom.CustomRulesPlugin",
        "Plugin-RequiredForLanguages" to "rust",
      )
    )
  }
}

tasks.shadowJar {
  archiveClassifier.set("")
}
