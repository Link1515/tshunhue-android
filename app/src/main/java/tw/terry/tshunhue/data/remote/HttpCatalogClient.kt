package tw.terry.tshunhue.data.remote

import tw.terry.tshunhue.data.model.HttpMetadata
import tw.terry.tshunhue.data.validation.CatalogLimits
import tw.terry.tshunhue.data.validation.CatalogValidator
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpDocumentResponse(val body: ByteArray?, val metadata: HttpMetadata)

/** HTTPS-only, bounded HTTP client used for untrusted catalog documents. */
class HttpCatalogClient(private val validator: CatalogValidator) {
    suspend fun get(url: String, byteLimit: Int): ByteArray = getDocument(url, null, byteLimit).body
        ?: error("伺服器回應沒有文件內容")

    suspend fun getDocument(url: String, validators: HttpMetadata?, byteLimit: Int): HttpDocumentResponse = withContext(Dispatchers.IO) {
        var current = validator.requireHttps(url, "請求 URL")
        repeat(CatalogLimits.MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                validators?.etag?.let { setRequestProperty("If-None-Match", it) }
                validators?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return@withContext HttpDocumentResponse(null, metadata(connection))
                    in 300..399 -> {
                        require(redirectCount < CatalogLimits.MAX_REDIRECTS) { "重新導向次數過多" }
                        val location = connection.getHeaderField("Location") ?: error("重新導向沒有目的地")
                        current = validator.requireHttps(URL(URL(current), location).toString(), "重新導向 URL")
                    }
                    in 200..299 -> {
                        val declaredLength = connection.contentLengthLong
                        require(declaredLength < 0 || declaredLength <= byteLimit) { "回應超過大小限制" }
                        val body = BufferedInputStream(connection.inputStream).use { input -> readBounded(input, byteLimit) }
                        return@withContext HttpDocumentResponse(body, metadata(connection))
                    }
                    else -> error("伺服器回應 HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("重新導向次數過多")
    }

    private fun readBounded(input: BufferedInputStream, byteLimit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= byteLimit) { "回應超過大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun metadata(connection: HttpURLConnection): HttpMetadata = HttpMetadata(
        etag = connection.getHeaderField("ETag"),
        lastModified = connection.getHeaderField("Last-Modified"),
        expiresAtEpochMillis = connection.getHeaderField("Expires")?.let { value ->
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        },
    )
}
