package com.livteam.typninja.language.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import java.awt.GraphicsEnvironment
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.analysis.TypstDefinition
import com.livteam.typninja.language.analysis.TypstModuleFiles
import com.livteam.typninja.language.analysis.TypstProjectLabels
import com.livteam.typninja.language.analysis.TypstProjectBibliography
import com.livteam.typninja.language.analysis.TypstTypeInference
import com.livteam.typninja.language.psi.TypstTokenTypes
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.settings.TypstSettingsService
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

class TypstCompletionContributor : CompletionContributor(), DumbAware {

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

        packageSpecificationPrefix(parameters)?.let { prefix ->
            for (specification in TypstPackageResolver.installedSpecifications(position.project)) {
                result.withPrefixMatcher(prefix).addElement(
                    LookupElementBuilder.create(specification).withTypeText("installed package", true),
                )
            }
            return
        }

        rawLanguagePrefix(parameters)?.let { prefix ->
            val rawResult = result.withPrefixMatcher(prefix)
            TypstCompletionData.rawLanguages.forEach { language ->
                rawResult.addElement(LookupElementBuilder.create(language).withTypeText("raw language", true))
            }
            return
        }

        fontFamilyPrefix(parameters)?.let { prefix ->
            val fontResult = result.withPrefixMatcher(prefix)
            TypstCompletionData.fontFamilies.forEach { family ->
                fontResult.addElement(LookupElementBuilder.create(family).withTypeText("font family", true))
            }
            return
        }

        pathCompletionPrefix(parameters, position)?.let { prefix ->
            addRelativePathCompletions(parameters, result.withPrefixMatcher(prefix), prefix)
            return
        }

        importMemberContext(parameters)?.let { context ->
            addImportMemberCompletions(parameters, context.pathString, result.withPrefixMatcher(context.prefix), context.prefix)
            return
        }

        if (isUnsupportedLeaf(position)) return

        val postfixContext = postfixContext(parameters)
        val addedPostfix = postfixContext?.let {
            addPostfixCompletions(parameters, result.withPrefixMatcher(it.prefix), it)
            true
        } ?: false

        mathCompletionPath(parameters)?.let { path ->
            val ownerPath = path.substringBeforeLast('.', missingDelimiterValue = "")
            val prefix = path.substringAfterLast('.')
            val mathResult = result.withPrefixMatcher(prefix)
            if (ownerPath.isNotEmpty()) {
                val metadata = when {
                    ownerPath == "sym" || ownerPath == "emoji" || ownerPath.startsWith("sym.") || ownerPath.startsWith("emoji.") ->
                        TypstBuiltins.dottedMembers(ownerPath)
                    else -> (TypstBuiltins.moduleMembers(ownerPath) + TypstBuiltins.dottedMembers("sym.$ownerPath"))
                        .distinctBy(TypstBuiltins.Metadata::name)
                }
                metadata.forEach { mathResult.addElement(metadataLookup(it)) }
                return
            }
            for (definition in TypstAnalysis.visibleDefinitions(position)) {
                if (seenNames.add(definition.name)) {
                    mathResult.addElement(definitionLookup(definition))
                }
            }
            addImportedSymbolCompletions(parameters, mathResult, seenNames)
            for (metadata in TypstBuiltins.mathMembers()) {
                if (seenNames.add(metadata.name)) mathResult.addElement(metadataLookup(metadata))
            }
            return
        }

        ancestor(position, E.FIELD_ACCESS)?.psi?.let { it as? TypstFieldAccess }?.let { fieldAccess ->
            for (definition in TypstAnalysis.fieldDefinitions(fieldAccess)) {
                if (seenNames.add(definition.name)) {
                    val metadata = fieldAccess.qualifierPath?.let { TypstBuiltins.dottedMemberMetadata(it, definition.name) }
                    result.addElement(metadata?.let(::metadataLookup) ?: definitionLookup(definition))
                }
            }
            return
        }

