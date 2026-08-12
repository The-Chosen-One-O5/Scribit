package com.thechosenone.scribit.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.thechosenone.scribit.data.AiMetadata
import com.thechosenone.scribit.data.AppSettings
import com.thechosenone.scribit.data.DocumentRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt


class ProviderHttpException(
    val statusCode: Int,
    message: String,
    val retryAfterMillis: Long?,
    val providerCode: String = ""
) : IOException(message) {
    val isRateLimited: Boolean get() = statusCode == 429
    val isRetryable: Boolean
        get() {
            val code = providerCode.lowercase()
            val text = message.orEmpty().lowercase()
            val looksLikeQuotaExhausted = code.contains("insufficient_quota") ||
                code.contains("billing") || code.contains("quota_exhausted") ||
                text.contains("insufficient quota") || text.contains("billing hard limit")
            return !looksLikeQuotaExhausted && statusCode in setOf(408, 425, 429, 500, 502, 503, 504)
        }
}

class AiClassifier(private val context: Context) {

    data class SearchPlan(
        val keywords: String,
        val category: String?,
        val expiringDays: Int?
    )

    fun testConnection(settings: AppSettings): Result<String> = runCatching {
        require(settings.isConfigured) { "Fill in base URL, API key and model first." }
        val payload = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0)
            put("max_tokens", 12)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Reply with exactly: OK")
            }))
        }
        val text = postChat(settings, payload)
        if (text.isBlank()) error("The provider returned an empty response.")
        text.trim()
    }

    fun classify(settings: AppSettings, document: DocumentRecord, availableCategories: List<String>): AiMetadata {
        require(settings.isConfigured) { "API setup is incomplete." }
        val file = File(document.archivePath)
        require(file.exists()) { "Imported file is missing from the private archive." }

        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", classificationInstruction(document, availableCategories))
            })

            when {
                document.mimeType.startsWith("image/") -> {
                    require(settings.supportsVision) { "Vision is disabled in Settings. Review this image manually or enable vision." }
                    put(imagePart(imageFileToDataUrl(file)))
                }
                document.mimeType == "application/pdf" -> {
                    require(settings.supportsVision) { "PDF classification currently uses rendered page images. Enable vision or review manually." }
                    val pages = pdfToDataUrls(file, maxPages = 3)
                    require(pages.isNotEmpty()) { "Could not render this PDF." }
                    pages.forEach { put(imagePart(it)) }
                }
                document.mimeType.startsWith("text/") -> {
                    val text = file.readText().take(60_000)
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "DOCUMENT TEXT:\n$text")
                    })
                }
                else -> error("Unsupported file type: ${document.mimeType}. You can still edit its metadata manually.")
            }
        }

        val payload = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0.1)
            put("max_tokens", 900)
            put("messages", JSONArray()
                .put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            )
        }

        val response = postChat(settings, payload)
        return parseMetadata(response, document, availableCategories)
    }

    fun planSearch(settings: AppSettings, query: String, availableCategories: List<String>): SearchPlan {
        require(settings.isConfigured) { "API setup is incomplete." }
        val payload = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0)
            put("max_tokens", 160)
            put("messages", JSONArray()
                .put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "Convert a natural-language personal document search into strict JSON only. " +
                            "Schema: {\"keywords\":\"space separated search words\",\"category\":null|string,\"expiring_days\":null|integer}. " +
                            "If category is not null it must be exactly one value from this JSON array: ${JSONArray(availableCategories)}. " +
                            "Do not invent document facts."
                    )
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            )
        }
        val raw = extractJson(postChat(settings, payload))
        val obj = JSONObject(raw)
        return SearchPlan(
            keywords = obj.optString("keywords", query).ifBlank { query },
            category = obj.optString("category", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { requested -> availableCategories.firstOrNull { it.equals(requested, ignoreCase = true) } },
            expiringDays = if (obj.isNull("expiring_days")) null else obj.optInt("expiring_days").takeIf { it > 0 }
        )
    }

    private fun postChat(settings: AppSettings, payload: JSONObject): String {
        val endpoint = normalizeEndpoint(settings.apiBaseUrl)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val errorObject = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                val providerMessage = errorObject?.optString("message")?.takeIf { it.isNotBlank() }
                val providerCode = errorObject?.optString("code").orEmpty()
                throw ProviderHttpException(
                    statusCode = status,
                    message = providerMessage ?: "Provider request failed with HTTP $status",
                    retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
                    providerCode = providerCode
                )
            }
            val json = JSONObject(body)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content")
                ?: error("Provider response did not contain choices[0].message.content")
            return when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        if (part?.optString("type") == "text") append(part.optString("text"))
                    }
                }
                else -> content.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L).coerceAtMost(60L * 60L * 1_000L)
        }
        return runCatching {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (target - System.currentTimeMillis()).coerceAtLeast(0L).coerceAtMost(60L * 60L * 1_000L)
        }.getOrNull()
    }

    private fun parseMetadata(raw: String, document: DocumentRecord, availableCategories: List<String>): AiMetadata {
        val obj = JSONObject(extractJson(raw))
        val confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val title = obj.optString("title", "").ifBlank { document.originalName.substringBeforeLast('.') }
        val requested = mutableListOf<String>()
        val categoryArray = obj.optJSONArray("categories")
        if (categoryArray != null) {
            for (i in 0 until categoryArray.length()) {
                categoryArray.optString(i).takeIf { it.isNotBlank() }?.let(requested::add)
            }
        } else {
            // Accept the old single-category shape from providers that ignored the updated schema.
            obj.optString("category", "").takeIf { it.isNotBlank() }?.let(requested::add)
        }
        val categories = requested.mapNotNull { wanted ->
            availableCategories.firstOrNull { it.equals(wanted.trim(), ignoreCase = true) }
        }.distinctBy { it.lowercase() }
        val tags = obj.optJSONArray("tags") ?: JSONArray()
        val identifiers = obj.optJSONArray("identifiers") ?: JSONArray()
        val searchTerms = obj.optJSONArray("search_terms") ?: JSONArray()
        val explicitReview = obj.optBoolean("needs_review", false)
        return AiMetadata(
            title = title.take(180),
            categories = categories,
            documentType = obj.optString("document_type", "").take(100),
            organization = obj.optString("organization", "").take(180),
            issueDate = normalizeIsoDate(obj.optString("issue_date", "")),
            expiryDate = normalizeIsoDate(obj.optString("expiry_date", "")),
            academicYear = obj.optString("academic_year", "").take(30),
            semester = obj.opt("semester")?.toString()?.takeIf { it != "null" }.orEmpty().take(20),
            identifiersJson = identifiers.toString(),
            tagsJson = tags.toString(),
            summary = obj.optString("summary", "").take(800),
            searchTermsJson = searchTerms.toString(),
            confidence = confidence,
            needsReview = explicitReview || confidence < 0.72 || title.isBlank() || categories.isEmpty()
        )
    }

    private fun classificationInstruction(document: DocumentRecord, availableCategories: List<String>): String = """
        Analyze this personal document and return JSON only.
        Original filename: ${document.originalName}
        MIME type: ${document.mimeType}

        Available categories (choose zero, one, or multiple; never invent a category):
        ${JSONArray(availableCategories)}

        Required schema:
        {
          "title": "short human-friendly title",
          "categories": ["one or more exact values from the available category list"],
          "document_type": "specific type such as Passport, Marksheet, Bank Statement",
          "organization": "issuer/institution or empty string",
          "issue_date": "YYYY-MM-DD or empty string",
          "expiry_date": "YYYY-MM-DD or empty string",
          "academic_year": "or empty string",
          "semester": "number/text or empty string",
          "identifiers": ["safe useful identifiers visible in the document"],
          "tags": ["concise", "searchable", "tags"],
          "summary": "one or two factual sentences",
          "search_terms": ["synonyms or phrases a person might remember"],
          "confidence": 0.0,
          "needs_review": false
        }

        Rules:
        - A document may belong to multiple categories when that is genuinely useful. Example: a university ID can be both Identity and Education.
        - Only use category names from the supplied list, exactly as written.
        - If none of the available categories fit, return an empty categories array and set needs_review=true.
        - Never invent missing dates, IDs, names, institutions, or expiry information.
        - Use empty strings/arrays for unknown fields.
        - If the image is unclear, partial, contradictory, or identity-sensitive details are uncertain, set needs_review=true.
        - Dates must be ISO YYYY-MM-DD only when clearly supported by the document.
        - Return strict JSON with no markdown fences or commentary.
    """.trimIndent()

    private fun normalizeEndpoint(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }


    private fun normalizeIsoDate(value: String): String {
        val clean = value.trim()
        return if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(clean)) clean else ""
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "The model did not return a JSON object." }
        return trimmed.substring(start, end + 1)
    }

    private fun imagePart(dataUrl: String) = JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply {
            put("url", dataUrl)
            put("detail", "auto")
        })
    }

    private fun imageFileToDataUrl(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode image." }
        val maxDimension = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > 1800) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not decode image.")
        return bitmap.useAsDataUrl()
    }

    private fun pdfToDataUrls(file: File, maxPages: Int): List<String> {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            val count = minOf(renderer.pageCount, maxPages)
            val result = ArrayList<String>(count)
            for (index in 0 until count) {
                renderer.openPage(index).use { page ->
                    val targetWidth = minOf(1500, max(800, page.width * 2))
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val targetHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result += bitmap.useAsDataUrl()
                }
            }
            return result
        }
    }

    private fun Bitmap.useAsDataUrl(): String = try {
        val bytes = ByteArrayOutputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
        "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    } finally {
        recycle()
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are the document understanding engine inside a private personal archive. Extract only facts visible in the supplied document. Never guess missing personal data. Your output is consumed by strict software, so return only the requested JSON object.
"""
    }
}
