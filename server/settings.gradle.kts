// 서버는 앱(Android) 빌드와 분리한 Gradle 빌드다 (docs/adr/0001-backend.md)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "phonedoctor-server"
