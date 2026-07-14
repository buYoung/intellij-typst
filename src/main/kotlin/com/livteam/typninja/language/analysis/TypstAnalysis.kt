package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.psi.TypstBindingDeclaration
import com.livteam.typninja.language.references.TypstBuiltinResolver
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.settings.TypstSettingsService
import java.nio.file.Paths
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

enum class TypstDefinitionKind {
    LET_VARIABLE,
    LET_FUNCTION,
    PARAMETER,
    LOOP_BINDING,
    LABEL,
    IMPORTED_SYMBOL,
    MODULE_ALIAS,
    BUILTIN_FUNCTION,
    BUILTIN_TYPE,
    BUILTIN_MODULE,
    BUILTIN_VALUE,
}

data class TypstDefinition(
    val name: String,
    val kind: TypstDefinitionKind,
    val nameElement: PsiElement,
    val declarationElement: PsiElement,
    val sourceName: String = name,
    val navigationElement: PsiElement = nameElement,
    /** The original symbol category after imports and aliases are followed. */
    val effectiveKind: TypstDefinitionKind = kind,
) {
}

data class TypstResolveResult(val definition: TypstDefinition)

data class TypstImportItem(
    val localName: String,
    val sourceSegments: List<String>,
    val sourceSegmentNodes: List<ASTNode>,
    val localNameNode: ASTNode,
    val itemNode: ASTNode,
) {
    val moduleName: String get() = sourceSegments.lastOrNull().orEmpty()
    val sourcePath: String get() = sourceSegments.joinToString(".")
    val nameNode: ASTNode get() = sourceSegmentNodes.lastOrNull() ?: localNameNode
}

data class TypstImportSummary(
    val pathString: String?,
    val items: List<TypstImportItem>,
    val isGlob: Boolean,
    val isPackageImport: Boolean,
    val moduleAlias: String?,
    val moduleAliasNode: ASTNode?,
)

data class TypstLocatedImport(
    val node: ASTNode,
    val summary: TypstImportSummary,
)

data class TypstPathLiteral(val text: String, val element: PsiElement)

data class TypstIndexedExportedSymbol(
    val name: String,
    val file: TypstFile,
    val definition: TypstDefinition,
)

data class TypstAnalysisSnapshot(
    val file: TypstFile,
    val declarations: List<TypstDefinition>,
    val imports: List<TypstLocatedImport>,
    val labels: List<TypstDefinition>,
    val headings: List<PsiElement>,
    val pathLiterals: List<TypstPathLiteral>,
    val referenceCandidates: Map<String, List<PsiElement>>,
) {
    fun exportedDefinition(name: String): TypstDefinition? =
        declarations.firstOrNull { it.name == name && isTopLevelExport(it.declarationElement.node) }

    fun exportedDefinitions(): List<TypstDefinition> =
        declarations.filter { isTopLevelExport(it.declarationElement.node) }

    fun labelDefinition(name: String): TypstDefinition? =
        labels.firstOrNull { it.name == name }

    private fun isTopLevelExport(node: ASTNode?): Boolean {
        val parent = node?.treeParent ?: return false
        if (parent.elementType == file.node.elementType) return true
        return parent.elementType == E.CODE_EXPRESSION && parent.treeParent?.elementType == file.node.elementType
    }
}

object TypstAnalysis {

    /** Returns everything that another Typst file can import, including re-exported imports. */
    fun exportedDefinitions(file: PsiFile): List<TypstDefinition> =
        exportedDefinitions(file, LinkedHashSet())

    /** Resolves one exported name through any number of import/re-export hops. */
    fun exportedDefinition(file: PsiFile, name: String): TypstDefinition? =
        exportedDefinition(file, name, LinkedHashSet())

    fun snapshot(file: PsiFile): TypstAnalysisSnapshot? {
        val typstFile = file as? TypstFile ?: file.originalFile as? TypstFile ?: return null
        val projectModel = TypstProjectModelService.getInstance(typstFile.project)
        return CachedValuesManager.getCachedValue(typstFile) {
            CachedValueProvider.Result.create(buildSnapshot(typstFile), typstFile, projectModel)
        }
    }

    fun parseImport(moduleImport: ASTNode): TypstImportSummary {
        val path = moduleImport.findChildByType(E.STRING_LITERAL)?.let { unquote(it.text) }
        val itemsNode = moduleImport.findChildByType(E.IMPORT_ITEMS)
        val isGlob = itemsNode?.findChildByType(E.IMPORT_GLOB) != null
        val items = if (itemsNode == null) emptyList() else parseItems(itemsNode)
        val moduleAliasNode = moduleAliasNode(moduleImport)
        return TypstImportSummary(
            pathString = path,
            items = items,
            isGlob = isGlob,
            isPackageImport = path?.startsWith("@") == true,
            moduleAlias = moduleAliasNode?.text,
            moduleAliasNode = moduleAliasNode,
        )
    }

