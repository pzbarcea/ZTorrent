package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageRequest;
import edu.umd.cs.ztorrent.protocol.PeerConnection;
import edu.umd.cs.ztorrent.protocol.PeerConnection.ConnectionState;

import java.io.IOException;
import java.util.*;

/**
 * Worker for a single peer. It handles all the processing that is needed to make a single peer function including
 * sending data that others request, and passing forward requests for data that we don't get have
 */
public class PeerWorker {
    //Store the time that we start the program, since we will need this both to use as a timeout mechanism for checking
    // the rarity as well as a way to calculate the download rate (Bytes downloaded / difference in time)
    private final long startTime = System.currentTimeMillis();
    private long timeout = System.currentTimeMillis();

    //This will handle all of our connections for us, including storing the state of each connection
    private final ConnectionsHandler connectionsHandler = new ConnectionsHandler();

    //The list of pieces that have finished
    private final List<Piece> recentlyFinished = new ArrayList<>();
    //A set of Piece indices that need to be removed (eventually)
    private final Set<Integer> toRemove = new HashSet<>();
    int connectionCounter = 0;
    private boolean waitIteration = false;
    private long callCounter;

    private int maxQueueSize = 50;

    private void destroyConnection(PeerConnection mc) {
        connectionsHandler.destroyConnection(mc);
        try {
            mc.tearDown();
        } catch (IOException e) {
            System.out.println("[ERROR] Could not tear down connection. Got IOException on socket close.");
        }
    }

    private long lastTime = System.currentTimeMillis();
    private long up = 0;
    private long down = 0;

    public void process(Torrent t) throws IOException {
        long now = System.currentTimeMillis();

        connectionCounter = 0;
        Set<PeerConnection> connections = t.getPeers();
        Iterator<PeerConnection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            PeerConnection mc = iterator.next();

            if (mc.getConnectionState() == ConnectionState.uninitialized) {
                start(t, mc);
            } else if (mc.getConnectionState() == ConnectionState.closed) {
                close(t, iterator, mc);
            } else {
                process(t, mc);
            }

            if (mc.getConnectionState() == ConnectionState.connected) {
                //Add a new connection
                connectionCounter++;

                callCounter += mc.haveSinceLastCall();

                if (mc.amChoking()) {
                    mc.setAmChoking(false);
                }
                if (!mc.amInterested()) {
                    mc.setAmInterested(true);
                }
                long bytesRead = connectionsHandler.readData(mc, t.pm.bitmap);
                down += bytesRead;
                t.addDownloaded(bytesRead);

                if (!mc.peerChoking()) {
                    for (MessageRequest r : mc.getPeerRequests()) {
                        if (t.pm.hasPiece(r.index)) {
                            Piece piece = t.pm.getPiece(r.index);
                            if (piece != null) {
                                System.out.println("[SENDING] Piece #" + r.index + " of size "+  r.len);
                                mc.pushRequestResponse(r, piece.getFromComplete(r.begin, r.len));
                                t.addUploaded(r.len);
                                up += r.len;
                            }
                        }
                    }

                    if (mc.getMaxRequests() < maxQueueSize) {
                        mc.resetHistory();

                        mc.setMaxRequests(maxQueueSize);
                    }

                    //write requests
                    Iterator<MessageRequest> rlist = connectionsHandler.getPendingRequests(mc).iterator();
                    while (rlist.hasNext()) {
                        MessageRequest r = rlist.next();
                        if (mc.getMaxRequests() >= mc.activeRequests() + 1) {
                            System.out.println("SENT Request " + r.index + "," + r.begin + "," + r.len + " to " + mc);
                            try {
                                r.timeSent = System.currentTimeMillis();
                                mc.pushRequest(r);
                            } catch (Exception e) {
                                break;
                            }
                            rlist.remove();
                        } else {
                            break;
                        }
                    }


                    //enqueue some requests if not fully filled.
                    connectionsHandler.addPieces(maxQueueSize + 3, mc);

                } else {
                    //Dequeue im choked lists are dropped.
                    mc.resetHistory();
                    Piece[] ps = connectionsHandler.getQueuedPieces(mc);
                    for (Piece p : ps) {
                        waitIteration = false;
                        connectionsHandler.removePiece(mc, (int) p.pieceIndex);
                    }
                }


                //check if any requests have timed out...
                processTimedoutRequests(mc);

                if (mc.peerInterested()) {
                    for (Piece piece : recentlyFinished) {
                        mc.pushHave((int) piece.pieceIndex);
                    }
                }
            }


        }

