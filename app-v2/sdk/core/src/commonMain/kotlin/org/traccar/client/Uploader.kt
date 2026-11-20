package org.traccar.client

interface Uploader {
    suspend fun upload(position: Position): Boolean
}

/** 每次上报的结果（R6 自检清单「锁屏时能不能发出去」）；Android 装了才有 */
fun interface UploadObserver {
    fun onUpload(success: Boolean)
}
