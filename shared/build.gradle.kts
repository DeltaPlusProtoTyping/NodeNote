import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val ktorVersion = "3.1.3"

kotlin {
    jvmToolchain(17)

    // ProjectStorage uses an expect/actual object; this opts in to that (stable in practice, formally Beta).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Windows / desktop target (run with: gradlew :shared:run)
    jvm("desktop")

    // Browser target. Build with: gradlew :shared:wasmJsBrowserDistribution
    // (output: shared/build/dist/wasmJs/productionExecutable, served by the :server module).
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "app"
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
        binaries.executable()
    }

    // iOS targets. These are configured here so the module is ready for an iOS app,
    // but they can only be compiled on a macOS host with Xcode installed.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            }
        }
        wasmJsMain.dependencies {
            implementation("io.ktor:ktor-client-js:$ktorVersion")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nodenote.MainKt"

        nativeDistributions {
            // Msi = Windows installer, Deb = Debian/Ubuntu/Mint installer, Dmg = macOS.
            // Each is only buildable on its own OS (jpackage targets the host OS), so the
            // GitHub Actions release workflow builds each on its matching runner.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "NodeNote"
            packageVersion = "2.0.0"
            // The trimmed runtime needs EC crypto for HTTPS (Tailscale serve certificates).
            modules("jdk.crypto.ec")

            windows {
                // jpackage/MSI can't show a "create a shortcut?" prompt or auto-launch
                // after install, so we just always create the shortcuts and install
                // per-user (no UAC prompt; shortcut lands on the current user's Desktop).
                shortcut = true      // Desktop shortcut
                menu = true          // Start Menu entry
                perUserInstall = true
                dirChooser = true    // let the user pick the install folder in the wizard
                upgradeUuid = "ea9ab37e-fc40-4600-85e2-a809fc7e14c0"
            }
            linux {
                // Debian package ids are lowercase by convention; jpackage wants a maintainer.
                packageName = "nodenote"
                debMaintainer = "benpauza@gmail.com"
            }
            macOS {
                bundleID = "com.nodenote.app"
            }
        }
    }
}
