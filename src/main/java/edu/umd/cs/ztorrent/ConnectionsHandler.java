package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageRequest;
import edu.umd.cs.ztorrent.message.MessageResponse;
import edu.umd.cs.ztorrent.protocol.PeerConnection;
import edu.umd.cs.ztorrent.protocol.PeerConnection.ConnectionState;

import java.util.*;

/**
 * Handles transfer of piece statuses across a connection and maintains the state for each piece being worked on
 *
 * This class allows us to know which pieces are being transferred, by which peer connection, and update them as they
 * are completed
 */
public class ConnectionsHandler {
    /**
     * Represents the status of a connection
     * For each connection, we have a set of pieces that are currently queued (in use)
     * and a list of pieces that are "buffered"
     *
     * Buffered pieces are stored as MessageRequests for specific pieces that we will send later, once we have
     * more space in the queue to add these new pieces
     */
    private class ConnectionStatus {
        Set<Piece> queuedPieces = new TreeSet<>();
        LinkedList<MessageRequest> requestsToSend = new LinkedList<>();
    }

    //Maps the specific connection to its connection status (the pieces it has queued, plus all the other piece requests it has queued)
    private final Map<PeerConnection, ConnectionStatus> clientToPieceSet = new HashMap<>();

    //Maps a piece to all the clients that have requested it. Useful because we need to know who to transfer a piece to
    private final Map<Piece, List<PeerConnection>> pieceToClients = new HashMap<>();

    //The set of pieces that need to be added eventually (we have a maximum number of piece requested at a time)
    private final Set<Piece> currentQueue = new TreeSet<>();

    //Maps the pieces that are currently in use by >= 1 connection
    private final Map<Integer, Piece> disseminatedPiecesToCompete = new HashMap<>();

    //Maps the pieces that are finished or finishing
    private final Map<Integer, Piece> otherPiecesGettingComplete = new HashMap<>();

    //Represents the set of pieces that have finished. Used by the PeerWorker to track which pieces finished (and also for the
    // CLI and GUI to print out which pieces finished so we know we are progressing in the download)
    // TODO: Can we use this to update the download bar in the GUI?
    private List<Piece> recentlyCompleted = new ArrayList<>();

    public void destroyConnection(PeerConnection mc) {
        Piece[] ps = clientToPieceSet.get(mc).queuedPieces.toArray(new Piece[0]);
        for (Piece p : ps) {
            cancelPieceForConnection(mc, (int) p.pieceIndex);
        }

        clientToPieceSet.remove(mc);
    }

    /**
     * This function enqueues at max "maxPieces" pieces to
     * this connection.
     * <p>
     * Pieces are removed from the current Queue.
     * <p>
     * Note that connection will not add more
     * then maxPieces to the connection.
     *
     * @param maxPieces
     * @param mc
     */

    /**
     *
     * Continues to add pieces from the current ongoing pieces queue to the
     *
     * @param maxPieces - the maximum size of the queue. We should not exceed this size to maintain some efficiency
     * @param connection - the connection to a specific Peer that we need to communicate with
     */
    public void enqueuePieces(int maxPieces, PeerConnection connection) {
        Iterator<Piece> iter = currentQueue.iterator();
        ConnectionStatus cstatus = clientToPieceSet.get(connection);
        while (iter.hasNext()) {
            Piece p = iter.next();
            if (cstatus.queuedPieces.size() + 1 > maxPieces) {
                break;
            }

            if (connection.getPeerBitmap().hasPiece((int) p.pieceIndex)) {
                //TO ENSURE SAME PIECE IS SHARED IN MULTI-QUE CASES
                Piece piece = disseminatedPiecesToCompete.get((int) p.pieceIndex);
                if (piece == null) {
                    piece = p;
                }
                List<PeerConnection> lMC = pieceToClients.get((int) piece.pieceIndex);
                if (lMC == null) {
                    lMC = new ArrayList<>();
                }
                lMC.add(connection);
                pieceToClients.put(piece, lMC);
                cstatus.queuedPieces.add(piece);
                cstatus.requestsToSend.addAll(piece.getAllBlocksLeft());
                iter.remove();
                disseminatedPiecesToCompete.put((int) p.pieceIndex, p);
            }
        }
    }

