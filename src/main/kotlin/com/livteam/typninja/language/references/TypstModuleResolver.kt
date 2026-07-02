package com.livteam.typninja.language.references

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/**
 * Cross-file (module) resolution for relative `#import` statements.
 *
 * Handles the common multi-file project shapes WITHOUT any project indexing:
 *  - the import PATH string (`#import "utils.typ": …`) resolves to the imported [TypstFile],
 *  - an explicit import ITEM (`foo`, or the `foo` of `foo as bar`) resolves to the `#let foo`
 *    exported (declared at top level) by that file,
 *  - a body usage of an imported name resolves the same way, including `#import "utils.typ": *`
 *    glob imports.
 *
 * OUT OF SCOPE (returns `null` gracefully): package specs (`@preview/…`, `@local/…` — need the
 * package cache), module-alias field access (`#import "u.typ" as u` then `u.foo`), and re-exports.
 * Path resolution is relative to the importing file's directory; a missing file resolves to `null`.
 */
object TypstModuleResolver {

    /** One explicit `#import "…": name (as alias)` item. */
    data class ImportItem(val localName: String, val moduleName: String, val nameNode: ASTNode)

    /** The parsed shape of a single `#import` statement. */
    data class ImportInfo(val pathString: String?, val items: List<ImportItem>, val glob: Boolean)

    // ---- parsing an import statement ----

    /** Parse a [E.MODULE_IMPORT] node into its path, explicit items, and glob flag. */
    fun parseImport(moduleImport: ASTNode): ImportInfo {
        val path = moduleImport.findChildByType(E.STRING_LITERAL)?.let { unquote(it.text) }
        val itemsNode = moduleImport.findChildByType(E.IMPORT_ITEMS)
        val glob = moduleImport.findChildByType(T.STAR) != null ||
            (itemsNode != null && itemsNode.findChildByType(T.STAR) != null)
        val items = if (itemsNode == null) emptyList() else parseItems(itemsNode)
        return ImportInfo(path, items, glob)
    }

    private fun parseItems(itemsNode: ASTNode): List<ImportItem> {
        val out = ArrayList<ImportItem>()
        var child = itemsNode.firstChildNode
        while (child != null) {
            if (child.elementType == T.IDENTIFIER) {
                val nameNode = child
                val moduleName = nameNode.text
                val asKeyword = nextMeaningful(nameNode)
                val alias = if (asKeyword?.elementType == T.KW_AS) nextMeaningful(asKeyword) else null
                val localName = if (alias?.elementType == T.IDENTIFIER) alias.text else moduleName
                out.add(ImportItem(localName, moduleName, nameNode))
                // Skip past a consumed `as alias` so the alias is not read as its own item.
                child = if (alias?.elementType == T.IDENTIFIER) alias.treeNext else nameNode.treeNext
            } else {
                child = child.treeNext
            }
        }
        return out
    }

    // ---- resolving ----

    /** Every `#import` statement in [file], paired with its parsed info. */
    fun importsIn(file: PsiFile): List<Pair<ASTNode, ImportInfo>> {
        val root = file.node ?: return emptyList()
        val out = ArrayList<Pair<ASTNode, ImportInfo>>()
        collectImports(root, out)
        return out
    }

    private fun collectImports(node: ASTNode, out: MutableList<Pair<ASTNode, ImportInfo>>) {
        if (node.elementType == E.MODULE_IMPORT) out.add(node to parseImport(node))
        var child = node.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            collectImports(child, out)
            child = child.treeNext
        }
    }

    /** Resolve a relative import [pathString] to the imported [TypstFile], or `null`. */
    fun resolveModuleFile(importingFile: PsiFile, pathString: String?): TypstFile? {
        if (pathString.isNullOrEmpty() || pathString.startsWith("@")) return null // packages: out of scope
        val base = (importingFile.virtualFile ?: importingFile.originalFile.virtualFile)?.parent ?: return null
        val target = base.findFileByRelativePath(pathString)
            ?: (if (!pathString.endsWith(".typ")) base.findFileByRelativePath("$pathString.typ") else null)
            ?: return null
        return PsiManager.getInstance(importingFile.project).findFile(target) as? TypstFile
    }

    /** The name element of a top-level `#let [name]` exported by [moduleFile], or `null`. */
    fun findExport(moduleFile: TypstFile, name: String): PsiElement? {
        if (name.isEmpty()) return null
        val root = moduleFile.node ?: return null
        var child = root.firstChildNode
        while (child != null) {
            val letBinding = when (child.elementType) {
                E.LET_BINDING -> child
                E.CODE_EXPRESSION -> child.findChildByType(E.LET_BINDING)
                else -> null
            }
            if (letBinding != null) {
                val nameLeaf = letBinding.findChildByType(T.IDENTIFIER)
                if (nameLeaf != null && nameLeaf.text == name) return nameLeaf.psi
            }
            child = child.treeNext
        }
        return null
    }

    /**
     * Resolve a body usage of [name] through the file's `#import` statements: an explicit item
     * (matched by its LOCAL name) or a glob import whose module happens to export [name]. Returns the
     * exported definition in the imported file, or `null` when no import provides the name.
     */
    fun resolveImportedName(usageFile: PsiFile, name: String): PsiElement? {
        for ((importNode, info) in importsIn(usageFile)) {
            val moduleName = info.items.firstOrNull { it.localName == name }?.moduleName
                ?: (if (info.glob) name else null)
                ?: continue
            val moduleFile = resolveModuleFile(usageFile, info.pathString) ?: continue
            findExport(moduleFile, moduleName)?.let { return it }
        }
        return null
    }

    // ---- helpers ----

    private fun unquote(text: String): String =
        text.removeSurrounding("\"").let { if (it == text) text.removePrefix("\"").removeSuffix("\"") else it }

    private fun nextMeaningful(node: ASTNode): ASTNode? {
        var sibling = node.treeNext
        while (sibling != null) {
            when (sibling.elementType) {
                com.intellij.psi.TokenType.WHITE_SPACE,
                T.LINE_COMMENT, T.BLOCK_COMMENT, T.COMMA -> sibling = sibling.treeNext
                else -> return sibling
            }
        }
        return null
    }
}
