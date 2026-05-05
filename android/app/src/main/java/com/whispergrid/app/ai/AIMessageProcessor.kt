package com.whispergrid.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AIMessageProcessor(
    private val ollamaService: OllamaService
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isOllamaAvailable = MutableStateFlow(false)
    val isOllamaAvailable: StateFlow<Boolean> = _isOllamaAvailable.asStateFlow()

    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled.asStateFlow()

    private val _targetLanguage = MutableStateFlow("English")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    companion object {
        private const val TAG = "AIMessageProcessor"
    }

    init {
        checkOllamaStatus()
    }

    fun checkOllamaStatus() {
        scope.launch {
            val available = ollamaService.isOllamaAvailable()
            _isOllamaAvailable.value = available
            Log.d(TAG, "Ollama available: $available")
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
    }

    fun setTargetLanguage(language: String) {
        _targetLanguage.value = language
    }

    suspend fun processMessage(text: String): ProcessedMessage {
        var processedText = text
        var detectedLanguage: String? = null
        var translatedText: String? = null
        var misinformationCheck: MisinformationCheck? = null

        if (!_isOllamaAvailable.value) {
            return ProcessedMessage(
                originalText = text,
                processedText = text,
                detectedLanguage = null,
                translatedText = null,
                misinformationCheck = null
            )
        }

        // Detect language
        try {
            detectedLanguage = ollamaService.detectLanguage(text).getOrNull()
            Log.d(TAG, "Detected language: $detectedLanguage")
        } catch (e: Exception) {
            Log.e(TAG, "Language detection failed", e)
        }

        // Translate if enabled and not in target language
        if (_translationEnabled.value &&
            detectedLanguage != null &&
            !detectedLanguage.contains(_targetLanguage.value, ignoreCase = true)) {
            try {
                translatedText = ollamaService.translate(text, _targetLanguage.value).getOrNull()
                if (translatedText != null) {
                    processedText = translatedText
                }
                Log.d(TAG, "Translated to ${_targetLanguage.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
            }
        }

        // Check for misinformation
        try {
            misinformationCheck = ollamaService.checkMisinformation(text).getOrNull()
            Log.d(TAG, "Misinformation check: ${misinformationCheck?.trustLevel}")
        } catch (e: Exception) {
            Log.e(TAG, "Misinformation check failed", e)
        }

        return ProcessedMessage(
            originalText = text,
            processedText = processedText,
            detectedLanguage = detectedLanguage,
            translatedText = translatedText,
            misinformationCheck = misinformationCheck
        )
    }

    suspend fun analyzeDamageReport(description: String): DamageAssessment? {
        if (!_isOllamaAvailable.value) return null

        return try {
            ollamaService.assessDamage(description).getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Damage assessment failed", e)
            null
        }
    }
}

data class ProcessedMessage(
    val originalText: String,
    val processedText: String,
    val detectedLanguage: String?,
    val translatedText: String?,
    val misinformationCheck: MisinformationCheck?
)