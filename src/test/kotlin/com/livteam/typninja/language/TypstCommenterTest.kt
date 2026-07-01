package com.livteam.typninja.language

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Locks the line-comment-only policy: toggling a line comment adds/removes `//` and never introduces
 * a `/* ... */` block comment. Add and remove are tested independently with an explicit caret so the
 * assertions do not depend on where the caret lands after the action.
 */
class TypstCommenterTest : BasePlatformTestCase() {

    fun testLineCommentToggleAddsSlashesAndNeverBlockComment() {
        myFixture.configureByText("test.typ", "#let x = 1<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)

        val commented = myFixture.editor.document.text
        assertTrue("line comment must add //", commented.contains("//"))
        assertFalse("line comment must never introduce a block comment", commented.contains("/*"))
        assertTrue("original content must remain", commented.contains("#let x = 1"))
    }

    fun testLineCommentToggleRemovesSlashes() {
        myFixture.configureByText("test.typ", "//#let x = 1<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("toggling a commented line must remove the //", "#let x = 1", myFixture.editor.document.text)
    }

    fun testLineCommentOnPlainMarkup() {
        myFixture.configureByText("test.typ", "plain markup line<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        val commented = myFixture.editor.document.text
        assertTrue(commented.contains("//"))
        assertFalse(commented.contains("/*"))
    }
}
