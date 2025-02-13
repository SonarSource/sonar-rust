plugins {
  `maven-publish`
  signing
  id("org.sonarqube") version "6.0.1.5171"
  id("com.jfrog.artifactory") version "5.2.5"
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

sonar {
  properties {
    property("sonar.projectName", "sonar-rust")
    property("sonar.projectKey", "SonarSource_sonar-rust")
    property("sonar.organization", "sonarsource")
    property("sonar.exclusions", "**/build/**/*")
    property("sonar.links.ci", "https://cirrus-ci.com/github/SonarSource/sonar-rust")
    property("sonar.links.scm", "https://github.com/SonarSource/sonar-rust")
    property("sonar.links.issue", "https://jira.sonarsource.com/projects/SKUNK")
  }
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
    }
  }
}
