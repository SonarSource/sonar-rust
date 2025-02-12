plugins {
    id("org.sonarqube") version "6.0.1.5171"
}


allprojects {
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



}
