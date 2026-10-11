package com.privacyguard.testapp;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Three independent ways to open a TLS connection. Each produces a different
 * ClientHello, which is the point: PrivacyGuard's baseline should learn one,
 * then flag another from the same app.
 */
final class TlsClients {

    interface Call { String run() throws Exception; }

    private TlsClients() {}

    /** OkHttp on the platform's Conscrypt, with OkHttp's own cipher list and h2 ALPN. */
    static String okHttp(String url) throws Exception {
        OkHttpClient client = new OkHttpClient();
        try (Response r = client.newCall(new Request.Builder().url(url).build()).execute()) {
            return "HTTP " + r.code() + " via " + r.protocol();
        }
    }

    /** Platform HttpsURLConnection (Android's bundled HTTP stack, http/1.1 only). */
    static String platform(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            return "HTTP " + c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /**
     * BouncyCastle's pure-Java TLS stack: different cipher order, extensions and
     * signature algorithms from Conscrypt. A raw socket keeps any HTTP library's
     * platform hooks from touching the handshake.
     */
    static String bouncyCastle(String host) throws Exception {
        Provider bc = new BouncyCastleProvider();
        Provider jsse = new BouncyCastleJsseProvider(bc);
        KeyStore androidCas = KeyStore.getInstance("AndroidCAStore");
        androidCas.load(null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", jsse);
        tmf.init(androidCas);
        SSLContext ctx = SSLContext.getInstance("TLS", jsse);
        ctx.init(null, tmf.getTrustManagers(), new SecureRandom());

        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(host, 443)) {
            s.setSoTimeout(15000);
            s.startHandshake();
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            return in.readLine() + " via " + s.getSession().getProtocol();
        }
    }
}
