package com.livteam.typninja.language.editor

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstInlayHintsProviderTest : DeclarativeInlayHintsProviderTestCase() {

    fun testProviderRendersTextFirstPositionalArgumentAsFillHint() {
        doTestProvider(
            "test.typ",
            """#text(/*<# fill: #>*/get-level-color(level), size: text-14, weight: "bold")[#formatted]""",
            TypstInlayHintsProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }

    fun testProviderSkipsNamedParametersWhenRenderingPositionalHint() {
        doTestProvider(
            "test.typ",
            """#link(dest: "https://example.com", /*<# body: #>*/[Docs])""",
            TypstInlayHintsProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }

    fun testTextFirstPositionalArgumentUsesFillHint() {
        val file = myFixture.configureByText(
            "test.typ",
            """#text(get-level-color(level), size: text-14, weight: "bold")[#formatted]""",
        )
        val firstArgument = firstPositionalArgument(file)

        assertEquals("fill", TypstInlayHintLogic.parameterNameForArgument(firstArgument))
    }

    fun testNamedArgumentsDoNotShiftPositionalHintIndex() {
        val file = myFixture.configureByText("test.typ", """#link(dest: "https://example.com", [Docs])""")
        val contentArgument = firstPositionalArgument(file)

        assertEquals("body", TypstInlayHintLogic.parameterNameForArgument(contentArgument))
    }

    fun testNamedArgumentDoesNotGetParameterHint() {
        val file = myFixture.configureByText("test.typ", "#table(columns: 2)\n")
        val namedArgument = firstNodeOf(file, TypstElementTypes.NAMED)

        assertNull(TypstInlayHintLogic.parameterNameForArgument(namedArgument))
    }

    private fun firstPositionalArgument(file: PsiFile): ASTNode {
        val args = firstNodeOf(file, TypstElementTypes.ARGS)
        var child = args.firstChildNode
        while (child != null) {
            if (isPositionalArgument(child.elementType)) return child
            child = child.treeNext
        }
        error("no positional argument found in: ${file.text}")
    }

    private fun isPositionalArgument(type: IElementType): Boolean =
        type != TypstTokenTypes.LPAREN &&
            type != TypstTokenTypes.RPAREN &&
            type != TypstTokenTypes.COMMA &&
            type != TypstElementTypes.NAMED &&
            type != TokenType.WHITE_SPACE &&
            type != TypstTokenTypes.LINE_COMMENT &&
            type != TypstTokenTypes.BLOCK_COMMENT &&
            type != TypstTokenTypes.PARBREAK

    private fun firstNodeOf(file: PsiFile, type: IElementType): ASTNode {
        var found: ASTNode? = null
        fun walk(node: ASTNode) {
            if (found != null) return
            if (node.elementType == type) {
                found = node
                return
            }
            var child = node.firstChildNode
            while (child != null) {
                walk(child)
                child = child.treeNext
            }
        }
        walk(file.node)
        return found ?: error("no $type node found in: ${file.text}")
    }
}
