


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




}