    fun resolve(usage: TypstReferenceExpression): TypstResolveResult? {
        return resolveName(usage, usage.referenceName)
    }

    fun resolveName(usage: PsiElement, name: String): TypstResolveResult? {
        if (name.isEmpty()) return null
        val usageNode = usage.node
        val usageOffset = usageNode.startOffset
        val snapshot = snapshot(usage.containingFile) ?: return null

        var scope: ASTNode? = usageNode.treeParent
        while (scope != null) {
            ProgressManager.checkCanceled()
            findInScope(snapshot, scope, name, usageOffset)?.let { return TypstResolveResult(it) }
            scope = scope.treeParent
        }

        resolveImportedName(usage.containingFile, name)?.let { return it }
        return resolveBuiltin(usage, name)
    }

    /** Local declarations visible at the caret, with shadowed names removed. */
    fun visibleDefinitions(usage: PsiElement): List<TypstDefinition> {
        val fileSnapshot = snapshot(usage.containingFile) ?: return emptyList()
        return fileSnapshot.declarations.asSequence()
            .map(TypstDefinition::name)
            .distinct()
            .mapNotNull { name -> resolveName(usage, name)?.definition }
            .filter { definition -> definition.kind !in setOf(
                TypstDefinitionKind.IMPORTED_SYMBOL,
                TypstDefinitionKind.MODULE_ALIAS,
                TypstDefinitionKind.BUILTIN_FUNCTION,
                TypstDefinitionKind.BUILTIN_TYPE,
                TypstDefinitionKind.BUILTIN_MODULE,
                TypstDefinitionKind.BUILTIN_VALUE,
            ) }
            .toList()
    }

    fun resolveMath(usage: TypstMathIdentifier): TypstResolveResult? {
        val path = usage.qualifiedPath
        if ('.' !in path) {
            resolveName(usage, usage.referenceName)?.let { return it }
            val metadata = TypstBuiltins.mathMetadata(usage.referenceName) ?: return null
            return builtinResult(usage, metadata)
        }

        val ownerPath = path.substringBeforeLast('.')
        val memberName = path.substringAfterLast('.')
        val metadata = when {
            ownerPath == "sym" || ownerPath == "emoji" || ownerPath.startsWith("sym.") || ownerPath.startsWith("emoji.") ->
                TypstBuiltins.dottedMemberMetadata(ownerPath, memberName)
            '.' !in ownerPath -> TypstBuiltins.moduleMemberMetadata(ownerPath, memberName)
                ?: TypstBuiltins.dottedMemberMetadata("sym.$ownerPath", memberName)
            else -> TypstBuiltins.dottedMemberMetadata("sym.$ownerPath", memberName)
        } ?: return null
        return builtinResult(usage, metadata)
    }

    fun resolveImportedName(usageFile: PsiFile, name: String): TypstResolveResult? {
        val snapshot = snapshot(usageFile) ?: return null
        for (locatedImport in snapshot.imports) {
            val summary = locatedImport.summary
            if (summary.moduleAlias == name) {
                val nameNode = summary.moduleAliasNode ?: continue
                val moduleFile = TypstModuleFiles.resolveModuleFile(usageFile, summary.pathString)
                return TypstResolveResult(
                    TypstDefinition(
                        name = name,
                        kind = TypstDefinitionKind.MODULE_ALIAS,
                        nameElement = nameNode.psi,
                        declarationElement = locatedImport.node.psi,
                        navigationElement = moduleFile ?: nameNode.psi,
                    ),
                )
            }
            val importItem = summary.items.firstOrNull { it.localName == name }
            if (importItem != null) {
                val moduleFile = TypstModuleFiles.resolveModuleFile(usageFile, summary.pathString)
                if (moduleFile == null && !summary.isPackageImport) continue
                val exportedDefinition = moduleFile?.let { exportPathDefinition(it, importItem.sourceSegments) }
                if (exportedDefinition != null) {
                    return TypstResolveResult(importedDefinition(importItem, exportedDefinition))
                }
                return TypstResolveResult(
                    TypstDefinition(
                        name = importItem.localName,
                        kind = TypstDefinitionKind.IMPORTED_SYMBOL,
                        nameElement = importItem.localNameNode.psi,
                        declarationElement = importItem.itemNode.psi,
                        sourceName = importItem.moduleName,
                        navigationElement = exportedDefinition?.navigationElement ?: importItem.localNameNode.psi,
                    ),
                )
            }
            if (!summary.isGlob) continue
            val moduleFile = TypstModuleFiles.resolveModuleFile(usageFile, summary.pathString) ?: continue
            val exportedDefinition = exportedDefinition(moduleFile, name) ?: continue
            return TypstResolveResult(exportedDefinition)
        }
        return null
    }

