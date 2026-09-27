package com.livteam.typninja.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.livteam.typninja.runtime.TypstWasmInstaller
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

data class TypstToolchainCapability(
    val version: String? = null,
    val isValid: Boolean = false,
    val failureMessage: String? = null,
    val isValidationPending: Boolean = false,
)

/** Validates the selected, checksum-pinned WASM engine independently of JCEF. */
@Service(Service.Level.PROJECT)
class TypstToolchainService(private val project: Project, private val coroutineScope: CoroutineScope) {
    private val validationGeneration = AtomicLong()
    @Volatile private var capability = TypstToolchainCapability()
    @Volatile private var validationJob: Job? = null

    init { requestValidation() }

    fun currentCapability(): TypstToolchainCapability = capability

    suspend fun awaitCapability(): TypstToolchainCapability {
        while (capability.isValidationPending) {
            val job = validationJob
            if (job == null) yield() else job.join()
        }
        return capability
    }

    fun requestValidation() {
        val generation = validationGeneration.incrementAndGet()
        validationJob?.cancel()
        validationJob = null
        capability = TypstToolchainCapability(isValidationPending = true)
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val validated = try {
                val settings = TypstSettingsService.getInstance(project).state
                val version = settings.typstWasmVersion?.takeIf(String::isNotBlank) ?: TypstWasmInstaller.defaultVersion
                TypstWasmInstaller.resolve(version, settings.autoDownloadWasm)
                TypstToolchainCapability(version = version, isValid = true)
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                TypstToolchainCapability(failureMessage = exception.message ?: "Cannot load the Typst WASM engine")
            }
            if (validationGeneration.get() == generation) capability = validated
        }
        validationJob = job
        job.start()
    }

    companion object { fun getInstance(project: Project): TypstToolchainService = project.service() }
}
