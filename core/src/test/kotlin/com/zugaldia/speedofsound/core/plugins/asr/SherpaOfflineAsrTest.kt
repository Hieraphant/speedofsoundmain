package com.zugaldia.speedofsound.core.plugins.asr

import com.zugaldia.speedofsound.core.Language
import com.zugaldia.speedofsound.core.plugins.AppPluginCategory
import com.zugaldia.speedofsound.core.plugins.AppPluginRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class SherpaOfflineAsrTest {
    @Test
    fun `same-provider registry selection reloads A B A before each inference`() = SherpaLifecycleFixture().use { f ->
        val registry = AppPluginRegistry()
        registry.register(AppPluginCategory.ASR, f.plugin)
        listOf("model-a", "model-b", "model-a").forEach { id ->
            f.select(id)
            registry.setActiveById(AppPluginCategory.ASR, f.plugin.id)
            assertEquals("$id/en/false", f.transcribe())
        }
        assertEquals(listOf("model-a", "model-b", "model-a"), f.engines.map { it.modelId })
        assertEquals(listOf(1, 1, 0), f.engines.map { it.closeCalls.get() })
        registry.shutdownAll()
        assertTrue(f.engines.all { it.closeCalls.get() == 1 })
        assertTrue(f.engines.flatMap { it.streams }.all { it.closeCalls.get() == 1 })
    }

    @Test
    fun `unchanged options and repeated enable reuse one engine`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        repeat(3) {
            f.select("model-a")
            f.plugin.enable()
            assertEquals("model-a/en/false", f.transcribe())
        }
        assertEquals(1, f.engines.size)
        assertEquals(0, f.engines.single().closeCalls.get())
        assertEquals(3, f.engines.single().streams.size)
    }

    @Test
    fun `language-only change recreates the configured engine`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.select("model-a", Language.SPANISH)
        assertEquals("model-a/es/false", f.transcribe())
        assertEquals(2, f.engines.size)
        assertEquals(1, f.engines.first().closeCalls.get())
    }

    @Test
    fun `debug change recreates once then reuses the engine`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.select("model-a", debug = true)
        repeat(2) { assertEquals("model-a/en/true", f.transcribe()) }
        assertEquals(2, f.engines.size)
        assertEquals(1, f.engines.first().closeCalls.get())
    }

    @Test
    fun `inactive option updates do not load or implicitly enable`() = SherpaLifecycleFixture().use { f ->
        f.select("model-b")
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertTrue(f.engines.isEmpty())
        f.plugin.enable()
        assertEquals("model-b/en/false", f.transcribe())
    }

    @Test
    fun `disable and shutdown are idempotent and cannot resurrect an engine`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.transcribe()
        f.plugin.disable()
        f.plugin.disable()
        f.plugin.shutdown()
        f.select("model-b")
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertEquals(1, f.engines.size)
        assertEquals(1, f.engines.first().closeCalls.get())
        f.plugin.enable()
        assertEquals("model-b/en/false", f.transcribe())
    }

    @Test
    fun `unknown replacement fails without stale output then a valid selection recovers`() {
        verifyInvalidModel("unknown", "not found")
    }

    @Test
    fun `undownloaded replacement fails without stale output then a valid selection recovers`() {
        verifyInvalidModel("not-downloaded", "not downloaded")
    }

    private fun verifyInvalidModel(modelId: String, reason: String) = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.transcribe()
        f.select(modelId)
        repeat(2) {
            val error = assertFailsWith<IllegalStateException> { f.transcribe() }
            assertTrue(error.message.orEmpty().contains(reason))
        }
        assertEquals(1, f.engines.size)
        assertEquals(1, f.engines.single().closeCalls.get())
        assertEquals(1, f.engines.single().streams.size, "Invalid selection must not decode with the old engine")
        f.select("model-b")
        assertEquals("model-b/en/false", f.transcribe())
    }

    @Test
    fun `failed native replacement retries without another option change`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.failCreation = true
        f.select("model-b")
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertEquals(1, f.engines.single().closeCalls.get())
        f.failCreation = false
        assertEquals("model-b/en/false", f.transcribe())
        assertEquals(2, f.engines.size)
    }

    @Test
    fun `failed initial enable stays disabled until a successful enable`() = SherpaLifecycleFixture().use { f ->
        f.failCreation = true
        assertFailsWith<IllegalStateException> { f.plugin.enable() }
        f.failCreation = false
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertTrue(f.engines.isEmpty())
        f.plugin.enable()
        assertEquals("model-a/en/false", f.transcribe())
    }

    @Test
    fun `failed language reload leaves no freed engine and can retry`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.failCreation = true
        f.select("model-a", Language.SPANISH)
        assertFailsWith<IllegalStateException> { f.transcribe() }
        f.plugin.disable()
        assertEquals(1, f.engines.single().closeCalls.get())
        f.failCreation = false
        f.plugin.enable()
        assertEquals("model-a/es/false", f.transcribe())
    }

    @Test
    fun `stream creation failure leaves the engine available for retry`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.engines.single().failAt = "create-stream"
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertTrue(f.engines.single().streams.isEmpty())
        f.engines.single().failAt = ""
        assertEquals("model-a/en/false", f.transcribe())
        assertEquals(1, f.engines.size)
    }

    @Test
    fun `waveform failure releases its stream and permits subsequent inference`() = verifyStreamFailure("accept")

    @Test
    fun `decode failure releases its stream and permits subsequent inference`() = verifyStreamFailure("decode")

    @Test
    fun `stream release failure does not trigger a second release`() = verifyStreamFailure("close-stream")

    private fun verifyStreamFailure(stage: String) = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        val engine = f.engines.single()
        engine.failAt = stage
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertEquals(1, engine.streams.single().closeCalls.get())
        assertEquals(0, engine.closeCalls.get())
        assertFalse(engine.decoding.get())
        engine.failAt = ""
        assertEquals("model-a/en/false", f.transcribe())
        assertTrue(engine.streams.all { it.closeCalls.get() == 1 })
    }

    @Test
    fun `release failure clears state and never double releases`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        f.engines.single().failAt = "close-engine"
        f.select("model-b")
        assertFailsWith<IllegalStateException> { f.transcribe() }
        assertEquals(1, f.engines.single().closeCalls.get())
        assertEquals("model-b/en/false", f.transcribe())
        f.plugin.disable()
        assertTrue(f.engines.all { it.closeCalls.get() == 1 })
    }
}
