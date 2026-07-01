package com.livteam.typninja.language

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.livteam.typninja.MyBundle
import javax.swing.Icon

/**
 * File type singleton for Typst (.typ) files.
 *
 * Registered in plugin.xml via:
 *   <fileType name="Typst"
 *             implementationClass="com.livteam.typninja.language.TypstFileType"
 *             fieldName="INSTANCE"
 *             language="Typst"
 *             extensions="typ"/>
 *
 * The Kotlin `object` declaration generates a JVM-level `public static final INSTANCE`
 * field automatically, satisfying the `fieldName="INSTANCE"` contract without an
 * explicit field declaration.
 *
 * Immutable singleton; no mutable state.
 */
object TypstFileType : LanguageFileType(TypstLanguage) {

    private val ICON: Icon by lazy {
        IconLoader.getIcon("/icons/typst.svg", TypstFileType::class.java)
    }

    /** Internal file type name — must match the `name` attribute in plugin.xml. */
    override fun getName(): String = "Typst"

    /** User-visible description sourced from the resource bundle. */
    override fun getDescription(): String = MyBundle.message("filetype.typst.description")

    /** Default file extension registered for this file type. */
    override fun getDefaultExtension(): String = "typ"

    override fun getIcon(): Icon = ICON
}
