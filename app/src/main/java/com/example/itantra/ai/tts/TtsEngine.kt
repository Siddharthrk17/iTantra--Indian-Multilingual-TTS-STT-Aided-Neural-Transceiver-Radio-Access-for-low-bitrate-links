package com.example.itantra.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.itantra.util.Logger
import java.util.Locale

class TtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context, this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts?.setAudioAttributes(audioAttributes)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            Logger.d("TTS: Initialized successfully")
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Logger.d("TTS Started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Logger.d("TTS Done: $utteranceId")
                }

                override fun onError(utteranceId: String?) {
                    Logger.e("TTS Error: $utteranceId")
                }
            })
        } else {
            Logger.e("TTS: Initialization failed")
        }
    }

    fun speak(text: String, languageCode: String = "en") {
        if (!isInitialized) {
            Logger.e("TTS: Not initialized")
            return
        }

        val locale = Locale(languageCode)
        val result = tts?.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Logger.e("TTS: Language $languageCode not supported or missing data")
        }

        Logger.d("TTS Speaking: $text")
        val resultSpeak = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "itantra_msg")
        if (resultSpeak == TextToSpeech.ERROR) {
            Logger.e("TTS: Failed to queue speak action")
        } else {
            Logger.d("TTS: Successfully queued speak action")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun close() {
        tts?.shutdown()
    }
}