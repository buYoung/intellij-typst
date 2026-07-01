package com.livteam.typninja.language

import com.intellij.lang.Commenter

/**
 * Line-comment support for Typst (`// ...`).
 *
 * v1 scope is the "line-comment-only" policy (typst-formatting FDD 9.6): IDE line-comment toggling
 * (Comment with Line Comment) adds and removes the `//` prefix predictably. Block-comment toggling
 * is intentionally NOT enabled — every block-comment method returns `null`, so the platform's
 * "Comment with Block Comment" action is disabled for Typst and line-comment toggling can never
 * introduce a `/* ... */` block comment.
 *
 * Registered via `<lang.commenter language="Typst" .../>`.
 */
class TypstCommenter : Commenter {

    /** Typst single-line comment prefix; the only construct line-comment toggling adds/removes. */
    override fun getLineCommentPrefix(): String = "//"

    // Block-comment toggling is out of scope for v1: returning null disables it entirely so line
    // commenting never falls back to block comments.
    override fun getBlockCommentPrefix(): String? = null

    override fun getBlockCommentSuffix(): String? = null

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
