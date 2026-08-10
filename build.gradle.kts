import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
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
    testImplementation("junit:junit:4.13.2")
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
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

val localTypstRuntimeExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "typst-runtime.exe"
} else {
    "typst-runtime"
}
val localTypstRuntimePath = layout.projectDirectory.file("renderer/target/debug/$localTypstRuntimeExecutable")

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        systemProperty("typst.test.project.path", layout.projectDirectory.asFile.absolutePath)
        environment("TYPST_RUNTIME_PATH", localTypstRuntimePath.asFile.absolutePath)
    }
}

tasks.named<KotlinCompile>("compileIntegrationTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

tasks.named<RunIdeTask>("runIde") {
    if (System.getenv("TYPST_RUNTIME_PATH").isNullOrBlank()) {
        environment("TYPST_RUNTIME_PATH", localTypstRuntimePath.asFile.absolutePath)
    }
}
