import java.security.MessageDigest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream
import groovy.json.JsonSlurper
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting

dependencies {
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:compiler:1.7.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.26.1")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaCommunity("2024.3.7.1")
        testFramework(TestFrameworkType.Platform)
        testFramework(
            TestFrameworkType.Starter,
            version = "252.28539.54",
            configurationName = "integrationTestImplementation",
        )
        zipSigner()
    }
}

// --- Lexer ---
//
// The Typst lexer is a hand-written, restartable `LexerBase` + `RestartableLexer`
// (src/main/kotlin/com/livteam/typninja/language/lexer/TypstLexer.kt). There is NO JFlex grammar,
// no generateLexer task, and no build-time JFlex tooling: mode switching and the depth-0-only
// restart contract require full control the JFlex `%state` model cannot express (see the design
// spec "Build implications"). The parser is likewise hand-written (TypstParser.kt) — no Grammar-Kit.

intellijPlatform {
    // Searchable options are generated in release CI.  The current IDE build aborts this
    // auxiliary sandbox task before packaging, although plugin compilation and instrumentation succeed.
    buildSearchableOptions = false
    pluginConfiguration {
        name = "Typstninja"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7.1")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }
    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    }
    publishing {
        channels = listOf("default")
        hidden = true
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.named<BuildPluginTask>("buildPlugin") {
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("src/main/resources/META-INF/third-party-notices.txt")) {
        rename { "THIRD-PARTY-NOTICES.txt" }
    }
}

tasks.named<VerifyPluginTask>("verifyPlugin") {
    offline = true
}

tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
    certificateChain.set(providers.environmentVariable("VERIFY_CERTIFICATE_CHAIN"))
    certificateChainFile.set(
        layout.file(
            providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map(::File),
        ),
    )
}

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        systemProperty("typst.test.project.path", layout.projectDirectory.asFile.absolutePath)
    }
}

tasks.named<KotlinCompile>("compileIntegrationTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}


val prepareTypstWasm by tasks.registering {
    val wasmManifestFile = layout.projectDirectory.file("packages/typst-wasm/plugin-manifest.json")
    val wasmDistributionDirectory = layout.projectDirectory.dir("packages/typst-wasm/dist")
    val bundledWasmDirectory = layout.buildDirectory.dir("generated/typst-wasm")
    group = "build"
    description = "Prepare the pinned Typst WASM engine and its licenses for the plugin"
    inputs.file(wasmManifestFile)
    outputs.dir(bundledWasmDirectory)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(wasmManifestFile.asFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val asset = (manifest.getValue("engines") as List<Map<String, Any>>).last()
        val version = asset.getValue("version").toString()
        val names = setOf("typst_wasm_raw.wasm", "LICENSE", "FONT-NOTICES.txt", "THIRD-PARTY-NOTICES.txt")
        val engineDirectory = wasmDistributionDirectory.dir("${manifest.getValue("releaseVersion")}/$version").asFile
        val digest: (ByteArray) -> String = { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        val files = if (names.all { engineDirectory.resolve(it).isFile }) {
            names.associateWith { engineDirectory.resolve(it).readBytes() }
        } else {
            val uri = URI(asset.getValue("url").toString())
            require(uri.scheme == "https") { "WASM downloads require HTTPS" }
            val archive = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build().use { client ->
                    val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                    check(response.statusCode() == 200) { "WASM download failed: HTTP ${response.statusCode()}" }
                    response.body()
                }
            check(archive.size.toLong() == (asset.getValue("sizeBytes") as Number).toLong() && digest(archive) == asset["sha256"]) { "WASM archive checksum mismatch" }
            buildMap {
                ZipInputStream(archive.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name in names) put(entry.name, zip.readBytes())
                    }
                }
            }
        }
        check(files.keys.containsAll(names)) { "WASM distribution is incomplete" }
        val wasm = files.getValue("typst_wasm_raw.wasm")
        check(wasm.size.toLong() == (asset.getValue("rawWasmSizeBytes") as Number).toLong() && digest(wasm) == asset["rawWasmSha256"]) { "WASM module checksum mismatch; rebuild the distribution" }
        val output = bundledWasmDirectory.get().dir("typst-wasm").asFile
        check(output.deleteRecursively()) { "Cannot replace the generated WASM resources" }
        output.mkdirs()
        output.resolve("$version.wasm").writeBytes(wasm)
        wasmManifestFile.asFile.copyTo(output.resolve("manifest.json"), overwrite = true)
        names.filter { it != "typst_wasm_raw.wasm" }.forEach { output.resolve(it).writeBytes(files.getValue(it)) }
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/typst-wasm")) }
tasks.named("processResources") { dependsOn(prepareTypstWasm) }

tasks.withType<Test>().configureEach {
    // The existing corpus now executes the full Typst compiler inside the test JVM.
    maxHeapSize = "2g"
}
