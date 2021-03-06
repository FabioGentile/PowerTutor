/*
Copyright (C) 2011 The University of Michigan

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Please send inquiries to powertutor@umich.edu
*/

package fabiogentile.powertutor.service;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import fabiogentile.powertutor.components.CPU;
import fabiogentile.powertutor.components.OLED;
import fabiogentile.powertutor.components.PowerComponent;
import fabiogentile.powertutor.phone.PhoneConstants;
import fabiogentile.powertutor.phone.PhoneSelector;
import fabiogentile.powertutor.phone.PowerFunction;
import fabiogentile.powertutor.util.BatteryStats;
import fabiogentile.powertutor.util.Counter;
import fabiogentile.powertutor.util.HistoryBuffer;
import fabiogentile.powertutor.util.NotificationService;
import fabiogentile.powertutor.util.SystemInfo;
import fabiogentile.powertutor.widget.PowerWidget;

/**
 * This class is responsible for starting the individual power component
 * loggers (CPU, GPS, etc...) and collecting the information they generate.
 * This information is used both to write a log file that will be send back
 * to spidermoneky (or looked at by the user) and to implement the
 * ICounterService IPC interface.
 */
public class PowerEstimator implements Runnable {
    public static final int ALL_COMPONENTS = -1;
    public static final int ITERATION_INTERVAL = 1000; // 1 second
    private static final String TAG = "PowerEstimator";
    /* A dictionary used to assist in compression of the log files.  Strings that
     * appear more frequently should be put towards the end of the dictionary. It
     * is not critical that every string that be written to the log appear here.
     */
    private static final String DEFLATE_DICTIONARY =
            "onoffidleoff-hookringinglowairplane-modebatteryedgeGPRS3Gunknown" +
                    "in-serviceemergency-onlyout-of-servicepower-offdisconnectedconnecting" +
                    "associateconnectedsuspendedphone-callservicenetworkbegin.0123456789" +
                    "GPSAudioWifi3GLCDCPU-power ";
    private static final int NOTIFICATION_UPDATE_INTERVAL = 5; //When update notification
    private UMLoggerService context;
    private SharedPreferences prefs;
    private boolean plugged;
    private Vector<PowerComponent> powerComponents;
    private Vector<PowerFunction> powerFunctions;
    private Vector<HistoryBuffer> histories;
    private Map<Integer, String> uidAppIds;
    // Miscellaneous data.
    private HistoryBuffer oledScoreHistory;
    private Object fileWriteLock = new Object();
    private LogUploader logUploader;
    private OutputStreamWriter logStream;
    private DeflaterOutputStream deflateStream;
    private Object iterationLock = new Object();
    private long lastWrittenIteration;

    public PowerEstimator(UMLoggerService context) {
        this.context = context;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        powerComponents = new Vector<PowerComponent>();
        powerFunctions = new Vector<PowerFunction>();
        uidAppIds = new HashMap<Integer, String>();

        PhoneSelector.generateComponents(context, powerComponents, powerFunctions);

        histories = new Vector<HistoryBuffer>();
        for (int i = 0; i < powerComponents.size(); i++) {
            histories.add(new HistoryBuffer(300));
        }
        oledScoreHistory = new HistoryBuffer(0);

        logUploader = new LogUploader(context);
        openLog(true);
    }

    private void openLog(boolean init) {
        /* Open up the log file if possible. */
        try {
            String logFilename = context.getFileStreamPath("PowerTrace.log").getAbsolutePath();
            Log.d(TAG, "openLog: logfile: " + logFilename);

            if (init && prefs.getBoolean("sendPermission", true) &&
                    new File(logFilename).length() > 0) {
                /* There is data to send.  Make sure that gets going in the sending
                 * process before we write over any old logs.
                 */
                logUploader.upload(logFilename);
            }

            Deflater deflater = new Deflater();
            deflater.setDictionary(DEFLATE_DICTIONARY.getBytes());
            deflateStream = new DeflaterOutputStream(new FileOutputStream(logFilename));
            //logStream = new OutputStreamWriter(deflateStream);

            // TODO: 12/09/16 NORMAL OUTPUT STREAM USED ONLY FOR DEBUG
            logStream = new OutputStreamWriter(new FileOutputStream(logFilename));

        } catch (IOException e) {
            logStream = null;
            Log.e(TAG, "Failed to open log file.  No log will be kept.");
        }
    }

