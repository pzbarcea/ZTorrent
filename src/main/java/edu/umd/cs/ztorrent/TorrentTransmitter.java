package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.Tracker;

import java.io.IOException;


/***
 * This is what allows for hot swappable logic 
 * of torrents. However we never ened up have enought time
 * so its a bit of a waste.
 *
 */
public class TorrentTransmitter {
    private PeerWorker worker;
    private final Torrent us;

    public TorrentTransmitter(PeerWorker pl, Torrent torr) {
        this.worker = pl;
        this.us = torr;
        for (Tracker tracker : us.getTrackers()) {
            tracker.initialize(torr);
        }
    }

    public void work() throws IOException {
        worker.doWork(us);
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
