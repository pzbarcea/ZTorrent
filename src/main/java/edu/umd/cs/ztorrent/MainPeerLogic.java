package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.BitMap.Rarity;
import edu.umd.cs.ztorrent.MessageParser.Request;
import edu.umd.cs.ztorrent.protocol.ManagedConnection;
import edu.umd.cs.ztorrent.protocol.ManagedConnection.ConnectionState;

import java.io.IOException;
import java.util.*;

/**
 * This is the implementation of peer logic
 * This is probably the most basic form of a client.
 * <p>
 * It follows the following rules:
 * -if some one requests something that we can give:
 * *give it to them
 * -collect list of rarity pieces we dont have and disseminate get requests for them
 * amoung our connections
 *
 * @author pzbarcea
 */
public class MainPeerLogic extends PeerLogic {
    int recvPieces = 0;
    int activeConnections = 0;
    private static final int MAX_ENQEUED = 32;//20*1024*16 =
    private static final int MAX_ACTIVE_PIECES = 2;
    private boolean exhausted = false;
    private long rarityTimer = System.currentTimeMillis();
    private long haveAccumulator = 0;
    private final long MIN_RARITY_TIME = 1000;
    private final ConnectionsManager connectionsManager = new ConnectionsManager();
    private final List<Piece> completedLast = new ArrayList<Piece>();
    private final Set<Integer> cleaningList = new HashSet<Integer>();
    private final long start = System.currentTimeMillis();

    private void connectionCleanUp(ManagedConnection mc) {
        connectionsManager.connectionCleanUp(mc);
        try {
            mc.shutDown();
        } catch (IOException e) {
        }
    }

    private long lastTime = System.currentTimeMillis();
    private long up = 0;
    private long down = 0;

