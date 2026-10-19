import org.gradle.internal.os.OperatingSystem

plugins {
  id("java")
  id("jacoco")
  `maven-publish`
  id("com.diffplug.spotless") version "8.10.4"
  id("org.sonarqube")
  id("com.gradleup.shadow") version "9.6.1"
  id("license-file-generator")
}


// Replaces the version defined in sources, usually x.y-SNAPSHOT, by a version identifying the build.
val buildNumber: String? = System.getProperty("buildNumber")
project.ext["buildNumber"] = buildNumber
val unqualifiedVersion = project.version
if (project.version.toString().endsWith("-SNAPSHOT") && buildNumber != null) {
  val versionSuffix = if (project.version.toString().count { it == '.' } == 1) ".0.$buildNumber" else ".$buildNumber"
  project.version = project.version.toString().replace("-SNAPSHOT", versionSuffix)
}

val sonarApiVersion = "14.2.0.5584"
val scannerEngineVersion = "13.16.0.6705"
val analyzerCommonsVersion = "2.33.0.5369"

dependencies {
  implementation("com.google.code.gson:gson:2.14.0")
  implementation("org.sonarsource.analyzer-commons:sonar-analyzer-commons:$analyzerCommonsVersion")
  implementation("org.sonarsource.analyzer-commons:sonar-xml-parsing:$analyzerCommonsVersion")
  implementation("org.tukaani:xz:1.12")
  compileOnly("org.sonarsource.api.plugin:sonar-plugin-api:$sonarApiVersion")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("org.sonarsource.api.plugin:sonar-plugin-api-test-fixtures:$sonarApiVersion")
  testImplementation("org.sonarsource.scanner.engine:plugin-api-scanner-impl:$scannerEngineVersion")
  testImplementation("org.sonarsource.scanner.engine:sensor-test-fixtures:$scannerEngineVersion")
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core:3.27.7")
  testImplementation("org.mockito:mockito-core:5.24.0")
  testImplementation("org.awaitility:awaitility:4.3.0")
  testRuntimeOnly(libs.junit.platform.launcher)
  
}

// Apply a specific Java toolchain to ease working on different environments.
java {
  toolchain {
    // Build, run, and compile bytecode for Java 21.
    languageVersion = JavaLanguageVersion.of(21)
  }
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(21)
}

tasks.jacocoTestReport {
  reports {
    xml.required.set(true)
    html.required.set(false)
  }
}

tasks.named<Test>("test") {
  // Use JUnit Platform for unit tests.
  useJUnitPlatform()
  finalizedBy("jacocoTestReport")
}

val skipAnalyzerBuild = providers.gradleProperty("skipAnalyzerBuild").map {
  it.isEmpty() || it.toBoolean()
}.getOrElse(false)

val skipCrossCompile = providers.gradleProperty("skipCrossCompile").map {
  it.isEmpty() || it.toBoolean()
}.getOrElse(false)

if (!skipAnalyzerBuild) {
  tasks.named("check") {
    dependsOn(":analyzer:testRust")
    dependsOn(":analyzer:checkRustFormat")
    dependsOn(":analyzer:checkRustLicense")
  }
}

tasks.jar {
  enabled = false
  dependsOn(tasks.shadowJar)
  manifest {
    attributes(
      mapOf(
        "Plugin-Key" to "rust",
        "Plugin-Name" to "Rust Code Quality and Security",
        "Plugin-Version" to project.version,
        "Plugin-Organization" to "SonarSource",
        "Implementation-Version" to project.version, // class.getPackage().getImplementationVersion()
        "Plugin-Class" to "org.sonarsource.rust.plugin.RustPlugin",
        "Plugin-RequiredForLanguages" to "rust",
      )
    )
  }
}

