package com.zugaldia.speedofsound.core.plugins.asr

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** Small native boundary so lifecycle tests need neither JNI nor downloaded model files. */
internal interface SherpaRecognizer : AutoCloseable {
    fun createStream(): SherpaRecognitionStream
}

internal interface SherpaRecognitionStream : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun decode(): String
}

internal class NativeSherpaRecognizer(config: OfflineRecognizerConfig) : SherpaRecognizer {
    private val recognizer = OfflineRecognizer(config)

    override fun createStream(): SherpaRecognitionStream {
        val stream = recognizer.createStream()
        return object : SherpaRecognitionStream {
            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
                stream.acceptWaveform(samples, sampleRate)
            }

            override fun decode(): String {
                recognizer.decode(stream)
                return recognizer.getResult(stream).text
            }

            override fun close() {
                stream.release()
            }
        }
    }

    override fun close() {
        recognizer.release()
    }
}