    /**
     * This is the loop that keeps updating the power profile
     */
    public void run() {
        SystemInfo sysInfo = SystemInfo.getInstance();
        PackageManager pm = context.getPackageManager();
        BatteryStats bst = BatteryStats.getInstance();
        PhoneConstants phoneConstants = PhoneSelector.getConstants(context);

        //Initialize each component
        int componentsNumber = powerComponents.size();
        long beginTime = SystemClock.elapsedRealtime();
        for (int i = 0; i < componentsNumber; i++) {
            powerComponents.get(i).init(beginTime, ITERATION_INTERVAL);
            // Start the thread for the component
            powerComponents.get(i).start(); // SPAWN THREAD
        }
        IterationData[] dataTemp = new IterationData[componentsNumber];

        long[] memInfo = new long[4];

        int oledId = -1;
        for (int i = 0; i < componentsNumber; i++) {
            if ("OLED".equals(powerComponents.get(i).getComponentName())) {
                oledId = i;
                break;
            }
        }

        // Last battery current measured (in uAh)
        double lastCurrent = -1;

        /* Indefinitely collect data on each of the power components. */
        boolean firstLogIteration = true;
        for (long iter = -1; !Thread.interrupted(); ) {
            long curTime = SystemClock.elapsedRealtime();

            /* Compute the next iteration that we can make the ending of.  We wait
             * for the end of the iteration so that the components had a chance to
             * collect data already.
             */
            iter = Math.max(iter + 1, (curTime - beginTime) / ITERATION_INTERVAL);

            /* Sleep until the next iteration completes. */
            try {
                Thread.currentThread().sleep(beginTime + (iter + 1) * ITERATION_INTERVAL - curTime);
            } catch (InterruptedException e) {
                break;
            }

            int totalPower = 0;
            //<editor-fold desc="Power Calculation">
            // Collect power for each component
            for (int i = 0; i < componentsNumber; i++) {
                PowerComponent comp = powerComponents.get(i);
                IterationData data = comp.getData(iter);

                dataTemp[i] = data;
                if (data == null) {
                    //No data present for this timestamp.  No power charged.
                    continue;
                }

                int compPower = 0;
                double totTime = 0.0, totTimeAll = 0.0;

                SparseArray<PowerData> uidPower = data.getUidPowerData();
                //Log.i(TAG, "run: [" + comp.getComponentName() + "] + uid# " + uidPower.size());
                //Iterage through each uid for the component i
                for (int j = 0; j < uidPower.size(); j++) {
                    int uid = uidPower.keyAt(j);
                    PowerData powerData = uidPower.valueAt(j);

                    if (powerData instanceof CPU.CpuData) {
                        if (uid != SystemInfo.AID_ALL) {
                            totTime += (((CPU.CpuData) powerData).sysPerc) + (((CPU.CpuData) powerData).usrPerc);
                            ((CPU.CpuData) powerData).setUidAll(false);
                        }
                        else {
                            totTimeAll += (((CPU.CpuData) powerData).sysPerc) + (((CPU.CpuData) powerData).usrPerc);
                            //Add base CPU power for general UID
                            ((CPU.CpuData) powerData).setUidAll(true);
                        }
                    }

                    int power = (int) powerFunctions.get(i).calculate(powerData);

                    powerData.setCachedPower(power);

                    //Add infromation to uid history
                    histories.get(i).add(uid, iter, power);
                    if (uid == SystemInfo.AID_ALL) {
                        totalPower += power;
                    }
                    else{
                        compPower += power;
                    }
//                    if (i == oledId) {
//                        OLED.OledData oledData = (OLED.OledData) powerData;
//                        if (oledData.pixPower >= 0) {
//                            oledScoreHistory.add(uid, iter, (int) (1000 * oledData.pixPower));
//                        }
//                    }
                }
                String formattedTime = String.format(Locale.getDefault(), "%1$.2f", totTime);
                String formattedTimeAll = String.format(Locale.getDefault(), "%1$.2f", totTimeAll);

                if(comp.getComponentName().compareTo("CPU") == 0)
                    Log.d(TAG, "run: [" + comp.getComponentName() + "] (COMP: " + compPower +
                            " - TOT: " + totalPower + ") time proc: " + formattedTime + " ALL: " + formattedTimeAll);
                else
                    Log.d(TAG, "run: [" + comp.getComponentName() + "] (" + compPower +
                            " - " + totalPower + ")");
            }
            //</editor-fold>

            //<editor-fold desc="Update UID set">
            synchronized (fileWriteLock) {
                synchronized (uidAppIds) {
                    for (int i = 0; i < componentsNumber; i++) {
                        IterationData data = dataTemp[i];
                        if (data == null) {
                            continue;
                        }

                        SparseArray<PowerData> uidPower = data.getUidPowerData();
                        for (int j = 0; j < uidPower.size(); j++) {
                            int uid = uidPower.keyAt(j);
                            if (uid < SystemInfo.AID_APP) { // System app
                                uidAppIds.put(uid, null);
                            } else { //User app
                                /* We only want to update app names when logging so the associcate
                                 * message gets written.
                                 */
                                String appId = uidAppIds.get(uid);
                                String newAppId = sysInfo.getAppId(uid, pm);
                                uidAppIds.put(uid, newAppId);

                                if (!firstLogIteration && logStream != null && (appId == null || !appId.equals(newAppId))) {
                                    try {
                                        logStream.write("associate+" + uid + "+" + newAppId + "\n");
                                    } catch (IOException e) {
                                        Log.w(TAG, "Failed to write to log file");
                                    }
                                }
                            }
                        }
                    }
                }
            }
            //</editor-fold>

            synchronized (iterationLock) {
                lastWrittenIteration = iter;
            }

            //<editor-fold desc="Notification update">
            // Update the icon display every NOTIFICATION_UPDATE_INTERVAL iterations
            if (iter % NOTIFICATION_UPDATE_INTERVAL == (NOTIFICATION_UPDATE_INTERVAL - 1)) {
                final double POLY_WEIGHT = 0.02;
                int count = 0; // Number of history data not null

                // Get info from all component for all UID
                int[] history = getComponentHistory(NOTIFICATION_UPDATE_INTERVAL, -1, SystemInfo.AID_ALL, -1); // TODO: 13/08/16 5 * 60 ??

                double weightedAvgPower = 0;
                for (int i = history.length - 1; i >= 0; i--) {
                    if (history[i] != 0) {
                        count++;
                        weightedAvgPower *= 1.0 - POLY_WEIGHT;
                        weightedAvgPower += POLY_WEIGHT * history[i] / 1000.0;
                    }
                }
                double avgPower = -1;
                if (count != 0)
                    avgPower = weightedAvgPower / (1.0 - Math.pow(1.0 - POLY_WEIGHT, count));

                avgPower *= 1000;
                int notificationLevel = (int) Math.min(8, 1 + 8 * avgPower / phoneConstants.maxPower());
                context.updateNotification(notificationLevel, avgPower);
//                context.updateNotification(0, totalPower);
            }
            //</editor-fold>

            writeToLog("begin+" + iter + "\n");

            //<editor-fold desc="Log Information">

            //<editor-fold desc="HEADER">
            if (bst.hasCurrent() && (iter % 60 == 0)) {
                double current = bst.getCurrent();
                if (current != lastCurrent) { // If battery current drawn has changed
                    writeToLog("batt_current+" +
                            String.format(Locale.getDefault(), "%1$.2f", current * 1000) + "\n");
                    lastCurrent = current;
                }
            }
            if (iter % (5 * 60) == 0) { // Every 300 iterations (5 minutes)
                if (bst.hasTemp())
                    writeToLog("batt_temp+" + bst.getTemp() + "\n");
                if (bst.hasCharge())
                    writeToLog("batt_charge+" + bst.getCharge() + "\n");
            }
            if (iter % (30 * 60) == 0) { // Every 1800 iterations (30 minutes)
                if (Settings.System.getInt(context.getContentResolver(),
                        "screen_brightness_mode", 0) != 0) {
                    writeToLog("setting_brightness+automatic\n");
                } else {
                    int brightness = Settings.System.getInt(context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, -1);
                    if (brightness != -1) {
                        writeToLog("setting_brightness+" + brightness + "\n");
                    }
                }
                int timeout = Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, -1);
                if (timeout != -1) {
                    writeToLog("setting_screen_timeout+" + timeout + "\n");
                }

                String httpProxy = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.HTTP_PROXY);
                if (httpProxy != null) {
                    writeToLog("setting_httpproxy " + httpProxy + "\n");
                }
            }
            //</editor-fold>

