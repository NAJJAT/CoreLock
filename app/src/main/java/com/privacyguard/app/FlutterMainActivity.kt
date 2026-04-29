package com.privacyguard.app

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import com.privacyguard.app.flutter.FlutterBridge

/**
 * Entry point for the Flutter UI. Launched from Settings when the Flutter
 * module has been set up (run `cd flutter && flutter pub get` first).
 */
class FlutterMainActivity : FlutterFragmentActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        FlutterBridge(this).register(flutterEngine)
    }
}
