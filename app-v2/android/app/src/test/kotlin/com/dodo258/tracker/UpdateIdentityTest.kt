package com.dodo258.tracker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 检查更新交给系统前的身份核对：对的包放行，找不到文件、身份不对拦下（R6 模拟器上发现对的包也被当成 unreadable 拦了）。 */
class UpdateIdentityTest {
    private val apk = File("/cache/update/306.apk")

    @Test
    fun rightPackagePasses() {
        assertNull(UpdateIdentity.problem(apk) { null })
    }

    @Test
    fun wrongIdentityIsReported() {
        assertEquals("signature", UpdateIdentity.problem(apk) { "signature" })
    }

    @Test
    fun missingFileIsUnreadable() {
        assertEquals("unreadable", UpdateIdentity.problem(null) { null })
    }
}
