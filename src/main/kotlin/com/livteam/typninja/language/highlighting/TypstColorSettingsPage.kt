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
            AttributesDescriptor(MyBundle.message("color.settings.typst.function.call"),        TypstTextAttributeKeys.FUNCTION_CALL),
            AttributesDescriptor(MyBundle.message("color.settings.typst.function.declaration"), TypstTextAttributeKeys.FUNCTION_DECLARATION),
            AttributesDescriptor(MyBundle.message("color.settings.typst.variable.definition"),  TypstTextAttributeKeys.VARIABLE_DEFINITION),
            AttributesDescriptor(MyBundle.message("color.settings.typst.variable"),             TypstTextAttributeKeys.VARIABLE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.parameter"),            TypstTextAttributeKeys.PARAMETER),
            AttributesDescriptor(MyBundle.message("color.settings.typst.named.argument"),       TypstTextAttributeKeys.NAMED_ARGUMENT),
            AttributesDescriptor(MyBundle.message("color.settings.typst.field"),                TypstTextAttributeKeys.FIELD),
            AttributesDescriptor(MyBundle.message("color.settings.typst.builtin.function"),      TypstTextAttributeKeys.BUILTIN_FUNCTION),
            AttributesDescriptor(MyBundle.message("color.settings.typst.builtin.type"),          TypstTextAttributeKeys.BUILTIN_TYPE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.escape"),          TypstTextAttributeKeys.ESCAPE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.heading"),         TypstTextAttributeKeys.HEADING),
            AttributesDescriptor(MyBundle.message("color.settings.typst.list.marker"),     TypstTextAttributeKeys.LIST_MARKER),
            AttributesDescriptor(MyBundle.message("color.settings.typst.strong"),          TypstTextAttributeKeys.STRONG),
            AttributesDescriptor(MyBundle.message("color.settings.typst.emphasis"),        TypstTextAttributeKeys.EMPH),
            AttributesDescriptor(MyBundle.message("color.settings.typst.markup.entity"),   TypstTextAttributeKeys.MARKUP_ENTITY),
            AttributesDescriptor(MyBundle.message("color.settings.typst.reference"),       TypstTextAttributeKeys.REFERENCE),
            AttributesDescriptor(MyBundle.message("color.settings.typst.label"),           TypstTextAttributeKeys.LABEL),
            AttributesDescriptor(MyBundle.message("color.settings.typst.link"),            TypstTextAttributeKeys.LINK),
            AttributesDescriptor(MyBundle.message("color.settings.typst.math.identifier"), TypstTextAttributeKeys.MATH_IDENT),
            AttributesDescriptor(MyBundle.message("color.settings.typst.math.operator"),   TypstTextAttributeKeys.MATH_OPERATOR),
            AttributesDescriptor(MyBundle.message("color.settings.typst.bad.character"),   TypstTextAttributeKeys.BAD_CHARACTER),
        )

        /**
         * Preview overlays for the keys the lexer cannot emit: the contextual modifiers
         * (`<strong>` / `<emph>`) and the PSI-driven semantic colors — definition keys
         * (`<fndecl>` / `<vardef>`) and usage keys resolved through references
         * (`<fncall>` / `<varref>` / `<param>` / `<namedarg>`). The color-settings framework strips
         * these tags from [DEMO_TEXT] before lexing and applies the mapped key on top, mirroring what
         * [TypstAnnotator] does in a real editor.
         */
        val TAG_TO_DESCRIPTOR: Map<String, TextAttributesKey> = mapOf(
            "strong" to TypstTextAttributeKeys.STRONG,
            "emph" to TypstTextAttributeKeys.EMPH,
            "fncall" to TypstTextAttributeKeys.FUNCTION_CALL,
            "fndecl" to TypstTextAttributeKeys.FUNCTION_DECLARATION,
            "vardef" to TypstTextAttributeKeys.VARIABLE_DEFINITION,
            "varref" to TypstTextAttributeKeys.VARIABLE,
            "param" to TypstTextAttributeKeys.PARAMETER,
            "namedarg" to TypstTextAttributeKeys.NAMED_ARGUMENT,
            "field" to TypstTextAttributeKeys.FIELD,
        )

        /**
         * Representative Typst demo snippet. Exercises every color category: comments, code/math
         * markers, keywords and keyword literals, strings and raw text, numbers and measurements,
         * identifiers, operators and delimiters, escapes, headings, list/enum/term markers, strong and
         * emphasis, the semantic colors (function call/declaration, variable definition, parameter,
         * named argument — all via preview tags), references, labels, links, math tokens, multi-line
         * code blocks/arrays/dictionaries, and a BAD_CHARACTER in code context.
         */
        val DEMO_TEXT: String = """
// Line comment: document preamble
/* Block comment:
   multi-line note */

#import "utils.typ": helper

= Heading
== Subheading

Plain prose with an \escape and a <strong>*bold*</strong> plus <emph>_italic_</emph> run.

- First bullet
- Second bullet
+ Numbered item
/ Term: its definition

See @intro and visit https://typst.app for details.

#let <vardef>title</vardef> = "Hello, Typst!"
#let <vardef>numbers</vardef> = (1, 2, 3)
#let <fndecl>scale</fndecl>(<param>factor</param>) = <param>factor</param> * 2
#let config = (width: 12pt, ratio: 1.5, flag: true, missing: none, mode: auto)
#config.<field>width</field>

// Usages resolve to their definitions: a variable read, a function read, a parameter read.
#(<varref>title</varref>, <fncall>scale</fncall>(2))

${'$'} x^2 + y^2 <= z^2 ${'$'}

#if <varref>numbers</varref> != none {
  "greater"
} else {
  "fallback"
}

#<fncall>table</fncall>(
  <namedarg>columns</namedarg>: 2,
  "Name", "Value",
  "alpha", "1",
  "beta",  "2",
)

#show heading: <param>it</param> => it
#set <fncall>text</fncall>(<namedarg>size</namedarg>: 12pt)

`inline raw`

// Bad character in code context (@ is not valid inside #{...}):
#{ @ }
""".trimIndent()
    }

    override fun getDisplayName(): String = MyBundle.message("color.settings.typst.display.name")

    override fun getIcon(): Icon = TypstFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = TypstSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = ATTRIBUTE_DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /** Maps the `<strong>` / `<emph>` preview tags in [DEMO_TEXT] to their contextual-modifier keys. */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAG_TO_DESCRIPTOR
}
