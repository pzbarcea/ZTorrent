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
    private Torrentable logic;
    private final Torrent us;

    public TorrentTransmitter(Torrentable pl, Torrent t) {
        this.logic = pl;
        this.us = t;
        for (Tracker tr : us.getTrackers()) {
            tr.initialize(t);
        }

    }

    public void work() throws IOException {
        logic.doWork(us);
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
