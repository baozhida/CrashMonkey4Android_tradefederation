/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.monkey;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.util.brillopad.item.AnrItem;
import com.android.tradefed.util.brillopad.item.BugreportItem;
import com.android.tradefed.util.brillopad.item.IItem;
import com.android.tradefed.util.brillopad.item.JavaCrashItem;
import com.android.tradefed.util.brillopad.item.LogcatItem;
import com.android.tradefed.util.brillopad.item.MonkeyLogItem;
import com.android.tradefed.util.brillopad.item.NativeCrashItem;
import com.android.tradefed.util.brillopad.parser.BugreportParser;
import com.android.tradefed.util.brillopad.parser.MonkeyLogParser;
import com.google.common.base.Throwables;

import junit.framework.Assert;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ResultForwarded} that intercepts monkey and bug report logs, extracts relevant metrics
 * from them using brillopad, and forwards the results to the specified
 * {@link ITestInvocationListener}.
 */
public class MonkeyBrillopadForwarder extends ResultForwarder {

    private enum MonkeyStatus {
        FINISHED, CRASHED, MISSING_COUNT, FALSE_COUNT, UPTIME_FAILURE;
    }

    private BugreportItem mBugreport = null;
    private MonkeyLogItem mMonkeyLog = null;

    public MonkeyBrillopadForwarder(ITestInvocationListener listener) {
        super(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        try {
            // just parse the logs for now. Forwarding of results will happen on test completion
            if (dataName.startsWith(MonkeyBase.BUGREPORT_NAME)) {
                CLog.i("Parsing %s", dataName);
                mBugreport = new BugreportParser().parse(dataStream);
            }
            if (dataName.startsWith(MonkeyBase.MONKEY_LOG_NAME)) {
                CLog.i("Parsing %s", dataName);
                mMonkeyLog = new MonkeyLogParser().parse(dataStream);
            }
        } catch (IOException e) {
            CLog.e("Could not parse file %s", dataName);
        }
        super.testLog(dataName, dataType, dataStream);
    }

    /**
     * {@inheritDoc}
     */
    @Override
   public void testEnded(TestIdentifier monkeyTest, Map<String, String> metrics) {
        Map<String, String> monkeyMetrics = new HashMap<String, String>();
        try {
            Assert.assertNotNull("Failed to parse or retrieve bug report", mBugreport);
            Assert.assertNotNull("Cannot report run to brillopad, monkey log does not exist",
                mMonkeyLog);
            Assert.assertNotNull("monkey log is missing start time info",
                    mMonkeyLog.getStartUptimeDuration());
            Assert.assertNotNull("monkey log is missing stop time info",
                    mMonkeyLog.getStopUptimeDuration());
            LogcatItem systemLog = mBugreport.getSystemLog();
            Assert.assertNotNull("system log is missing from bugreport",
                    systemLog);

            MonkeyStatus status = reportMonkeyStats(mMonkeyLog, monkeyMetrics);
            StringBuilder crashTrace = new StringBuilder();
            reportAnrs(mMonkeyLog, monkeyMetrics, crashTrace);
            reportJavaCrashes(mMonkeyLog, monkeyMetrics, crashTrace);
            reportNativeCrashes(systemLog, monkeyMetrics, crashTrace);

            if (!status.equals(MonkeyStatus.FINISHED)) {
                String failure = String.format("Monkey run failed due to %s.\n%s", status,
                        crashTrace.toString());
                super.testFailed(TestFailure.FAILURE, monkeyTest, failure);
            }
        } catch (AssertionError e) {
            super.testFailed(TestFailure.FAILURE, monkeyTest, Throwables.getStackTraceAsString(e));
        } catch (RuntimeException e) {
            super.testFailed(TestFailure.ERROR, monkeyTest, Throwables.getStackTraceAsString(e));
        } finally {
            super.testEnded(monkeyTest, monkeyMetrics);
        }
    }

    /**
     * Report stats about the monkey run from the monkey log.
     */
    private MonkeyStatus reportMonkeyStats(MonkeyLogItem monkeyLog,
            Map<String, String> monkeyMetrics) {
        MonkeyStatus status = getStatus(monkeyLog);
        monkeyMetrics.put("throttle", Integer.toString(monkeyLog.getThrottle()));
        monkeyMetrics.put("status", status.toString());
        monkeyMetrics.put("target_count", Integer.toString(monkeyLog.getTargetCount()));
        monkeyMetrics.put("injected_count", Integer.toString(monkeyLog.getFinalCount()));
        monkeyMetrics.put("run_duration", Long.toString(monkeyLog.getTotalDuration()));
        monkeyMetrics.put("uptime", Long.toString((monkeyLog.getStopUptimeDuration() -
                monkeyLog.getStartUptimeDuration())));
        return status;
    }

    /**
     * Report stats about Java crashes from the monkey log.
     */
    private void reportJavaCrashes(MonkeyLogItem monkeyLog, Map<String, String> metrics,
            StringBuilder crashTrace) {

        if (monkeyLog.getCrash() != null && monkeyLog.getCrash() instanceof JavaCrashItem) {
            JavaCrashItem jc = (JavaCrashItem) monkeyLog.getCrash();
            metrics.put("java_crash", "1");
            crashTrace.append("Detected java crash:\n");
            crashTrace.append(jc.getStack());
            crashTrace.append("\n");
        }
    }

    /**
     * Report stats about the native crashes from the bugreport.
     */
    private void reportNativeCrashes(LogcatItem systemLog, Map<String, String> metrics,
            StringBuilder crashTrace)  {
        if (systemLog.getEvents().size() > 0) {
            int nativeCrashes = 0;
            for (IItem item : systemLog.getEvents()) {
                if (item instanceof NativeCrashItem) {
                    nativeCrashes++;
                    crashTrace.append("Detected native crash:\n");
                    crashTrace.append(((NativeCrashItem)item).getStack());
                    crashTrace.append("\n");
                }
            }
            metrics.put("native_crash", Integer.toString(nativeCrashes));
        }
    }

    /**
     * Report stats about the ANRs from the monkey log.
     */
    private void reportAnrs(MonkeyLogItem monkeyLog, Map<String, String> metrics,
            StringBuilder crashTrace) {
        if (monkeyLog.getCrash() != null && monkeyLog.getCrash() instanceof AnrItem) {
            AnrItem anr = (AnrItem) monkeyLog.getCrash();
            metrics.put("anr_crash", "1");
            crashTrace.append("Detected ANR:\n");
            crashTrace.append(anr.getStack());
            crashTrace.append("\n");
        }
    }

    /**
     * Return the {@link MonkeyStatus} based on how the monkey run ran.
     */
    private MonkeyStatus getStatus(MonkeyLogItem monkeyLog) {
        // Uptime
        try {
            long startUptime = monkeyLog.getStartUptimeDuration();
            long stopUptime = monkeyLog.getStopUptimeDuration();
            long totalDuration = monkeyLog.getTotalDuration();
            if (stopUptime - startUptime < totalDuration - MonkeyBase.UPTIME_BUFFER) {
                return MonkeyStatus.UPTIME_FAILURE;
            }
        } catch (NullPointerException e) {
            return MonkeyStatus.UPTIME_FAILURE;
        }

        // False count
        if (monkeyLog.getIsFinished() &&
                monkeyLog.getIntermediateCount() + 100 < monkeyLog.getTargetCount()) {
            return MonkeyStatus.FALSE_COUNT;
        }

        // Finished
        if (monkeyLog.getIsFinished()) {
            return MonkeyStatus.FINISHED;
        }

        // Crashed
        if (monkeyLog.getFinalCount() != null) {
            return MonkeyStatus.CRASHED;
        }

        // Missing count
        return MonkeyStatus.MISSING_COUNT;
    }
}