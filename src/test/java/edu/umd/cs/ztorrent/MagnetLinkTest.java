package edu.umd.cs.ztorrent;

import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;

public class MagnetLinkTest {
    @Test
    public void testMagenetLink() throws IOException, InterruptedException, NoSuchAlgorithmException {
        String currentDirectory = System.getProperty("user.dir");
        System.out.println("The current working directory is " + currentDirectory);

        Torrent t = TorrentParser.parseTorrentFile("src/test/resources/raspios.torrent");
        System.out.println("[INFO] Using torrent file: " + t.getFile().getAbsolutePath());

        BigInteger big = new BigInteger(1, t.hashInfo);
        MagnetLinkClient ml = MagnetLinkClient.createFromURI(String.format("%0" + (t.hashInfo.length << 1) + "X", big));

        int i = 500;
        while (i > 0 || !ml.isComplete()) {
            ml.doWork();
            Thread.sleep(10);
            --i;
        }


    }

}
