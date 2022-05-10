package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.protocol.Tracker;

import java.io.IOException;


/***
 * This is what allows for hot swappable logic 
 * of torrents. However we never ened up have enought time
 * so its a bit of a waste.
 * @author pzbarcea
 *
 */
public class TorrentTransmitter {
    private Torrentable logic;
    private final Torrent ourTorrent;

    //TODO: trackers
    public Torrentable getLogic() {
        return logic;
    }

    public void setLogic(Torrentable logic) {
        this.logic = logic;
    }

    public Torrent getOurTorrent() {
        return ourTorrent;
    }

    public TorrentTransmitter(Torrentable pl, Torrent t) {
        this.logic = pl;
        this.ourTorrent = t;
        for (Tracker tr : ourTorrent.getTrackers()) {
            tr.initialize(t);
        }

    }

    public void work() throws IOException {
        logic.doWork(ourTorrent);
        for (Tracker t : ourTorrent.getTrackers()) {
            t.doWork();
        }

    }

    public void close() {
        for (Tracker tr : ourTorrent.getTrackers()) {
            tr.close(ourTorrent);
        }
    }

}
