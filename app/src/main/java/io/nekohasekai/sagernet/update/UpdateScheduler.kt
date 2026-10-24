package app.qcoldthrone.mobile.update

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteWorkManager
import app.qcoldthrone.mobile.ktx.Logs
import app.qcoldthrone.mobile.ktx.app
import app.qcoldthrone.mobile.ktx.runOnDefaultDispatcher

/** Migration from the old daily notification check to foreground discovery and the home banner. */
object UpdateScheduler {
    private const val WORK_NAME = "UpdateCheck"

    fun cancelLegacyCheck() {
        runOnDefaultDispatcher {
            try {
                RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME).get()
            } catch (e: Exception) {
                Logs.w("UpdateScheduler: cancellation failed", e)
            }
        }
    }

    /** Kept so a worker persisted by an older app can finish harmlessly before cancellation is applied. */
    class CheckTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result = Result.success()
    }
}