    fun fieldDefinitions(fieldAccess: TypstFieldAccess): List<TypstDefinition> {
        val qualifier = fieldAccess.qualifierName
        if (qualifier != null) {
            val fileSnapshot = snapshot(fieldAccess.containingFile) ?: return emptyList()
            val moduleImport = fileSnapshot.imports.firstOrNull { it.summary.moduleAlias == qualifier }
            if (moduleImport != null) {
                val moduleFile = TypstModuleFiles.resolveModuleFile(fieldAccess.containingFile, moduleImport.summary.pathString)
                    ?: return emptyList()
                return exportedDefinitions(moduleFile)
            }
        }

        val definitions = LinkedHashMap<String, TypstDefinition>()
        staticFieldDefinitions(fieldAccess.qualifierElement, LinkedHashSet()).forEach {
            definitions.putIfAbsent(it.name, it)
        }
        var members = fieldAccess.qualifierPath?.let(TypstBuiltins::dottedMembers).orEmpty()
        val ownerType = when {
            qualifier != null && (TypstBuiltins.isModule(qualifier) || TypstBuiltins.isType(qualifier) ||
                TypstBuiltins.typeMembers(qualifier).isNotEmpty()) -> qualifier
            else -> fieldAccess.qualifierElement?.let(TypstTypeInference::typeName)
        }
        if (members.isEmpty()) members = qualifier?.let(TypstBuiltins::moduleMembers).orEmpty()
        if (members.isEmpty() && ownerType != null) members = TypstBuiltins.typeMembers(ownerType)
        if (members.isEmpty() && isUnresolvedImportedValue(fieldAccess.qualifierElement)) {
            // A package may be referenced before its local cache has been indexed. `with` and
            // `where` are the standard members of imported Typst functions/elements, so keeping
            // these two available avoids turning a valid package API into an editor error.
            members = TypstBuiltins.typeMembers("function")
        }
        members.mapNotNull { metadata ->
            val target = TypstBuiltinResolver.resolve(fieldAccess.project, metadata.name) ?: return@mapNotNull null
            val kind = when (metadata.kind) {
                TypstBuiltins.Kind.FUNCTION -> TypstDefinitionKind.BUILTIN_FUNCTION
                TypstBuiltins.Kind.TYPE -> TypstDefinitionKind.BUILTIN_TYPE
                TypstBuiltins.Kind.MODULE -> TypstDefinitionKind.BUILTIN_MODULE
                TypstBuiltins.Kind.VALUE -> TypstDefinitionKind.BUILTIN_VALUE
            }
            TypstDefinition(metadata.name, kind, target, target)
        }.forEach { definitions.putIfAbsent(it.name, it) }
        return definitions.values.toList()
    }

    fun resolveField(fieldAccess: TypstFieldAccess): TypstDefinition? {
        val member = fieldAccess.memberName ?: return null
        return fieldDefinitions(fieldAccess).firstOrNull { it.name == member }
    }

    fun fieldMetadata(fieldAccess: TypstFieldAccess): TypstBuiltins.Metadata? {
        val memberName = fieldAccess.memberName ?: return null
        fieldAccess.qualifierPath?.let { path ->
            TypstBuiltins.dottedMemberMetadata(path, memberName)?.let { return it }
        }
        val qualifierName = fieldAccess.qualifierName
        if (qualifierName != null) {
            TypstBuiltins.moduleMemberMetadata(qualifierName, memberName)?.let { return it }
            TypstBuiltins.typeMemberMetadata(qualifierName, memberName)?.let { return it }
        }
        val ownerType = fieldAccess.qualifierElement?.let(TypstTypeInference::typeName)
        return ownerType?.let { TypstBuiltins.typeMemberMetadata(it, memberName) }
            ?: if (isUnresolvedImportedValue(fieldAccess.qualifierElement)) {
                TypstBuiltins.typeMemberMetadata("function", memberName)
            } else null
    }

    fun fieldValueElement(fieldAccess: TypstFieldAccess): PsiElement? =
        resolveField(fieldAccess)?.let(::definitionValueNode)?.psi

