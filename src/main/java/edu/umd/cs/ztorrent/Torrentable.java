package edu.umd.cs.ztorrent;

import java.io.IOException;

/**
 * Wrapper interface for all connections
 * Any connection must implement doWork() so that it can properly process a torrent file
 */
public interface Torrentable {
    void doWork(Torrent t) throws IOException;
}