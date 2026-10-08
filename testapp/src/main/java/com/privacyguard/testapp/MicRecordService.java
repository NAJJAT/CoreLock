package com.privacyguard.testapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Records from the microphone after a delay, so the screen can be turned off
 * first: the "app listening while your screen is off" case that CoreLock's Camera
 * & Mic Watch must flag as CRITICAL. The audio is read and thrown away.
 */
public class MicRecordService extends Service {

    static final String EXTRA_DELAY_S = "delay";
    static final String EXTRA_SECONDS = "seconds";
    private static final String TAG = "MicRecordService";
    private static final String CHANNEL = "mic_test";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mic test", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Mic test")
                .setContentText("Will record from the microphone")
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }

        int delayS = intent != null ? intent.getIntExtra(EXTRA_DELAY_S, 8) : 8;
        int seconds = intent != null ? intent.getIntExtra(EXTRA_SECONDS, 20) : 20;
        new Thread(() -> {
            SystemClock.sleep(delayS * 1000L);
            record(seconds);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "mic-test").start();
        return START_NOT_STICKY;
    }

    private void record(int seconds) {
        int rate = 16_000;
        int size = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        } catch (SecurityException e) {
            Log.e(TAG, "No RECORD_AUDIO permission", e);
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialize");
            rec.release();
            return;
        }
        byte[] buf = new byte[size];
        long end = SystemClock.elapsedRealtime() + seconds * 1000L;
        long total = 0;
        rec.startRecording();
        Log.i(TAG, "Recording started for " + seconds + " s");
        while (SystemClock.elapsedRealtime() < end) {
            int n = rec.read(buf, 0, buf.length);
            if (n > 0) total += n;
        }
        rec.stop();
        rec.release();
        Log.i(TAG, "Recording stopped, read " + total + " bytes");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
