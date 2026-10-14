package com.privacyguard.testapp;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Scripted checks for the VPN packet engine, run from adb:
 *   adb shell am start -n com.privacyguard.testapp/.MainActivity --es probe <name> [--es host H --ei port P --ei mb N]
 * Every probe returns lines starting with PASS / FAIL / INFO (logged under tag PGProbe).
 */
final class NetProbes {

    private NetProbes() {}

    private static final int TIMEOUT_MS = 5000;

    // ── DNS bypass ───────────────────────────────────────────────────────────

    /** DNS over TCP to 8.8.8.8:53 must be dropped; normal lookups must still work. */
    static String dnsTcp() {
        StringBuilder sb = new StringBuilder();
        sb.append(expectBlockedTcp("DNS-over-TCP 8.8.8.8:53", "8.8.8.8", 53, true));
        sb.append(expectResolves("system DNS still works", "www.example.com"));
        return sb.toString();
    }

    /** DoT and DoH to well-known resolvers by IPv6 address, plus plain DNS over IPv6. */
    static String dnsV6() {
        StringBuilder sb = new StringBuilder();
        sb.append(expectBlockedTcp("DoH Cloudflare [2606:4700:4700::1111]:443", "2606:4700:4700::1111", 443, false));
        sb.append(expectBlockedTcp("DoH Google [2001:4860:4860::8888]:443", "2001:4860:4860::8888", 443, false));
        sb.append(expectBlockedTcp("DoT Cloudflare [2606:4700:4700::1111]:853", "2606:4700:4700::1111", 853, false));
        sb.append(expectBlockedTcp("DoT Cloudflare 1.1.1.1:853", "1.1.1.1", 853, false));
        sb.append(expectNoUdpDnsAnswer("UDP DNS to [2001:4860:4860::8888]:53", "2001:4860:4860::8888"));
        sb.append(dohBySniOverV6("dns.nextdns.io"));
        return sb.toString();
    }

