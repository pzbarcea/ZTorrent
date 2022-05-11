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

    //TODO: What if we made this bigger? 50 seems to be fine
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

    private void start(Torrent t, PeerConnection mc) {
        mc.initializeConnection(t.pm.pieceOrganizer.getMapCopy(), t);
        connectionsHandler.beginConnection(mc);
        t.pm.pieceOrganizer.addPeerMap(mc.getPeerBitmap());
    }

    public void process(Torrent torrent) throws IOException {
        long now = System.currentTimeMillis();

        connectionCounter = 0;
        Set<PeerConnection> connections = torrent.getPeers();
        Iterator<PeerConnection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            PeerConnection connection = iterator.next();

            if (connection.getConnectionState() == ConnectionState.uninitialized) {
                start(torrent, connection);
            } else if (connection.getConnectionState() == ConnectionState.closed) {
                close(torrent, iterator, connection);
            } else {
                process(torrent, connection);
            }

            if (connection.getConnectionState() == ConnectionState.connected) {
                //Add a new connection
                connectionCounter++;

                callCounter += connection.haveSinceLastCall();

                if (connection.amChoking()) {
                    connection.setAmChoking(false);
                }
                if (!connection.amInterested()) {
                    connection.setAmInterested(true);
                }
                long bytesRead = connectionsHandler.readData(connection, torrent.pm.pieceOrganizer);
                down += bytesRead;
                torrent.addDownloaded(bytesRead);

                if (!connection.peerChoking()) {
                    for (MessageRequest r : connection.getPeerRequests()) {
                        if (torrent.pm.hasPiece(r.index)) {
                            Piece piece = torrent.pm.getPiece(r.index);
                            if (piece != null) {
                                System.out.println("[SENDING] Piece #" + r.index + " of size "+  r.len);
                                connection.pushRequestResponse(r, piece.getFromComplete(r.begin, r.len));
                                torrent.addUploaded(r.len);
                                up += r.len;
                            }
                        }
                    }

                    if (connection.getMaxRequests() < maxQueueSize) {
                        connection.resetHistory();

                        connection.setMaxRequests(maxQueueSize);
                    }

                    //Send MessageRequests
                    Iterator<MessageRequest> requestIterator = connectionsHandler.getPendingRequests(connection).iterator();
                    while (requestIterator.hasNext()) {
                        MessageRequest request = requestIterator.next();
                        if (connection.getMaxRequests() >= connection.activeRequests() + 1) {
                            System.out.println("[SENDING] Request for piece #" + request.index + " to " + connection);
                            try {
                                request.timeSent = System.currentTimeMillis();
                                connection.pushRequest(request);
                            } catch (Exception e) {
                                System.out.println("[ERROR] Failed to write request");
                                break;
                            }
                            requestIterator.remove();
                        } else {
                            break;
                        }
                    }

                    connectionsHandler.addPieces(maxQueueSize + 3, connection);

                } else {
                    connection.resetHistory();

                    Piece[] ps = connectionsHandler.getQueuedPieces(connection);
                    for (Piece p : ps) {
                        waitIteration = false;
                        connectionsHandler.removePiece(connection, (int) p.pieceIndex);
                    }
                }


                //If any requests died (timed out) then we need to process these separately (remove them as active)
                processTimedoutRequests(connection);

                if (connection.peerInterested()) {
                    for (Piece piece : recentlyFinished) {
                        //Send a have message notifying our peers that we have the piece
                        connection.pushHave((int) piece.pieceIndex);
                    }
                }
            }
        }

        recentlyFinished.clear();
        List<Piece> piecesCompleted = connectionsHandler.getCompletedPieces();
        if (piecesCompleted != null) {
            for (Piece p : piecesCompleted) {
                recentlyFinished.add(p);
                torrent.pm.putPiece(p);
            }
        }


        //If timeout is large enough, we can determine the rarity
        if ((System.currentTimeMillis() - timeout) > 1000 && (callCounter > 0)) {
            torrent.pm.pieceOrganizer.recomputeRarity();
            callCounter = 0;
            timeout = System.currentTimeMillis();
            waitIteration = false;
        }


        Set<Piece> workingQueue = connectionsHandler.getQueue();
        if (workingQueue.size() == 0 && !torrent.pm.pieceOrganizer.isComplete() && !waitIteration) {
            workingQueue.clear();
            List<Rarity> rList = torrent.pm.pieceOrganizer.getRarityList();
            boolean addedOnce = false;
            for (Rarity rarity : rList) {
                if (workingQueue.size() >= 5 * connectionCounter || workingQueue.size() >= 50) {
                    addedOnce = true;
                    break;
                }

                if (rarity.getValue() > 0 && !torrent.pm.hasPiece(rarity.index) && !connectionsHandler.getActivePieces().contains(rarity.index)) {
                    System.out.println("[QUEUEING] Queued Piece #" + rarity.index);
                    workingQueue.add(torrent.pm.pieceOrganizer.createPiece(rarity.index));
                    addedOnce = true;
                } else if (rarity.getValue() == 0) {
                    System.out.println("[MISSING] Couldn't find any peer with piece #" + rarity.index);
                }
            }
            waitIteration = !addedOnce;
        }


        if (System.currentTimeMillis() % 10000 == 0) {
            System.out.println("[CONNECTIONS] " + connectionCounter + " currently active");
            System.out.println("[DOWNLOAD RATE] " + (torrent.getDownloaded() / (System.currentTimeMillis() - this.startTime)) + " KB/s");
        }

        if (now - lastTime > 10 * 1000) {
            torrent.setRecentDown(down / (now - lastTime));
            torrent.setRecentUp(up / (now - lastTime));
            lastTime = System.currentTimeMillis();
            down = up = 0;
        }


        torrent.pm.processBlocking();
    }

    private void processTimedoutRequests(PeerConnection connection) {

        toRemove.clear();

        for (MessageRequest request : connection.getActiveRequest()) {
            if (System.currentTimeMillis() - request.timeSent > 1000 * 10) {
                toRemove.add(request.index);
            }
        }

        for (Integer p : toRemove) {
            System.out.println("[TIMEOUT] Piece #" + p + " on " + connection);
            connection.resetHistory();
            connectionsHandler.removePiece(connection, p);
        }

        if (toRemove.size() > 0) {
            waitIteration = false;
            int newSize = connection.getMaxRequests();
            newSize *= 0.5;

            if (newSize < 1) {
                newSize = 1;
            }

            connection.setMaxRequests(newSize);
            System.out.println("[CHOKING] Reducing Queue size of " + connection + " to " + newSize);
        }
    }

    private void process(Torrent t, PeerConnection mc) throws IOException {
        mc.doWork(t);
    }

    private void close(Torrent t, Iterator<PeerConnection> itor, PeerConnection mc) {
        itor.remove();
        destroyConnection(mc);
        callCounter = 1;
        t.pm.pieceOrganizer.removePeerMap(mc.getPeerBitmap());
    }

}
