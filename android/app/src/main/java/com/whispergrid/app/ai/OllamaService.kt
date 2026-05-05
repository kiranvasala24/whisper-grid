package com.whispergrid.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class OllamaRequest(
    val model: String = "gemma2:2b",
    val prompt: String,
    val stream: Boolean = false
)

@Serializable
data class OllamaResponse(
    val model: String,
    val response: String,
    val done: Boolean
)

class OllamaService(
    private val baseUrl: String = "http://10.0.2.2:11434" // Android emulator -> host machine
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val TAG = "OllamaService"
    }

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = OllamaRequest(prompt = prompt)
            val requestBody = json.encodeToString(request)

            val url = URL("$baseUrl/api/generate")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 60000
            }

            // Send request
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray())
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val response = json.decodeFromString<OllamaResponse>(responseText)

                Log.d(TAG, "Ollama response: ${response.response.take(100)}")
                Result.success(response.response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode"
                Log.e(TAG, "Ollama error: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama request failed", e)
            Result.failure(e)
        }
    }

    suspend fun translate(text: String, targetLanguage: String): Result<String> {
        val prompt = """
            Translate the following text to $targetLanguage.
            Only respond with the translation, nothing else.
            
            Text: $text
        """.trimIndent()

        return generate(prompt)
    }

    suspend fun detectLanguage(text: String): Result<String> {
        val prompt = """
            What language is this text in? Respond with only the language name.
            
            Text: $text
        """.trimIndent()

        return generate(prompt)
    }

    suspend fun assessDamage(description: String): Result<DamageAssessment> {
        val prompt = """
            Analyze this disaster damage report and assess the severity.
            Respond in JSON format with: severity (LOW/MEDIUM/HIGH/CRITICAL), category (STRUCTURAL/INFRASTRUCTURE/HUMAN/ENVIRONMENTAL), urgency (1-10).
            
            Report: $description
        """.trimIndent()

        return try {
            val response = generate(prompt).getOrThrow()

            // Parse severity from response
            val severity = when {
                response.contains("CRITICAL", ignoreCase = true) -> DamageSeverity.CRITICAL
                response.contains("HIGH", ignoreCase = true) -> DamageSeverity.HIGH
                response.contains("MEDIUM", ignoreCase = true) -> DamageSeverity.MEDIUM
                else -> DamageSeverity.LOW
            }

            val category = when {
                response.contains("STRUCTURAL", ignoreCase = true) -> DamageCategory.STRUCTURAL
                response.contains("INFRASTRUCTURE", ignoreCase = true) -> DamageCategory.INFRASTRUCTURE
                response.contains("HUMAN", ignoreCase = true) -> DamageCategory.HUMAN
                else -> DamageCategory.ENVIRONMENTAL
            }

            Result.success(DamageAssessment(severity, category, description))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkMisinformation(text: String): Result<MisinformationCheck> {
        val prompt = """
            Analyze if this message contains potential misinformation or unreliable claims.
            Respond with: SAFE, QUESTIONABLE, or SUSPICIOUS, followed by a brief reason.
            
            Message: $text
        """.trimIndent()

        return try {
            val response = generate(prompt).getOrThrow()

            val trustLevel = when {
                response.contains("SUSPICIOUS", ignoreCase = true) -> TrustLevel.SUSPICIOUS
                response.contains("QUESTIONABLE", ignoreCase = true) -> TrustLevel.QUESTIONABLE
                else -> TrustLevel.SAFE
            }

            Result.success(MisinformationCheck(trustLevel, response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isOllamaAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/tags")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val responseCode = connection.responseCode
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "Ollama not available", e)
            false
        }
    }
}

data class DamageAssessment(
    val severity: DamageSeverity,
    val category: DamageCategory,
    val description: String
)

enum class DamageSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

enum class DamageCategory {
    STRUCTURAL, INFRASTRUCTURE, HUMAN, ENVIRONMENTAL
}

data class MisinformationCheck(
    val trustLevel: TrustLevel,
    val reason: String
)

enum class TrustLevel {
    SAFE, QUESTIONABLE, SUSPICIOUS
}