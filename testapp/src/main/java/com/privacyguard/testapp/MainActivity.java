package com.privacyguard.testapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal test app. On button press it sends one HTTPS POST with a JSON body
 * containing a fake "location" field, so we can confirm the whole PrivacyGuard
 * chain (VPN -> MitmEngine -> PayloadParser -> UI) decrypts and displays it.
 *
 * The request goes to httpbin.org/anything/... which echoes the request back,
 * so both the OUTBOUND request body and the INBOUND response body are captured.
 * httpbin is on none of PrivacyGuard's bypass/pinned lists, and this app trusts
 * user CAs (see res/xml/network_security_config.xml), so interception succeeds.
 */
public class MainActivity extends Activity {

    // A path that looks like the target endpoint; httpbin's /anything accepts any
    // sub-path and returns 200, echoing method, headers and body.
    private static final String ENDPOINT = "https://httpbin.org/anything/api/test";
    private static final String JSON_BODY = "{\"message\":\"hello\",\"location\":\"test\"}";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PrivacyGuard Test App");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Sends one HTTPS POST to:\n" + ENDPOINT
                + "\n\nBody:\n" + JSON_BODY
                + "\n\n1) Start PrivacyGuard protection first.\n"
                + "2) Then tap the button.\n"
                + "3) Open PrivacyGuard's payload list — you should see the decrypted body.");
        info.setTextColor(Color.DKGRAY);
        info.setPadding(0, dp(12), 0, dp(12));
        root.addView(info);

        Button send = new Button(this);
        send.setText("Send HTTPS POST");
        send.setOnClickListener(v -> sendRequest());
        root.addView(send, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // TLS fingerprint baseline test: each button uses a different TLS client,
        // so PrivacyGuard sees a different JA4 fingerprint from the same app UID.
        addButton(root, "TLS test: OkHttp", () -> TlsClients.okHttp(TLS_TEST_URL));
        addButton(root, "TLS test: BouncyCastle TLS", () -> TlsClients.bouncyCastle(TLS_TEST_HOST));
        addButton(root, "TLS test: HttpsURLConnection", () -> TlsClients.platform(TLS_TEST_URL));

        // Ad-privacy tests: a known mic use for the timeline, and what DNS returns
        // to a non-Meta app for ad SDK hosts versus login/push hosts.
        addButton(root, "Record mic 5 s", PrivacyProbes::recordMic);
        addButton(root, "Resolve ad/login hosts", PrivacyProbes::resolveHosts);

        status = new TextView(this);
        status.setText("Idle.");
        status.setTextColor(Color.BLACK);
        status.setPadding(0, dp(16), 0, 0);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll);

        setContentView(root);
        runProbeFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        runProbeFromIntent(intent);
    }

    /** adb shell am start -n com.privacyguard.testapp/.MainActivity --es probe dns_tcp (see NetProbes). */
    private void runProbeFromIntent(android.content.Intent intent) {
        if (intent == null) return;
        String probe = intent.getStringExtra("probe");
        if (probe == null) return;
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 0);
        int mb = intent.getIntExtra("mb", 0);
        intent.removeExtra("probe");
        status.setText("Probe " + probe + "...");
        new Thread(() -> {
            String out;
            try {
                out = NetProbes.run(probe, host, port, mb);
            } catch (Exception e) {
                out = "FAIL " + probe + ": " + e + "\n";
            }
            for (String line : out.split("\n")) android.util.Log.w("PGProbe", "[" + probe + "] " + line);
            android.util.Log.w("PGProbe", "[" + probe + "] DONE");
            final String text = out;
            main.post(() -> status.setText(text));
        }).start();
    }

    private void sendRequest() {
        status.setText("Sending...");
        new Thread(() -> {
            String result;
            try {
                URL url = new URL(ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Test-Header", "privacyguard");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                byte[] payload = JSON_BODY.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    }
                }
                conn.disconnect();
                result = "HTTP " + code + "\n\nResponse:\n" + sb;
            } catch (Exception e) {
                result = "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String out = result;
            main.post(() -> status.setText(out));
        }).start();
    }

    private static final String TLS_TEST_HOST = "www.example.com";
    private static final String TLS_TEST_URL = "https://" + TLS_TEST_HOST + "/";

    private void addButton(LinearLayout root, String label, TlsClients.Call call) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> {
            status.setText(label + "...");
            new Thread(() -> {
                String out;
                try {
                    out = label + "\n" + call.run();
                } catch (Exception e) {
                    out = label + " FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String text = out;
                main.post(() -> status.setText(text));
            }).start();
        });
        root.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