    /** Resolves every proven segment of an explicitly imported dotted path. */
    fun exportPathDefinition(file: PsiFile, sourceSegments: List<String>, segmentIndex: Int = sourceSegments.lastIndex): TypstDefinition? {
        if (sourceSegments.isEmpty() || segmentIndex !in sourceSegments.indices) return null
        var definition = exportedDefinition(file, sourceSegments.first()) ?: return null
        if (segmentIndex == 0) return definition
        for (index in 1..segmentIndex) {
            ProgressManager.checkCanceled()
            definition = staticFieldDefinitions(definitionValueNode(definition)?.psi, LinkedHashSet())
                .firstOrNull { it.name == sourceSegments[index] }
                ?: return null
        }
        return definition
    }

    fun exportPathMembers(file: PsiFile, sourceSegments: List<String>): List<TypstDefinition> {
        if (sourceSegments.isEmpty()) return exportedDefinitions(file)
        val definition = exportPathDefinition(file, sourceSegments) ?: return emptyList()
        return staticFieldDefinitions(definitionValueNode(definition)?.psi, LinkedHashSet())
    }

    fun resolveBuiltin(anchor: PsiElement, name: String): TypstResolveResult? {
        val kind = when {
            TypstBuiltins.isType(name) -> TypstDefinitionKind.BUILTIN_TYPE
            TypstBuiltins.isModule(name) -> TypstDefinitionKind.BUILTIN_MODULE
            TypstBuiltins.isFunction(name) -> TypstDefinitionKind.BUILTIN_FUNCTION
            TypstBuiltins.isValue(name) -> TypstDefinitionKind.BUILTIN_VALUE
            else -> return null
        }
        val target = TypstBuiltinResolver.resolve(anchor, name) ?: return null
        return TypstResolveResult(TypstDefinition(name, kind, target, target))
    }

    private fun builtinResult(anchor: PsiElement, metadata: TypstBuiltins.Metadata): TypstResolveResult? {
        val target = TypstBuiltinResolver.resolve(anchor.project, metadata.name) ?: return null
        val kind = when (metadata.kind) {
            TypstBuiltins.Kind.FUNCTION -> TypstDefinitionKind.BUILTIN_FUNCTION
            TypstBuiltins.Kind.TYPE -> TypstDefinitionKind.BUILTIN_TYPE
            TypstBuiltins.Kind.MODULE -> TypstDefinitionKind.BUILTIN_MODULE
            TypstBuiltins.Kind.VALUE -> TypstDefinitionKind.BUILTIN_VALUE
        }
        return TypstResolveResult(TypstDefinition(metadata.name, kind, target, target))
    }

    private fun isUnresolvedImportedValue(element: PsiElement?): Boolean {
        val reference = element as? TypstReferenceExpression ?: return false
        return resolve(reference)?.definition?.effectiveKind == TypstDefinitionKind.IMPORTED_SYMBOL
    }

    private fun exportedDefinition(
        file: PsiFile,
        name: String,
        visitingFiles: MutableSet<PsiFile>,
    ): TypstDefinition? {
        if (!visitingFiles.add(file)) return null
        try {
            val fileSnapshot = snapshot(file) ?: return null
            fileSnapshot.exportedDefinition(name)?.let { return it }
            for (locatedImport in fileSnapshot.imports) {
                ProgressManager.checkCanceled()
                val summary = locatedImport.summary
                if (summary.moduleAlias == name) {
                    val aliasNode = summary.moduleAliasNode ?: continue
                    val moduleFile = TypstModuleFiles.resolveModuleFile(file, summary.pathString)
                    return TypstDefinition(
                        name = name,
                        kind = TypstDefinitionKind.MODULE_ALIAS,
                        nameElement = aliasNode.psi,
                        declarationElement = locatedImport.node.psi,
                        navigationElement = moduleFile ?: aliasNode.psi,
                    )
                }
                val item = summary.items.firstOrNull { it.localName == name }
                if (item != null) {
                    val moduleFile = TypstModuleFiles.resolveModuleFile(file, summary.pathString)
                    val source = moduleFile?.let { exportPathDefinition(it, item.sourceSegments) }
                    if (source != null) return importedDefinition(item, source)
                    if (summary.isPackageImport) {
                        return TypstDefinition(
                            name = item.localName,
                            kind = TypstDefinitionKind.IMPORTED_SYMBOL,
                            nameElement = item.localNameNode.psi,
                            declarationElement = item.itemNode.psi,
                            sourceName = item.moduleName,
                        )
                    }
                }
                if (!summary.isGlob) continue
                val moduleFile = TypstModuleFiles.resolveModuleFile(file, summary.pathString) ?: continue
                exportedDefinition(moduleFile, name, visitingFiles)?.let { return it }
            }
            return null
        } finally {
            visitingFiles.remove(file)
        }
    }

