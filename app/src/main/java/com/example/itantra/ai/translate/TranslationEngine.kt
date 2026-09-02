package com.example.itantra.ai.translate

import android.content.Context
import com.example.itantra.util.Logger
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class TranslationEngine(private val context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private var currentTranslator: Translator? = null

    suspend fun identifyLanguage(text: String): String {
        return try {
            val langCode = languageIdentifier.identifyLanguage(text).await()
            if (langCode == "und") "en" else langCode
        } catch (e: Exception) {
            Logger.e("MLKit: Language ID failed", e)
            "en"
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String {
        if (sourceLang == targetLang || sourceLang == "und") return text

        return try {
            Logger.d("MLKit: Translating '$text' from $sourceLang to $targetLang")
            
            val sourceTag = TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH
            val targetTag = TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.ENGLISH
            
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()

            val translator = Translation.getClient(options)
            
            val conditions = DownloadConditions.Builder()
                .build() // Remove wifi requirement for more reliability
            
            // This might take time on first run
            translator.downloadModelIfNeeded(conditions).await()
            
            val result = translator.translate(text).await()
            translator.close()
            result
        } catch (e: Exception) {
            Logger.e("MLKit Translation Error: ${e.message}")
            text // Fallback to original text so user hears SOMETHING
        }
    }


    fun close() {
        languageIdentifier.close()
    }
}
