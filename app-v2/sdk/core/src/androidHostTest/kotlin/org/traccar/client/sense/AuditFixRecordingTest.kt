package org.traccar.client.sense

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 审计中 11：外部存储里旧版本留下的录制搬进 App 私有目录，外面不留；合计上限照旧 */
class AuditFixRecordingTest {

    private val root = Files.createTempDirectory("rec").toFile()
    private val external = File(root, "external/recorder").apply { mkdirs() }
    private val private = File(root, "files/recorder")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun oldRecordingsMoveOutOfExternalStorage() {
        File(external, "rec-20261118-134404.csv").writeText("time,lat\n1,39.9\n")
        File(external, "rec-20261119-080000.csv").writeText("time,lat\n2,39.9\n")
        assertEquals(2, moveRecordings(external, private, totalMax = 1_000_000))
        assertFalse(external.exists(), "外部存储里不留")
        assertEquals(listOf("rec-20261118-134404.csv", "rec-20261119-080000.csv"), private.list()!!.sorted())
        assertEquals("time,lat\n1,39.9\n", File(private, "rec-20261118-134404.csv").readText(), "内容原样，主人还没拉走的数据不丢")
        assertEquals(0, moveRecordings(external, private, totalMax = 1_000_000), "搬过了再起进程什么都不做")
    }

    @Test
    fun movedRecordingsStillKeepTheTotalLimit() {
        private.mkdirs()
        File(private, "rec-20261119-090000.csv").writeText("x".repeat(60))
        File(external, "rec-20261117-000000.csv").writeText("y".repeat(60))
        moveRecordings(external, private, totalMax = 100)
        assertEquals(listOf("rec-20261119-090000.csv"), private.list()!!.toList(), "超过合计上限删最旧的")
        assertTrue(external.listFiles().isNullOrEmpty())
    }
}
