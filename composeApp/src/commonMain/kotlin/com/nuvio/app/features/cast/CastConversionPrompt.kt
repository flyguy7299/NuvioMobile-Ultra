package com.nuvio.app.features.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A yes/no question [CastDelivery] needs answered mid-cast, such as "convert this M3U8 stream?".
 *
 * Delivery runs in a coroutine with no UI of its own, so it parks on [ask] while the player shows
 * [pending] as a dialog and reports the choice through [answer].
 */
object CastConversionPrompt {

    data class Request(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val declineLabel: String,
        internal val reply: CompletableDeferred<Boolean>,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    suspend fun ask(title: String, message: String, confirmLabel: String, declineLabel: String): Boolean {
        val request = Request(title, message, confirmLabel, declineLabel, CompletableDeferred())
        _pending.value = request
        return try {
            request.reply.await()
        } finally {
            // Also clears the dialog when the cast is abandoned (player closed) mid-question.
            _pending.compareAndSet(request, null)
        }
    }

    fun answer(confirmed: Boolean) {
        val request = _pending.value ?: return
        _pending.value = null
        request.reply.complete(confirmed)
    }
}
