package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.DHTTracker;
import edu.umd.cs.ztorrent.protocol.PeerConnection;
import edu.umd.cs.ztorrent.protocol.Tracker;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Torrent {
    public int torrentPort = 5555;
    public TorrentInfo info;
    public String name;
    public String status = "";
    private Set<PeerConnection> peerList = new HashSet<>();
    public PieceManager pm;
    public int numFiles;
    public int pieceLength;
    public FileResource[] files;
    public long totalBytes;
    public Bencoder pieceHash;
    private long downloaded;
    private long uploaded;
    private boolean haveFiles;
    private long recentDownRate;
    private long recentUpRate;
    private List<Tracker> trackers;
    private long left;
    private File f;
    public byte[] hashInfo;
    public byte[] peerID;


    public Torrent(String name, int pieceLength, FileResource[] files, long totalBytes, byte[] byteStringHashInfo, String urlEncodedHash,
                   Bencoder pieceHash, TorrentInfo md, List<Tracker> trackers, String file) throws IOException {

        this.hashInfo = byteStringHashInfo;
        this.peerID = genRandomSessionKey(20).getBytes(StandardCharsets.UTF_8);
        this.info = md;

        this.numFiles = files.length;
        f = new File(file);
        this.pieceLength = pieceLength;
        this.files = files;
        this.totalBytes = totalBytes;
        this.name = name;
        downloaded = 0;
        uploaded = 0;
        left = totalBytes;
        this.pieceHash = pieceHash;
        try {
            pm = new PieceManager(files, 1024 * 1024 * 20, pieceLength, totalBytes, pieceHash);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("IO-Problems on piecemanager init.");
        }
        for (FileResource resource : files) {
            resource.setup(pieceLength, pm.pieceOrganizer);
        }
        this.status = "Checking files";
        pm.checkFiles();
        this.status = "Getting Peers";
        haveFiles = true;
        this.trackers = new ArrayList<Tracker>();
        for (Tracker t : trackers) {
            this.trackers.add(t);
        }
        this.trackers.add(new DHTTracker(hashInfo, peerID));
    }

    public String getStatus() {
        return status;
    }

    public static String genRandomSessionKey(int length) {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        int n = alphabet.length();
        String result = "";
        Random r = new Random();
        for (int i = 0; i < length; i++)
            result = result + alphabet.charAt(r.nextInt(n));

        return result;
    }

    public void reload() throws NoSuchAlgorithmException, IOException {
        Torrent t = ParserTorrentFile.parseTorrentFile(f.getAbsolutePath());
        this.trackers = t.trackers;
        int i = 0;
        for (FileResource f : t.files) {
            this.files[i++] = f;
        }
    }

    @Override
    public String toString() {
        String s = "";
        for (Tracker t : trackers) {
            s += t.toString() + ",";
        }


        return name + "\nfiles: " + numFiles + "\nSize: " + totalBytes + "\nTackers: " + s + "\nPeers: " + peerList.size() + "\n";
    }

    public void addPeer(InetAddress inet, int port, byte[] id) {
        PeerConnection mc = new PeerConnection(inet, port);
        if (id != null) {
            mc.setPreConnectionPeerID(id);
        }
        if (!peerList.contains(mc) && peerList.size() < 50) {
            peerList.add(mc);
        }
    }

    public void addConnection(PeerConnection mc) {
        peerList.add(mc);
    }

    public byte[] getInfoHash() {
        return hashInfo.clone();
    }

    public byte[] getPeerID() {
        return peerID;
    }

    public long getDownloaded() {
        return downloaded;
    }

    public long getUploaded() {
        return uploaded;
    }

    public void addDownloaded(long bytes) {
        downloaded += bytes;
    }

    public void addUploaded(long bytes) {
        downloaded += bytes;
    }

    public long getLeftToDownload() {
        left = (totalBytes - pm.getCompletedBytes());
        left = left > 0 ? left : 0;
        return left;
    }


    public Set<PeerConnection> getPeers() {
        return peerList;
    }

    public void setRecentDown(long dl) {
        this.recentDownRate = dl;
    }

    public void setRecentUp(long up) {
        this.recentUpRate = up;
    }

    public long getRecentDownRate() {
        return recentDownRate;
    }

    public long getRecentUpRate() {
        return recentUpRate;
    }

    public Tracker[] getTrackers() {
        return trackers.toArray(new Tracker[0]);
    }

    public File getFile() {
        return f;
    }

    public boolean equals(Object o) {
        if (o instanceof Torrent) {
            Torrent t = (Torrent) o;
            return Arrays.equals(t.hashInfo, hashInfo);
        }
        return false;
    }

    public void shutdown() {
        Iterator<PeerConnection> mcs = getPeers().iterator();
        while (mcs.hasNext()) {
            try {
                PeerConnection mc = mcs.next();
                mc.tearDown();
            } catch (Exception e) {
            }
            mcs.remove();
        }

        for (Tracker tr : getTrackers()) {
            tr.close(this);
        }
    }

}
