package com.livteam.typninja.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "TypstProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TypstSettingsService : SimplePersistentStateComponent<TypstSettingsService.Settings>(Settings()) {

    class Settings : BaseState() {
        var useDefaultPackageRoots by property(true)
        var packagePath by string("")
        var packageCachePath by string("")
        var enableSemanticDiagnostics by property(true)
        var enableLint by property(true)
        var typstExecutablePath by string("")
        var previewPpi by property(144)
        var defaultExportFormat by string("pdf")
    }

    fun packageRoots(): List<String> = buildList {
        state.packagePath?.takeIf { it.isNotBlank() }?.let(::add)
        state.packageCachePath?.takeIf { it.isNotBlank() }?.let(::add)
    }

    companion object {
        fun getInstance(project: Project): TypstSettingsService = project.service()
    }
}
