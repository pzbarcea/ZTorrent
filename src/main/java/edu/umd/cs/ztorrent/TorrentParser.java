package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.Tracker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;


/**
 * @author pzbarcea
 */
public class TorrentParser {
    //TODO: ERROR CHECKING!!! OMG. Seriously.
    private static final String hex = "0123456789ABCDEF";

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    static char to_hex(byte code) {
        return hex.charAt(code & 15);
    }

    public static String urlEncode(byte[] array) {
        int size = array.length - 1;
        byte[] out = new byte[size * 3 + 2];
        int cout = 0;
        size = 0;
        while (size < array.length) {
            if (Character.isDigit(array[size]) || Character.isAlphabetic(array[size]) || array[size] == '-' || array[size] == '_' || array[size] == '.' || array[size] == '~') {
                out[cout++] = array[size];
            } else {
                out[cout++] = '%';
                out[cout++] = (byte) to_hex((byte) (array[size] >> 4));
                out[cout++] = (byte) to_hex((byte) (array[size] & 15));
            }
            size++;
        }
        out[cout++] = '\0';
        String s = null;
        s = new String(out, StandardCharsets.UTF_8);
        return s.trim();
    }


    //Hash to string
    public static byte[] SHAsum(byte[] convertme) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return md.digest(convertme);
    }

    public static Torrent parseTorrentFile(String filePath) throws IOException, NoSuchAlgorithmException {
        //get file
        File file = new File(filePath);
        int len = (int) file.length();
        byte[] data = new byte[len];
        Files.newInputStream(file.toPath()).read(data);
        Bencoder encoding = new Bencoder(data);

        Bencoder info = encoding.dictionary.get("info");
        String name = info.dictionary.get("name").getString();
        byte[] byteStringHashInfo = SHAsum(info.toByteArray());
        String urlEncodedHash = urlEncode(byteStringHashInfo);
        long totalBytes = 0;
        int numFiles;
        FileResource[] dfiles;
        if (info.dictionary.containsKey("files")) {//multiple files
            Bencoder files = info.dictionary.get("files");
            numFiles = files.list.size();
            dfiles = new FileResource[numFiles];
            int i = 0;
            for (Bencoder b : files.list) {
                long integ = b.dictionary.get("length").integer;

                String dlFilePath = "";
                for (Bencoder e : b.dictionary.get("path").list) {
                    dlFilePath += e.getString();
                }
                dfiles[i] = new FileResource(name, dlFilePath, new Long(integ), totalBytes);
                i++;
                totalBytes += integ;
            }
        } else {
            numFiles = 1;
            dfiles = new FileResource[numFiles];
            totalBytes = info.dictionary.get("length").integer;
            dfiles[0] = new FileResource(name, name, totalBytes, 0);
        }

        List<Tracker> trackers = new ArrayList<Tracker>();
        String primaryTracker = encoding.dictionary.get("announce").getString();
        Tracker t = Tracker.makeTracker(primaryTracker);
        if (t != null) {
            trackers.add(t);
        }

        if (encoding.dictionary.containsKey("announce-list")) {
            for (Bencoder b : encoding.dictionary.get("announce-list").list) {
                t = Tracker.makeTracker(b.list.get(0).getString());
                if (t != null) {
                    trackers.add(t);
                }
            }
        }

        int pieceLength = (int) ((long) (info.dictionary.get("piece length").integer));//TODO: Rofl! yeah why cant i just do new Integer()?
        Bencoder pieceHashes = info.dictionary.get("pieces");


        Torrent torrent = new Torrent(name, pieceLength, dfiles, totalBytes, byteStringHashInfo, urlEncodedHash, pieceHashes, new TorrentInfo(info), trackers, filePath);
        return torrent;
    }
}
