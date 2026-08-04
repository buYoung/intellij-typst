# Typstninja

![Build](https://github.com/buYoung/intellij-typst/workflows/Build/badge.svg)

Native [Typst](https://typst.app) language support for IntelliJ-based IDEs 2024.3 and newer.

## Features

- **File recognition** — `.typ` files are opened as Typst source files.
- **Syntax highlighting** — a hand-written restartable lexer highlights keywords, operators, strings, comments, math mode, and more. Colours are fully customisable via Settings → Editor → Color Scheme → Typst.
- **Recoverable parser / PSI** — hand-written parser that keeps the editor usable on incomplete or malformed input; unclosed groups end gracefully without collapsing the rest of the file.
- **Conservative formatter** — normalises spacing and indentation inside closed single-line and multi-line code groups without touching the surrounding markup.
- **Line-comment toggling** — the standard Code → Comment with Line Comment action inserts or removes `//` prefixes.
- **Navigation and usages** — Go To Declaration supports file-local symbols, labels, relative imports, imported names and known builtins; Find Usages is available for supported `#let` declarations.
- **Completion and diagnostics** — contextual completion covers local symbols, import members, labels, named arguments, relative paths, keywords and known Typst standard-library symbols; weak diagnostics flag high-confidence unresolved references, labels and relative imports/includes.
- **Compiler diagnostics** — a project-scoped runtime can compile on save or after a 500ms typing pause, reject stale document generations, and fall back to short-format Typst CLI diagnostics.
- **Preview packages** — completed `@preview/name:version` imports can be downloaded over HTTPS into the configured or default Typst cache, then exposed to navigation and completion.
- **Split editor preview** — Typst files open with the standard IntelliJ editor/preview/split modes. JCEF-capable IDEs use responsive SVG previews with document links and click-to-source navigation; environments without JCEF show an explicit unavailable state. Explicit exports continue to use the Typst CLI.
- **Documentation and signatures** — quick documentation and signature help are available for supported user-defined and builtin symbols.
- **Editor services** — structure view, workspace symbol search, same-symbol highlighting, safe URL/relative-path links, conservative color previews, heading-level intention and comment-aware Enter handling are included.

The native PSI language services remain available when the optional runtime cannot be downloaded or started. IDE runtimes without JCEF show an explicit preview-unavailable state. The plugin can access GitHub Releases to download native runtimes and `packages.typst.org` to download Typst packages; it does not collect telemetry or analytics. The integration does not launch a Tinymist executable or use LSP/Tinymist protocols.

## Build & Run

```
# Compile Kotlin sources
./gradlew compileKotlin

# Test the native runtime
cargo test --manifest-path renderer/Cargo.toml

# Regenerate Rust third-party notices (cargo-about 0.9.1)
cargo about generate renderer/about.hbs --locked --all-features \
  --manifest-path renderer/Cargo.toml \
  --output-file src/main/resources/META-INF/third-party-notices.txt

# Launch a sandboxed IDE with the plugin loaded
./gradlew runIde
```

## Source repository

<https://github.com/buYoung/intellij-typst>

## License

[Apache License 2.0](LICENSE). Native runtime dependency notices are included in [third-party-notices.txt](src/main/resources/META-INF/third-party-notices.txt).