    @Override
    public void doWork(Torrent t) throws IOException {

        // save time
        long now = System.currentTimeMillis();

        activeConnections = 0;
        Set<ManagedConnection> pList = t.getPeers();
        Iterator<ManagedConnection> itor = pList.iterator();
        while (itor.hasNext()) {
            ManagedConnection mc = itor.next();
            if (mc.getConnectionState() == ConnectionState.uninitialized) {
                mc.initalizeConnection(t.pm.bitmap.getMapCopy(), t);
                connectionsManager.initializeConnection(mc);
                t.pm.bitmap.addPeerMap(mc.getPeerBitmap());//adds
            } else if (mc.getConnectionState() == ConnectionState.closed) {
                //pull off anything new?
                itor.remove();
                connectionCleanUp(mc);
                haveAccumulator = 1;//just set so can recalculate.
                t.pm.bitmap.removePeerMap(mc.getPeerBitmap());
            } else {
                mc.doWork(t);
            }

            if (mc.getConnectionState() == ConnectionState.connected) {
                //Read state.
                //Push requests (if allowed)
                //Push blocks (if allowed)
                activeConnections++;
                haveAccumulator += mc.haveSinceLastCall();

                if (mc.amChoking()) {
                    mc.setAmChoking(false);
                }
                if (!mc.amInterested()) {
                    mc.setAmInterested(true);
                }
                long l = connectionsManager.readFromConnection(mc, t.pm.bitmap);
                down += l;
                t.addDownloaded(l);

                if (!mc.peerChoking()) {
                    //Give them whatever they want.
                    for (Request r : mc.getPeerRequests()) {
                        if (t.pm.hasPiece(r.index)) {
                            Piece piece = t.pm.getPiece(r.index);//can return null.
                            if (piece != null) {
                                System.out.println("SENT RESPONSE " + r.index + "," + r.begin + "," + r.len);
                                mc.pushRequestResponse(r, piece.getFromComplete(r.begin, r.len));
                                t.addUploaded(r.len);
                                up += r.len;
                            }
                        }
                    }


                    //If the connection has been good (queue size < 50% full and track history >3*queue size)
                    //reset history value
                    //the queue will reset.
                    //TODO: time must be a factor of the equation x complete over y time
                    if (mc.getHistorySize() > 3 * mc.getMaxRequests() && mc.getActiveRequest().length < mc.getMaxRequests() / 2.0 && mc.getMaxRequests() < MAX_ENQEUED) {//less then 50%
                        mc.resetHistory();
                        int i = mc.getMaxRequests();
                        i *= 1.5;//3*1.5 =
                        if (i > MAX_ENQEUED) {
                            i = MAX_ENQEUED;
                        }
                        mc.setMaxRequests(i);
                        System.out.println("" + mc + " has new max limit " + i);
                    }


                    //write requests
                    Iterator<Request> rlist = connectionsManager.getBufferedRequests(mc).iterator();
                    if (connectionsManager.getBufferedRequests(mc).size() > 0) {
//						System.out.println("Something could.");
                    }
                    while (rlist.hasNext()) {
                        Request r = rlist.next();
                        if (mc.getMaxRequests() >= mc.activeRequests() + 1) {
                            System.out.println("SENT Request " + r.index + "," + r.begin + "," + r.len + " to " + mc);
                            try {
                                r.sentTime = System.currentTimeMillis();
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
                    connectionsManager.enqueuePieces(MAX_ENQEUED + 3, mc);

                } else {
                    //Dequeue im choked lists are dropped.
                    mc.resetHistory();
                    Piece[] ps = connectionsManager.getQueuedPieces(mc);
                    for (Piece p : ps) {
                        exhausted = false;
                        connectionsManager.cancelPieceForConnection(mc, (int) p.pieceIndex);
                    }
                }


                //check if any requests have timed out...
                cleaningList.clear();
                for (Request r : mc.getActiveRequest()) {
                    if (System.currentTimeMillis() - r.sentTime > 1000 * 10) {//10 second timeout
                        cleaningList.add(r.index);
                    }
                }

                for (Integer p : cleaningList) {
                    System.out.println("Time expired. " + p + " on " + mc);
                    mc.resetHistory();
                    connectionsManager.cancelPieceForConnection(mc, p);
                }

                if (cleaningList.size() > 0) {
                    //drop max queue size.
                    exhausted = false;
                    int i = mc.getMaxRequests();
                    i *= .5;
                    if (i < 1) {
                        i = 1;
                    }
                    mc.setMaxRequests(i);
                    System.out.println("Dropping max queue for " + mc + " to " + i);
                }


                if (mc.peerInterested()) {
                    for (Piece piece : completedLast) {
                        mc.pushHave((int) piece.pieceIndex);
                    }
                }
            }


        }

        completedLast.clear();
        List<Piece> piecesCompleted = connectionsManager.recentlyCompletedPieces();
        if (piecesCompleted != null) {
            for (Piece p : piecesCompleted) {
                completedLast.add(p);
                t.pm.putPiece(p);
            }
        }


        //TODO: something about not write values being thrown in loop
        //TODO: check for recently called. We may just have everything that we can get.
        //TODO: timer, delta have's
        //Recomputes rarity based on time and new have's
        if ((System.currentTimeMillis() - rarityTimer) > MIN_RARITY_TIME && (haveAccumulator > 0)) {
            t.pm.bitmap.recomputeRarity();//TODO: Dont recompute so often too cpu intensive.
            haveAccumulator = 0;
            rarityTimer = System.currentTimeMillis();
            exhausted = false;
        }


        //Sets Completion queue by rarity.
        //TODO: client may leave, access to piece might disapear..
        Set<Piece> workingQueue = connectionsManager.currentQueue();
        if (workingQueue.size() == 0 && !t.pm.bitmap.isComplete() && !exhausted) {//&&!exhausted
            workingQueue.clear();
            List<Rarity> rList = t.pm.bitmap.getRarity();
            boolean addedOnce = false;
            for (Rarity rar : rList) {
                if (workingQueue.size() >= 5 * activeConnections || workingQueue.size() >= 50) {
                    addedOnce = true;
                    break;
                }
                //if some one has it and we don't and were not working to get it yet.
                if (rar.getCount() > 0 && !t.pm.hasPiece(rar.index) && !connectionsManager.getWorkingSet().contains(rar.index)) {
                    System.out.println("Queued " + rar.index);
                    workingQueue.add(t.pm.bitmap.createPiece(rar.index));
                    addedOnce = true;
                } else if (rar.getCount() == 0) {
                    System.out.println("No one has " + rar.index);
                }
            }
            exhausted = !addedOnce;
            if (exhausted) {
                System.out.println("Exhausted");
            }
        }


        if (System.currentTimeMillis() % 10000 == 0) {
            System.out.println("Active Connections: " + activeConnections);
            System.out.println("Average dl: " + (t.getDownloaded() / (System.currentTimeMillis() - start)) + " KB/s");
        }

        if (now - lastTime > 10 * 1000) {
            t.setRecentDown(down / (now - lastTime));
            t.setRecentUp(up / (now - lastTime));
            lastTime = System.currentTimeMillis();
            down = up = 0;
        }


        t.pm.doBlockingWork(); // TODO: remove from here. set to threaded process.
    }

}
