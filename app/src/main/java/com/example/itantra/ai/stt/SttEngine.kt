package com.example.itantra.ai.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.itantra.util.Logger

class SttEngine(private val context: Context) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResultListener: ((String) -> Unit)? = null
    private var isInitialized = false

    fun initModel(unused: String, onComplete: (Boolean) -> Unit) {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (isAvailable) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
            isInitialized = true
            Logger.d("STT: Google SpeechRecognizer initialized")
        } else {
            Logger.e("STT: Google SpeechRecognizer NOT available on this device")
        }
        onComplete(isAvailable)
    }

    fun startListening(onResult: (String) -> Unit) {
        if (!isInitialized) return
        this.onResultListener = onResult

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        try {
            speechRecognizer?.startListening(intent)
            Logger.d("STT: System listener started")
        } catch (e: Exception) {
            Logger.e("STT: Failed to start system recognizer", e)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        Logger.d("STT: System listener stopped")
    }

    // RecognitionListener implementation
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    
    override fun onError(error: Int) {
        Logger.e("STT Error: $error")
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            Logger.d("STT Final Result: $text")
            onResultListener?.invoke(text)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Logger.d("STT Partial Result: ${matches[0]}")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
