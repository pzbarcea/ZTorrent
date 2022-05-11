package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.Torrent;

import java.net.UnknownHostException;

public abstract class Tracker {
    protected boolean workingTracker = true;
    private long time = 0;

    private void updateTimer() {
        time = System.currentTimeMillis();
    }

    private long getTimeSinceWork() {
        return System.currentTimeMillis() - time;
    }

    protected abstract long getWaitMS();

    protected abstract void work();

    public void doWork() {
        if (getTimeSinceWork() > getWaitMS() && workingTracker) {
            work();
            updateTimer();
        }

    }

    /**
     * Updates the peerlist to include newly discovered peers
     */
    public abstract void update(Torrent t);

    /***
     * Closes the connection
     */
    public abstract void close(Torrent t);

    /***
     * Does the work to complete a torrent download
     */
    public void complete(Torrent t) {
    }

    /**
     * Does the work needed to begin downloading a torrent
     */
    public void initialize(Torrent t) {
    }

    /**
     * Creates the proper type of Tracker, depending on the link inside the torrent file
     * Tracker can be: HTTP, HTTPS, DHT, or UDP
     * However, we currently do not support HTTPS (project spec doesn't require secured connections)
     */
    public static Tracker makeTracker(String url) throws UnknownHostException {
        if (url.startsWith("udp")) {
            return new UDPTracker(url);
        } else if (url.startsWith("magnet")) {
            return null;
        } else if (url.startsWith("http:")) {
            return new HTTPTracker(url);
        } else {
            return null;
        }
    }

    public abstract int totalPeersObtained();
}
