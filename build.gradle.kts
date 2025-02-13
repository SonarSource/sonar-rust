import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

plugins {
  id("org.sonarqube") version "6.0.1.5171"
  id("com.jfrog.artifactory") version "5.2.5" apply false
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
      setPublishIvy(false) // Publish generated Ivy descriptor files to Artifactory (true by default)
    }
  }
}


