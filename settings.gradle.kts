pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Trail"
include(":app", ":shared")

// Independent simulator probe; never packaged into the Android application.
include(":iosProbe")
project(":iosProbe").projectDir = file("experiments/ios-map")
