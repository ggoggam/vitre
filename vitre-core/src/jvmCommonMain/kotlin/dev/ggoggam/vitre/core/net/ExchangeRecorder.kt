package dev.ggoggam.vitre.core.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The [NetworkTap] both JVM interceptors publish to, and the body-capture policy that goes with it.
 *
 * Separated from the interceptors because it is where the *interesting* data ends up and neither
 * platform has anything to add to it. A shop that renders its results from `GET /api/search` is
 * handing over typed JSON, and reading it here beats parsing `$1,299.00` back out of three nested
 * spans — so every exchange is recorded whether anything rewrote it or not.
 *
 * Dropping oldest rather than suspending, because recording runs inside somebody's resource load:
 * a tap nobody is collecting must never be able to stall a page.
 */
internal class ExchangeRecorder(
    private val policy: InterceptionPolicy,
) : NetworkTap {
    private val published =
        MutableSharedFlow<NetworkExchange>(
            extraBufferCapacity = EXCHANGE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val ids = AtomicLong(0)

    override val exchanges: SharedFlow<NetworkExchange> get() = published.asSharedFlow()

    /** Records a request that never became a response — declined, unsupported, or failed. */
    fun passthrough(
        request: InterceptedRequest,
        reason: String,
        outcome: ExchangeOutcome = ExchangeOutcome.PassedThrough,
    ) {
        published.tryEmit(
            NetworkExchange(
                id = ids.incrementAndGet(),
                method = request.method,
                url = request.url,
                outcome = outcome,
                status = 0,
                requestHeaders = request.headers,
                responseHeaders = emptyMap(),
                contentType = null,
                body = null,
                bodyTruncated = false,
                durationMs = 0,
                error = reason,
            ),
        )
    }

    /** Records one request/response pair, capturing the body when the policy says to and it is text. */
    fun record(
        request: InterceptedRequest,
        response: InterceptedResponse,
        outcome: ExchangeOutcome,
        durationMs: Long,
        error: String? = null,
    ) {
        val capture = policy.captureBodies && response.contentType.isTextualContentType()
        val truncated = capture && response.body.size > policy.maxCapturedBodyBytes
        val text =
            if (!capture) {
                null
            } else {
                response.body
                    .copyOf(minOf(response.body.size, policy.maxCapturedBodyBytes))
                    .toString(Charsets.UTF_8)
            }
        published.tryEmit(
            NetworkExchange(
                id = ids.incrementAndGet(),
                method = request.method,
                url = request.url,
                outcome = outcome,
                status = response.status,
                requestHeaders = request.headers,
                responseHeaders = response.headers,
                contentType = response.contentType,
                body = text,
                bodyTruncated = truncated,
                durationMs = durationMs,
                error = error,
            ),
        )
    }

    private companion object {
        const val EXCHANGE_BUFFER = 256
    }
}
