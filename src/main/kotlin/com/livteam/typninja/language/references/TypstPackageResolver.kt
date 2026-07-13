package com.livteam.typninja.language.references

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.livteam.typninja.language.analysis.TypstProjectModelService

data class TypstPackageSpec(val namespace: String, val name: String, val version: String) {
    override fun toString(): String = "@$namespace/$name:$version"
}

object TypstPackageResolver {

    private val packagePattern = Regex("@([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+):([0-9A-Za-z.+_-]+)")

    fun parse(specification: String?): TypstPackageSpec? {
        val match = specification?.let { packagePattern.matchEntire(it) } ?: return null
        return TypstPackageSpec(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    fun resolveEntrypoint(project: Project, specification: String?): VirtualFile? =
        parse(specification)?.toString()?.let { TypstProjectModelService.getInstance(project).packageCatalog().entrypoint(it) }

    fun installedSpecifications(project: Project): List<String> =
        TypstProjectModelService.getInstance(project).packageCatalog().specifications()
}
