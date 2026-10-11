pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google(); mavenCentral()
    maven {
      url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
      credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USER") ?: System.getenv("USERNAME") ?: ""
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN") ?: System.getenv("TOKEN") ?: ""
      }
    }
  }
}

rootProject.name = "ktop"
include(":app")
