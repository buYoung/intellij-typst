<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-typst Changelog

## [Unreleased]
### Added
- Typst file type: `.typ` files are recognised and opened as Typst language files.
- Lexer and token model: hand-written JFlex lexer covering keywords, identifiers, operators, strings, numbers, comments, raw text, math mode, labels, and references.
- Parser and PSI foundation: recoverable hand-written parser producing a Typst PSI tree; unclosed groups terminate gracefully without collapsing the rest of the file.
- Syntax highlighting with colour settings: lexer-token-to-colour-key mapping with `DefaultLanguageHighlighterColors` fallbacks; all categories are exposed in Settings → Editor → Color Scheme → Typst.
- Conservative formatter: normalises spacing and indentation for closed single-line code groups; unclosed or multi-line groups are preserved as-is.
- Line-comment commenter: Code → Comment with Line Comment inserts or removes `//` prefixes; block-comment actions are intentionally unsupported.
