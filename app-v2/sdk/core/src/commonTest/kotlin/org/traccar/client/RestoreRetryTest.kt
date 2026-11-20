package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** 审计高 3：开机恢复读库失败后按退避再试，成功清零 */
class RestoreRetryTest {

    @Test
    fun failuresBackOffUpToFifteenMinutesAndSuccessResets() {
        val retry = RestoreRetry()
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L), List(6) { retry.failed() })
        retry.succeeded()
        assertEquals(60_000L, retry.failed())
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val retry = RestoreRetry()
        repeat(100) { retry.failed() }
        assertEquals(900_000L, retry.failed())
    }
}