    private fun exportedDefinitions(
        file: PsiFile,
        visitingFiles: MutableSet<PsiFile>,
    ): List<TypstDefinition> {
        if (!visitingFiles.add(file)) return emptyList()
        try {
            val fileSnapshot = snapshot(file) ?: return emptyList()
            val definitions = LinkedHashMap<String, TypstDefinition>()
            fileSnapshot.exportedDefinitions().forEach { definitions.putIfAbsent(it.name, it) }
            for (locatedImport in fileSnapshot.imports) {
                ProgressManager.checkCanceled()
                val summary = locatedImport.summary
                val moduleFile = TypstModuleFiles.resolveModuleFile(file, summary.pathString)
                summary.moduleAliasNode?.let { aliasNode ->
                    definitions.putIfAbsent(
                        aliasNode.text,
                        TypstDefinition(
                            name = aliasNode.text,
                            kind = TypstDefinitionKind.MODULE_ALIAS,
                            nameElement = aliasNode.psi,
                            declarationElement = locatedImport.node.psi,
                            navigationElement = moduleFile ?: aliasNode.psi,
                        ),
                    )
                }
                for (item in summary.items) {
                    val source = moduleFile?.let { exportPathDefinition(it, item.sourceSegments) }
                    val imported = source?.let { importedDefinition(item, it) }
                        ?: if (summary.isPackageImport) {
                            TypstDefinition(
                                name = item.localName,
                                kind = TypstDefinitionKind.IMPORTED_SYMBOL,
                                nameElement = item.localNameNode.psi,
                                declarationElement = item.itemNode.psi,
                                sourceName = item.moduleName,
                            )
                        } else null
                    if (imported != null) definitions.putIfAbsent(item.localName, imported)
                }
                if (summary.isGlob && moduleFile != null) {
                    exportedDefinitions(moduleFile, visitingFiles).forEach { definitions.putIfAbsent(it.name, it) }
                }
            }
            return definitions.values.toList()
        } finally {
            visitingFiles.remove(file)
        }
    }

    private fun importedDefinition(item: TypstImportItem, source: TypstDefinition): TypstDefinition =
        TypstDefinition(
            name = item.localName,
            kind = TypstDefinitionKind.IMPORTED_SYMBOL,
            nameElement = item.localNameNode.psi,
            declarationElement = item.itemNode.psi,
            sourceName = item.moduleName,
            navigationElement = source.navigationElement,
            effectiveKind = source.effectiveKind,
        )

    private fun staticFieldDefinitions(
        qualifierElement: PsiElement?,
        visitingElements: MutableSet<PsiElement>,
    ): List<TypstDefinition> {
        qualifierElement ?: return emptyList()
        if (!visitingElements.add(qualifierElement)) return emptyList()
        try {
            val valueNode = when (qualifierElement) {
                is TypstReferenceExpression -> resolve(qualifierElement)?.definition?.let(::definitionValueNode)
                is TypstFieldAccess -> resolveField(qualifierElement)?.let(::definitionValueNode)
                else -> qualifierElement.node
            } ?: return emptyList()
            val dictionary = when (valueNode.elementType) {
                E.DICT -> valueNode
                E.PARENTHESIZED, E.CODE_EXPRESSION -> firstMeaningfulChild(valueNode)
                else -> null
            } ?: return emptyList()
            if (dictionary.elementType != E.DICT) return emptyList()

            val definitions = LinkedHashMap<String, TypstDefinition>()
            var child = dictionary.firstChildNode
            while (child != null) {
                ProgressManager.checkCanceled()
                when (child.elementType) {
                    E.NAMED -> firstDirectChildOfType(child, T.IDENTIFIER)?.let { nameNode ->
                        definitions.putIfAbsent(
                            nameNode.text,
                            TypstDefinition(nameNode.text, TypstDefinitionKind.LET_VARIABLE, nameNode.psi, child.psi),
                        )
                    }
                    E.KEYED -> firstDirectChildOfType(child, T.STRING)?.let { nameNode ->
                        val name = unquote(nameNode.text)
                        definitions.putIfAbsent(
                            name,
                            TypstDefinition(name, TypstDefinitionKind.LET_VARIABLE, nameNode.psi, child.psi),
                        )
                    }
                    E.SPREAD -> firstMeaningfulChildAfterSpread(child)?.let { spreadValue ->
                        staticFieldDefinitions(spreadValue.psi, visitingElements).forEach {
                            definitions.putIfAbsent(it.name, it)
                        }
                    }
                }
                child = child.treeNext
            }
            return definitions.values.toList()
        } finally {
            visitingElements.remove(qualifierElement)
        }
    }

