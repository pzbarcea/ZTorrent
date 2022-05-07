package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.ManagedConnection;

import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/***
 * Manages connection between torrent states.
 * TODO: On initialization looks for user data.
 *
 * @author wiselion
 */
public class TorrentClient extends AbstractTableModel {
    private static final long serialVersionUID = -143709093895815620L;
    public boolean on = true;

    final Set<Torrent> allTorrents = Collections.synchronizedSet(new HashSet<Torrent>());
    final Map<Torrent, TorrentTransmitter> activeTorrents = new ConcurrentHashMap<Torrent, TorrentTransmitter>();//Seeding or leeching states
    final Set<Torrent> inactiveTorrents = Collections.synchronizedSet(new HashSet<Torrent>());//Completed or inactive

    final Queue<Torrent> newTorrents = new ConcurrentLinkedQueue<Torrent>();
    TorrentServerSocket tss;

    public TorrentClient() {
        try {
            tss = new TorrentServerSocket(6881);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void mainLoop() throws IOException, InterruptedException {
        while (on) {
            for (TorrentTransmitter tt : activeTorrents.values()) {
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
                    activeTorrents.put(t, new TorrentTransmitter(new MainPeerLogic(), t));
                }
            }

            Thread.sleep(10);
            this.fireTableRowsUpdated(0, allTorrents.size() - 1);
            //this.fireTableRowsUpdated(0,allTorrents.size());
            for (ManagedConnection mc : tss.getNewConnections()) {
                for (Torrent a : allTorrents) {
                    if (Arrays.equals(mc.getInfoHash(), a.hashInfo)) {
                        //add connection to torrent.
                        a.addConnection(mc);
                    }
                }
            }


        }
        //Shut everything down.
        System.out.println("Shutting down.");
        for (Torrent t : allTorrents) {
            t.shutdown();
        }
        tss.close();
    }

    public void addTorrent(Torrent t) {
        newTorrents.add(t);
    }

    //--------------------------------
    public void setTorrentInactive(Torrent t) throws IOException {
        activeTorrents.remove(t);
        inactiveTorrents.add(t);

        //Drop connections
        t.shutdown();

        // close files
        for (DownloadFile f : t.files) {
            f.close();
        }
    }

    public void deleteTorrentData(Torrent t) {
        try {
            setTorrentInactive(t);
            inactiveTorrents.remove(t);
            allTorrents.remove(t);
            for (DownloadFile f : t.files) {
                f.delete();
            }
        } catch (IOException io) {
            io.printStackTrace();
        }
    }

    public void deleteTorrent(Torrent t) throws IOException {
        setTorrentInactive(t);
        t.getFile().delete(); // we presume the .delete() is always successful
    }

    public void deleteTorrentAndData(Torrent t) throws IOException {
        deleteTorrentData(t);
        deleteTorrent(t);
    }

    public void reActivate(Torrent t) throws NoSuchAlgorithmException, IOException {
        if (!activeTorrents.containsKey(t)) {
            t.reload();
            activeTorrents.put(t, new TorrentTransmitter(new MainPeerLogic(), t));
            inactiveTorrents.remove(t);
        } else {
            System.out.println("Do nothing already active");
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
                name = "Status";
                break;
            case 3:
                name = "Down Speed";
                break;
            case 4:
                name = "Up Speed";
                break;
            case 5:
                name = "Peers";
                break;
        }
        return name;
    }

    @Override
    public int getColumnCount() {
        // TODO Auto-generated method stub
        return 6;
    }

    @Override
    public int getRowCount() {
        return allTorrents.size();
    }

    private static final DecimalFormat dg = new DecimalFormat();
    {
        dg.setMaximumFractionDigits(3);
    }

    /**
     * based on apache.commons.io
     *   more: https://commons.apache.org/proper/commons-io/javadocs/api-2.5/org/apache/commons/io/FileUtils.html#byteCountToDisplaySize(long)
     *
     * @param size
     * @return
     */
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
        //return percent dl
        Torrent t = allTorrents.toArray(new Torrent[0])[arg0];// slow
        switch (arg1) {
            case 0:
                return t.name;
            case 1:
                return byteCountToDisplaySize(t.totalBytes);
            case 2:
                String progress = ratioPercentageToDisplay(t.totalBytes, t.getLeftToDownload());
                if (t.getStatus().equals("Checking files")) {
                    progress = "Checking files " + progress;
                }
                return progress;
            case 3:
                return t.getRecentDownRate();// (bytes/ms)=kb/s
            case 4:
                return t.getRecentUpRate();
            case 5:
                return t.getPeers().size();
        }
        return null;
    }

    public Torrent getTorrent(int i) {
        if (i < allTorrents.size()) {
            System.out.println("get: " + i + " from all: " + allTorrents.size());
            return allTorrents.toArray(new Torrent[0])[i];
        }

        return null;
    }
}
