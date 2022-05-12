package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.PeerConnection;
import edu.umd.cs.ztorrent.protocol.Tracker;

import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TorrentClient extends AbstractTableModel {
    private static long serialVersionUID = -143709093895815620L;
    public boolean on = true;

    Set<Torrent> allTorrents = Collections.synchronizedSet(new HashSet<>());
    Set<Torrent> inactiveTorrents = Collections.synchronizedSet(new HashSet<>());
    Map<Torrent, TorrentWorker> activeTorrents = new ConcurrentHashMap<>();
    Queue<Torrent> newTorrents = new ConcurrentLinkedQueue<>();
    TorrentSocket tSocket;

    public TorrentClient() {
        for(int i = 6881; i <= 6889; i++){
            try {
                tSocket = new TorrentSocket(i);
                System.out.println("[BIND SUCCESS] Port "+ i + " Bound");
                break;
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("[BIND ERROR] Port "+ i + " Already in use....");
            }
        }
    }

    public void mainLoop() throws IOException, InterruptedException {
        while (on) {
            for (TorrentWorker tt : activeTorrents.values()) {
                tt.work();
            }

            while (!newTorrents.isEmpty()) {
                Torrent t = newTorrents.poll();
                boolean has = false;
                for (Torrent a : allTorrents) {
                    if (Arrays.equals(t.hashInfo, a.hashInfo)) {
                        has = true;
                        break;
                    }
                }

                if (!has) {
                    allTorrents.add(t);
                    activeTorrents.put(t, new TorrentWorker(new PeerWorker(), t));
                }
            }

            Thread.sleep(10);
            this.fireTableRowsUpdated(0, allTorrents.size() - 1);
            for (PeerConnection mc : tSocket.getNewConnections()) {
                for (Torrent a : allTorrents) {
                    if (Arrays.equals(mc.getInfoHash(), a.hashInfo)) {
                        a.addConnection(mc);
                    }
                }
            }


        }
        System.out.println("[SHUTDOWN] Finalizing and then Closing zTorrent");
        for (Torrent t : allTorrents) {
            t.shutdown();
        }
        tSocket.close();
    }

    public void addTorrent(Torrent t) {
        newTorrents.add(t);
    }

    public void setTorrentInactive(Torrent t) throws IOException {
        activeTorrents.remove(t);
        inactiveTorrents.add(t);

        t.shutdown();

        for (FileResource f : t.files) {
            f.closeFile();
        }
    }

    public void deleteTorrentData(Torrent t) {
        try {
            setTorrentInactive(t);
            inactiveTorrents.remove(t);
            allTorrents.remove(t);
            for (FileResource f : t.files) {
                f.deleteFile();
            }
        } catch (IOException io) {
            io.printStackTrace();
        }
    }

    public void deleteTorrent(Torrent t) throws IOException {
        setTorrentInactive(t);
        t.getFile().delete();
    }

    public void reActivate(Torrent t) throws NoSuchAlgorithmException, IOException {
        if (!activeTorrents.containsKey(t)) {
            t.reload();
            activeTorrents.put(t, new TorrentWorker(new PeerWorker(), t));
            inactiveTorrents.remove(t);
        } else {
            System.out.println("[STATUS] Already active");
        }
    }

    @Override
    public String getColumnName(int column) {
        String name = "??";
        switch (column) {
            case 0:
                name = "Name";
                break;
            case 1:
                name = "Size";
                break;
            case 2:
                name = "Downloaded";
                break;
            case 3:
                name = "Status";
                break;
            case 4:
                name = "Down Speed";
                break;
            case 5:
                name = "Up Speed";
                break;
            case 6:
                name = "Peers";
                break;
            case 7:
                name = "Uploaded";
                break;
        }
        return name;
    }

    @Override
    public int getColumnCount() {
        return 8;
    }

    @Override
    public int getRowCount() {
        return allTorrents.size();
    }

    private static DecimalFormat dg = new DecimalFormat();
    {
        dg.setMaximumFractionDigits(3);
    }

    private static String byteCountToDisplaySize(long size) {
        if (size / 1024 < 999) {
            return "" + dg.format(size / 1024.0) + " KB";
        } else if (size / (1024 * 1024) < 999) {
            return "" + dg.format(size / (1024.0 * 1024.0)) + " MB";
        } else {
            return "" + dg.format(size / (1024.0 * 1024.0 * 1024.0)) + " GB";
        }
    }

    private static String ratioPercentageToDisplay(long size, long progress) {
        float f = 1.0f - (((float) progress) / size);
        return dg.format(f * 100.0) + "%";
    }

    @Override
    public Object getValueAt(int arg0, int arg1) {
        Torrent t = allTorrents.toArray(new Torrent[0])[arg0];
        switch (arg1) {
            case 0:
                return t.name;
            case 1:
                return byteCountToDisplaySize(t.totalBytes);
            case 2:
                return byteCountToDisplaySize(t.getDownloaded());
            case 3:
                String progress = ratioPercentageToDisplay(t.totalBytes, t.getLeftToDownload());
                if (t.getStatus().equals("Checking files")) {
                    progress = "Checking files " + progress;
                }
                return progress;
            case 4:
                return byteCountToDisplaySize(t.getRecentDownRate() * 1024) + "/s";
            case 5:
                return t.getRecentUpRate();
            case 6:
                return t.getPeers().size();
            case 7:
                return t.getUploaded();
        }
        return null;
    }

    public Torrent getTorrent(int i) {
        if (i < allTorrents.size()) {
            System.out.println("[GETTING] Get Piece #" + i + " with " + allTorrents.size() + " number of peers");
            return allTorrents.toArray(new Torrent[0])[i];
        }

        return null;
    }

    public static class TorrentWorker {
        private PeerWorker worker;
        private Torrent us;

        public TorrentWorker(PeerWorker worker, Torrent torr) {
            this.worker = worker;
            this.us = torr;
            for (Tracker tracker : us.getTrackers()) {
                tracker.initialize(torr);
            }
        }

        public void work() throws IOException {
            worker.process(us);
            for (Tracker t : us.getTrackers()) {
                t.doWork();
            }

        }

        public void close() {
            for (Tracker tr : us.getTrackers()) {
                tr.close(us);
            }
        }
    }
}