    private fun definitionValueNode(definition: TypstDefinition): ASTNode? {
        val declaration = definition.declarationElement.node ?: return null
        return when (declaration.elementType) {
            E.LET_BINDING -> childAfterToken(declaration, T.EQ)
            E.NAMED, E.KEYED -> childAfterToken(declaration, T.COLON)
            else -> {
                val letBinding = generateSequence(definition.navigationElement.node) { it.treeParent }
                    .firstOrNull { it.elementType == E.LET_BINDING }
                letBinding?.let { childAfterToken(it, T.EQ) }
            }
        }
    }

    private fun childAfterToken(node: ASTNode, tokenType: IElementType): ASTNode? {
        var child = node.firstChildNode
        var sawToken = false
        while (child != null) {
            if (child.elementType == tokenType) sawToken = true
            else if (sawToken && !isSkippable(child.elementType)) return child
            child = child.treeNext
        }
        return null
    }

    private fun firstMeaningfulChildAfterSpread(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType != T.DOT_DOT && !isSkippable(child.elementType)) return child
            child = child.treeNext
        }
        return null
    }

    private fun buildSnapshot(file: TypstFile): TypstAnalysisSnapshot {
        val declarations = ArrayList<TypstDefinition>()
        val imports = ArrayList<TypstLocatedImport>()
        val labels = ArrayList<TypstDefinition>()
        val headings = ArrayList<PsiElement>()
        val pathLiterals = ArrayList<TypstPathLiteral>()
        val referenceCandidates = LinkedHashMap<String, MutableList<PsiElement>>()
        val root = file.node
        collect(root, declarations, imports, labels, headings, pathLiterals, referenceCandidates)
        return TypstAnalysisSnapshot(file, declarations, imports, labels, headings, pathLiterals, referenceCandidates)
    }

    private fun collect(
        node: ASTNode,
        declarations: MutableList<TypstDefinition>,
        imports: MutableList<TypstLocatedImport>,
        labels: MutableList<TypstDefinition>,
        headings: MutableList<PsiElement>,
        pathLiterals: MutableList<TypstPathLiteral>,
        referenceCandidates: MutableMap<String, MutableList<PsiElement>>,
    ) {
        ProgressManager.checkCanceled()
        when (node.elementType) {
            E.LET_BINDING -> letDefinition(node)?.let { declarations.add(it) }
            E.BINDING_DECLARATION -> bindingDefinition(node)?.let { declarations.add(it) }
            E.MODULE_IMPORT -> imports.add(TypstLocatedImport(node, parseImport(node)))
            E.MODULE_INCLUDE -> collectIncludePath(node, pathLiterals)
            E.HEADING -> headings.add(node.psi)
            E.REFERENCE_EXPR -> (node.psi as? TypstReferenceExpression)?.referenceName?.takeIf(String::isNotEmpty)
                ?.let { name -> referenceCandidates.getOrPut(name, ::ArrayList).add(node.psi) }
            E.REF -> (node.psi as? TypstRef)?.referenceName?.takeIf(String::isNotEmpty)
                ?.let { name -> referenceCandidates.getOrPut(name, ::ArrayList).add(node.psi) }
        }
        if (node.elementType == E.LABEL) {
            val labelNode = node.findChildByType(T.LABEL_DEF)
            val name = labelNode?.text?.let(::labelName).orEmpty()
            if (name.isNotEmpty() && labelNode != null) {
                labels.add(TypstDefinition(name, TypstDefinitionKind.LABEL, labelNode.psi, node.psi))
            }
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, declarations, imports, labels, headings, pathLiterals, referenceCandidates)
            child = child.treeNext
        }
    }

    private fun findInScope(
        snapshot: TypstAnalysisSnapshot,
        scope: ASTNode,
        name: String,
        usageOffset: Int,
    ): TypstDefinition? {
        when (scope.elementType) {
            E.CLOSURE -> matchClosureParam(snapshot, scope, name)?.let { return it }
            E.FOR_LOOP -> if (isInLoopBody(scope, usageOffset)) matchForBinding(snapshot, scope, name)?.let { return it }
            E.LET_BINDING -> matchFunctionParam(snapshot, scope, name)?.let { return it }
        }
        return matchLetDeclaration(snapshot, scope, name, usageOffset)
    }

    private fun matchLetDeclaration(
        snapshot: TypstAnalysisSnapshot,
        scope: ASTNode,
        name: String,
        usageOffset: Int,
    ): TypstDefinition? {
        var best: TypstDefinition? = null
        var fallback: TypstDefinition? = null
        for (definition in snapshot.declarations) {
            val node = definition.declarationElement.node
            if (definition.name != name || node?.elementType != E.LET_BINDING) continue
            if (!isDirectLetInScope(node, scope)) continue
            if (fallback == null) fallback = definition
            if (definition.nameElement.textRange.startOffset <= usageOffset &&
                (best == null || definition.nameElement.textRange.startOffset > best!!.nameElement.textRange.startOffset)
            ) {
                best = definition
            }
        }
        return best ?: fallback
    }

    private fun matchFunctionParam(snapshot: TypstAnalysisSnapshot, letBinding: ASTNode, name: String): TypstDefinition? =
        snapshot.declarations.firstOrNull {
            it.name == name &&
                it.kind == TypstDefinitionKind.PARAMETER &&
                isDescendantOf(it.nameElement.node, letBinding.findChildByType(E.PARAMS))
        }

    private fun matchClosureParam(snapshot: TypstAnalysisSnapshot, closure: ASTNode, name: String): TypstDefinition? =
        snapshot.declarations.firstOrNull {
            it.name == name &&
                it.kind == TypstDefinitionKind.PARAMETER &&
                isDescendantOf(it.nameElement.node, closure)
        }

    private fun matchForBinding(snapshot: TypstAnalysisSnapshot, forLoop: ASTNode, name: String): TypstDefinition? =
        snapshot.declarations.firstOrNull {
            it.name == name &&
                it.kind == TypstDefinitionKind.LOOP_BINDING &&
                isDescendantOf(it.nameElement.node, forLoop)
        }

    private fun isDirectLetInScope(letBinding: ASTNode, scope: ASTNode): Boolean {
        val parent = letBinding.treeParent
        return parent === scope || (parent?.elementType == E.CODE_EXPRESSION && parent.treeParent === scope)
    }

    private fun letDefinition(letBinding: ASTNode): TypstDefinition? {
        val name = firstDirectChildOfType(letBinding, T.IDENTIFIER) ?: return null
        val kind = if (nextMeaningfulSibling(name)?.elementType == E.PARAMS) {
            TypstDefinitionKind.LET_FUNCTION
        } else {
            TypstDefinitionKind.LET_VARIABLE
        }
        return TypstDefinition(name.text, kind, name.psi, letBinding.psi)
    }

    private fun bindingDefinition(bindingNode: ASTNode): TypstDefinition? {
        val binding = bindingNode.psi as? TypstBindingDeclaration ?: return null
        val nameElement = binding.nameIdentifier ?: return null
        val forLoop = generateSequence(bindingNode.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == E.FOR_LOOP }
        val parameterOwner = generateSequence(bindingNode.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == E.PARAMS || it.elementType == E.CLOSURE }
        val letBinding = generateSequence(bindingNode.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == E.LET_BINDING }
        val kind = when {
            forLoop != null && parameterOwner == null -> TypstDefinitionKind.LOOP_BINDING
            parameterOwner != null -> TypstDefinitionKind.PARAMETER
            letBinding != null -> TypstDefinitionKind.LET_VARIABLE
            else -> return null
        }
        val declaration = if (kind == TypstDefinitionKind.LET_VARIABLE) letBinding?.psi ?: binding else binding
        return TypstDefinition(binding.name.orEmpty(), kind, nameElement, declaration, navigationElement = binding)
    }

    private fun collectIncludePath(node: ASTNode, pathLiterals: MutableList<TypstPathLiteral>) {
        node.findChildByType(E.STRING_LITERAL)?.let {
            pathLiterals.add(TypstPathLiteral(unquote(it.text), it.psi))
        }
    }

    private fun parseItems(itemsNode: ASTNode): List<TypstImportItem> {
        val items = ArrayList<TypstImportItem>()
        var child = itemsNode.firstChildNode
        while (child != null) {
            if (child.elementType == E.IMPORT_ITEM) {
                val sourceNodes = importSourceNodes(child)
                if (sourceNodes.isEmpty()) {
                    child = child.treeNext
                    continue
                }
                val nameNode = sourceNodes.last()
                val asKeyword = nextMeaningfulSibling(nameNode)
                val alias = if (asKeyword?.elementType == T.KW_AS) nextMeaningfulSibling(asKeyword) else null
                val localNameNode = alias?.takeIf { it.elementType == T.IDENTIFIER } ?: nameNode
                items.add(TypstImportItem(localNameNode.text, sourceNodes.map { it.text }, sourceNodes, localNameNode, child))
            }
            child = child.treeNext
        }
        return items
    }

    private fun moduleAliasNode(moduleImport: ASTNode): ASTNode? {
        var sawAs = false
        var child = moduleImport.firstChildNode
        while (child != null) {
            if (child.elementType == E.IMPORT_ITEMS) {
                child = child.treeNext
                continue
            }
            if (child.elementType == T.KW_AS) sawAs = true
            else if (sawAs && child.elementType == T.IDENTIFIER) return child
            child = child.treeNext
        }
        return null
    }

    private fun isInLoopBody(forLoop: ASTNode, usageOffset: Int): Boolean {
        val body = loopBody(forLoop) ?: return false
        return body.textRange.contains(usageOffset)
    }

    private fun loopBody(forLoop: ASTNode): ASTNode? {
        var child = forLoop.lastChildNode
        while (child != null) {
            if (child.elementType == E.CODE_BLOCK || child.elementType == E.CONTENT_BLOCK) return child
            child = child.treePrev
        }
        return null
    }

    private fun isDescendantOf(node: ASTNode?, ancestor: ASTNode?): Boolean {
        if (node == null || ancestor == null) return false
        var current: ASTNode? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.treeParent
        }
        return false
    }

    private fun firstDirectChildOfType(node: ASTNode, type: IElementType): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == type) return child
            child = child.treeNext
        }
        return null
    }

    private fun directChildrenOfType(node: ASTNode, type: IElementType): List<ASTNode> {
        val children = ArrayList<ASTNode>()
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == type) children.add(child)
            child = child.treeNext
        }
        return children
    }

    private fun importSourceNodes(item: ASTNode): List<ASTNode> {
        val nodes = ArrayList<ASTNode>()
        var child = item.firstChildNode
        while (child != null && child.elementType != T.KW_AS) {
            if (child.elementType == T.IDENTIFIER) nodes.add(child)
            child = child.treeNext
        }
        return nodes
    }

    private fun firstMeaningfulChild(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        while (child != null && isSkippable(child.elementType)) child = child.treeNext
        return child
    }

    private fun nextMeaningfulSibling(node: ASTNode): ASTNode? {
        var sibling = node.treeNext
        while (sibling != null) {
            when (sibling.elementType) {
                TokenType.WHITE_SPACE,
                T.LINE_COMMENT, T.BLOCK_COMMENT, T.COMMA -> sibling = sibling.treeNext
                else -> return sibling
            }
        }
        return null
    }

    private fun isSkippable(type: IElementType): Boolean = when (type) {
        TokenType.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.PARBREAK -> true
        else -> false
    }

    private fun unquote(text: String): String =
        text.removeSurrounding("\"").let { if (it == text) text.removePrefix("\"").removeSuffix("\"") else it }

    private fun labelName(labelDefText: String): String =
        labelDefText.removePrefix("<").removeSuffix(">")
}