    /** A blocked name must not resolve, for A or AAAA. */
    static String blockedV6() {
        StringBuilder sb = new StringBuilder();
        String host = "ads.snapchat.com";   // CuratedAdDomains: always blocked
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            boolean real = false;
            StringBuilder ips = new StringBuilder();
            for (InetAddress a : all) {
                ips.append(a.getHostAddress()).append(' ');
                if (!a.isAnyLocalAddress() && !a.isLoopbackAddress()) real = true;
            }
            sb.append(real ? "FAIL " : "PASS ").append(host).append(" -> ").append(ips).append('\n');
        } catch (Exception e) {
            sb.append("PASS ").append(host).append(" not resolved (").append(e.getClass().getSimpleName()).append(")\n");
        }
        return sb.toString();
    }

    /** IPv6 must actually carry traffic, including responses larger than one MTU. */
    static String ipv6Works() {
        StringBuilder sb = new StringBuilder();
        try {
            InetAddress[] all = InetAddress.getAllByName("ipv6.google.com");
            sb.append("INFO ipv6.google.com -> ").append(all[0].getHostAddress()).append('\n');
        } catch (Exception e) {
            return "FAIL ipv6.google.com did not resolve: " + e + "\n";
        }
        for (String url : new String[]{"https://ipv6.google.com/", "https://ipv6.google.com/?hl=en&q=mtu"}) {
            try {
                long t0 = System.currentTimeMillis();
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                long n = drain(c.getInputStream());
                c.disconnect();
                boolean ok = code == 200 && n > 1440;   // > one IPv6 MSS: segmentation worked
                sb.append(ok ? "PASS " : "FAIL ").append(url).append(" HTTP ").append(code)
                        .append(' ').append(n).append(" B in ").append(System.currentTimeMillis() - t0).append(" ms\n");
            } catch (Exception e) {
                sb.append("FAIL ").append(url).append(' ').append(e).append('\n');
            }
        }
        return sb.toString();
    }

    // ── DNS flood ────────────────────────────────────────────────────────────

    /** [n] raw queries for random names to the tunnel resolver, then checks DNS + HTTPS recover. */
    static String dnsFlood(int n) throws Exception {
        StringBuilder sb = new StringBuilder();
        InetAddress resolver = InetAddress.getByName("10.0.0.1");
        AtomicInteger answered = new AtomicInteger();
        Random rnd = new Random();
        long t0 = System.currentTimeMillis();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(200);
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[1500];
                long end = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < end && !s.isClosed()) {
                    try { s.receive(new DatagramPacket(buf, buf.length)); answered.incrementAndGet(); }
                    catch (Exception ignored) { }
                }
            });
            reader.start();
            for (int i = 0; i < n; i++) {
                byte[] q = dnsQuery(rnd.nextInt(0xFFFF), "flood" + Long.toHexString(rnd.nextLong()) + ".example.com");
                s.send(new DatagramPacket(q, q.length, resolver, 53));
            }
            long sendMs = System.currentTimeMillis() - t0;
            sb.append("INFO sent ").append(n).append(" queries in ").append(sendMs).append(" ms\n");
            reader.join();
        }
        sb.append("INFO answers received during flood: ").append(answered.get()).append('\n');
        Thread.sleep(3000);
        sb.append(expectResolves("DNS works after flood", "www.wikipedia.org"));
        sb.append(httpsGet("HTTPS works after flood", "https://www.example.com/"));
        return sb.toString();
    }

    // ── Upload integrity ─────────────────────────────────────────────────────

    /**
     * Streams [mb] MB of seeded pseudo-random bytes to the PC's hash server and
     * compares SHA-256 values. Protocol: 8-byte length, data, server replies hex hash.
     */
    static String upload(String host, int port, int mb) throws Exception {
        long total = (long) mb * 1024 * 1024;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Random rnd = new Random(42);
        byte[] chunk = new byte[64 * 1024];
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 10000);
            s.setSoTimeout(120000);
            OutputStream out = s.getOutputStream();
            for (int i = 7; i >= 0; i--) out.write((int) (total >>> (i * 8)) & 0xFF);
            long sent = 0;
            while (sent < total) {
                int len = (int) Math.min(chunk.length, total - sent);
                rnd.nextBytes(chunk);
                md.update(chunk, 0, len);
                out.write(chunk, 0, len);
                sent += len;
            }
            out.flush();
            s.shutdownOutput();
            String remote = new String(readAll(s.getInputStream()), StandardCharsets.US_ASCII).trim();
            String local = hex(md.digest());
            long ms = System.currentTimeMillis() - t0;
            boolean ok = local.equalsIgnoreCase(remote);
            return (ok ? "PASS" : "FAIL") + " upload " + mb + " MB in " + ms + " ms ("
                    + (total * 1000 / Math.max(ms, 1) / 1024) + " KB/s)\n"
                    + "INFO phone  sha256=" + local + "\n"
                    + "INFO server sha256=" + remote + "\n";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String expectBlockedTcp(String label, String ip, int port, boolean sendDnsQuery) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName(ip), port), TIMEOUT_MS);
            if (!sendDnsQuery) return "FAIL " + label + " connected\n";
            s.setSoTimeout(TIMEOUT_MS);
            byte[] q = dnsQuery(0x1234, "www.example.com");
            OutputStream out = s.getOutputStream();
            out.write(new byte[]{(byte) (q.length >> 8), (byte) q.length});
            out.write(q);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = in.readUnsignedShort();
            return "FAIL " + label + " answered (" + len + " B)\n";
        } catch (Exception e) {
            return "PASS " + label + " blocked (" + e.getClass().getSimpleName() + ")\n";
        }
    }

    private static String expectNoUdpDnsAnswer(String label, String ip) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            byte[] q = dnsQuery(0x4321, "www.example.com");
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 53));
            byte[] buf = new byte[1500];
            s.receive(new DatagramPacket(buf, buf.length));
            return "FAIL " + label + " answered\n";
        } catch (Exception e) {
            return "PASS " + label + " no answer (" + e.getClass().getSimpleName() + ")\n";
        }
    }

    /** TLS to a DoH host's own IPv6 address (not on the IP list): must be cut on the SNI. */
    private static String dohBySniOverV6(String host) {
        Inet6Address v6 = null;
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet6Address) { v6 = (Inet6Address) a; break; }
            }
        } catch (Exception e) {
            return "INFO " + host + " did not resolve (" + e.getClass().getSimpleName() + "), SNI check skipped\n";
        }
        if (v6 == null) return "INFO " + host + " has no AAAA record, SNI check skipped\n";
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(v6, 443), TIMEOUT_MS);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            ssl.setSoTimeout(TIMEOUT_MS);
            ssl.startHandshake();
            return "FAIL DoH by SNI " + host + " [" + v6.getHostAddress() + "] TLS completed\n";
        } catch (Exception e) {
            return "PASS DoH by SNI " + host + " over IPv6 cut (" + e.getClass().getSimpleName() + ")\n";
        }
    }

    private static String expectResolves(String label, String host) {
        try {
            return "PASS " + label + ": " + host + " -> " + InetAddress.getByName(host).getHostAddress() + "\n";
        } catch (Exception e) {
            return "FAIL " + label + ": " + e + "\n";
        }
    }

    private static String httpsGet(String label, String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            long n = drain(c.getInputStream());
            c.disconnect();
            return (code == 200 ? "PASS " : "FAIL ") + label + ": HTTP " + code + ", " + n + " B\n";
        } catch (Exception e) {
            return "FAIL " + label + ": " + e + "\n";
        }
    }

    static byte[] dnsQuery(int id, String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id >> 8); b.write(id);
        b.write(0x01); b.write(0x00);           // RD
        b.write(0); b.write(1);                 // QDCOUNT
        for (int i = 0; i < 6; i++) b.write(0);  // AN/NS/AR
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            b.write(l.length);
            b.write(l, 0, l.length);
        }
        b.write(0);
        b.write(0); b.write(1);                 // A
        b.write(0); b.write(1);                 // IN
        return b.toByteArray();
    }

    private static long drain(InputStream in) throws Exception {
        byte[] buf = new byte[16 * 1024];
        long n = 0;
        int r;
        try (InputStream i = in) { while ((r = i.read(buf)) > 0) n += r; }
        return n;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        return b.toByteArray();
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String run(String probe, String host, int port, int mb) throws Exception {
        switch (probe) {
            case "dns_tcp": return dnsTcp();
            case "dns_v6": return dnsV6();
            case "blocked_v6": return blockedV6();
            case "ipv6": return ipv6Works();
            case "dns_flood": return dnsFlood(mb > 0 ? mb : 5000);
            case "upload": return upload(host, port, mb > 0 ? mb : 200);
            case "https": return httpsGet("HTTPS", "https://www.example.com/");
            // TLS fingerprint baseline tests; tls_bc with --ei mb N offers N fewer suites.
            case "tls_okhttp": return TlsClients.okHttp("https://www.example.com/") + "\n";
            case "tls_platform": return TlsClients.platform("https://www.example.com/") + "\n";
            case "tls_bc": return TlsClients.bouncyCastle("www.example.com", mb) + "\n";
            case "ad_hosts": return PrivacyProbes.resolveHosts();
            case "mic": return PrivacyProbes.recordMic() + "\n";
            default: return "FAIL unknown probe " + probe + "\n";
        }
    }
}
