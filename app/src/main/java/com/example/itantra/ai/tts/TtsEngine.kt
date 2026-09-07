package com.example.itantra.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
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

    private fun getLocaleForLanguage(languageCode: String): Locale {
        return when (languageCode.lowercase()) {
            "ml" -> Locale("ml", "IN")
            "ta" -> Locale("ta", "IN")
            "hi" -> Locale("hi", "IN")
            "te" -> Locale("te", "IN")
            "mr" -> Locale("mr", "IN")
            "bn" -> Locale("bn", "IN")
            "gu" -> Locale("gu", "IN")
            "kn" -> Locale("kn", "IN")
            "pa" -> Locale("pa", "IN")
            "ur" -> Locale("ur", "IN")
            "es" -> Locale("es", "ES")
            "fr" -> Locale("fr", "FR")
            "de" -> Locale("de", "DE")
            "en" -> Locale.US
            else -> Locale.forLanguageTag(languageCode)
        }
    }

    fun speak(text: String, languageCode: String = "en", isEmergency: Boolean = false) {
        if (!isInitialized) {
            Logger.e("TTS: Not initialized")
            return
        }

        val locale = getLocaleForLanguage(languageCode)
        var result = tts?.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Logger.e("TTS: Specific locale $locale not supported, trying fallback for $languageCode")
            val fallbackLocale = Locale(languageCode)
            result = tts?.setLanguage(fallbackLocale)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (isEmergency) {
            // Force system media volume to maximum for emergency broadcasts
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            
            tts?.setSpeechRate(1.2f) // Urgent, faster speech
            tts?.setPitch(1.25f)     // Higher pitch for alert tone
            Logger.e("TTS EMERGENCY BROADCAST TRIGGERED: $text")
        } else {
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        Logger.d("TTS Speaking ($languageCode / $locale): $text")
        val resultSpeak = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "itantra_msg")
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
