<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Typstninja Changelog

## [Unreleased]

## [0.1.0] - 2026-08-03
### Added
- Project-scoped Typst runtime protocol v1 with versioned request correlation, open-document overlays, compiler diagnostics, secure `@preview` package installation, and an editor-integrated responsive JCEF SVG preview with click-to-source navigation.
- Typst files now use the IntelliJ editor/preview/split surface instead of a separate preview Tool Window.
- Parse single discarded-parameter closures such as `_ => value` without false syntax errors.
- Resolve the Typst standard-library `range` function globally as well as through `array.range`.
- Platform runtime release builds for Apple Silicon macOS, Intel macOS, 64-bit Windows, and 64-bit GNU/Linux, including size and SHA-256 manifest verification.
- Settings for compiler diagnostic trigger, package auto-download, native renderer use, and renderer auto-download.
- Typst file type: `.typ` files are recognised and opened as Typst language files.
- Lexer and token model: hand-written restartable lexer covering keywords, identifiers, operators, strings, numbers, comments, raw text, math mode, labels, and references.
- Parser and PSI foundation: recoverable hand-written parser producing a Typst PSI tree; unclosed groups terminate gracefully without collapsing the rest of the file.
- Syntax highlighting with colour settings: lexer-token-to-colour-key mapping with `DefaultLanguageHighlighterColors` fallbacks; all categories are exposed in Settings → Editor → Color Scheme → Typst.
- Conservative formatter: normalises spacing and indentation for closed single-line and multi-line code groups; unclosed groups are preserved as-is.
- Line-comment commenter: Code → Comment with Line Comment inserts or removes `//` prefixes; block-comment actions are intentionally unsupported.
