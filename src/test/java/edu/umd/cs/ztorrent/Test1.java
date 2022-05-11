package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.DHTTracker;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

public class Test1 {

    @Test
    public void testDHTTracker() throws InterruptedException, NoSuchAlgorithmException, IOException {

        byte[] b = hexStringToByteArray("ffffffffffffffffffffffffffffffffffffffff");
        System.out.println(b.length);
        BigInteger big = new BigInteger(1, b);
        System.out.println(String.format("%0" + (b.length << 1) + "X", big));

        DHTTracker dht = new DHTTracker(b, Torrent.genRandomSessionKey(20).getBytes(StandardCharsets.UTF_8));


        for (int i = 0; i < 200; i++) {
            dht.doWork();
            Thread.sleep(10);
        }

        System.out.println("[TEST] Complete");
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
