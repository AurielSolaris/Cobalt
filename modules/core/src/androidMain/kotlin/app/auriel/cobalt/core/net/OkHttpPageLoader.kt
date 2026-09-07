package app.auriel.cobalt.core.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The 0.1.0 network stack: OkHttp, a size cap, and typed errors.
 *
 * This exists to get bytes on screen before Chromium builds, and it is deleted
 * in Stage 6 along with the rest of the bring-up shim. It is not the place to
 * grow a cache, a cookie jar, or a connection policy — Chromium already has all
 * three, and duplicating them here would only create something to migrate off.
 */
class OkHttpPageLoader(
    private val decoder: TextDecoder = AndroidTextDecoder,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : PageLoader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun load(url: Url): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toString())
            .header("User-Agent", UserAgent.VALUE)
            .header("Accept", UserAgent.ACCEPT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Failure(
                        FetchError.HttpStatus(response.code, response.message.ifEmpty { "The server refused the request." })
                    )
                }

                val body = response.body
                    ?: return@withContext FetchResult.Failure(FetchError.Unknown("The server sent an empty response."))

                val contentType = ContentType.parse(response.header("Content-Type")) ?: ContentType.HTML
                if (!contentType.isText) {
                    return@withContext FetchResult.Failure(FetchError.UnsupportedContent(contentType.mimeType))
                }

                // Bounded read: an unbounded one turns a hostile or merely huge
                // response into an OOM on a phone.
                val bytes = body.source().let { source ->
                    source.request(maxBytes + 1)
                    source.buffer.snapshot().toByteArray()
                }.let { if (it.size > maxBytes) it.copyOf(maxBytes.toInt()) else it }

                val finalUrl = Url.parse(response.request.url.toString())
                    ?: return@withContext FetchResult.Failure(FetchError.Unknown("The server redirected somewhere Cobalt cannot follow."))

                val charset = CharsetDetection.select(contentType.charset, bytes)

                FetchResult.Success(
                    Page(
                        finalUrl = finalUrl,
                        text = decoder.decode(bytes, charset),
                        contentType = contentType,
                        statusCode = response.code,
                    )
                )
            }
        } catch (e: UnknownHostException) {
            FetchResult.Failure(FetchError.HostNotFound(url.host))
        } catch (e: SSLException) {
            FetchResult.Failure(FetchError.SecureConnectionFailed(e.message ?: "The certificate could not be verified."))
        } catch (e: SocketTimeoutException) {
            FetchResult.Failure(FetchError.Timeout(timeoutSeconds))
        } catch (e: IOException) {
            FetchResult.Failure(FetchError.Unreachable(e.message ?: "The connection failed."))
        } catch (e: Exception) {
            FetchResult.Failure(FetchError.Unknown(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 20
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
    }
}
