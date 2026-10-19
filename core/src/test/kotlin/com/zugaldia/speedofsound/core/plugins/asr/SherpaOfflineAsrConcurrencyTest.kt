package com.zugaldia.speedofsound.core.plugins.asr

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SherpaOfflineAsrConcurrencyTest {
    @Test
    fun `option update waits for in-flight decode and applies on next call`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        val gate = TestGate()
        f.onDecode = { gate.block() }
        withTwoWorkers { workers ->
            val decoding = workers.submit(Callable { f.transcribe() })
            gate.awaitEntered()
            val started = CountDownLatch(1)
            val changing = workers.submit {
                started.countDown()
                f.select("model-b")
            }
            try {
                await(started)
                assertBlocked(changing)
                assertEquals(0, f.engines.single().closeCalls.get())
            } finally {
                gate.release()
            }
            assertEquals("model-a/en/false", decoding.get(5, TimeUnit.SECONDS))
            changing.get(5, TimeUnit.SECONDS)
            assertEquals("model-b/en/false", f.transcribe())
            assertEquals(listOf("model-a", "model-b"), f.engines.map { it.modelId })
        }
    }

    @Test
    fun `disable waits for decoding before releasing the engine`() = verifyStopDuringDecode { it.plugin.disable() }

    @Test
    fun `shutdown waits for decoding before releasing the engine`() = verifyStopDuringDecode { it.plugin.shutdown() }

    private fun verifyStopDuringDecode(stop: (SherpaLifecycleFixture) -> Unit) = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        val gate = TestGate()
        f.onDecode = { gate.block() }
        withTwoWorkers { workers ->
            val decoding = workers.submit(Callable { f.transcribe() })
            gate.awaitEntered()
            val started = CountDownLatch(1)
            val stopping = workers.submit {
                started.countDown()
                stop(f)
            }
            try {
                await(started)
                assertBlocked(stopping)
                assertEquals(0, f.engines.single().closeCalls.get())
            } finally {
                gate.release()
            }
            assertEquals("model-a/en/false", decoding.get(5, TimeUnit.SECONDS))
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals(1, f.engines.single().closeCalls.get())
            assertEquals(1, f.engines.single().streams.single().closeCalls.get())
            assertFailsWith<IllegalStateException> { f.transcribe() }
        }
    }

    @Test
    fun `concurrent transcription calls do not share an engine unsafely`() = SherpaLifecycleFixture().use { f ->
        f.plugin.enable()
        val gate = TestGate()
        f.onDecode = { gate.block() }
        withTwoWorkers { workers ->
            val first = workers.submit(Callable { f.transcribe() })
            gate.awaitEntered()
            val started = CountDownLatch(1)
            val second = workers.submit(Callable {
                started.countDown()
                f.transcribe()
            })
            try {
                await(started)
                assertBlocked(second)
                assertEquals(1, f.engines.single().streams.size)
            } finally {
                gate.release()
            }
            assertEquals("model-a/en/false", first.get(5, TimeUnit.SECONDS))
            assertEquals("model-a/en/false", second.get(5, TimeUnit.SECONDS))
            assertEquals(2, f.engines.single().streams.size)
            assertTrue(f.engines.single().streams.all { it.closeCalls.get() == 1 })
        }
    }

    @Test
    fun `subclass creation hooks see one consistent option snapshot`() = SherpaLifecycleFixture().use { f ->
        val gate = TestGate()
        f.beforeCreate = { gate.block() }
        withTwoWorkers { workers ->
            val enabling = workers.submit { f.plugin.enable() }
            gate.awaitEntered()
            val started = CountDownLatch(1)
            val changing = workers.submit {
                started.countDown()
                f.select("model-b")
            }
            try {
                await(started)
                assertBlocked(changing)
                assertEquals("model-a", f.creationOptions.single().modelId)
            } finally {
                gate.release()
            }
            enabling.get(5, TimeUnit.SECONDS)
            changing.get(5, TimeUnit.SECONDS)
            assertEquals("model-a", f.engines.single().modelId)
            assertEquals("model-b/en/false", f.transcribe())
            assertEquals(listOf("model-a", "model-b"), f.creationOptions.map { it.modelId })
        }
    }

    private fun withTwoWorkers(block: (java.util.concurrent.ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        try {
            block(workers)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Test workers must terminate")
        }
    }

    private fun assertBlocked(future: Future<*>) {
        assertFailsWith<TimeoutException> { future.get(100, TimeUnit.MILLISECONDS) }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for a test checkpoint")
    }

    private class TestGate {
        private val entered = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        fun block() {
            entered.countDown()
            assertTrue(proceed.await(5, TimeUnit.SECONDS), "Test gate was not released")
        }
        fun awaitEntered() {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Test gate was not reached")
        }
        fun release() {
            proceed.countDown()
        }
    }
}
