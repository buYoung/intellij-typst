package com.livteam.typninja.language.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.analysis.TypstModuleFiles
import com.livteam.typninja.language.psi.TypstTokenTypes
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

class TypstCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(TypstLanguage),
            TypstCompletionProvider,
        )
    }
}

private object TypstCompletionProvider : CompletionProvider<CompletionParameters>() {

    private val keywords = listOf(
        "let", "set", "show", "context", "if", "else", "for", "while", "import", "include",
        "return", "break", "continue",
    )

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        val snapshot = TypstAnalysis.snapshot(parameters.originalFile)
        val seenNames = HashSet<String>()

        pathCompletionPrefix(parameters, position)?.let { prefix ->
            addRelativePathCompletions(parameters, result.withPrefixMatcher(prefix))
            return
        }

        importMemberContext(parameters)?.let { context ->
            addImportMemberCompletions(parameters, context.pathString, result.withPrefixMatcher(context.prefix))
            return
        }

        if (isUnsupportedLeaf(position)) return

        val importItems = ancestor(position, E.IMPORT_ITEMS)
        if (importItems != null) {
            addImportMemberCompletions(parameters, importItems, result)
            return
        }

        if (isLabelCompletionContext(parameters, position)) {
            val labelResult = result.withPrefixMatcher(labelPrefix(parameters))
            for (label in snapshot?.labels.orEmpty()) {
                if (seenNames.add(label.name)) {
                    labelResult.addElement(LookupElementBuilder.create(label.name).withTypeText("label", true))
                }
                val markerLookup = "@${label.name}"
                if (position.text.startsWith("@") && seenNames.add(markerLookup)) {
                    labelResult.addElement(LookupElementBuilder.create(markerLookup).withTypeText("label", true))
                }
            }
            return
        }

        addNamedArgumentCompletions(position, result, seenNames)

        for (definition in snapshot?.declarations.orEmpty()) {
            if (seenNames.add(definition.name)) {
                result.addElement(
                    LookupElementBuilder.create(definition.name)
                        .withTypeText(typeText(definition.kind), true),
                )
            }
        }

        addImportedSymbolCompletions(parameters, result, seenNames)

        for (metadata in TypstBuiltins.allMetadata()) {
            if (seenNames.add(metadata.name)) {
                val lookup = LookupElementBuilder.create(metadata.name)
                    .withTypeText(metadata.kind.name.lowercase(), true)
                result.addElement(metadata.summary?.let { lookup.withTailText(" - $it", true) } ?: lookup)
            }
        }

