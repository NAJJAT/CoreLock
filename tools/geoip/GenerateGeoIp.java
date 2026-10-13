import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Builds app/src/main/assets/geoip_v4.bin from the five RIR "delegated-extended"
 * files (ARIN, RIPE NCC, APNIC, LACNIC, AFRINIC), which are freely published.
 *
 *   java tools/geoip/GenerateGeoIp.java OUT_FILE delegated-*-extended-latest...
 *
 * Format (big-endian): int count, then per range: int first, int last (unsigned
 * IPv4 as signed ints), 2 ASCII bytes country code. Sorted, non-overlapping, and
 * neighbours with the same country merged.
 *
 * The country is where the block was registered, not where a server stands: a
 * cloud provider's addresses show the provider's home country.
 */
public class GenerateGeoIp {
    public static void main(String[] args) throws IOException {
        TreeMap<Long, long[]> ranges = new TreeMap<>(); // first -> {last, cc}
        for (int i = 1; i < args.length; i++) {
            for (String line : Files.readAllLines(Paths.get(args[i]))) {
                String[] f = line.split("\\|");
                if (f.length < 7 || !f[2].equals("ipv4") || f[1].length() != 2) continue;
                if (!f[6].equals("allocated") && !f[6].equals("assigned")) continue;
                long first = ipv4(f[3]);
                long last = first + Long.parseLong(f[4]) - 1;
                long cc = (f[1].charAt(0) << 8) | f[1].charAt(1);
                ranges.put(first, new long[]{last, cc});
            }
        }
        List<long[]> merged = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : ranges.entrySet()) {
            long first = e.getKey(), last = e.getValue()[0], cc = e.getValue()[1];
            long[] prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (prev != null && first <= prev[1]) continue; // overlap: keep the earlier block
            if (prev != null && prev[2] == cc && prev[1] + 1 == first) prev[1] = last;
            else merged.add(new long[]{first, last, cc});
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(merged.size());
            for (long[] r : merged) {
                out.writeInt((int) r[0]);
                out.writeInt((int) r[1]);
                out.writeShort((int) r[2]);
            }
        }
        System.out.println("ranges=" + merged.size() + " bytes=" + new File(args[0]).length());
    }

    private static long ipv4(String s) {
        String[] p = s.split("\\.");
        return (Long.parseLong(p[0]) << 24) | (Long.parseLong(p[1]) << 16) | (Long.parseLong(p[2]) << 8) | Long.parseLong(p[3]);
    }
}
