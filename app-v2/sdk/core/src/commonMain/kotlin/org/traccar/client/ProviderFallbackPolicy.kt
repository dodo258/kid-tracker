package org.traccar.client

/**
 * Decides when a tracking session gives up on the primary location provider (AMap) and switches to
 * the platform one. Pure logic so it can be unit tested; the Android source only feeds it results.
 *
 * - An error in [fatalErrors] (bad key, broken SDK) switches at once: retrying cannot help.
 * - Any other error switches after [maxConsecutiveErrors] in a row; a fix in between resets the count.
 * - An error in [offlineErrors] while the phone is offline is ignored: it says nothing about the
 *   primary provider, and the platform one has no network either.
 * - An error in [environmentErrors] is always ignored: it describes where the phone is (no Wi-Fi and
 *   no cell info, airplane mode, no SIM with Wi-Fi off), not a broken provider. P20 has no SIM card, so
 *   with Wi-Fi off 1 s continuous AMap fixes used to switch to the platform provider for the whole session.
 *
 * Once switched it stays switched until [onSessionEnd], so the next session tries the primary again.
 */
internal class ProviderFallbackPolicy(
    private val fatalErrors: Set<Int>,
    private val maxConsecutiveErrors: Int,
    private val offlineErrors: Set<Int> = emptySet(),
    private val environmentErrors: Set<Int> = emptySet(),
) {

    data class Reason(val errorCode: Int, val fatal: Boolean, val consecutiveErrors: Int)

    var fallenBack = false
        private set

    private var consecutiveErrors = 0

    /** Feeds one primary result ([SUCCESS] for a fix); returns the reason the moment it switches. */
    fun onResult(errorCode: Int, online: Boolean = true): Reason? {
        if (fallenBack) return null
        if (!online && errorCode in offlineErrors) return null
        if (errorCode in environmentErrors) return null
        if (errorCode == SUCCESS) {
            consecutiveErrors = 0
            return null
        }
        consecutiveErrors++
        val fatal = errorCode in fatalErrors
        if (!fatal && consecutiveErrors < maxConsecutiveErrors) return null
        fallenBack = true
        return Reason(errorCode, fatal, consecutiveErrors)
    }

    fun onSessionEnd() {
        fallenBack = false
        consecutiveErrors = 0
    }

    companion object {
        const val SUCCESS = 0
    }
}
