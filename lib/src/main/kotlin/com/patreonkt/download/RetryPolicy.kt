package com.patreonkt.download

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Executes [block] with exponential backoff retry logic.
 *
 * Delays between attempts: 1 s, 2 s, 4 s, 8 s, … (capped at 60 s).
 *
 * @param maxAttempts Maximum number of total attempts (including the first).
 * @param block Suspending lambda to execute; should throw on failure.
 * @return The result of the first successful invocation of [block].
 * @throws Throwable The last exception if all attempts fail.
 */
suspend fun <T> withRetry(maxAttempts: Int, block: suspend () -> T): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            if (attempt < maxAttempts - 1) {
                val delayMs = minOf(1000L * 2.0.pow(attempt).toLong(), 60_000L)
                delay(delayMs)
            }
        }
    }
    throw lastError!!
}
