pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "circle-suite"

include(":core:crypto")
include(":core:protocol")
include(":vault-core")
include(":circle-core")
include(":vault")
include(":circleid")
