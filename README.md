# intellij-typst

![Build](https://github.com/buYoung/intellij-typst/workflows/Build/badge.svg)

Native [Typst](https://typst.app) language support for IntelliJ-based IDEs.

## Features

- **File recognition** — `.typ` files are opened as Typst source files.
- **Syntax highlighting** — lexer-based highlighting for keywords, operators, strings, comments, math mode, and more. Colours are fully customisable via Settings → Editor → Color Scheme → Typst.
- **Recoverable parser / PSI** — hand-written parser that keeps the editor usable on incomplete or malformed input; unclosed groups end gracefully without collapsing the rest of the file.
- **Conservative formatter** — normalises spacing and indentation inside closed single-line code groups without touching the surrounding markup.
- **Line-comment toggling** — the standard Code → Comment with Line Comment action inserts or removes `//` prefixes.

## Not included in this release

Completion, reference resolution, quick documentation, Typst rendering, and PDF preview are deferred to future releases.

## Build & Run

```
# Compile Kotlin sources
./gradlew compileKotlin

# Launch a sandboxed IDE with the plugin loaded
./gradlew runIde
```

## Source repository

<https://github.com/buYoung/intellij-typst>
