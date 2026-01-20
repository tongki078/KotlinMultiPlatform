package com.nas.musicplayer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Part(val text: String)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class GenerateContentRequest(val contents: List<Content>)

@Serializable
data class Candidate(val content: Content)

@Serializable
data class GeminiError(val message: String)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val error: GeminiError? = null
)

class GeminiService(private val apiKey: String) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        install(Logging) {
            level = LogLevel.BODY
        }
    }

    suspend fun generateText(prompt: String): String? {
        val requestBody = GenerateContentRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = prompt)))
            )
        )

        return try {
            val response: HttpResponse = client.post("https://generativelanguage.googleapis.com/v1/models/gemini-2.0-flash:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (response.status.isSuccess()) {
                val result = response.body<GenerateContentResponse>()
                result.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: result.error?.message?.let { "API Error: $it" } ?: "Response received but content is empty."
            } else {
                val errorBody = response.body<GenerateContentResponse>()
                "API Error: ${errorBody.error?.message ?: response.status.description}"
            }
        } catch (e: Exception) {
            println("Ktor request failed: ${e.message}")
            null
        }
    }
}
