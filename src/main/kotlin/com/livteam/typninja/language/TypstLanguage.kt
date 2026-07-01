package com.livteam.typninja.language

import com.intellij.lang.Language

/**
 * Typst language singleton.
 *
 * Language ID: "Typst" — this exact string is a stable contract for phases 05/06/07
 * and must be reproduced verbatim in every `lang.*` extension point registration in
 * plugin.xml (e.g. syntaxHighlighter, parserDefinition, colorSettingsPage).
 *
 * Immutable singleton; no mutable state.
 */
object TypstLanguage : Language("Typst")
