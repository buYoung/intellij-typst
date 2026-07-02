package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.references.TypstBuiltinResolver
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

enum class TypstDefinitionKind {
    LET_VARIABLE,
    LET_FUNCTION,
    PARAMETER,
    LOOP_BINDING,
    LABEL,
    BUILTIN_FUNCTION,
    BUILTIN_TYPE,
    BUILTIN_MODULE,
}

data class TypstDefinition(
    val name: String,
    val kind: TypstDefinitionKind,
    val nameElement: PsiElement,
    val declarationElement: PsiElement,
) {
    val navigationElement: PsiElement
        get() = declarationElement
}

data class TypstResolveResult(val definition: TypstDefinition)

data class TypstImportItem(
    val localName: String,
    val moduleName: String,
    val nameNode: ASTNode,
)

data class TypstImportSummary(
    val pathString: String?,
    val items: List<TypstImportItem>,
    val isGlob: Boolean,
    val isPackageImport: Boolean,
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

    fun snapshot(file: PsiFile): TypstAnalysisSnapshot? {
        val typstFile = file as? TypstFile ?: file.originalFile as? TypstFile ?: return null
        return CachedValuesManager.getCachedValue(typstFile) {
            CachedValueProvider.Result.create(buildSnapshot(typstFile), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    fun parseImport(moduleImport: ASTNode): TypstImportSummary {
        val path = moduleImport.findChildByType(E.STRING_LITERAL)?.let { unquote(it.text) }
        val itemsNode = moduleImport.findChildByType(E.IMPORT_ITEMS)
        val isGlob = moduleImport.findChildByType(T.STAR) != null ||
            (itemsNode != null && itemsNode.findChildByType(T.STAR) != null)
        val items = if (itemsNode == null) emptyList() else parseItems(itemsNode)
        return TypstImportSummary(
            pathString = path,
            items = items,
            isGlob = isGlob,
            isPackageImport = path?.startsWith("@") == true,
        )
    }

    fun resolve(usage: TypstReferenceExpression): TypstResolveResult? {
        val name = usage.referenceName
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

    fun resolveImportedName(usageFile: PsiFile, name: String): TypstResolveResult? {
        val snapshot = snapshot(usageFile) ?: return null
        for (locatedImport in snapshot.imports) {
            val summary = locatedImport.summary
            val moduleName = summary.items.firstOrNull { it.localName == name }?.moduleName
                ?: (if (summary.isGlob) name else null)
                ?: continue
            val moduleFile = TypstModuleFiles.resolveModuleFile(usageFile, summary.pathString) ?: continue
            val exportedDefinition = snapshot(moduleFile)?.exportedDefinition(moduleName) ?: continue
            return TypstResolveResult(exportedDefinition)
        }
        return null
    }

    fun resolveBuiltin(anchor: TypstReferenceExpression, name: String): TypstResolveResult? {
        val kind = when {
            TypstBuiltins.isType(name) -> TypstDefinitionKind.BUILTIN_TYPE
            TypstBuiltins.isModule(name) -> TypstDefinitionKind.BUILTIN_MODULE
            TypstBuiltins.isFunction(name) -> TypstDefinitionKind.BUILTIN_FUNCTION
            else -> return null
        }
        val target = TypstBuiltinResolver.resolve(anchor, name) ?: return null
        return TypstResolveResult(TypstDefinition(name, kind, target, target))
    }

    private fun buildSnapshot(file: TypstFile): TypstAnalysisSnapshot {
        val declarations = ArrayList<TypstDefinition>()
        val imports = ArrayList<TypstLocatedImport>()
        val labels = ArrayList<TypstDefinition>()
        val headings = ArrayList<PsiElement>()
        val pathLiterals = ArrayList<TypstPathLiteral>()
        val root = file.node
        collect(root, declarations, imports, labels, headings, pathLiterals)
        return TypstAnalysisSnapshot(file, declarations, imports, labels, headings, pathLiterals)
    }

    private fun collect(
        node: ASTNode,
        declarations: MutableList<TypstDefinition>,
        imports: MutableList<TypstLocatedImport>,
        labels: MutableList<TypstDefinition>,
        headings: MutableList<PsiElement>,
        pathLiterals: MutableList<TypstPathLiteral>,
    ) {
        ProgressManager.checkCanceled()
        when (node.elementType) {
            E.LET_BINDING -> letDefinition(node)?.let { declarations.add(it) }
            E.PARAMS -> collectParameterDefinitions(node, declarations)
            E.CLOSURE -> bareClosureParameterDefinition(node)?.let { declarations.add(it) }
            E.FOR_LOOP -> collectLoopBindingDefinitions(node, declarations)
            E.MODULE_IMPORT -> imports.add(TypstLocatedImport(node, parseImport(node)))
            E.MODULE_INCLUDE -> collectIncludePath(node, pathLiterals)
            E.HEADING -> headings.add(node.psi)
        }
        if (node.elementType == T.LABEL_DEF) {
            val name = labelName(node.text)
            if (name.isNotEmpty()) labels.add(TypstDefinition(name, TypstDefinitionKind.LABEL, node.psi, node.psi))
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, declarations, imports, labels, headings, pathLiterals)
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

    private fun collectParameterDefinitions(container: ASTNode, declarations: MutableList<TypstDefinition>) {
        var child = container.firstChildNode
        while (child != null) {
            when (child.elementType) {
                T.IDENTIFIER -> declarations.add(parameterDefinition(child))
                E.NAMED -> firstDirectChildOfType(child, T.IDENTIFIER)?.let { declarations.add(parameterDefinition(it)) }
                E.DESTRUCTURING -> collectParameterDefinitions(child, declarations)
            }
            child = child.treeNext
        }
    }

    private fun bareClosureParameterDefinition(closure: ASTNode): TypstDefinition? {
        if (closure.findChildByType(E.PARAMS) != null) return null
        val first = firstMeaningfulChild(closure) ?: return null
        return if (first.elementType == T.IDENTIFIER) parameterDefinition(first) else null
    }

    private fun collectLoopBindingDefinitions(forLoop: ASTNode, declarations: MutableList<TypstDefinition>) {
        var child = forLoop.firstChildNode
        while (child != null && child.elementType != T.KW_IN) {
            when (child.elementType) {
                T.IDENTIFIER -> declarations.add(
                    TypstDefinition(child.text, TypstDefinitionKind.LOOP_BINDING, child.psi, child.psi),
                )
                E.DESTRUCTURING -> collectLoopBindingDefinitions(child, declarations)
            }
            child = child.treeNext
        }
    }

    private fun parameterDefinition(nameNode: ASTNode): TypstDefinition =
        TypstDefinition(nameNode.text, TypstDefinitionKind.PARAMETER, nameNode.psi, nameNode.psi)

    private fun collectIncludePath(node: ASTNode, pathLiterals: MutableList<TypstPathLiteral>) {
        node.findChildByType(E.STRING_LITERAL)?.let {
            pathLiterals.add(TypstPathLiteral(unquote(it.text), it.psi))
        }
    }

    private fun parseItems(itemsNode: ASTNode): List<TypstImportItem> {
        val items = ArrayList<TypstImportItem>()
        var child = itemsNode.firstChildNode
        while (child != null) {
            if (child.elementType == T.IDENTIFIER) {
                val nameNode = child
                val moduleName = nameNode.text
                val asKeyword = nextMeaningfulSibling(nameNode)
                val alias = if (asKeyword?.elementType == T.KW_AS) nextMeaningfulSibling(asKeyword) else null
                val localName = if (alias?.elementType == T.IDENTIFIER) alias.text else moduleName
                items.add(TypstImportItem(localName, moduleName, nameNode))
                child = if (alias?.elementType == T.IDENTIFIER) alias.treeNext else nameNode.treeNext
            } else {
                child = child.treeNext
            }
        }
        return items
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
        if (pathString.isNullOrEmpty() || pathString.startsWith("@")) return null
        val base = (importingFile.virtualFile ?: importingFile.originalFile.virtualFile)?.parent ?: return null
        val target = base.findFileByRelativePath(pathString)
            ?: (if (!pathString.endsWith(".typ")) base.findFileByRelativePath("$pathString.typ") else null)
            ?: return null
        return PsiManager.getInstance(importingFile.project).findFile(target) as? TypstFile
    }
}