        if (addedPostfix) return

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
            for (labelName in TypstProjectLabels.names(position.project)) {
                if (seenNames.add(labelName)) {
                    labelResult.addElement(LookupElementBuilder.create(labelName).withTypeText("project label", true))
                }
                val markerLookup = "@$labelName"
                if (position.text.startsWith("@") && seenNames.add(markerLookup)) {
                    labelResult.addElement(LookupElementBuilder.create(markerLookup).withTypeText("project label", true))
                }
            }
            for (citationKey in TypstProjectBibliography.keys(position.project)) {
                if (seenNames.add(citationKey)) {
                    labelResult.addElement(LookupElementBuilder.create(citationKey).withTypeText("bibliography", true))
                }
            }
            return
        }

        addNamedArgumentCompletions(position, result, seenNames)

        for (definition in TypstAnalysis.visibleDefinitions(position)) {
            if (seenNames.add(definition.name)) {
                result.addElement(definitionLookup(definition))
            }
        }

        addImportedSymbolCompletions(parameters, result, seenNames)

        for (metadata in TypstBuiltins.allMetadata()) {
            if (seenNames.add(metadata.name)) {
                result.addElement(metadataLookup(metadata))
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
            for (item in summary.items) {
                if (seenNames.add(item.localName)) {
                    result.addElement(LookupElementBuilder.create(item.localName).withTypeText("import", true))
                }
            }

            if (!summary.isGlob) continue
            val moduleFile = TypstModuleFiles.resolveModuleFile(sourceFile(parameters), summary.pathString) ?: continue
            for (definition in TypstAnalysis.exportedDefinitions(moduleFile)) {
                if (seenNames.add(definition.name)) {
                    result.addElement(definitionLookup(definition))
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
        addImportMemberCompletions(parameters, summary.pathString, result)
    }

    private fun addImportMemberCompletions(
        parameters: CompletionParameters,
        pathString: String?,
        result: CompletionResultSet,
        sourcePrefix: String? = null,
    ) {
        val moduleFile = TypstModuleFiles.resolveModuleFile(sourceFile(parameters), pathString) ?: return
        val ownerPath = sourcePrefix?.substringBeforeLast('.', missingDelimiterValue = "").orEmpty()
        val ownerSegments = ownerPath.split('.').filter(String::isNotEmpty)
        for (definition in TypstAnalysis.exportPathMembers(moduleFile, ownerSegments)) {
            val lookupName = if (ownerPath.isEmpty()) definition.name else "$ownerPath.${definition.name}"
            result.addElement(
                definitionLookup(definition, lookupName)
                    .withPresentableText(definition.name)
                    .withTypeText(typeText(definition.effectiveKind), true),
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
                        .withTypeText("argument", true)
                        .withInsertHandler { insertionContext, _ ->
                            val tailOffset = insertionContext.tailOffset
                            if (tailOffset >= insertionContext.document.textLength || insertionContext.document.charsSequence[tailOffset] != ' ') {
                                insertionContext.document.insertString(tailOffset, " ")
                                insertionContext.tailOffset = tailOffset + 1
                            }
                            insertionContext.editor.caretModel.moveToOffset(insertionContext.tailOffset)
                            AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                        },
                )
            }
        }
    }

    private fun addRelativePathCompletions(parameters: CompletionParameters, result: CompletionResultSet, prefix: String) {
        val baseDirectory = sourceFile(parameters).virtualFile?.parent ?: return
        val directoryPrefix = prefix.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }
        val targetDirectory = if (directoryPrefix.isEmpty()) baseDirectory
        else baseDirectory.findFileByRelativePath(directoryPrefix.removeSuffix("/")) ?: return
        val importLike = ancestor(parameters.position, E.MODULE_IMPORT) != null ||
            ancestor(parameters.position, E.MODULE_INCLUDE) != null
        for (child in targetDirectory.children.sortedBy { it.name }) {
            val lookupPath = directoryPrefix + child.name + if (child.isDirectory) "/" else ""
            when {
                child.isDirectory -> result.addElement(
                    LookupElementBuilder.create(lookupPath).withPresentableText("${child.name}/").withTypeText("directory", true),
                )
                !importLike || child.extension == "typ" -> result.addElement(
                    LookupElementBuilder.create(lookupPath).withPresentableText(child.name).withTypeText("path", true),
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

    private fun packageSpecificationPrefix(parameters: CompletionParameters): String? {
        val beforeCaret = currentLineBeforeCaret(parameters)
        val match = Regex("""#\s*import\s+\"(@[^\"\n]*)$""").find(beforeCaret) ?: return null
        return cleanupCompletionDummy(match.groupValues[1])
    }

    private fun rawLanguagePrefix(parameters: CompletionParameters): String? {
        val match = Regex("`{3,}([A-Za-z0-9_+.-]*)$").find(currentLineBeforeCaret(parameters)) ?: return null
        return cleanupCompletionDummy(match.groupValues[1])
    }

    private fun fontFamilyPrefix(parameters: CompletionParameters): String? {
        val match = Regex("\\bfont\\s*:\\s*(?:\\([^\"\\n]*)?\"([^\"\\n]*)$")
            .find(currentLineBeforeCaret(parameters)) ?: return null
        return cleanupCompletionDummy(match.groupValues[1])
    }

    private fun currentLineBeforeCaret(parameters: CompletionParameters): String {
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset.coerceIn(0, text.length)
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
        return text.subSequence(lineStart, offset).toString()
    }

    private fun mathCompletionPath(parameters: CompletionParameters): String? {
        if (ancestor(parameters.position, E.MATH) == null) return null
        val text = parameters.editor.document.charsSequence
        var start = parameters.offset.coerceIn(0, text.length)
        while (start > 0) {
            val character = text[start - 1]
            if (!character.isLetterOrDigit() && character != '_' && character != '-' && character != '.') break
            start--
        }
        return cleanupCompletionDummy(text.subSequence(start, parameters.offset.coerceAtMost(text.length)).toString())
    }

    private fun metadataLookup(metadata: TypstBuiltins.Metadata): LookupElementBuilder {
        val lookup = LookupElementBuilder.create(metadata.name)
            .withTypeText(metadata.kind.name.lowercase(), true)
        val documented = metadata.summary?.let { lookup.withTailText(" - $it", true) } ?: lookup
        return if (metadata.kind == TypstBuiltins.Kind.FUNCTION) documented.withInsertHandler(::insertFunctionCall) else documented
    }

    private fun definitionLookup(definition: TypstDefinition, lookupName: String = definition.name): LookupElementBuilder {
        val lookup = LookupElementBuilder.create(lookupName)
            .withTypeText(typeText(definition.effectiveKind), true)
        return if (definition.effectiveKind == TypstDefinitionKind.LET_FUNCTION || definition.effectiveKind == TypstDefinitionKind.BUILTIN_FUNCTION) {
            lookup.withInsertHandler(::insertFunctionCall)
        } else {
            lookup
        }
    }

    private fun insertFunctionCall(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        lookupElement: com.intellij.codeInsight.lookup.LookupElement,
    ) {
        val tailOffset = insertionContext.tailOffset
        if (tailOffset < insertionContext.document.textLength && insertionContext.document.charsSequence[tailOffset] == '(') return
        insertionContext.document.insertString(tailOffset, "()")
        insertionContext.tailOffset = tailOffset + 2
        insertionContext.editor.caretModel.moveToOffset(tailOffset + 1)
        insertionContext.setLaterRunnable {
            AutoPopupController.getInstance(insertionContext.project)
                .autoPopupParameterInfo(insertionContext.editor, null)
        }
    }

    private data class PostfixContext(
        val targetRange: com.intellij.openapi.util.TextRange,
        val targetText: String,
        val prefix: String,
        val isContent: Boolean,
    )

    private data class PostfixExpansion(val text: String, val caretOffset: Int)

    private data class PostfixSnippet(
        val label: String,
        val description: String,
        val contentOnly: Boolean = false,
        val expand: (String) -> PostfixExpansion,
    )

    private fun addPostfixCompletions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        postfix: PostfixContext,
    ) {
        val settings = TypstSettingsService.getInstance(parameters.position.project).state
        if (!settings.enablePostfixCompletion) return
        for (snippet in postfixSnippets) {
            if (snippet.contentOnly && !postfix.isContent) continue
            result.addElement(
                postfixLookup(snippet.label, snippet.description, postfix) { snippet.expand(postfix.targetText) },
            )
        }
        if (!postfix.isContent) return
        for (metadata in TypstBuiltins.allMetadata().asSequence()
            .filter { it.kind == TypstBuiltins.Kind.FUNCTION && it.parameters.any { parameter -> parameter.name == "body" } }
            .sortedBy(TypstBuiltins.Metadata::name)
        ) {
            if (settings.enableUfcsCompletion) {
                result.addElement(postfixLookup(metadata.name, "wrap content", postfix) {
                    val text = "${metadata.name}[${postfix.targetText}]"
                    PostfixExpansion(text, text.length)
                }.withPresentableText("${metadata.name} — wrap"))
            }
            if (settings.enableUfcsLeftCompletion && metadata.parameters.any { it.name != "body" }) {
                result.addElement(postfixLookup(metadata.name, "arguments before content", postfix) {
                    val prefix = "${metadata.name}("
                    val text = "$prefix)[${postfix.targetText}]"
                    PostfixExpansion(text, prefix.length)
                }.withPresentableText("${metadata.name} — arguments first"))
            }
            if (settings.enableUfcsRightCompletion && metadata.parameters.any { it.name != "body" }) {
                result.addElement(postfixLookup(metadata.name, "content as first argument", postfix) {
                    val prefix = "${metadata.name}(${postfix.targetText}, "
                    val text = "$prefix)"
                    PostfixExpansion(text, prefix.length)
                }.withPresentableText("${metadata.name} — content first"))
            }
        }
    }

    private fun postfixLookup(
        label: String,
        description: String,
        postfix: PostfixContext,
        expansion: () -> PostfixExpansion,
    ): LookupElementBuilder = LookupElementBuilder.create(label)
        .withTypeText("postfix", true)
        .withTailText(" — $description", true)
        .withInsertHandler { insertionContext, _ ->
            val replacement = expansion()
            val startOffset = postfix.targetRange.startOffset.coerceIn(0, insertionContext.document.textLength)
            val endOffset = insertionContext.tailOffset.coerceIn(startOffset, insertionContext.document.textLength)
            insertionContext.document.replaceString(startOffset, endOffset, replacement.text)
            insertionContext.tailOffset = startOffset + replacement.text.length
            insertionContext.editor.caretModel.moveToOffset(startOffset + replacement.caretOffset)
        }

    private fun postfixContext(parameters: CompletionParameters): PostfixContext? {
        if (ancestor(parameters.position, E.MATH) != null) return null
        val document = parameters.editor.document
        val caretOffset = parameters.offset.coerceIn(0, document.textLength)
        val beforeCaret = cleanupCompletionDummy(document.charsSequence.subSequence(0, caretOffset).toString())
        val match = Regex("\\.([A-Za-z][A-Za-z0-9-]*)?$").find(beforeCaret) ?: return null
        val prefix = match.groupValues.getOrElse(1) { "" }
        val dotOffset = match.range.first
        if (dotOffset <= 0) return null

        val file = parameters.position.containingFile
        val leaf = file.findElementAt((dotOffset - 1).coerceAtLeast(0))
        var current = leaf
        var target: PsiElement? = null
        while (current != null && current.textRange.endOffset <= dotOffset) {
            if (current.node.elementType in postfixTargetTypes) target = current
            current = current.parent
        }
        val targetRange = target?.textRange?.takeIf { it.endOffset == dotOffset }
            ?: scannedPostfixTargetRange(document.charsSequence, dotOffset)
            ?: return null
        if (targetRange.isEmpty || targetRange.startOffset >= dotOffset) return null
        val targetText = document.charsSequence.subSequence(targetRange.startOffset, dotOffset).toString()
        if (targetText.isBlank()) return null
        val isContent = target?.let(TypstTypeInference::typeName) == "content" ||
            targetText.startsWith('[') || targetText.startsWith("#[")
        return PostfixContext(targetRange, targetText, prefix, isContent)
    }

    private fun scannedPostfixTargetRange(text: CharSequence, dotOffset: Int): com.intellij.openapi.util.TextRange? {
        var start = dotOffset - 1
        var parenthesisDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        while (start >= 0) {
            val character = text[start]
            if (character == '"' && (start == 0 || text[start - 1] != '\\')) inString = !inString
            if (!inString) {
                when (character) {
                    ')' -> parenthesisDepth++
                    '(' -> if (parenthesisDepth > 0) parenthesisDepth-- else break
                    ']' -> bracketDepth++
                    '[' -> if (bracketDepth > 0) bracketDepth-- else break
                    '}' -> braceDepth++
                    '{' -> if (braceDepth > 0) braceDepth-- else break
                    ',', ';', '\n', '\r' -> if (parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                    ' ', '\t' -> if (parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                    '#' -> if (parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                }
            }
            start--
        }
        val rangeStart = start + 1
        return if (rangeStart < dotOffset) com.intellij.openapi.util.TextRange(rangeStart, dotOffset) else null
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
        TypstDefinitionKind.IMPORTED_SYMBOL -> "import"
        TypstDefinitionKind.MODULE_ALIAS -> "module"
        TypstDefinitionKind.BUILTIN_FUNCTION -> "builtin function"
        TypstDefinitionKind.BUILTIN_TYPE -> "builtin type"
        TypstDefinitionKind.BUILTIN_MODULE -> "builtin module"
        TypstDefinitionKind.BUILTIN_VALUE -> "builtin value"
    }

    private val pathFunctions = setOf("image", "bibliography", "read", "csv", "json", "toml", "yaml", "xml", "cbor")

    private val postfixTargetTypes = setOf(
        E.REFERENCE_EXPR, E.FIELD_ACCESS, E.FUNC_CALL, E.ARRAY, E.DICT, E.PARENTHESIZED,
        E.CONTENT_BLOCK, E.CODE_BLOCK, E.UNARY, E.BINARY, E.STRING_LITERAL,
    )

    private val postfixSnippets = listOf(
        PostfixSnippet("text fill", "wrap with text fill", contentOnly = true) { target ->
            val prefix = "text(fill: "
            PostfixExpansion("$prefix, $target)", prefix.length)
        },
        PostfixSnippet("text size", "wrap with text size", contentOnly = true) { target ->
            val prefix = "text(size: "
            PostfixExpansion("$prefix, $target)", prefix.length)
        },
        PostfixSnippet("align", "wrap with alignment", contentOnly = true) { target ->
            val prefix = "align("
            PostfixExpansion("$prefix, $target)", prefix.length)
        },
        PostfixSnippet("if", "wrap as if expression") { target ->
            val prefix = "if $target { "
            PostfixExpansion("$prefix }", prefix.length)
        },
        PostfixSnippet("else", "wrap as if-not expression") { target ->
            val prefix = "if not $target { "
            PostfixExpansion("$prefix }", prefix.length)
        },
        PostfixSnippet("none", "check for none") { target ->
            val prefix = "if $target == none { "
            PostfixExpansion("$prefix }", prefix.length)
        },
        PostfixSnippet("notnone", "check for a value") { target ->
            val prefix = "if $target != none { "
            PostfixExpansion("$prefix }", prefix.length)
        },
        PostfixSnippet("return", "return this value") { target ->
            val text = "return $target"
            PostfixExpansion(text, text.length)
        },
        PostfixSnippet("tup", "put into a tuple") { target ->
            val prefix = "($target, "
            PostfixExpansion("$prefix)", prefix.length)
        },
        PostfixSnippet("let", "bind this value") { target ->
            val prefix = "let "
            PostfixExpansion("$prefix = $target", prefix.length)
        },
        PostfixSnippet("in", "use as the right side of in") { target ->
            PostfixExpansion(" in $target", 0)
        },
    )
}

private object TypstCompletionData {
    val rawLanguages = listOf(
        "typ", "bash", "c", "cpp", "css", "go", "html", "java", "javascript", "json", "kotlin",
        "latex", "markdown", "python", "rust", "sql", "swift", "toml", "typescript", "xml", "yaml",
    )

    val fontFamilies: List<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted() }
            .getOrDefault(emptyList())
    }
}