        for (keyword in keywords) {
            if (seenNames.add(keyword)) {
                result.addElement(LookupElementBuilder.create(keyword).withTypeText("keyword", true))
            }
        }
    }

    private fun addImportedSymbolCompletions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        seenNames: MutableSet<String>,
    ) {
        val snapshot = TypstAnalysis.snapshot(parameters.originalFile) ?: return
        for (locatedImport in snapshot.imports) {
            val summary = locatedImport.summary
            if (summary.isPackageImport) continue

            for (item in summary.items) {
                if (seenNames.add(item.localName)) {
                    result.addElement(LookupElementBuilder.create(item.localName).withTypeText("import", true))
                }
            }

            if (!summary.isGlob) continue
            val moduleFile = TypstModuleFiles.resolveModuleFile(sourceFile(parameters), summary.pathString) ?: continue
            for (definition in TypstAnalysis.snapshot(moduleFile)?.exportedDefinitions().orEmpty()) {
                if (seenNames.add(definition.name)) {
                    result.addElement(
                        LookupElementBuilder.create(definition.name)
                            .withTypeText(typeText(definition.kind), true),
                    )
                }
            }
        }
    }

    private fun isLabelCompletionContext(parameters: CompletionParameters, position: PsiElement): Boolean {
        if (position.node.elementType == T.REF_MARKER || ancestor(position, E.REF) != null) return true
        val text = parameters.editor.document.charsSequence
        var offset = parameters.offset - 1
        while (offset >= 0) {
            val char = text[offset]
            if (char == '@') return true
            if (char.isWhitespace() || char in "[](){}.,;:") return false
            offset--
        }
        return false
    }

    private fun labelPrefix(parameters: CompletionParameters): String {
        val text = parameters.editor.document.charsSequence
        var start = parameters.offset - 1
        while (start >= 0 && text[start] != '@') {
            if (text[start].isWhitespace() || text[start] in "[](){}.,;:") return ""
            start--
        }
        return if (start >= 0 && text[start] == '@') {
            text.subSequence(start + 1, parameters.offset).toString()
        } else {
            ""
        }
    }

    private fun addImportMemberCompletions(
        parameters: CompletionParameters,
        importItems: ASTNode,
        result: CompletionResultSet,
    ) {
        val moduleImport = parentOfType(importItems, E.MODULE_IMPORT) ?: return
        val summary = TypstAnalysis.parseImport(moduleImport)
        if (summary.isPackageImport) return
        addImportMemberCompletions(parameters, summary.pathString, result)
    }

    private fun addImportMemberCompletions(
        parameters: CompletionParameters,
        pathString: String?,
        result: CompletionResultSet,
    ) {
        val moduleFile = TypstModuleFiles.resolveModuleFile(sourceFile(parameters), pathString) ?: return
        for (definition in TypstAnalysis.snapshot(moduleFile)?.exportedDefinitions().orEmpty()) {
            result.addElement(
                LookupElementBuilder.create(definition.name)
                    .withTypeText(typeText(definition.kind), true),
            )
        }
    }

    private fun addNamedArgumentCompletions(
        position: PsiElement,
        result: CompletionResultSet,
        seenNames: MutableSet<String>,
    ) {
        if (ancestor(position, E.ARGS) == null) return
        val call = ancestor(position, E.FUNC_CALL) ?: return
        val calleeName = firstIdentifierText(call) ?: return
        val metadata = TypstBuiltins.metadata(calleeName) ?: return
        for (parameter in metadata.parameters) {
            if (seenNames.add(parameter.name)) {
                result.addElement(
                    LookupElementBuilder.create("${parameter.name}:")
                        .withPresentableText(parameter.name)
                        .withTypeText("argument", true),
                )
            }
        }
    }

    private fun addRelativePathCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val baseDirectory = sourceFile(parameters).virtualFile?.parent ?: return
        val importLike = ancestor(parameters.position, E.MODULE_IMPORT) != null ||
            ancestor(parameters.position, E.MODULE_INCLUDE) != null
        for (child in baseDirectory.children.sortedBy { it.name }) {
            when {
                child.isDirectory -> result.addElement(
                    LookupElementBuilder.create("${child.name}/").withTypeText("directory", true),
                )
                !importLike || child.extension == "typ" -> result.addElement(
                    LookupElementBuilder.create(child.name).withTypeText("path", true),
                )
            }
        }
    }

    private fun pathCompletionPrefix(parameters: CompletionParameters, position: PsiElement): String? =
        when {
            importPathStringPrefix(parameters) != null -> importPathStringPrefix(parameters)
            isPathStringContext(position) -> ""
            else -> null
        }

    private fun isPathStringContext(position: PsiElement): Boolean =
        position.node.elementType == T.STRING &&
            (
                ancestor(position, E.MODULE_IMPORT) != null ||
                    ancestor(position, E.MODULE_INCLUDE) != null ||
                    pathFunctionName(position) != null
                )

    private fun sourceFile(parameters: CompletionParameters) =
        listOfNotNull(
            FileDocumentManager.getInstance().getFile(parameters.editor.document)
                ?.let { PsiManager.getInstance(parameters.position.project).findFile(it) },
            parameters.originalFile.originalFile,
            parameters.originalFile,
            parameters.position.containingFile.originalFile,
        )
            .firstOrNull { it.virtualFile != null }
            ?: parameters.originalFile

    private data class ImportMemberContext(val pathString: String, val prefix: String)

    private fun importMemberContext(parameters: CompletionParameters): ImportMemberContext? {
        val beforeCaret = currentLineBeforeCaret(parameters)
        val match = Regex("""#\s*import\s+"([^"]*)"\s*:\s*([^"\n]*)$""").find(beforeCaret) ?: return null
        return ImportMemberContext(match.groupValues[1], cleanupCompletionDummy(match.groupValues[2]).trimStart())
    }

    private fun importPathStringPrefix(parameters: CompletionParameters): String? {
        val beforeCaret = currentLineBeforeCaret(parameters)
        val match = Regex("""#\s*(import|include)\s+"([^"\n]*)$""").find(beforeCaret)
            ?: Regex("""#\s*(image|bibliography|read|csv|json|toml|yaml|xml|cbor)\s*\([^"\n]*"([^"\n]*)$""").find(beforeCaret)
            ?: return null
        return cleanupCompletionDummy(match.groupValues.last())
    }

    private fun currentLineBeforeCaret(parameters: CompletionParameters): String {
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset.coerceIn(0, text.length)
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
        return text.subSequence(lineStart, offset).toString()
    }

    private fun cleanupCompletionDummy(text: String): String =
        text.replace("IntellijIdeaRulezzz", "").replace("IntellijIdeaRulezzz ", "")

    private fun pathFunctionName(position: PsiElement): String? {
        if (ancestor(position, E.ARGS) == null) return null
        val call = ancestor(position, E.FUNC_CALL) ?: return null
        val name = firstIdentifierText(call) ?: return null
        return name.takeIf { it in pathFunctions }
    }

    private fun isUnsupportedLeaf(position: PsiElement): Boolean {
        val type = position.node.elementType
        return type == T.LINE_COMMENT ||
            type == T.BLOCK_COMMENT ||
            type == T.RAW_TEXT ||
            (type == T.STRING && !isPathStringContext(position))
    }

    private fun ancestor(element: PsiElement, type: com.intellij.psi.tree.IElementType): ASTNode? =
        parentOfType(element.node, type)

    private fun parentOfType(node: ASTNode?, type: com.intellij.psi.tree.IElementType): ASTNode? {
        var current = node
        while (current != null) {
            if (current.elementType == type) return current
            current = current.treeParent
        }
        return null
    }

    private fun firstIdentifierText(node: ASTNode): String? {
        if (node.elementType == T.IDENTIFIER) return node.text
        var child = node.firstChildNode
        while (child != null) {
            firstIdentifierText(child)?.let { return it }
            child = child.treeNext
        }
        return null
    }

    private fun typeText(kind: TypstDefinitionKind): String = when (kind) {
        TypstDefinitionKind.LET_VARIABLE -> "variable"
        TypstDefinitionKind.LET_FUNCTION -> "function"
        TypstDefinitionKind.PARAMETER -> "parameter"
        TypstDefinitionKind.LOOP_BINDING -> "loop binding"
        TypstDefinitionKind.LABEL -> "label"
        TypstDefinitionKind.BUILTIN_FUNCTION -> "builtin function"
        TypstDefinitionKind.BUILTIN_TYPE -> "builtin type"
        TypstDefinitionKind.BUILTIN_MODULE -> "builtin module"
        TypstDefinitionKind.BUILTIN_VALUE -> "builtin value"
    }

    private val pathFunctions = setOf("image", "bibliography", "read", "csv", "json", "toml", "yaml", "xml", "cbor")
}
