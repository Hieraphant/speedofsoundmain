package com.zugaldia.speedofsound.core.plugins.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.zugaldia.speedofsound.core.Language
import com.zugaldia.speedofsound.core.models.voice.VoiceModel
import java.nio.file.Path

class SherpaMoonshineAsr(
    options: SherpaMoonshineAsrOptions = SherpaMoonshineAsrOptions(),
) : SherpaOfflineAsr<SherpaMoonshineAsrOptions>(initialOptions = options) {
    override val id: String = ID

    companion object {
        const val ID = "ASR_SHERPA_MOONSHINE"
    }

    override fun supportedModels(): Map<String, VoiceModel> = SUPPORTED_SHERPA_MOONSHINE_ASR_MODELS

    override fun applyModelSpecificConfig(
        builder: OfflineModelConfig.Builder,
        model: VoiceModel,
        modelPath: Path,
        language: Language,
    ): OfflineModelConfig.Builder {
        // Moonshine v2 models ship an encoder plus a single merged decoder. (Moonshine v1 used separate
        // preprocessor/uncached/cached decoder files and is not supported here.) Every Moonshine model is
        // monolingual, so the language is fixed by the model and not passed to the recognizer.
        val encoder = modelPath.resolve(model.components[0].name).toString()
        val mergedDecoder = modelPath.resolve(model.components[1].name).toString()
        val moonshine = OfflineMoonshineModelConfig.builder()
            .setEncoder(encoder)
            .setMergedDecoder(mergedDecoder)
            .build()
        return builder.setMoonshine(moonshine)
    }
}
