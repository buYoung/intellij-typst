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
        val typeName: String? = null,
    )

    data class Metadata(
        val name: String,
        val kind: Kind,
        val signature: String? = null,
        val parameters: List<Parameter> = emptyList(),
        val summary: String? = null,
        val documentationPath: String? = null,
        val returnType: String? = null,
        val isParameterListComplete: Boolean = false,
    )

    /** Global functions (called or passed as values). */
    val FUNCTIONS: Set<String> = setOf(
        "text", "par", "parbreak", "heading", "list", "enum", "terms", "table", "grid",
        "figure", "divider", "title", "image", "box", "block", "place", "stack", "align", "pad", "columns",
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
        "int", "float", "str", "bool", "bytes", "decimal", "label", "content", "array", "path",
        "dictionary", "arguments", "module", "function", "type", "version", "length", "ratio",
        "relative", "fraction", "angle", "alignment", "direction", "color", "gradient", "stroke",
        "tiling", "pattern", "datetime", "duration", "regex", "symbol", "selector", "location",
    )

    /** Top-level modules that group functions/values (`calc.abs`, `sys.version`, `sym.arrow`, …). */
    val MODULES: Set<String> = setOf("calc", "sys", "std", "sym", "emoji", "math", "pdf", "html")

    /** Global values that are available without imports. */
    val VALUES: Set<String> = setOf(
        // Alignment values. These are ordinary global values in Typst, not keywords.
        "start", "left", "center", "right", "end", "top", "horizon", "bottom",
        // Text and layout directions.
        "ltr", "rtl", "ttb", "btt",
        // Named colors.
        "black", "gray", "silver", "white",
        "navy", "blue", "aqua", "teal",
        "eastern", "purple", "fuchsia", "maroon",
        "red", "orange", "yellow", "olive", "green", "lime",
    )

    private val ALL: Set<String> = FUNCTIONS + TYPES + MODULES + VALUES

    private val commonParameters: Map<String, List<Parameter>> = mapOf(
        "text" to listOf(
            Parameter("fill", typeName = "color"), Parameter("font"), Parameter("fallback"),
            Parameter("style"), Parameter("weight"), Parameter("stretch"), Parameter("size", typeName = "length"),
            Parameter("stroke"), Parameter("tracking", typeName = "length"), Parameter("spacing", typeName = "relative"),
            Parameter("cjk-latin-spacing"), Parameter("baseline", typeName = "length"), Parameter("overhang"),
            Parameter("top-edge"), Parameter("bottom-edge"), Parameter("lang", typeName = "str"),
            Parameter("region", typeName = "str"), Parameter("script", typeName = "str"),
            Parameter("dir", typeName = "direction"), Parameter("hyphenate", typeName = "bool"),
            Parameter("costs"), Parameter("kerning", typeName = "bool"), Parameter("alternates", typeName = "bool"),
            Parameter("stylistic-set"), Parameter("ligatures", typeName = "bool"),
            Parameter("discretionary-ligatures", typeName = "bool"), Parameter("historical-ligatures", typeName = "bool"),
            Parameter("number-type"), Parameter("number-width"), Parameter("slashed-zero", typeName = "bool"),
            Parameter("fractions", typeName = "bool"), Parameter("features"),
            Parameter("body", isRequired = true, typeName = "content"),
        ),
        "heading" to listOf(
            Parameter("level", typeName = "int"), Parameter("depth", typeName = "int"),
            Parameter("offset", typeName = "int"), Parameter("numbering"), Parameter("supplement"),
            Parameter("outlined", typeName = "bool"), Parameter("bookmarked", typeName = "bool"),
            Parameter("hanging-indent", typeName = "length"), Parameter("body", isRequired = true, typeName = "content"),
        ),
        "link" to listOf(Parameter("dest", isRequired = true), Parameter("body", typeName = "content")),
        "ref" to listOf(Parameter("target", isRequired = true, typeName = "label"), Parameter("supplement")),
        "cite" to listOf(Parameter("key", isRequired = true), Parameter("supplement"), Parameter("form"), Parameter("style")),
        "image" to listOf(
            Parameter("path", isRequired = true, typeName = "str"), Parameter("format", typeName = "str"),
            Parameter("width"), Parameter("height"), Parameter("alt", typeName = "str"),
            Parameter("fit"), Parameter("scaling"), Parameter("icc"),
        ),
        // Typst 0.15 signatures mirrored from Tinymist's checked completion snapshots.
        "table" to listOf(
            Parameter("columns"), Parameter("rows"), Parameter("gutter"), Parameter("column-gutter"),
            Parameter("row-gutter"), Parameter("fill", typeName = "color"), Parameter("align", typeName = "alignment"),
            Parameter("stroke", typeName = "stroke"), Parameter("inset"), Parameter("body", typeName = "content"),
        ),
        "grid" to listOf(
            Parameter("columns"), Parameter("rows"), Parameter("gutter"), Parameter("column-gutter"),
            Parameter("row-gutter"), Parameter("align", typeName = "alignment"),
            Parameter("fill", typeName = "color"), Parameter("stroke", typeName = "stroke"),
            Parameter("inset"), Parameter("body", typeName = "content"),
        ),
        "figure" to listOf(
            Parameter("placement"), Parameter("scope"), Parameter("caption"), Parameter("kind"),
            Parameter("supplement"), Parameter("numbering"), Parameter("gap", typeName = "length"),
            Parameter("outlined", typeName = "bool"), Parameter("body", isRequired = true, typeName = "content"),
        ),
        "box" to listOf(
            Parameter("width"), Parameter("height"), Parameter("baseline"), Parameter("rel"),
            Parameter("fill", typeName = "color"), Parameter("stroke", typeName = "stroke"),
            Parameter("radius"), Parameter("inset"), Parameter("outset"), Parameter("clip", typeName = "bool"),
            Parameter("body", typeName = "content"),
        ),
        "block" to listOf(
            Parameter("width"), Parameter("height"), Parameter("breakable", typeName = "bool"),
            Parameter("fill", typeName = "color"), Parameter("stroke", typeName = "stroke"),
            Parameter("radius"), Parameter("inset"), Parameter("outset"), Parameter("spacing"),
            Parameter("above"), Parameter("below"), Parameter("clip", typeName = "bool"),
            Parameter("sticky", typeName = "bool"), Parameter("body", typeName = "content"),
        ),
        "align" to listOf(Parameter("alignment", isRequired = true, typeName = "alignment"), Parameter("body", typeName = "content")),
        "place" to listOf(
            Parameter("alignment", typeName = "alignment"), Parameter("scope"), Parameter("float", typeName = "bool"),
            Parameter("clearance", typeName = "length"), Parameter("dx", typeName = "relative"),
            Parameter("dy", typeName = "relative"), Parameter("body", typeName = "content"),
        ),
        "pad" to listOf(
            Parameter("left"), Parameter("top"), Parameter("right"), Parameter("bottom"),
            Parameter("x"), Parameter("y"), Parameter("rest"), Parameter("body", typeName = "content"),
        ),
        "page" to listOf(
            Parameter("paper"), Parameter("width"), Parameter("height"), Parameter("flipped", typeName = "bool"),
            Parameter("margin"), Parameter("binding"), Parameter("columns", typeName = "int"),
            Parameter("fill", typeName = "color"), Parameter("numbering"), Parameter("number-align", typeName = "alignment"),
            Parameter("header"), Parameter("header-ascent"), Parameter("footer"), Parameter("footer-descent"),
            Parameter("background"), Parameter("foreground"), Parameter("body", typeName = "content"),
        ),
        "bibliography" to listOf(Parameter("path", isRequired = true), Parameter("title"), Parameter("full", typeName = "bool"), Parameter("style")),
        "raw" to listOf(Parameter("text", isRequired = true, typeName = "str"), Parameter("block", typeName = "bool"), Parameter("lang", typeName = "str"), Parameter("align", typeName = "alignment"), Parameter("syntaxes"), Parameter("theme"), Parameter("tab-size", typeName = "int")),
    )

    // Only signatures checked against the bundled Tinymist 0.15 snapshots are used to report an
    // unknown named argument. Other entries still power completion and navigation, but remain
    // deliberately permissive so a partial catalog never creates a false editor warning.
    private val completeParameterLists = setOf("table")

    private val returnTypes: Map<String, String> = mapOf(
        "text" to "content", "heading" to "content", "link" to "content", "ref" to "content",
        "cite" to "content", "image" to "content", "table" to "content", "grid" to "content",
        "figure" to "content", "box" to "content", "block" to "content", "align" to "content",
        "place" to "content", "pad" to "content", "page" to "content", "raw" to "content",
        "strong" to "content", "emph" to "content", "underline" to "content", "overline" to "content",
        "strike" to "content", "highlight" to "content", "sub" to "content", "super" to "content",
        "smallcaps" to "content", "upper" to "str", "lower" to "str", "repr" to "str",
        "read" to "str", "csv" to "array", "json" to "dictionary", "toml" to "dictionary",
        "yaml" to "dictionary", "xml" to "array", "cbor" to "dictionary", "type" to "type",
        "rgb" to "color", "luma" to "color", "cmyk" to "color", "oklab" to "color",
        "oklch" to "color", "linear-rgb" to "color", "hsv" to "color", "here" to "location",
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
            returnType = returnTypes[name],
            isParameterListComplete = name in completeParameterLists,
        )
    }

    private val TYPE_METADATA: Map<String, Metadata> = TYPES.associateWith { name ->
        Metadata(
            name = name,
            kind = Kind.TYPE,
            summary = commonSummaries[name],
            documentationPath = "/docs/reference/foundations/$name/",
            returnType = name,
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
        val valueType = when (name) {
            "start", "left", "center", "right", "end", "top", "horizon", "bottom" -> "alignment"
            "ltr", "rtl", "ttb", "btt" -> "direction"
            else -> "color"
        }
        Metadata(
            name = name,
            kind = Kind.VALUE,
            summary = "Built-in $valueType value.",
            documentationPath = if (valueType == "color") "/docs/reference/visualize/color/" else "/docs/reference/layout/$valueType/",
            returnType = valueType,
        )
    }

    /** Conservative 0.15 module members used by all native code-insight consumers. */
    private val MODULE_MEMBERS: Map<String, List<Metadata>> = mapOf(
        "calc" to listOf(
            Metadata("abs", Kind.FUNCTION, "abs(value)", listOf(Parameter("value", true)), "Returns the absolute value."),
            Metadata("ceil", Kind.FUNCTION, "ceil(value)", listOf(Parameter("value", true)), "Rounds up."),
            Metadata("floor", Kind.FUNCTION, "floor(value)", listOf(Parameter("value", true)), "Rounds down."),
            Metadata("min", Kind.FUNCTION, "min(..values)", listOf(Parameter("values", true)), "Returns the smallest value."),
            Metadata("max", Kind.FUNCTION, "max(..values)", listOf(Parameter("values", true)), "Returns the largest value."),
            Metadata("pow", Kind.FUNCTION, "pow(base, exponent)", listOf(Parameter("base", true), Parameter("exponent", true)), "Raises a value to a power."),
        ),
    )

    private val METADATA: Map<String, Metadata> = FUNCTION_METADATA + TYPE_METADATA + MODULE_METADATA + VALUE_METADATA

    fun isBuiltin(name: String): Boolean = name in ALL
    fun isFunction(name: String): Boolean = name in FUNCTIONS
    fun isType(name: String): Boolean = name in TYPES
    fun isModule(name: String): Boolean = name in MODULES
    fun isValue(name: String): Boolean = name in VALUES
    fun metadata(name: String): Metadata? = METADATA[name]

    fun moduleMemberMetadata(moduleName: String, memberName: String): Metadata? =
        MODULE_MEMBERS[moduleName]?.firstOrNull { it.name == memberName }

    fun moduleMembers(moduleName: String): List<Metadata> = MODULE_MEMBERS[moduleName].orEmpty()

    fun allStubNames(): Set<String> = ALL + MODULE_MEMBERS.values.flatten().map(Metadata::name)

    fun stubMetadata(name: String): Metadata? = METADATA[name]
        ?: MODULE_MEMBERS.values.asSequence().flatten().firstOrNull { it.name == name }

    fun allMetadata(): Collection<Metadata> = METADATA.values
}
