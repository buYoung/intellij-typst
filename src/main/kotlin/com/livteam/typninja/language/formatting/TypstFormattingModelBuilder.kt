package com.livteam.typninja.language.formatting

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent

/**
 * Entry point for Typst formatting.
 *
 * Builds a [TypstBlock] tree rooted at the whole file and wraps it in the platform's standard
 * document-based formatting model, so all edits go through IntelliJ's formatter (minimal whitespace
 * edits, undo-compatible, cancellable). No external process, no whole-file rewrite, no analysis on
 * the EDT beyond the lightweight block walk.
 *
 * Selection formatting is left to the platform: the model spans the file, the engine clips to the
 * requested range, and because preserved regions use read-only spacing and are leaf blocks (see
 * [TypstSpacingBuilder] / [TypstBlock]), a partial selection of prose or of an incomplete structure
 * is not rewritten. `getRangeAffectingIndent` is intentionally not overridden, so no selection ever
 * expands beyond what the user picked.
 *
 * Registered via `<lang.formatter language="Typst" .../>`.
 */
class TypstFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val containingFile = formattingContext.containingFile
        val rootBlock = TypstBlock(
            node = containingFile.node,
            blockIndent = Indent.getNoneIndent(),
            spacingBuilder = TypstSpacingBuilder(settings),
        )
        return FormattingModelProvider.createFormattingModelForPsiFile(containingFile, rootBlock, settings)
    }
}
