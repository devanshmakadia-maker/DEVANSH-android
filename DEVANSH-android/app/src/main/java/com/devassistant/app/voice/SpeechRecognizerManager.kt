package com.devassistant.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in SpeechRecognizer for two purposes:
 *  1. Wake-word spotting: short, continuously-restarted recognition sessions whose partial
 *     results are checked for the phrase "hey devansh".
 *  2. Command capture: a single longer recognition session after the wake word fires.
 *
 * Note: Android's on-device recognizer is not a true low-power hotword engine. This is a
 * pragmatic wake-word approximation; for production-grade "always listening at near-zero
 * battery cost" behavior, integrate a dedicated hotword SDK (e.g. Porcupine) behind this same
 * interface.
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun startListening(
        prompt: String? = null,
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            prompt?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it) }
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onError(error)

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        r.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}
