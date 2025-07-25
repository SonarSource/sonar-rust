import org.gradle.internal.os.OperatingSystem
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

plugins {
  id("java")
  id("jacoco")
  `maven-publish`
  signing
  id("com.diffplug.spotless") version "7.0.2"
  id("org.sonarqube")
  id("com.jfrog.artifactory")
  id("com.gradleup.shadow") version "8.3.5"
}


// Replaces the version defined in sources, usually x.y-SNAPSHOT, by a version identifying the build.
val buildNumber: String? = System.getProperty("buildNumber")
project.ext["buildNumber"] = buildNumber
val unqualifiedVersion = project.version
if (project.version.toString().endsWith("-SNAPSHOT") && buildNumber != null) {
  val versionSuffix = if (project.version.toString().count { it == '.' } == 1) ".0.$buildNumber" else ".$buildNumber"
  project.version = project.version.toString().replace("-SNAPSHOT", versionSuffix)
}

val sonarApiVersion = "11.4.0.2922"
val sonarApiImplVersion = "25.2.0.102705"
val analyzerCommonsVersion = "2.17.0.3322"

dependencies {
  implementation("com.google.code.gson:gson:2.13.1")
  implementation("org.sonarsource.analyzer-commons:sonar-analyzer-commons:$analyzerCommonsVersion")
  implementation("org.sonarsource.analyzer-commons:sonar-xml-parsing:$analyzerCommonsVersion")
  implementation("org.tukaani:xz:1.10")
  compileOnly("org.sonarsource.api.plugin:sonar-plugin-api:$sonarApiVersion")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("org.sonarsource.api.plugin:sonar-plugin-api-test-fixtures:$sonarApiVersion")
  testImplementation("org.sonarsource.sonarqube:sonar-plugin-api-impl:$sonarApiImplVersion")
  testImplementation(platform("org.junit:junit-bom:5.12.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:3.27.3")
  testImplementation("org.mockito:mockito-core:5.18.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
  withSourcesJar()
  withJavadocJar()
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

tasks.named("check") {
  dependsOn(":analyzer:testRust")
  dependsOn(":analyzer:checkRustFormat")
  dependsOn(":analyzer:checkRustLicense")
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
        "Implementation-Version" to project.version, // class.getPackage().getImplementationVersion()
        "Plugin-Class" to "org.sonarsource.rust.plugin.RustPlugin",
        "Plugin-RequiredForLanguages" to "rust",
      )
    )
  }
}

tasks.register<Copy>("copyRustOutputs") {
  description = "Copy native analyzer binary to the build/classes dir for packaging"
  group = "Build"
  val compileRustLinuxMusl = project(":analyzer").tasks.named("compileRustLinuxMusl").get()
  val compileRustLinuxArm = project(":analyzer").tasks.named("compileRustLinuxArm").get()
  val compileRustWin = project(":analyzer").tasks.named("compileRustWin").get()
  val compileRustDarwin = project(":analyzer").tasks.named("compileRustDarwin").get()
  val compileRustDarwinX86 = project(":analyzer").tasks.named("compileRustDarwinX86").get()

  dependsOn(compileRustLinuxMusl, compileRustWin)
  if (OperatingSystem.current().isMacOsX) {
    dependsOn(compileRustDarwin, compileRustDarwinX86)
  }
  from(compileRustLinuxMusl.outputs.files) {
    into("linux-x64-musl")
  }
  from(compileRustLinuxArm.outputs.files) {
    into("linux-aarch64-musl")
  }
  from(compileRustWin.outputs.files) {
    into("win-x64")
  }
  // we hardcode the path to the binary because on CI binary is downloaded from another task
  from("${project(":analyzer").layout.projectDirectory}/target/aarch64-apple-darwin/release/analyzer.xz") {
    into("darwin-aarch64")
  }
  from("${project(":analyzer").layout.projectDirectory}/target/x86_64-apple-darwin/release/analyzer.xz") {
    into("darwin-x86_64")
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
  val artifactoryUsername = System.getenv("ARTIFACTORY_PRIVATE_USERNAME") ?: project.findProperty("artifactoryUsername")
  val artifactoryPassword = System.getenv("ARTIFACTORY_PRIVATE_PASSWORD") ?: project.findProperty("artifactoryPassword")
  if (artifactoryUsername is String && artifactoryPassword is String)
    maven {
      url = uri("https://repox.jfrog.io/repox/sonarsource")
      credentials {
        username = artifactoryUsername
        password = artifactoryPassword
      }
    }
  mavenLocal()
  mavenCentral()
}

signing {
  val signingKeyId: String? by project
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
  sign(publishing.publications)
  setRequired { gradle.taskGraph.hasTask(":artifactoryPublish") }
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
          url.set("https://github.com/SonarSource/sonar-rust")
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

configure<ArtifactoryPluginConvention> {
  clientConfig.info.buildName = "sonar-rust"
  clientConfig.info.buildNumber = System.getenv("BUILD_NUMBER")
  clientConfig.isIncludeEnvVars = true
  clientConfig.envVarsExcludePatterns =
    "*password*,*PASSWORD*,*secret*,*MAVEN_CMD_LINE_ARGS*,sun.java.command,*token*,*TOKEN*,*LOGIN*,*login*,*key*,*KEY*,*PASSPHRASE*,*signing*"

  // Define the artifacts to be deployed to https://binaries.sonarsource.com on releases
  clientConfig.info.addEnvironmentProperty("ARTIFACTS_TO_PUBLISH", "${project.group}:sonar-rust-plugin:jar")
  // The name of this variable is important because it's used by the delivery process when extracting version from Artifactory build info.
  clientConfig.info.addEnvironmentProperty("PROJECT_VERSION", version.toString())

  setContextUrl(System.getenv("ARTIFACTORY_URL"))
  publish {
    repository {
      repoKey = System.getenv("ARTIFACTORY_DEPLOY_REPO")
      username = System.getenv("ARTIFACTORY_DEPLOY_USERNAME")
      password = System.getenv("ARTIFACTORY_DEPLOY_PASSWORD")
    }
    defaults {
      setProperties(
        mapOf(
          "build.name" to "sonar-rust",
          "build.number" to project.ext["buildNumber"].toString(),
          "pr.branch.target" to System.getenv("PULL_REQUEST_BRANCH_TARGET"),
          "pr.number" to System.getenv("PULL_REQUEST_NUMBER"),
          "vcs.branch" to System.getenv("GIT_BRANCH"),
          "vcs.revision" to System.getenv("GIT_COMMIT"),
          "version" to project.version.toString()
        )
      )
      publications("mavenJava")
      setPublishIvy(false) // Publish generated Ivy descriptor files to Artifactory (true by default)
    }
  }
}

