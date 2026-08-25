package tw.terry.tshunhue.data.remote

import tw.terry.tshunhue.data.validation.CatalogLimits
import tw.terry.tshunhue.data.validation.CatalogValidator
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small HTTPS-only client: it follows a bounded redirect chain and enforces byte limits while reading. */
class HttpCatalogClient(private val validator: CatalogValidator) {
    suspend fun get(url: String, byteLimit: Int): ByteArray = withContext(Dispatchers.IO) {
        var current = validator.requireHttps(url, "請求 URL")
        repeat(CatalogLimits.MAX_REDIRECTS + 1) { attempt ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(attempt < CatalogLimits.MAX_REDIRECTS) { "重新導向次數過多" }
                    val location = connection.getHeaderField("Location") ?: error("重新導向沒有目的地")
                    current = validator.requireHttps(URL(URL(current), location).toString(), "重新導向 URL")
                    return@repeat
                }
                require(status in 200..299) { "伺服器回應 HTTP $status" }
                val declaredLength = connection.contentLengthLong
                require(declaredLength < 0 || declaredLength <= byteLimit) { "回應超過大小限制" }
                return@withContext BufferedInputStream(connection.inputStream).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= byteLimit) { "回應超過大小限制" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("重新導向次數過多")
    }
}
