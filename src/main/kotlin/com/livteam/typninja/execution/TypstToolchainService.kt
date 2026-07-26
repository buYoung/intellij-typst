package com.livteam.typninja.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TypstToolchainCapability(
    val executablePath: String? = null,
    val version: String? = null,
    val isValid: Boolean = false,
    val failureMessage: String? = null,
    val isValidationPending: Boolean = false,
)

/** Validates the configured Typst CLI without using a language server or a shell. */
@Service(Service.Level.PROJECT)
class TypstToolchainService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val validationGeneration = AtomicLong()

    @Volatile
    private var capability = TypstToolchainCapability()

    @Volatile
    private var validationJob: Job? = null

    init {
        requestValidation()
    }

    fun currentCapability(): TypstToolchainCapability = capability

    suspend fun awaitCapability(): TypstToolchainCapability {
        while (capability.isValidationPending) {
            val currentJob = validationJob
            if (currentJob == null) {
                yield()
            } else {
                currentJob.join()
            }
        }
        return capability
    }

    fun requestValidation() {
        val settings = TypstSettingsService.getInstance(project)
        val executablePath = TypstExecutableResolver.resolve(project, settings.state.typstExecutablePath.orEmpty())
        val generation = validationGeneration.incrementAndGet()
        validationJob?.cancel()
        validationJob = null
        if (executablePath == null) {
            capability = TypstToolchainCapability(failureMessage = "Typst executable was not found automatically")
            return
        }
        capability = TypstToolchainCapability(executablePath = executablePath, isValidationPending = true)
        validationJob = coroutineScope.launch {
            val validated = validate(executablePath)
            val currentSettings = TypstSettingsService.getInstance(project)
            val currentPath = TypstExecutableResolver.resolve(project, currentSettings.state.typstExecutablePath.orEmpty())
            if (validationGeneration.get() == generation && currentPath == executablePath) {
                capability = validated
            }
        }
    }

    private suspend fun validate(executablePath: String): TypstToolchainCapability = withContext(Dispatchers.IO) {
        try {
            val versionCommand = GeneralCommandLine(executablePath).withParameters("--version")
            val versionResult = runCommand(versionCommand)
            if (versionResult.exitCode != 0) {
                return@withContext TypstToolchainCapability(executablePath, failureMessage = versionResult.error.ifBlank {
                    "Typst executable exited with code ${versionResult.exitCode}"
                })
            }
            val version = VERSION_PATTERN.find(versionResult.output)?.groupValues?.get(1)
            val helpResult = runCommand(GeneralCommandLine(executablePath).withParameters("compile", "--help"))
            if (helpResult.exitCode != 0 || !helpResult.output.contains("--format") || !helpResult.output.contains("--root") ||
                !helpResult.output.contains("--ppi") || !helpResult.output.contains("png")) {
                return@withContext TypstToolchainCapability(executablePath, version, false, "Typst compile capabilities are insufficient")
            }
            TypstToolchainCapability(executablePath, version, true)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            TypstToolchainCapability(executablePath, failureMessage = exception.message ?: "Cannot start Typst executable")
        }
    }

    private suspend fun runCommand(commandLine: GeneralCommandLine): CommandResult = coroutineScope {
        val process = commandLine.createProcess()
        try {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val error = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                ensureActive()
                delay(1)
            }
            CommandResult(process.exitValue(), output.await().trim(), error.await().trim())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    companion object {
        private val VERSION_PATTERN = Regex("""typst\s+([0-9]+\.[0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)

        fun getInstance(project: Project): TypstToolchainService = project.service()
    }
}
