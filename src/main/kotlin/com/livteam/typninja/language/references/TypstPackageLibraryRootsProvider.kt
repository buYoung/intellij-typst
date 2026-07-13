package com.livteam.typninja.language.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.livteam.typninja.language.analysis.TypstProjectModelService

/** Exposes only the already-scanned local package roots to IntelliJ's library model. */
class TypstPackageLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val roots = TypstProjectModelService.getInstance(project).packageCatalog().packageRoots
            .filter(VirtualFile::isValid)
        if (roots.isEmpty()) return emptyList()
        return listOf(object : SyntheticLibrary() {
            override fun getSourceRoots(): Collection<VirtualFile> = roots

            override fun equals(other: Any?): Boolean = other is SyntheticLibrary && sourceRoots == other.sourceRoots

            override fun hashCode(): Int = roots.map { it.url }.hashCode()
        })
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        TypstProjectModelService.getInstance(project).packageCatalog().packageRoots.filter(VirtualFile::isValid)
}
