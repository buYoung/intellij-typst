package com.livteam.typninja.language.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode

/**
 * Generic PSI element for every Typst composite node.
 *
 * The node's [com.intellij.psi.tree.IElementType] (one of [TypstElementTypes]) identifies which
 * syntax region it is, so the MVP does not need a separate PSI class per region. Hand-written
 * mixins or typed wrappers can be introduced later without changing the parser.
 */
open class TypstPsiElement(node: ASTNode) : ASTWrapperPsiElement(node)
