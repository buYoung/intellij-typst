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

    enum class Kind {
        FUNCTION,
        TYPE,
        MODULE,
        VALUE,
    }

    data class Parameter(
        val name: String,
        val isRequired: Boolean = false,
    )

    data class Metadata(
        val name: String,
        val kind: Kind,
        val signature: String? = null,
        val parameters: List<Parameter> = emptyList(),
        val summary: String? = null,
        val documentationPath: String? = null,
    )

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
        "rgb", "luma", "cmyk", "oklab", "oklch", "linear-rgb", "hsv",
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

    /** Global values that are available without imports. */
    val VALUES: Set<String> = setOf(
        "black", "gray", "silver", "white",
        "navy", "blue", "aqua", "teal",
        "eastern", "purple", "fuchsia", "maroon",
        "red", "orange", "yellow", "olive", "green", "lime",
    )

    private val ALL: Set<String> = FUNCTIONS + TYPES + MODULES + VALUES

    private val commonParameters: Map<String, List<Parameter>> = mapOf(
        "text" to listOf(Parameter("fill"), Parameter("size"), Parameter("weight"), Parameter("body", isRequired = true)),
        "heading" to listOf(Parameter("body", isRequired = true)),
        "link" to listOf(Parameter("dest", isRequired = true), Parameter("body")),
        "ref" to listOf(Parameter("target", isRequired = true)),
        "cite" to listOf(Parameter("key", isRequired = true)),
        "image" to listOf(Parameter("path", isRequired = true)),
        "table" to listOf(Parameter("columns"), Parameter("rows"), Parameter("body")),
        "grid" to listOf(Parameter("columns"), Parameter("rows"), Parameter("body")),
        "figure" to listOf(Parameter("body", isRequired = true), Parameter("caption")),
        "bibliography" to listOf(Parameter("path", isRequired = true)),
        "raw" to listOf(Parameter("text", isRequired = true), Parameter("lang")),
    )

    private val commonSummaries: Map<String, String> = mapOf(
        "text" to "Displays text with optional styling.",
        "heading" to "Creates a document heading.",
        "link" to "Creates a link to a URL, label, or location.",
        "ref" to "References a label in the document.",
        "cite" to "Cites a bibliography entry.",
        "image" to "Embeds an image from a path.",
        "table" to "Lays out content in a table.",
        "grid" to "Lays out content in a grid.",
        "figure" to "Wraps content as a numbered figure.",
        "bibliography" to "Includes bibliography data.",
        "raw" to "Displays raw text or code.",
        "color" to "Represents a color value.",
        "str" to "Represents text strings.",
        "int" to "Represents integer numbers.",
        "float" to "Represents floating-point numbers.",
        "bool" to "Represents true or false values.",
    )

    private val FUNCTION_METADATA: Map<String, Metadata> = FUNCTIONS.associateWith { name ->
        val parameters = commonParameters[name].orEmpty()
        Metadata(
            name = name,
            kind = Kind.FUNCTION,
            signature = if (parameters.isEmpty()) "$name(..)" else "$name(${parameters.joinToString { it.name }})",
            parameters = parameters,
            summary = commonSummaries[name],
            documentationPath = "/docs/reference/$name/",
        )
    }

    private val TYPE_METADATA: Map<String, Metadata> = TYPES.associateWith { name ->
        Metadata(
            name = name,
            kind = Kind.TYPE,
            summary = commonSummaries[name],
            documentationPath = "/docs/reference/foundations/$name/",
        )
    }

    private val MODULE_METADATA: Map<String, Metadata> = MODULES.associateWith { name ->
        Metadata(
            name = name,
            kind = Kind.MODULE,
            summary = commonSummaries[name],
            documentationPath = "/docs/reference/$name/",
        )
    }

    private val VALUE_METADATA: Map<String, Metadata> = VALUES.associateWith { name ->
        Metadata(
            name = name,
            kind = Kind.VALUE,
            summary = "Built-in color value.",
            documentationPath = "/docs/reference/visualize/color/",
        )
    }

    private val METADATA: Map<String, Metadata> = FUNCTION_METADATA + TYPE_METADATA + MODULE_METADATA + VALUE_METADATA

    fun isBuiltin(name: String): Boolean = name in ALL
    fun isFunction(name: String): Boolean = name in FUNCTIONS
    fun isType(name: String): Boolean = name in TYPES
    fun isModule(name: String): Boolean = name in MODULES
    fun isValue(name: String): Boolean = name in VALUES
    fun metadata(name: String): Metadata? = METADATA[name]

    fun allMetadata(): Collection<Metadata> = METADATA.values
}
