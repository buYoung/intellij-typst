import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
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
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.26.1")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
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
        ideaVersion {
            sinceBuild = "252"
            untilBuild = "252.*"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
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

tasks.named<RunIdeTask>("runIde") {
    if (System.getenv("TYPST_RUNTIME_PATH").isNullOrBlank()) {
        environment("TYPST_RUNTIME_PATH", localTypstRuntimePath.asFile.absolutePath)
    }
}
