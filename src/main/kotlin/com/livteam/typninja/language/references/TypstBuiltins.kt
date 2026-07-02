package com.livteam.typninja.language.references

/**
 * Curated set of Typst 0.15 standard-library GLOBAL symbols — the names available without any import.
 *
 * Sourced from the official reference (typst.app/docs/reference). Used to (1) color builtin usages
 * distinctly (mirroring tinymist's `support.function.builtin` / `entity.name.type.primitive` scopes)
 * and (2) navigate a builtin usage to a generated stub. The set is intentionally CONSERVATIVE: an
 * unknown name is simply not treated as a builtin (it stays uncolored and unresolved, exactly as
 * before), so the list can never produce a false positive — only a miss on a name we forgot.
 *
 * `none` / `auto` / `true` / `false` are NOT here: the lexer emits them as dedicated keyword-literal
 * tokens, so they never reach identifier reference resolution.
 */
object TypstBuiltins {

    /** Global functions (called or passed as values). */
    val FUNCTIONS: Set<String> = setOf(
        "text", "par", "parbreak", "heading", "list", "enum", "terms", "table", "grid",
        "figure", "image", "box", "block", "place", "stack", "align", "pad", "columns",
        "colbreak", "pagebreak", "page", "repeat", "move", "scale", "rotate", "skew", "hide",
        "layout", "measure", "h", "v", "line", "rect", "square", "circle", "ellipse", "polygon",
        "curve", "link", "ref", "cite", "footnote", "quote", "bibliography", "outline", "document",
        "numbering", "raw", "strong", "emph", "underline", "overline", "strike", "highlight",
        "sub", "super", "smallcaps", "upper", "lower", "smartquote", "linebreak", "lorem",
        "counter", "state", "query", "locate", "here", "metadata", "assert", "eval", "panic",
        "repr", "target", "plugin", "read", "csv", "json", "toml", "yaml", "xml", "cbor",
    )

    /** Built-in types (usable as values, e.g. `type(x) == int`). */
    val TYPES: Set<String> = setOf(
        "int", "float", "str", "bool", "bytes", "decimal", "label", "content", "array",
        "dictionary", "arguments", "module", "function", "type", "version", "length", "ratio",
        "relative", "fraction", "angle", "alignment", "direction", "color", "gradient", "stroke",
        "tiling", "pattern", "datetime", "duration", "regex", "symbol", "selector", "location",
    )

    /** Top-level modules that group functions/values (`calc.abs`, `sys.version`, `sym.arrow`, …). */
    val MODULES: Set<String> = setOf("calc", "sys", "std", "sym", "emoji")

    private val ALL: Set<String> = FUNCTIONS + TYPES + MODULES

    fun isBuiltin(name: String): Boolean = name in ALL
    fun isFunction(name: String): Boolean = name in FUNCTIONS
    fun isType(name: String): Boolean = name in TYPES
    fun isModule(name: String): Boolean = name in MODULES
}
