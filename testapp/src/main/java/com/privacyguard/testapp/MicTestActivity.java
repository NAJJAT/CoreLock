package com.privacyguard.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Starts {@link MicRecordService} and closes. Exists only so the service is
 * started from the foreground: Android 11+ gives a microphone foreground service
 * mic access only if it was started while the app was visible.
 *
 * <pre>
 * adb shell pm grant com.privacyguard.testapp android.permission.RECORD_AUDIO
 * adb shell am start -n com.privacyguard.testapp/.MicTestActivity --ei delay 8 --ei seconds 20
 * adb shell input keyevent KEYCODE_SLEEP   # screen off before the delay ends
 * </pre>
 */
public class MicTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent service = new Intent(this, MicRecordService.class)
                .putExtra(MicRecordService.EXTRA_DELAY_S, getIntent().getIntExtra(MicRecordService.EXTRA_DELAY_S, 8))
                .putExtra(MicRecordService.EXTRA_SECONDS, getIntent().getIntExtra(MicRecordService.EXTRA_SECONDS, 20));
        startForegroundService(service);
        finish();
    }
}
