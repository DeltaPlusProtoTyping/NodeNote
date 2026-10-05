// The NodeNote server: Ktor + SQLite. Serves the board API and the browser build of the
// app, and mirrors every board into an Obsidian vault as generated markdown.
//
//   gradlew :server:run --args="--port 8870 --vault <dir>"   run from the repo (serves the
//                                                             wasm build if it has been built)
//   gradlew :server:installDist -PwithWeb                    self-contained install that also
//                                                             bundles the browser app
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

val ktorVersion = "3.1.3"

dependencies {
    implementation(project(":core"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
}

application {
    mainClass.set("com.nodenote.server.MainKt")
}

// `gradlew :server:run` would otherwise run inside server/, where the default web dir
// (shared/build/dist/...) and any relative --data/--vault paths would not resolve.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

// Opt-in (-PwithWeb): bundle the browser app into the jar under web/ so installDist /
// distZip ship a server that needs nothing else. Off by default because the wasm
// production build is slow and plain server work doesn't need it. On the way in,
// tools/prepare-web.mjs adds brotli/gzip copies and wasm preload hints (faster first load).
if (providers.gradleProperty("withWeb").isPresent) {
    val webDist = rootProject.layout.projectDirectory.dir("shared/build/dist/wasmJs/productionExecutable")
    val preparedWeb = layout.buildDirectory.dir("web")
    val prepareWeb = tasks.register<Exec>("prepareWeb") {
        description = "Brotli/gzip copies of the browser app plus wasm preload hints, for the jar."
        dependsOn(":shared:wasmJsBrowserDistribution")
        val script = rootProject.file("tools/prepare-web.mjs")
        inputs.dir(webDist)
        inputs.file(script)
        outputs.dir(preparedWeb)
        executable = nodeExecutable()
        args(script.absolutePath, webDist.asFile.absolutePath, preparedWeb.get().asFile.absolutePath)
    }
    tasks.named<ProcessResources>("processResources") {
        dependsOn(prepareWeb)
        from(preparedWeb) {
            into("web")
        }
    }
}

/** The Node.js the Kotlin/Wasm build downloaded (~/.gradle/nodejs), else `node` from the PATH. */
fun nodeExecutable(): String {
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val dir = File(System.getProperty("user.home"), ".gradle/nodejs").listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("node-v") }
        ?.maxByOrNull { it.name }
    val exe = dir?.let { File(it, if (windows) "node.exe" else "bin/node") }
    return exe?.takeIf { it.isFile }?.absolutePath ?: "node"
}
