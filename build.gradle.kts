import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    // JFlex source generation, provided natively by the IntelliJ Platform Gradle Plugin
    // (successor to the now-sunset standalone org.jetbrains.grammarkit plugin).
    id("org.jetbrains.intellij.platform.grammarkit")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)

        // Build-time-only JFlex tooling used by the generateLexer task.
        // No-arg form lets the platform pick a version compatible with the target IDE.
        // This is NOT a plugin runtime dependency.
        // grammarKit() is intentionally omitted — the parser is hand-written (TypstParser.kt);
        // there is no Typst.bnf and no generateParser task.
        jflex()
    }
}

// --- Typst lexer source generation ---
//
// Grammar input (created and filled by phase 04):
//   - src/main/grammar/Typst.flex  ->  JFlex lexer source  ->  _TypstLexer.java
//
// The parser is hand-written (TypstParser.kt); no Typst.bnf / generateParser wiring.
//
// Generated output lives under build/ (regenerated, never committed) and is kept
// completely separate from hand-written sources in src/main/kotlin, so it cannot
// shadow any com.livteam.typninja package file.
val grammarKitLexerOutputDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/sources/grammarkit-lexer/java/main")

tasks {
    named<GenerateLexerTask>("generateLexer") {
        sourceFile = layout.projectDirectory.file("src/main/grammar/Typst.flex")
        targetRootOutputDir = grammarKitLexerOutputDir
        purgeOldFiles = true
    }

    // Lexer generation must precede compilation so that _TypstLexer.java is available
    // when Kotlin/Java sources are compiled.
    named("compileKotlin") { dependsOn("generateLexer") }
    named("compileJava") { dependsOn("generateLexer") }
}

// Register the generated lexer output as a Java source root so it compiles and is
// visible to Kotlin. Gradle tolerates this dir being absent until generation has run.
sourceSets {
    main {
        java {
            srcDir(grammarKitLexerOutputDir)
        }
    }
}
