package com.musictracker.app.data.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface ArtworkHttpClient {
    suspend fun getText(url: String): String?
    suspend fun getBytes(url: String): ByteArray?
}

class UrlConnectionArtworkHttpClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : ArtworkHttpClient {

    override suspend fun getText(url: String): String? {
        val bytes = fetch(url = url, maxBytes = MAX_TEXT_BYTES) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    override suspend fun getBytes(url: String): ByteArray? =
        fetch(url = url, maxBytes = MAX_IMAGE_BYTES)

    private suspend fun fetch(url: String, maxBytes: Int): ByteArray? = withContext(ioDispatcher) {
        var connection: HttpURLConnection? = null
        try {
            val parsed = URL(url)
            val protocol = parsed.protocol.lowercase()
            if (protocol != "http" && protocol != "https") {
                return@withContext null
            }
            connection = (parsed.openConnection() as? HttpURLConnection) ?: return@withContext null
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")

            val status = connection.responseCode
            if (status !in 200..299) {
                return@withContext null
            }
            connection.inputStream.use { input ->
                readBoundedBytes(input, maxBytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray? {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
        private const val MAX_TEXT_BYTES = 1 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val USER_AGENT = "Collecto/2.0 (Android)"
    }
}