tasks.register<Copy>("copyRustOutputs") {
  description = "Copy native analyzer binary and licenses to the build/classes dir for packaging"
  group = "Build"

  // Establish task ordering: if compile tasks run, they must run before copyRustOutputs
  // This doesn't force compilation - files may already exist from CI downloads
  val analyzerProject = project(":analyzer")
  val compileTasks = mutableListOf<Task>()
  compileTasks.add(analyzerProject.tasks.named("compileRustLinuxMusl").get())
  if (!skipCrossCompile) {
    analyzerProject.tasks.findByName("compileRustWin")?.let {
      compileTasks.add(it)
    }
    analyzerProject.tasks.findByName("compileRustLinuxArm")?.let {
      compileTasks.add(it)
    }
    if (OperatingSystem.current().isMacOsX) {
      analyzerProject.tasks.findByName("compileRustDarwin")?.let {
        compileTasks.add(it)
      }
      analyzerProject.tasks.findByName("compileRustDarwinX86")?.let {
        compileTasks.add(it)
      }
    }
  }
  mustRunAfter(compileTasks)

  val analyzerLicensesDir = "${analyzerProject.layout.projectDirectory}/licenses"

  // we hardcode the paths to the binaries because on CI binaries are downloaded from other jobs
  from("${analyzerProject.layout.projectDirectory}/target/x86_64-unknown-linux-musl/release/analyzer.xz") {
    into("linux-x64-musl")
  }
  from(analyzerLicensesDir) {
    into("linux-x64-musl/licenses")
  }
  if (!skipCrossCompile) {
    from("${analyzerProject.layout.projectDirectory}/target/aarch64-unknown-linux-musl/release/analyzer.xz") {
      into("linux-aarch64-musl")
    }
    from(analyzerLicensesDir) {
      into("linux-aarch64-musl/licenses")
    }
    from("${analyzerProject.layout.projectDirectory}/target/x86_64-pc-windows-gnu/release/analyzer.exe.xz") {
      into("win-x64")
    }
    from(analyzerLicensesDir) {
      into("win-x64/licenses")
    }
    from("${analyzerProject.layout.projectDirectory}/target/aarch64-apple-darwin/release/analyzer.xz") {
      into("darwin-aarch64")
    }
    from(analyzerLicensesDir) {
      into("darwin-aarch64/licenses")
    }
    from("${analyzerProject.layout.projectDirectory}/target/x86_64-apple-darwin/release/analyzer.xz") {
      into("darwin-x86_64")
    }
    from(analyzerLicensesDir) {
      into("darwin-x86_64/licenses")
    }
  }
  into("${layout.buildDirectory.get()}/resources/main/analyzer")
}

/**
 * Copy the native analyzer binary to the resources directory so it can be packaged in the jar.
 */
tasks.named("processResources") {
  dependsOn("copyRustOutputs")
}

tasks.shadowJar {
  archiveClassifier.set("")
}

spotless {
  val licenseHeaderFile = rootProject.file("license-header.txt")
  val licenseHeader = licenseHeaderFile.readText().trim()
  java {
    licenseHeader(licenseHeader)
    trimTrailingWhitespace()
  }
}

repositories {
  mavenLocal()
  mavenCentral()
}

val projectTitle: String by project

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      pom {
        name.set(projectTitle)
        description.set(project.description)
        url.set("http://www.sonarsource.com/")
        organization {
          name.set("SonarSource")
          url.set("http://www.sonarsource.com/")
        }
        licenses {
          license {
            name.set("SSALv1")
            url.set("https://sonarsource.com/license/ssal/")
            distribution.set("repo")
          }
        }
        scm {
          url.set("https://github.com/SonarQubeCommunity/sonar-rust")
        }
        developers {
          developer {
            id.set("sonarsource-team")
            name.set("SonarSource Team")
          }
        }
      }
      artifact(tasks.shadowJar) {
        classifier = null
      }
      artifact(tasks.named("javadocJar"))
      artifact(tasks.named("sourcesJar"))
    }
  }
}
