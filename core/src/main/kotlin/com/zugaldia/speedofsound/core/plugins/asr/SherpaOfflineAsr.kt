package com.zugaldia.speedofsound.core.plugins.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.zugaldia.speedofsound.core.Language
import com.zugaldia.speedofsound.core.audio.AudioManager
import com.zugaldia.speedofsound.core.models.voice.ModelManager
import com.zugaldia.speedofsound.core.models.voice.VoiceModel
import java.nio.file.Path

/**
 * Abstract base class for Sherpa ONNX offline ASR plugins.
 *
 * Handles the common recognizer lifecycle (create, configuration changes, transcribe, close)
 * while delegating the model-specific OfflineModelConfig setup to subclasses.
 */
@Suppress("TooManyFunctions") // Lifecycle overrides plus the two internal native/validation test seams.
abstract class SherpaOfflineAsr<Options : AsrPluginOptions>(
    initialOptions: Options,
) : AsrPlugin<Options>(initialOptions = initialOptions) {

    companion object {
        // Ideally, we use "cuda" for faster inference whenever available (Sherpa fallbacks to CPU if CUDA is not
        // available). However, it seems that the official JAR files do not include this support. Assuming we can
        // access the GPU from the sandboxed environment, we need a build of Sherpa with -DSHERPA_ONNX_ENABLE_GPU=ON.
        // The next step is to make it work outside Flatpak/Snap (CLI), then we can tackle the sandboxes.
        const val PROVIDER = "cpu"
    }

    // Never acquire a plugin-registry lock while holding this lock. Subclass creation hooks run under it too.
    private val recognizerLock = Any()
    private var recognizer: SherpaRecognizer? = null
    private var recognizerConfiguration: RecognizerConfiguration? = null
    private var enabled = false

    // These are the options consumed by the shared factory and the model-specific configuration hooks.
    private data class RecognizerConfiguration(val modelId: String, val language: Language, val enableDebug: Boolean)

    internal open fun createModelManager(): ModelManager = ModelManager()

    internal open fun createNativeRecognizer(config: OfflineRecognizerConfig): SherpaRecognizer =
        NativeSherpaRecognizer(config)

    override fun updateOptions(options: Options) = synchronized(recognizerLock) {
        // Defer expensive loading to enable/next transcription, rather than doing it in the settings callback.
        super.updateOptions(options)
    }

    /**
     * Returns the supported model registry for this plugin.
     */
    protected abstract fun supportedModels(): Map<String, VoiceModel>

    /**
     * Applies the model-specific configuration (e.g. Whisper, Canary, Transducer) to the
     * [OfflineModelConfig.Builder].
     *
     * Subclasses should extract the component paths they need from [model] and [modelPath],
     * call the appropriate setter (e.g. `setWhisper`, `setCanary`, `setTransducer`) on the builder,
     * and return the resulting builder.
     */
    protected abstract fun applyModelSpecificConfig(
        builder: OfflineModelConfig.Builder,
        model: VoiceModel,
        modelPath: Path,
        language: Language,
    ): OfflineModelConfig.Builder

    /**
     * Called before the recognizer is created. Override for any pre-creation setup
     * such as extracting bundled models.
     */
    protected open fun onBeforeCreateRecognizer(modelManager: ModelManager) {
        // Default: no-op
    }

    override fun enable() = synchronized(recognizerLock) {
        super.enable()
        ensureRecognizerConfiguration()
        enabled = true
    }

    private fun createRecognizer(configuration: RecognizerConfiguration) {
        val modelManager = createModelManager()
        onBeforeCreateRecognizer(modelManager)

        val model = supportedModels()[configuration.modelId]
        if (model == null || !modelManager.isModelDownloaded(configuration.modelId)) {
            val reason = if (model == null) "not found" else "not downloaded"
            throw IllegalStateException("Model ${configuration.modelId} $reason.")
        }

        val modelPath = modelManager.getModelPath(configuration.modelId)
        val tokens = modelPath.resolve(model.components.last().name).toString()

        val modelConfigBuilder = applyModelSpecificConfig(
            OfflineModelConfig.builder(),
            model,
            modelPath,
            configuration.language,
        )

        val modelConfig = modelConfigBuilder
            .setTokens(tokens)
            .setNumThreads(Runtime.getRuntime().availableProcessors())
            .setProvider(PROVIDER)
            .setDebug(configuration.enableDebug)
            .build()

        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build()

        recognizer = createNativeRecognizer(config)
        recognizerConfiguration = configuration
        log.info("Recognizer created: ${configuration.modelId}/${configuration.language.iso2}")
    }

    private fun ensureRecognizerConfiguration() {
        val requested = RecognizerConfiguration(
            currentOptions.modelId, currentOptions.language, currentOptions.enableDebug
        )
        if (recognizer == null || requested != recognizerConfiguration) {
            if (recognizer != null) log.info("Recognizer configuration changed, reinitializing.")
            // Clear/release before replacement: failure must not leave a freed or incorrectly labelled engine usable.
            closeRecognizer()
            createRecognizer(requested)
        }
    }

    override fun transcribe(request: AsrRequest): Result<AsrResponse> = synchronized(recognizerLock) {
        runCatching {
            check(enabled) { "Recognizer not initialized, plugin must be enabled first" }
            ensureRecognizerConfiguration()
            val currentRecognizer = checkNotNull(recognizer)
            val loaded = checkNotNull(recognizerConfiguration)
            try {
                currentRecognizer.createStream().use { stream ->
                    log.info("Transcribing with ${loaded.modelId}/${loaded.language}")
                    val floatArray = AudioManager.convertPcm16ToFloat(request.audioData)
                    stream.acceptWaveform(floatArray, request.audioInfo.sampleRate)
                    AsrResponse(stream.decode())
                }
            } finally {
                log.info("Transcription completed.")
            }
        }
    }

    private fun closeRecognizer() {
        val oldRecognizer = recognizer
        recognizer = null
        recognizerConfiguration = null
        oldRecognizer?.close()
    }

    override fun disable() = synchronized(recognizerLock) {
        enabled = false
        closeRecognizer()
        super.disable()
    }

    override fun shutdown() = synchronized(recognizerLock) {
        enabled = false
        closeRecognizer()
        super.shutdown()
    }
}