            /* Let's only grab memory information every 10 seconds to try to keep log
             * file size down and the notice_data table size down.
             */
            boolean hasMem = false;
//            if (iter % 10 == 0) {
//                hasMem = sysInfo.getMemInfo(memInfo);
//            }

            //<editor-fold desc="BODY">
            synchronized (fileWriteLock) {
                if (logStream != null) {
                    try {
                        if (firstLogIteration) {
                            Log.d(TAG, "run: FIRST LOG ITERATION");
                            firstLogIteration = false;
                            logStream.write("iteration_interval+" + ITERATION_INTERVAL + "\n");
                            logStream.write("time+" + System.currentTimeMillis() + "\n");
                            Calendar cal = new GregorianCalendar();
                            logStream.write("localtime_offset+" +
                                    (cal.get(Calendar.ZONE_OFFSET) +
                                            cal.get(Calendar.DST_OFFSET)) + "\n");
                            logStream.write("model+" + phoneConstants.modelName() + "\n");

                            if (NotificationService.available())
                                logStream.write("notifications-active\n");

                            if (bst.hasFullCapacity())
                                logStream.write("batt_full_capacity+" + bst.getFullCapacity() + "\n");

                            synchronized (uidAppIds) {
                                for (int uid : uidAppIds.keySet()) {
                                    if (uid < SystemInfo.AID_APP) {
                                        continue;
                                    }
                                    logStream.write("associate+" + uid + "+" + uidAppIds.get(uid) + "\n");
                                }
                            }
                        }

                        logStream.write("total power+" + (long) Math.round(totalPower) + '\n');

                        if (hasMem)
                            logStream.write("meminfo+" + memInfo[0] + "+" + memInfo[1] +
                                    "+" + memInfo[2] + "+" + memInfo[3] + "\n");

                        // Log information for every component
                        for (int i = 0; i < componentsNumber; i++) {
                            //Log.d(TAG, "run: Log for component " + i);
                            IterationData data = dataTemp[i];

                            if (data != null) {
                                String name = powerComponents.get(i).getComponentName();
                                SparseArray<PowerData> uidData = data.getUidPowerData();

                                //Iterate through UIDs
                                for (int j = 0; j < uidData.size(); j++) {
                                    int uid = uidData.keyAt(j);
                                    PowerData powerData = uidData.valueAt(j);

                                    if (uid == SystemInfo.AID_ALL) {
                                        // Write log data for each component
                                        powerData.writeLogDataInfo(logStream);
                                        logStream.write(name + "+ALL++" + (long) Math.round(powerData.getCachedPower()) + "\n");
                                    } else {
                                        logStream.write(name + "+" + uid + "+" + sysInfo.getUidName(uid, pm) +
                                                "+" + (long) Math.round(powerData.getCachedPower()) + "\n");
                                    }
                                }
                                data.recycle();
                            }
                        }
                        logStream.write("------ END OF ITERATION ------\n");
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to write to log file");
                    }
                }
            }
            //</editor-fold>

