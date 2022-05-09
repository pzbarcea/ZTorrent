package edu.umd.cs.ztorrent;

import java.io.IOException;

/**
 * The purpose of this class is to make it easy
 * to interchange the logic used to sustain connections.
 * E.g. to easily go from pop
 * See Diagram: PeerLogic_Control_Flow.jgp
 *
 * @author pzbarcea
 */
public abstract class AbstractPeerWorker {

    /**
     * At its essence this is all peer logic is
     * We assume the AbstractPeerWorker will loop over all
     * the managed connection and push and pull data
     * as needed.
     *
     * @param t
     */
    public abstract void doWork(Torrent t) throws IOException;
}