object TypstModuleFiles {

    fun resolveModuleFile(importingFile: PsiFile, pathString: String?): TypstFile? {
        if (pathString.isNullOrEmpty()) return null
        if (pathString.startsWith("@")) {
            val target = TypstPackageResolver.resolveEntrypoint(importingFile.project, pathString) ?: return null
            return PsiManager.getInstance(importingFile.project).findFile(target) as? TypstFile
        }
        val importingVirtualFile = importingFile.virtualFile ?: importingFile.originalFile.virtualFile ?: return null
        val base = importingVirtualFile.parent ?: return null
        val target = if (pathString.startsWith('/')) {
            val workspaceRoot = TypstSettingsService.getInstance(importingFile.project)
                .workspaceRoot(Paths.get(importingVirtualFile.path))
            val resolved = workspaceRoot.resolve(pathString.removePrefix("/")).normalize()
            if (!resolved.startsWith(workspaceRoot)) null
            else LocalFileSystem.getInstance().findFileByNioFile(resolved)
                ?: if (!pathString.endsWith(".typ")) LocalFileSystem.getInstance().findFileByNioFile(Paths.get("$resolved.typ")) else null
        } else {
            base.findFileByRelativePath(pathString)
                ?: if (!pathString.endsWith(".typ")) base.findFileByRelativePath("$pathString.typ") else null
        }
            ?: return null
        return PsiManager.getInstance(importingFile.project).findFile(target) as? TypstFile
    }
}
