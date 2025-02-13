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


group = "com.sonarsource.rust"
version = "0.1.0-SNAPSHOT"

// Replaces the version defined in sources, usually x.y-SNAPSHOT, by a version identifying the build.
val buildNumber: String? = System.getProperty("buildNumber")
project.ext["buildNumber"] = buildNumber
val unqualifiedVersion = project.version
if (project.version.toString().endsWith("-SNAPSHOT") && buildNumber != null) {
  val versionSuffix = if (project.version.toString().count { it == '.' } == 1) ".0.$buildNumber" else ".$buildNumber"
  project.version = project.version.toString().replace("-SNAPSHOT", versionSuffix)
}

dependencies {
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("org.sonarsource.analyzer-commons:sonar-analyzer-commons:2.16.0.3141")
  compileOnly("org.sonarsource.api.plugin:sonar-plugin-api:10.1.0.809")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("org.sonarsource.api.plugin:sonar-plugin-api-test-fixtures:10.1.0.809")
  testImplementation("org.sonarsource.sonarqube:sonar-plugin-api-impl:10.1.0.73491")
  testImplementation(platform("org.junit:junit-bom:5.10.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:3.26.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
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
        "Plugin-Class" to "com.sonarsource.rust.plugin.RustPlugin",
        "Plugin-RequiredForLanguages" to "rust",
      )
    )
  }
}

tasks.shadowJar {
  dependsOn(":analyzer:compileRust")
  from(project(":analyzer").tasks.named("compileRust").get().outputs.files) {
    into("analyzer")
  }
  archiveClassifier.set("")
}

spotless {
  java {
    licenseHeader(
      """
            /*
             * Copyright (C) 2025 SonarSource SA
             * All rights reserved
             * mailto:info AT sonarsource DOT com
             */
            """.trimIndent()
    )
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
            name.set("SonarSource")
          }
        }
        scm {
          url.set("https://github.com/SonarSource/sonar-armor")
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
  // The name of this variable is important because it"s used by the delivery process when extracting version from Artifactory build info.
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
      setPublishArtifacts(true)
      setPublishPom(true) // Publish generated POM files to Artifactory (true by default)
      setPublishIvy(false) // Publish generated Ivy descriptor files to Artifactory (true by default)
    }
  }
}