        recentlyFinished.clear();
        List<Piece> piecesCompleted = connectionsHandler.getCompletedPieces();
        if (piecesCompleted != null) {
            for (Piece p : piecesCompleted) {
                recentlyFinished.add(p);
                t.pm.putPiece(p);
            }
        }


        //TODO: something about not write values being thrown in loop
        //TODO: check for recently called. We may just have everything that we can get.
        //TODO: timer, delta have's
        //Recomputes rarity based on time and new have's
        if ((System.currentTimeMillis() - timeout) > 1000 && (callCounter > 0)) {
            t.pm.bitmap.recomputeRarity();//TODO: Dont recompute so often too cpu intensive.
            callCounter = 0;
            timeout = System.currentTimeMillis();
            waitIteration = false;
        }


        //Sets Completion queue by rarity.
        //TODO: client may leave, access to piece might disapear..
        Set<Piece> workingQueue = connectionsHandler.getQueue();
        if (workingQueue.size() == 0 && !t.pm.bitmap.isComplete() && !waitIteration) {
            workingQueue.clear();
            List<Rarity> rList = t.pm.bitmap.getRarityList();
            boolean addedOnce = false;
            for (Rarity rar : rList) {
                if (workingQueue.size() >= 5 * connectionCounter || workingQueue.size() >= 50) {
                    addedOnce = true;
                    break;
                }
                //if some one has it and we don't and were not working to get it yet.
                if (rar.getValue() > 0 && !t.pm.hasPiece(rar.index) && !connectionsHandler.getActivePieces().contains(rar.index)) {
                    System.out.println("Queued " + rar.index);
                    workingQueue.add(t.pm.bitmap.createPiece(rar.index));
                    addedOnce = true;
                } else if (rar.getValue() == 0) {
                    System.out.println("No one has " + rar.index);
                }
            }
            waitIteration = !addedOnce;
            if (waitIteration) {
                System.out.println("Exhausted");
            }
        }


        if (System.currentTimeMillis() % 10000 == 0) {
            System.out.println("Active Connections: " + connectionCounter);
            System.out.println("Average dl: " + (t.getDownloaded() / (System.currentTimeMillis() - this.startTime)) + " KB/s");
        }

        if (now - lastTime > 10 * 1000) {
            t.setRecentDown(down / (now - lastTime));
            t.setRecentUp(up / (now - lastTime));
            lastTime = System.currentTimeMillis();
            down = up = 0;
        }


        t.pm.doBlockingWork(); // TODO: remove from here. set to threaded process.
    }

    private void processTimedoutRequests(PeerConnection mc) {

        toRemove.clear();
        for (MessageRequest r : mc.getActiveRequest()) {
            if (System.currentTimeMillis() - r.timeSent > 1000 * 10) {//10 second timeout
                toRemove.add(r.index);
            }
        }

        for (Integer p : toRemove) {
            System.out.println("Time expired. " + p + " on " + mc);
            mc.resetHistory();
            connectionsHandler.removePiece(mc, p);
        }

        if (toRemove.size() > 0) {
            //drop max queue size.
            waitIteration = false;
            int i = mc.getMaxRequests();
            i *= .5;
            if (i < 1) {
                i = 1;
            }
            mc.setMaxRequests(i);
            System.out.println("Dropping max queue for " + mc + " to " + i);
        }
    }

    private void process(Torrent t, PeerConnection mc) throws IOException {
        mc.doWork(t);
    }

    private void close(Torrent t, Iterator<PeerConnection> itor, PeerConnection mc) {
        itor.remove();
        destroyConnection(mc);
        callCounter = 1;//just set so can recalculate.
        t.pm.bitmap.removePeerMap(mc.getPeerBitmap());
    }

    private void start(Torrent t, PeerConnection mc) {
        mc.initializeConnection(t.pm.bitmap.getMapCopy(), t);
        connectionsHandler.beginConnection(mc);
        t.pm.bitmap.addPeerMap(mc.getPeerBitmap());//adds
    }

}
