package com.example.itantra.ai.translate

import android.content.Context
import com.example.itantra.util.Logger
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class TranslationEngine(private val context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val translationMemoryCache = ConcurrentHashMap<String, String>()

    suspend fun identifyLanguage(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "en"
        return@withContext try {
            val langCode = languageIdentifier.identifyLanguage(text).await()
            if (langCode == "und") "en" else langCode
        } catch (e: Exception) {
            Logger.e("MLKit: Language ID failed", e)
            "en"
        }
    }

    private fun getTranslator(sourceTag: String, targetTag: String): Translator {
        val key = "$sourceTag->$targetTag"
        return translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun preloadLanguageModel(langCode: String): Boolean = withContext(Dispatchers.IO) {
        if (langCode == "en" || langCode == "und") return@withContext true
        return@withContext try {
            val langTag = TranslateLanguage.fromLanguageTag(langCode) ?: return@withContext false
            Logger.d("MLKit: Preloading models for $langCode <-> English")
            
            val conditions = DownloadConditions.Builder().build()
            
            val toEng = getTranslator(langTag, TranslateLanguage.ENGLISH)
            toEng.downloadModelIfNeeded(conditions).await()

            val fromEng = getTranslator(TranslateLanguage.ENGLISH, langTag)
            fromEng.downloadModelIfNeeded(conditions).await()

            Logger.d("MLKit: Preload complete for $langCode")
            true
        } catch (e: Exception) {
            Logger.e("MLKit: Model preload failed for $langCode: ${e.message}", e)
            false
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        if (sourceLang == targetLang || text.isBlank()) return@withContext text

        val cacheKey = "$sourceLang->$targetLang:$text"
        translationMemoryCache[cacheKey]?.let {
            return@withContext it
        }

        // Google ML Kit does not support Urdu ("ur"). Route directly to offline rescue dictionary.
        if (sourceLang == "ur" || targetLang == "ur") {
            val fallback = getOfflineFallbackTranslation(text, sourceLang, targetLang)
            translationMemoryCache[cacheKey] = fallback
            return@withContext fallback
        }

        return@withContext try {
            Logger.d("MLKit: Translating '$text' ($sourceLang -> $targetLang)")
            
            val sourceTag = TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH
            val targetTag = TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.ENGLISH
            
            val translator = getTranslator(sourceTag, targetTag)
            val conditions = DownloadConditions.Builder().build()
            
            translator.downloadModelIfNeeded(conditions).await()
            val result = translator.translate(text).await()
            
            translationMemoryCache[cacheKey] = result
            result
        } catch (e: Exception) {
            Logger.e("MLKit Translation Error ($sourceLang -> $targetLang): ${e.message}")
            // Use fully bidirectional offline fallback dictionary
            val fallback = getOfflineFallbackTranslation(text, sourceLang, targetLang)
            translationMemoryCache[cacheKey] = fallback
            fallback
        }
    }

    /**
     * Fully Bidirectional Offline Rescue Dictionary (Works for both Lang -> English and English -> Lang)
     */
    private fun getOfflineFallbackTranslation(text: String, sourceLang: String, targetLang: String): String {
        val cleanInput = text.trim().lowercase()

        // Fully bidirectional pairs: English <-> Tamil
        val tamilDict = mapOf(
            "hello" to "வணக்கம்", "வணக்கம்" to "hello",
            "hi" to "ஹாய்", "ஹாய்" to "hi",
            "how are you" to "எப்படி இருக்கிறீர்கள்", "எப்படி இருக்கிறீர்கள்" to "how are you",
            "i am fine" to "நான் நலமாக இருக்கிறேன்", "நான் நலமாக இருக்கிறேன்" to "i am fine",
            "good morning" to "காலை வணக்கம்", "காலை வணக்கம்" to "good morning",
            "thank you" to "நன்றி", "நன்றி" to "thank you",
            "yes" to "ஆம்", "ஆம்" to "yes",
            "no" to "இல்லை", "இல்லை" to "no",
            "system ready" to "கட்டமைப்பு தயார்", "கட்டமைப்பு தயார்" to "system ready",
            "help" to "உதவி", "உதவி" to "help",
            "emergency" to "அவசரம்", "அவசரம்" to "emergency",
            "ready" to "தயார்", "தயார்" to "ready",
            "ok" to "சரி", "சரி" to "ok",
            "stop" to "நிறுத்து", "நிறுத்து" to "stop",
            "go" to "செல்க", "செல்க" to "go",
            "wait" to "காத்திருங்கள்", "காத்திருங்கள்" to "wait"
        )

        // Fully bidirectional pairs: English <-> Malayalam
        val malayalamDict = mapOf(
            "hello" to "ഹലോ", "ഹലോ" to "hello",
            "hi" to "ഹായ്", "ഹായ്" to "hi",
            "how are you" to "സുഖമാണോ", "സുഖമാണോ" to "how are you",
            "i am fine" to "എനിക്ക് സുഖമാണ്", "എനിക്ക് സുഖമാണ്" to "i am fine",
            "good morning" to "സുപ്രഭാതം", "സുപ്രഭാതം" to "good morning",
            "thank you" to "നന്ദി", "നന്ദി" to "thank you",
            "yes" to "അതെ", "അതെ" to "yes",
            "no" to "ഇല്ല", "ഇല്ല" to "no",
            "system ready" to "സിസ്റ്റം സജ്ജമാണ്", "സിസ്റ്റം സജ്ജമാണ്" to "system ready",
            "help" to "സഹായം", "സഹായം" to "help",
            "emergency" to "അടിയന്തരഘട്ടം", "അടിയന്തരഘട്ടം" to "emergency",
            "ready" to "തയ്യാറാണ്", "തയ്യാറാണ്" to "ready",
            "ok" to "ശരി", "ശരി" to "ok",
            "stop" to "നിർത്തുക", "നിർത്തുക" to "stop",
            "go" to "പോകൂ", "പോകൂ" to "go",
            "wait" to "കാത്തിരിക്കൂ", "കാത്തിരിക്കൂ" to "wait"
        )

        // Fully bidirectional pairs: English <-> Hindi
        val hindiDict = mapOf(
            "hello" to "नमस्ते", "नमस्ते" to "hello",
            "hi" to "हाय", "हाय" to "hi",
            "how are you" to "आप कैसे हैं", "आप कैसे हैं" to "how are you",
            "i am fine" to "मैं ठीक हूँ", "मैं ठीक हूँ" to "i am fine",
            "good morning" to "शुभ प्रभात", "शुभ प्रभात" to "good morning",
            "thank you" to "धन्यवाद", "धन्यवाद" to "thank you",
            "yes" to "हाँ", "हाँ" to "yes",
            "no" to "नहीं", "नहीं" to "no",
            "system ready" to "सिस्टम तैयार है", "सिस्टम तैयार है" to "system ready",
            "help" to "मदद", "मदद" to "help",
            "emergency" to "आपातकाल", "आपातकाल" to "emergency",
            "ready" to "तैयार", "तैयार" to "ready",
            "ok" to "ठीक है", "ठीक है" to "ok",
            "stop" to "روکو", "روکو" to "stop",
            "go" to "जाओ", "जाओ" to "go",
            "wait" to "انتظار کریں", "انتظار کریں" to "wait"
        )

        // Fully bidirectional pairs: English <-> Urdu
        val urduDict = mapOf(
            "hello" to "ہیلو", "ہیلو" to "hello",
            "hi" to "ہائے", "ہائے" to "hi",
            "how are you" to "آپ کیسے ہیں", "آپ کیسے ہیں" to "how are you",
            "i am fine" to "میں ٹھیک ہوں", "میں ٹھیک ہوں" to "i am fine",
            "good morning" to "صبح بخیر", "صبح بخیر" to "good morning",
            "thank you" to "شکریہ", "شکریہ" to "thank you",
            "yes" to "ہاں", "ہاں" to "yes",
            "no" to "نہیں", "نہیں" to "no",
            "system ready" to "নظام تیار ہے", "নظام تیار ہے" to "system ready",
            "help" to "مدد", "مدد" to "help",
            "emergency" to "ہنگامی صورتحال", "ہنگامی صورتحال" to "emergency",
            "ready" to "تیار", "تیار" to "ready",
            "ok" to "ٹھیک ہے", "ٹھیک ہے" to "ok"
        )

        val activeMap = when {
            sourceLang == "ta" || targetLang == "ta" -> tamilDict
            sourceLang == "ml" || targetLang == "ml" -> malayalamDict
            sourceLang == "hi" || targetLang == "hi" -> hindiDict
            sourceLang == "ur" || targetLang == "ur" -> urduDict
            else -> emptyMap()
        }

        // Look up cleanInput in the bidirectional map (works for both English->Lang and Lang->English)
        activeMap[cleanInput]?.let { return it }

        return text
    }

    fun close() {
        languageIdentifier.close()
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        translationMemoryCache.clear()
    }
}
