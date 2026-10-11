package com.privacyguard.testapp;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.net.InetAddress;

/** Probes for PrivacyGuard's "Why am I seeing this ad?" features. */
final class PrivacyProbes {

    private PrivacyProbes() {}

    /** Opens the mic for 5 s (audio is discarded). Needs RECORD_AUDIO, grant with adb. */
    static String recordMic() throws Exception {
        int rate = 16000;
        int buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        try {
            rec.startRecording();
            byte[] data = new byte[buf];
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) rec.read(data, 0, data.length);
            return "Recorded 5 s from the mic (discarded)";
        } finally {
            rec.stop();
            rec.release();
        }
    }

    static String resolveHosts() {
        String[] hosts = {
                "an.facebook.com", "app.adjust.com", "inapps.appsflyersdk.com", "app-measurement.com",
                "graph.facebook.com", "m.facebook.com", "firebaseinstallations.googleapis.com", "www.example.com",
        };
        StringBuilder sb = new StringBuilder();
        for (String h : hosts) {
            String r;
            try {
                r = InetAddress.getByName(h).getHostAddress();
            } catch (Exception e) {
                r = "BLOCKED (" + e.getClass().getSimpleName() + ")";
            }
            sb.append(h).append(" -> ").append(r).append('\n');
        }
        return sb.toString();
    }
}
