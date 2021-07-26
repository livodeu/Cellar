/*
 * QueueJobService.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.queue;

import android.app.job.JobParameters;
import android.app.job.JobService;

import androidx.annotation.MainThread;
import androidx.annotation.RequiresApi;

import net.cellar.BuildConfig;
import net.cellar.supp.Log;

import java.lang.reflect.Method;

/**
 * The purpose of this Service is to pop the next element from the download queue.<br>
 * It is used when an immediate download is not possible due to a missing or unusable network connection.<br>
 * Gets called by the {@link JobService} and invokes {@link QueueManager#nextPlease()}.
 */
@RequiresApi(21)
public class QueueJobService extends JobService {

    /** {@inheritDoc} */
    @Override
    @MainThread
    public boolean onStartJob(JobParameters params) {
        if (BuildConfig.DEBUG) {
            Log.i(QueueJobService.class.getSimpleName(), "onStartJob(" + params + ")");
        }
        // get the next element off the queue and finish
        QueueManager qm = QueueManager.getInstance();
        qm.nextPlease();
        jobFinished(params, qm.hasQueuedStuff());
        return false;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions"})
    @Override
    public boolean onStopJob(JobParameters params) {
        if (BuildConfig.DEBUG) {
            Log.i(QueueJobService.class.getSimpleName(), "onStopJob(" + params + ")");
            try {
                Method getStopReason = JobParameters.class.getMethod("getStopReason");
                int reason = (int) getStopReason.invoke(params);
                String reasons;
                switch (reason) {
                    case 0:
                        reasons = "cancelled";
                        break;
                    case 1:
                        reasons = "constraints not satisfied";
                        break;
                    case 2:
                        reasons = "preempt";
                        break;
                    case 3:
                        reasons = "timeout";
                        break;
                    case 4:
                        reasons = "device idle";
                        break;
                    default:
                        reasons = "?";
                }
                Log.w(QueueJobService.class.getSimpleName(), "onStopJob(): reason for stopping the job is: " + reasons);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