            //</editor-fold>

            if (iter % 30 == 0) { // Every 300 iterations (5 minutes)
                synchronized (fileWriteLock) {
                    if (logStream != null)
                        try {
                            logStream.flush();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to flush logfile: " + e);
                        }
                }
            }

        } //Thread loop 'run'


        // LOOP ENDED

        // Blank the widget's display and turn off power button
        PowerWidget.updateWidgetDone(context);

        // Have all of the power component threads exit
        logUploader.interrupt();

        // Interrupt power component calculator
        for (int i = 0; i < componentsNumber; i++) {
            powerComponents.get(i).interrupt();
        }


        try {
            logUploader.join();
        } catch (InterruptedException e) {
        }

        // Wait power component end
        for (int i = 0; i < componentsNumber; i++) {
            try {
                powerComponents.get(i).join();
            } catch (InterruptedException e) {
            }
        }

        //This is reached only when service is stopped

        // Close logstream and flush everything to file
        synchronized (fileWriteLock) {
            if (logStream != null) try {
                logStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to flush log file on exit");
            }
        }
    }

    public void plug(boolean plugged) {
        logUploader.plug(plugged);
    }

    /**
     * Write a message to log file
     *
     * @param m The message to be writed
     */
    public void writeToLog(String m) {
        synchronized (fileWriteLock) {
            if (logStream != null)
                try {
                    logStream.write(m);
                    //Log.d(TAG, "writeToLog: writed to log: " + m);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to write message to power log: " + e);
                }
        }
    }

    public String[] getComponents() {
        int components = powerComponents.size();
        String[] ret = new String[components];
        for (int i = 0; i < components; i++) {
            ret[i] = powerComponents.get(i).getComponentName();
        }
        return ret;
    }

    public int[] getComponentsMaxPower() {
        PhoneConstants constants = PhoneSelector.getConstants(context);
        int components = powerComponents.size();
        int[] ret = new int[components];
        for (int i = 0; i < components; i++) {
            ret[i] = (int) constants.getMaxPower(
                    powerComponents.get(i).getComponentName());
        }
        return ret;
    }

    public int getNoUidMask() {
        int components = powerComponents.size();
        int ret = 0;
        for (int i = 0; i < components; i++) {
            if (!powerComponents.get(i).hasUidInformation()) {
                ret |= 1 << i;
            }
        }
        return ret;
    }

    /**
     * Get historical information about a specific component
     *
     * @param count       How many data retrieve
     * @param componentId The component we want to get info about
     * @param uid         Specify for wich UID get info
     * @param iteration
     * @return
     */
    public int[] getComponentHistory(int count, int componentId, int uid, long iteration) {
        if (iteration == -1) synchronized (iterationLock) {
            iteration = lastWrittenIteration;
        }
        int components = powerComponents.size();
        if (componentId == ALL_COMPONENTS) {
            int[] result = new int[count];
            for (int i = 0; i < components; i++) {
                int[] comp = histories.get(i).get(uid, iteration, count);
                for (int j = 0; j < count; j++) {
                    result[j] += comp[j];
                }
            }
            return result;
        }
        if (componentId < 0 || components <= componentId) return null;
        return histories.get(componentId).get(uid, iteration, count);
    }

    public long[] getTotals(int uid, int windowType) {
        int components = powerComponents.size();
        long[] ret = new long[components];
        for (int i = 0; i < components; i++) {
            ret[i] = histories.get(i).getTotal(uid, windowType) *
                    ITERATION_INTERVAL / 1000;
        }
        return ret;
    }

    public long getRuntime(int uid, int windowType) {
        long runningTime = 0;
        int components = powerComponents.size();
        for (int i = 0; i < components; i++) {
            long entries = histories.get(i).getCount(uid, windowType);
            runningTime = entries > runningTime ? entries : runningTime;
        }
        return runningTime * ITERATION_INTERVAL / 1000;
    }

    public long[] getMeans(int uid, int windowType) {
        long[] ret = getTotals(uid, windowType);
        long runningTime = getRuntime(uid, windowType);
        runningTime = runningTime == 0 ? 1 : runningTime;
        for (int i = 0; i < ret.length; i++) {
            ret[i] /= runningTime;
        }
        return ret;
    }

    public UidInfo[] getUidInfo(int windowType, int ignoreMask) {
        long iteration;
        synchronized (iterationLock) {
            iteration = lastWrittenIteration;
        }
        int components = powerComponents.size();
        synchronized (uidAppIds) {
            int pos = 0;
            UidInfo[] result = new UidInfo[uidAppIds.size()];
            for (Integer uid : uidAppIds.keySet()) {
                UidInfo info = UidInfo.obtain();
                int currentPower = 0;
                for (int i = 0; i < components; i++) {
                    if ((ignoreMask & 1 << i) == 0) {
                        currentPower += histories.get(i).get(uid, iteration, 1)[0];
                    }
                }
                double scale = ITERATION_INTERVAL / 1000.0;
                info.init(uid, currentPower,
                        sumArray(getTotals(uid, windowType), ignoreMask) *
                                ITERATION_INTERVAL / 1000,
                        getRuntime(uid, windowType) * ITERATION_INTERVAL / 1000);
                result[pos++] = info;
            }
            return result;
        }
    }

    private long sumArray(long[] A, int ignoreMask) {
        long ret = 0;
        for (int i = 0; i < A.length; i++) {
            if ((ignoreMask & 1 << i) == 0) {
                ret += A[i];
            }
        }
        return ret;
    }

    public long getUidExtra(String name, int uid) {
        if ("OLEDSCORE".equals(name)) {
            long entries = oledScoreHistory.getCount(uid, Counter.WINDOW_TOTAL);
            if (entries <= 0) return -2;
            double result = oledScoreHistory.getTotal(uid, Counter.WINDOW_TOTAL) /
                    1000.0;
            result /= entries;
            PhoneConstants phoneConstants = PhoneSelector.getConstants(context);
            result *= 255 / (phoneConstants.getMaxPower("OLED") -
                    phoneConstants.oledBasePower());
            return Math.round(result * 100);
        }
        return -1;
    }
}

