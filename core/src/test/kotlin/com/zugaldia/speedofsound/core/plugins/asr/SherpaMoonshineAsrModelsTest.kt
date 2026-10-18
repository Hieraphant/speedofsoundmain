package com.zugaldia.speedofsound.core.plugins.asr

import com.zugaldia.speedofsound.core.SHERPA_ONNX_ASR_MODELS_URL
import com.zugaldia.speedofsound.core.desktop.settings.SUPPORTED_LOCAL_ASR_MODELS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SherpaMoonshineAsrModelsTest {

    private val models = SUPPORTED_SHERPA_MOONSHINE_ASR_MODELS

    @Test
    fun `every entry is keyed by its own id and uses the Moonshine provider`() {
        assertTrue(models.isNotEmpty())
        models.forEach { (key, model) ->
            assertEquals(key, model.id)
            assertEquals(AsrProvider.SHERPA_MOONSHINE, model.provider)
        }
    }

    @Test
    fun `every model ships encoder, merged decoder, and tokens with tokens last`() {
        // SherpaOfflineAsr resolves the tokens file as components.last(); SherpaMoonshineAsr reads
        // components[0] as the encoder and components[1] as the merged decoder.
        val expected = listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt")
        models.values.forEach { model ->
            assertEquals(expected, model.components.map { it.name }, "Unexpected components for ${model.id}")
        }
    }

    @Test
    fun `every model has a downloadable archive with a sha256 and a measured size`() {
        val sha256 = Regex("^[0-9a-f]{64}$")
        models.values.forEach { model ->
            val archive = assertNotNull(model.archiveFile, "Missing archive for ${model.id}")
            assertEquals(model.id, archive.name)
            assertEquals("$SHERPA_ONNX_ASR_MODELS_URL/${model.id}.tar.bz2", archive.url)
            assertTrue(sha256.matches(archive.sha256sum.orEmpty()), "Bad sha256 for ${model.id}")
            assertTrue(model.dataSizeMegabytes > 0, "Missing size for ${model.id}")
        }
    }

    @Test
    fun `every model is monolingual`() {
        models.values.forEach { model ->
            assertEquals(1, model.languages.size, "Expected exactly one language for ${model.id}")
        }
    }

    @Test
    fun `default model exists and the provider helpers resolve to this family`() {
        assertTrue(DEFAULT_ASR_SHERPA_MOONSHINE_MODEL_ID in models)
        assertEquals(SherpaMoonshineAsr.ID, pluginIdForProvider(AsrProvider.SHERPA_MOONSHINE))
        assertEquals(models, getModelsForProvider(AsrProvider.SHERPA_MOONSHINE))
    }

    @Test
    fun `model ids do not clash with other families and are part of the local catalog`() {
        val otherFamilies = SUPPORTED_SHERPA_WHISPER_ASR_MODELS.keys +
            SUPPORTED_SHERPA_CANARY_ASR_MODELS.keys +
            SUPPORTED_SHERPA_PARAKEET_ASR_MODELS.keys
        assertTrue(models.keys.none { it in otherFamilies })
        assertTrue(SUPPORTED_LOCAL_ASR_MODELS.keys.containsAll(models.keys))
    }
}
