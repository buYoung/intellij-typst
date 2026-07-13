import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
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
