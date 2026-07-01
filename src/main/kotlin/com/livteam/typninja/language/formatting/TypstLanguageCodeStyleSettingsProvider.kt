package com.livteam.typninja.language.formatting

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.livteam.typninja.language.TypstLanguage

/**
 * Exposes a Settings → Code Style → Typst page and, more importantly, gives the Typst language its
 * own [CommonCodeStyleSettings.IndentOptions].
 *
 * The formatter ([TypstBlock]) expresses structural indentation purely through
 * [com.intellij.formatting.Indent.getNormalIndent] / [com.intellij.formatting.Indent.getNoneIndent].
 * The concrete width of one indent level is resolved by the engine from the Typst indent options
 * (INDENT_SIZE / TAB_SIZE / USE_TAB_CHARACTER / CONTINUATION_INDENT_SIZE). Registering this provider
 * is what makes those options exist for the language, so a user's indent preferences on the Typst
 * code-style page are honoured by the formatter without any manual column math.
 *
 * Providing [getIndentOptionsEditor] renders the standard "Tabs and Indents" tab.
 *
 * Stateless; registered via `<langCodeStyleSettingsProvider .../>`.
 */
class TypstLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = TypstLanguage

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        // Typst source conventionally uses a 2-space indent.
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        // Only surface the option the formatter actually consumes; indentation is handled by the
        // indent-options editor above. The spacing builder reads KEEP_BLANK_LINES_IN_CODE.
        if (settingsType == SettingsType.BLANK_LINES_SETTINGS) {
            consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE")
        }
    }

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    private companion object {
        private val CODE_SAMPLE =
            """
            #let greeting = "Hello"

            #let numbers = (1, 2, 3)

            #table(
              columns: 2,
              [A], [B],
              [C], [D],
            )

            #if numbers.len() > 0 {
              "non-empty"
            } else {
              "empty"
            }

            = Heading
            This is markup prose with *emphasis*.
            """.trimIndent()
    }
}
