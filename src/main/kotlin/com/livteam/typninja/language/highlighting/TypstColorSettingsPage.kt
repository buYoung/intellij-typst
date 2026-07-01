package com.livteam.typninja.language.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.livteam.typninja.MyBundle
import com.livteam.typninja.language.TypstFileType
import javax.swing.Icon

/**
 * Color settings page for Typst, exposed in IDE Settings → Editor → Color Scheme → Typst.
 *
 * The demo text is a representative Typst snippet that exercises every color category.
 * All attribute display names are sourced from [MyBundle] so they are resource-bundle-ready.
 *
 * Registered in plugin.xml:
 * ```xml
 * <colorSettingsPage implementation="com.livteam.typninja.language.highlighting.TypstColorSettingsPage"/>
 * ```
 */
class TypstColorSettingsPage : ColorSettingsPage {

    private companion object {
        val ATTRIBUTE_DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            AttributesDescriptor(MyBundle.message("color.settings.typst.line.comment"),    TypstTextAttributeKeys.LINE_COMMENT),
            AttributesDescriptor(MyBundle.message("color.settings.typst.block.comment"),   TypstTextAttributeKeys.BLOCK_COMMENT),
            AttributesDescriptor(MyBundle.message("color.settings.typst.string"),          TypstTextAttributeKeys.STRING),
            AttributesDescriptor(MyBundle.message("color.settings.typst.raw.text"),        TypstTextAttributeKeys.RAW_TEXT),
            AttributesDescriptor(MyBundle.message("color.settings.typst.hash"),            TypstTextAttributeKeys.HASH),
            AttributesDescriptor(MyBundle.message("color.settings.typst.dollar"),          TypstTextAttributeKeys.DOLLAR),
            AttributesDescriptor(MyBundle.message("color.settings.typst.keyword"),         TypstTextAttributeKeys.KEYWORD),
            AttributesDescriptor(MyBundle.message("color.settings.typst.keyword.literal"), TypstTextAttributeKeys.KEYWORD_LITERAL),
            AttributesDescriptor(MyBundle.message("color.settings.typst.number"),          TypstTextAttributeKeys.NUMBER),
            AttributesDescriptor(MyBundle.message("color.settings.typst.operator"),        TypstTextAttributeKeys.OPERATOR),
            AttributesDescriptor(MyBundle.message("color.settings.typst.delimiter"),       TypstTextAttributeKeys.DELIMITER),
            AttributesDescriptor(MyBundle.message("color.settings.typst.identifier"),      TypstTextAttributeKeys.IDENTIFIER),
            AttributesDescriptor(MyBundle.message("color.settings.typst.escape"),          TypstTextAttributeKeys.ESCAPE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.reference"),       TypstTextAttributeKeys.REFERENCE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.bad.character"),   TypstTextAttributeKeys.BAD_CHARACTER),
        )

        /**
         * Representative Typst demo snippet.
         *
         * Exercises all token categories:
         * - LINE_COMMENT, BLOCK_COMMENT
         * - HASH, DOLLAR, KW_* (keywords)
         * - TRUE, FALSE, NONE, AUTO (keyword literals)
         * - STRING, RAW_TEXT
         * - INTEGER_LITERAL, FLOAT_LITERAL
         * - IDENTIFIER
         * - OPERATOR (arithmetic, comparison, assignment, punctuation)
         * - DELIMITER (parens, braces, brackets)
         * - ESCAPE
         * - REFERENCE (@label sigil in markup)
         * - BAD_CHARACTER (@ in pure code context)
         * - TEXT (plain markup prose, content-block prose)
         *
         * Multi-line code blocks (#if, #table) highlight correctly after R3 lexer fix.
         */
        val DEMO_TEXT: String = """
// Line comment: document preamble
/* Block comment:
   multi-line note */

#import "utils.typ": helper

#let title = "Hello, Typst!"
#let count = 42
#let ratio = 1.5
#let flag = true
#let missing = none
#let mode = auto

= Heading

This is plain text with an \escape sequence.

See @introduction and @fig:1 for details.

#emph[Prose inside a content block — #strong[nested markup] works too.]

${'$'}x^2 + y^2 = z^2${'$'}

#if flag {
  "greater"
} else if count != 0 {
  "nonzero"
}

#table(
  columns: 2,
  "Name", "Value",
  "alpha", "1",
  "beta",  "2",
)

#show heading: it => it
#set text(size: 12pt)

`inline raw`

// Bad character in code context (@  is not valid inside #{...}):
#{ @ }
""".trimIndent()
    }

    override fun getDisplayName(): String = MyBundle.message("color.settings.typst.display.name")

    override fun getIcon(): Icon = TypstFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = TypstSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = ATTRIBUTE_DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /** No custom XML-tag-based additional highlighting; the lexer handles all token coloring. */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
}
