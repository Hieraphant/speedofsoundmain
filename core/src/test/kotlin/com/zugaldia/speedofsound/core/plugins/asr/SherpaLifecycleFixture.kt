package com.zugaldia.speedofsound.core.plugins.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.zugaldia.speedofsound.core.Language
import com.zugaldia.speedofsound.core.models.voice.ModelManager
import com.zugaldia.speedofsound.core.models.voice.PathProvider
import com.zugaldia.speedofsound.core.models.voice.VoiceModel
import com.zugaldia.speedofsound.core.models.voice.VoiceModelCatalog
import com.zugaldia.speedofsound.core.models.voice.VoiceModelFile
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real model validation/configuration with temporary component files, but fake native engines/streams. */
internal class SherpaLifecycleFixture : AutoCloseable {
    val directory: Path = createTempDirectory("sos-sherpa-lifecycle-")
    val engines = CopyOnWriteArrayList<FakeSherpaRecognizer>()
    val creationOptions = CopyOnWriteArrayList<SherpaWhisperAsrOptions>()
    var failCreation = false
    var beforeCreate: () -> Unit = {}
    var onDecode: () -> Unit = {}
    val models = listOf("model-a", "model-b", "not-downloaded").associateWith { modelId ->
        VoiceModel(
            id = modelId,
            name = modelId,
            provider = AsrProvider.SHERPA_WHISPER,
            components = listOf(VoiceModelFile(name = "encoder"), VoiceModelFile(name = "decoder"),
                VoiceModelFile(name = "tokens")),
        )
    }
    val manager = ModelManager(
        pathProvider = object : PathProvider {
            override fun getDataDir(): Path = directory
            override fun getCacheDir(): Path = directory.resolve("cache")
        },
        voiceModelCatalog = object : VoiceModelCatalog {
            override fun getModel(modelId: String): VoiceModel? = models[modelId]
            override fun getDefaultModelId(): String = "model-a"
        },
    )
    val plugin = FakeSherpaPlugin(this)

    init {
        listOf("model-a", "model-b").forEach { id ->
            requireNotNull(models[id]).components.forEach {
                manager.getModelPath(id).resolve(it.name).toFile().writeText("test component")
            }
        }
    }

    fun select(id: String, language: Language = Language.ENGLISH, debug: Boolean = false) {
        plugin.updateOptions(SherpaWhisperAsrOptions(id, language, debug))
    }

    fun transcribe(): String = plugin.transcribe(AsrRequest(byteArrayOf(0, 0, 1, 0))).getOrThrow().text

    override fun close() {
        try {
            plugin.shutdown()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

internal class FakeSherpaPlugin(private val fixture: SherpaLifecycleFixture) :
    SherpaOfflineAsr<SherpaWhisperAsrOptions>(SherpaWhisperAsrOptions("model-a", Language.ENGLISH)) {
    override val id = "TEST_SHERPA"
    override fun supportedModels(): Map<String, VoiceModel> = fixture.models
    internal override fun createModelManager(): ModelManager = fixture.manager
    override fun onBeforeCreateRecognizer(modelManager: ModelManager) {
        fixture.creationOptions.add(currentOptions)
        fixture.beforeCreate()
        // A settings update on another thread must not change the options halfway through a creation hook.
        assertEquals(fixture.creationOptions.last(), currentOptions)
    }

    override fun applyModelSpecificConfig(
        builder: OfflineModelConfig.Builder,
        model: VoiceModel,
        modelPath: Path,
        language: Language,
    ): OfflineModelConfig.Builder = builder.setWhisper(
        OfflineWhisperModelConfig.builder()
            .setEncoder(modelPath.resolve("encoder").toString())
            .setDecoder(modelPath.resolve("decoder").toString())
            .setLanguage(language.iso2)
            .build()
    )

    internal override fun createNativeRecognizer(config: OfflineRecognizerConfig): SherpaRecognizer {
        check(!fixture.failCreation) { "native creation failed" }
        return FakeSherpaRecognizer(config, fixture).also { fixture.engines.add(it) }
    }
}

internal class FakeSherpaRecognizer(
    val config: OfflineRecognizerConfig,
    private val fixture: SherpaLifecycleFixture,
) : SherpaRecognizer {
    val modelId: String = Path.of(config.modelConfig.tokens).parent.fileName.toString()
    val label: String = "$modelId/${config.modelConfig.whisper.language}/${config.modelConfig.debug}"
    val closeCalls = AtomicInteger()
    val decoding = AtomicBoolean()
    val streams = CopyOnWriteArrayList<FakeSherpaStream>()
    var failAt = ""

    override fun createStream(): SherpaRecognitionStream {
        assertEquals(0, closeCalls.get(), "A closed recognizer must not be used")
        check(failAt != "create-stream") { "stream creation failed" }
        return FakeSherpaStream(this, fixture).also { streams.add(it) }
    }

    override fun close() {
        assertFalse(decoding.get(), "An engine must not be released during decoding")
        assertEquals(1, closeCalls.incrementAndGet(), "An engine must be released exactly once")
        check(failAt != "close-engine") { "engine release failed" }
    }
}

internal class FakeSherpaStream(
    private val engine: FakeSherpaRecognizer,
    private val fixture: SherpaLifecycleFixture,
) : SherpaRecognitionStream {
    val closeCalls = AtomicInteger()
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        assertTrue(samples.isNotEmpty())
        assertEquals(16000, sampleRate)
        check(engine.failAt != "accept") { "waveform failed" }
    }

    override fun decode(): String {
        assertEquals(0, engine.closeCalls.get())
        assertTrue(engine.decoding.compareAndSet(false, true))
        try {
            fixture.onDecode()
            check(engine.failAt != "decode") { "decode failed" }
            return engine.label
        } finally {
            engine.decoding.set(false)
        }
    }

    override fun close() {
        assertFalse(engine.decoding.get())
        assertEquals(1, closeCalls.incrementAndGet(), "A stream must be released exactly once")
        check(engine.failAt != "close-stream") { "stream release failed" }
    }
}
