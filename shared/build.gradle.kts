import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)

    // ProjectStorage uses an expect/actual object; this opts in to that (stable in practice, formally Beta).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Windows / desktop target (run with: gradlew :shared:run)
    jvm("desktop")

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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
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
            packageVersion = "1.0.1"

            windows {
                // jpackage/MSI can't show a "create a shortcut?" prompt or auto-launch
                // after install, so we just always create the shortcuts and install
                // per-user (no UAC prompt; shortcut lands on the current user's Desktop).
                shortcut = true      // Desktop shortcut
                menu = true          // Start Menu entry
                perUserInstall = true
                dirChooser = true    // let the user pick the install folder in the wizard
                upgradeUuid = "8f0d6d3e-2b6b-4f3a-9b2a-2d8e6a1c4f57"
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
