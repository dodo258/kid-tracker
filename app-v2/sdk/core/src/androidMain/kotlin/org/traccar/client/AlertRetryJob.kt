package org.traccar.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.content.getSystemService
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.alert.AlertRetry

/**
 * 有没发出去的告警时挂一个要联网才跑的系统任务（重启后还在）：进程被杀了、追踪关着没有前台服务撑着时，联网后系统把进程叫起来补发。
 * 进程活着时发件箱自己联网就发，这个任务跑起来发现没东西就结束；两边同时发由发件箱的全进程锁排队，不会发两次。
 */
internal class AndroidAlertRetry(context: Context) : AlertRetry {

    private val appContext = context.applicationContext

    override fun schedule() {
        val scheduler = appContext.getSystemService<JobScheduler>() ?: return
        // 已经挂着（或正在跑）就不重挂：同一个编号再 schedule 会把正在跑的那次停掉
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, AlertRetryJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = try {
            scheduler.schedule(job)
        } catch (e: RuntimeException) {
            Log.log("Alert retry job not scheduled: $e")
            return
        }
        if (result != JobScheduler.RESULT_SUCCESS) Log.log("Alert retry job not scheduled: $result")
    }

    internal companion object {
        const val JOB_ID = 5301
        const val BACKOFF_MILLIS = 30_000L
    }
}

class AlertRetryJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val done = try {
                // 没有配置就没有可发的
                withTimeoutOrNull(RUN_LIMIT) { sharedTracker()?.flushAlerts() ?: true } ?: false
            } catch (e: Exception) {
                Log.log("Alert retry job failed: ${e.rootCause()}")
                false
            }
            Log.log("Alert retry job: ${if (done) "all sent" else "still pending, retrying later"}")
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    private companion object {
        val RUN_LIMIT = 60.seconds
    }
}
