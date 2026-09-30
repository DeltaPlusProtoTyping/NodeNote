pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NodeNote"
// core   = serializable models + board patch/sync logic, shared by every client and the server.
// shared = the Compose UI: desktop (JVM) app and the browser (Kotlin/Wasm) app.
// server = Ktor + SQLite backend; serves the web app and mirrors boards into an Obsidian vault.
include(":core")
include(":shared")
include(":server")