    public Set<Piece> getCurrentQueue() {
        return currentQueue;
    }

    /**
     * Pulls all the read in blocks.
     * On completion cancels any mismatched sections.
     */
    public long readFromConnection(PeerConnection mc, PieceOrganizer b) {
        List<MessageResponse> rlist = mc.getPeerResponseBlocks();
        long bytes = 0;
        if (rlist != null) {
            for (MessageResponse r : rlist) {
                bytes += r.block.length;
                if (disseminatedPiecesToCompete.containsKey(r.index)) {
                    if (disseminatedPiecesToCompete.get(r.index).addData(r.begin, r.block)) {
                        //complete
                        System.out.println("Complete! " + r.index);
                        Piece p = disseminatedPiecesToCompete.remove(r.index);
                        recentlyCompleted.add(p);
                        dequeuePiece(p);
                    }
                } else {
                    //TODO: check if we need piece?
                    if (!otherPiecesGettingComplete.containsKey(r.index)) {
                        otherPiecesGettingComplete.put(r.index, b.createPiece(r.index));
                    }

                    if (otherPiecesGettingComplete.get(r.index).addData(r.begin, r.block)) {
                        System.out.println("Complete! -Unown- " + r.index);
                        recentlyCompleted.add(otherPiecesGettingComplete.remove(r.index));
                    }
                }
            }
        }
        return bytes;
    }

    public void initializeConnection(PeerConnection mc) {
        if (clientToPieceSet.containsKey(mc)) {
            throw new RuntimeException("Incorrect use. MC already initialized");
        }
        ConnectionStatus cw = new ConnectionStatus();
        clientToPieceSet.put(mc, cw);
    }


    public List<Piece> recentlyCompletedPieces() {
        if (recentlyCompleted.size() == 0) {
            return null;
        }

        List<Piece> plist = recentlyCompleted;
        recentlyCompleted = new ArrayList<>();
        return plist;
    }

    /**
     * This dequeue's piece # from all clients
     * actively working to complete.
     *
     * @param p
     */
    public void dequeuePiece(Piece p) {
        List<PeerConnection> mcs = pieceToClients.get(p);
        if (mcs != null) {
            for (PeerConnection mc : mcs) {
                ConnectionStatus cw = clientToPieceSet.get(mc);
                cw.queuedPieces.remove(p);
                cw.requestsToSend.removeIf(r -> r.index == p.pieceIndex);
                mc.cancelPiece(p);
            }
        }
        pieceToClients.remove(p);
    }

    /**
     * Returns the set of pieces that are
     * actively being worked on by *one or more*
     * connections
     *
     * @return
     */
    public Set<Integer> getWorkingSet() {
        return disseminatedPiecesToCompete.keySet();
    }

    /**
     * Returns a list of requests still
     * waiting to be made on the connection.
     *
     * @param mc
     * @return
     */
    public List<MessageRequest> getBufferedRequests(PeerConnection mc) {
        return clientToPieceSet.get(mc).requestsToSend;
    }

    public Piece[] getQueuedPieces(PeerConnection mc) {
        ConnectionStatus cw = clientToPieceSet.get(mc);
        return cw.queuedPieces.toArray(new Piece[0]);
    }

    /**
     * Cancels the piece for a specific given connection.
     *
     * @param mc
     * @param piece
     */
    public void cancelPieceForConnection(PeerConnection mc, int piece) {
        Piece p = disseminatedPiecesToCompete.get(piece);
        if (p != null) {
            List<PeerConnection> list = pieceToClients.get(p);
            list.remove(mc);
            if (list.size() == 0) {
                //ok remove this from every where.
                //This might be the only client that had the piece :-|
                pieceToClients.remove(p);
                disseminatedPiecesToCompete.remove(piece);
            }

            ConnectionStatus cw = clientToPieceSet.get(mc);
            cw.queuedPieces.remove(p);
            cw.requestsToSend.removeIf(r -> r.index == p.pieceIndex);
            if (mc.getConnectionState() == ConnectionState.connected) {
                mc.cancelPiece(p);
            }
        }
    }

}
