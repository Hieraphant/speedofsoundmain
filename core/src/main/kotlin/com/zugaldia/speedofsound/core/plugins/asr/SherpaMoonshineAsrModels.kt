package com.zugaldia.speedofsound.core.plugins.asr

import com.zugaldia.speedofsound.core.Language
import com.zugaldia.speedofsound.core.SHERPA_ONNX_ASR_MODELS_URL
import com.zugaldia.speedofsound.core.models.voice.VoiceModel
import com.zugaldia.speedofsound.core.models.voice.VoiceModelFile

/**
 * Builds the catalog entry for a Moonshine v2 archive published by sherpa-onnx. Every one of these archives has
 * the same layout: an encoder, a merged decoder, and a tokens file. The tokens file must stay last because
 * [SherpaOfflineAsr] reads `components.last()` to locate it.
 */
private fun moonshineV2Model(
    id: String,
    name: String,
    language: Language,
    dataSizeMegabytes: Long,
    sha256sum: String,
): Pair<String, VoiceModel> = id to VoiceModel(
    id = id,
    name = name,
    provider = AsrProvider.SHERPA_MOONSHINE,
    dataSizeMegabytes = dataSizeMegabytes,
    archiveFile = VoiceModelFile(
        name = id,
        url = "$SHERPA_ONNX_ASR_MODELS_URL/$id.tar.bz2",
        sha256sum = sha256sum,
    ),
    components = listOf(
        VoiceModelFile(name = "encoder_model.ort"),
        VoiceModelFile(name = "decoder_model_merged.ort"),
        VoiceModelFile(name = "tokens.txt"),
    ),
    languages = listOf(language),
)

val SUPPORTED_SHERPA_MOONSHINE_ASR_MODELS = mapOf(
    // The English models are released under the MIT License.
    moonshineV2Model(
        id = DEFAULT_ASR_SHERPA_MOONSHINE_MODEL_ID,
        name = "Moonshine v2 Base (English only)",
        language = Language.ENGLISH,
        dataSizeMegabytes = 135L,
        sha256sum = "43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
        name = "Moonshine v2 Tiny (English only)",
        language = Language.ENGLISH,
        dataSizeMegabytes = 43L,
        sha256sum = "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
    ),

    // The non-English models are released under the Moonshine Community License, which permits
    // non-commercial use only. See the LICENSE file inside each archive.
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-ar-quantized-2026-02-27",
        name = "Moonshine v2 Base (Arabic only)",
        language = Language.ARABIC,
        dataSizeMegabytes = 135L,
        sha256sum = "4d3f16fe354d8536d3323d3eaf4c12fafb1976b5dc0a12862ab25934da94285b",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-zh-quantized-2026-02-27",
        name = "Moonshine v2 Base (Chinese only)",
        language = Language.CHINESE,
        dataSizeMegabytes = 135L,
        sha256sum = "6495d4240f66bcdd2bbbbfefaa687c463150d467854001270934e88940b733c1",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27",
        name = "Moonshine v2 Base (Japanese only)",
        language = Language.JAPANESE,
        dataSizeMegabytes = 135L,
        sha256sum = "c66eb87d229a52d43bbe3b7c52a1d23f6208c0fd7c2e81802ecbda83b192798a",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27",
        name = "Moonshine v2 Tiny (Japanese only)",
        language = Language.JAPANESE,
        dataSizeMegabytes = 69L,
        sha256sum = "880305c9a6c33572ab269ff9731977ea42ca34c8cffcdd5d99558a9ea2b47cc2",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27",
        name = "Moonshine v2 Tiny (Korean only)",
        language = Language.KOREAN,
        dataSizeMegabytes = 69L,
        sha256sum = "d3b6c5390a7859c9ef20ff4f20b0766fcbad1dc06c0f509fe4840a3a302112dc",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-es-quantized-2026-02-27",
        name = "Moonshine v2 Base (Spanish only)",
        language = Language.SPANISH,
        dataSizeMegabytes = 63L,
        sha256sum = "850c3dcc5dfccc8b1feb10bb221b11d6039b6f5c626241729f46863771016383",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-uk-quantized-2026-02-27",
        name = "Moonshine v2 Base (Ukrainian only)",
        language = Language.UKRAINIAN,
        dataSizeMegabytes = 135L,
        sha256sum = "d8489b159afabf4cfb734002ae8a5361781ff8a381fa18d284d32d1eeb1c0376",
    ),
    moonshineV2Model(
        id = "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27",
        name = "Moonshine v2 Base (Vietnamese only)",
        language = Language.VIETNAMESE,
        dataSizeMegabytes = 135L,
        sha256sum = "97b53f5fb75a2a9dd6327440a34f45fc06c927ed2fa7217e5ebe54058e269416",
    ),
)
