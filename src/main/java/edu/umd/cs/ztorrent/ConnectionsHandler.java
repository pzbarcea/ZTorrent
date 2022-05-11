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

    //Maps the specific connection to its connection status (the pieces it has queued, plus all the other piece requests it has queued)
    private Map<PeerConnection, ConnectionStatus> clientToPieceSet = new HashMap<>();

    //Maps a piece to all the clients that have requested it. Useful because we need to know who to transfer a piece to
    private Map<Piece, List<PeerConnection>> pieceToClients = new HashMap<>();

    //The set of pieces that need to be added eventually (we have a maximum number of piece requested at a time)
    private Set<Piece> currentQueue = new TreeSet<>();

    //Maps the pieces that are currently in use by >= 1 connection that we need to finish
    private Map<Integer, Piece> piecesToFinish = new HashMap<>();

    //Maps the pieces that are finished or finishing
    private Map<Integer, Piece> otherFinishedPieces = new HashMap<>();

    //Represents the set of pieces that have finished. Used by the PeerWorker to track which pieces finished (and also for the
    // CLI and GUI to print out which pieces finished so we know we are progressing in the download)
    // TODO: Can we use this to update the download bar in the GUI?
    // TODO: We could make it similar to the Availability and Completed display that uTorrent has
    private List<Piece> completedPieces = new ArrayList<>();

    /**
     * Opens a connection from us to a new peer
     *
     * This involved creating a new ConnectionStatus associated with this new connection and adding it to our map of
     * peers -> pieces
     *
     * If the connection already exists, we throw an exception
     *
     * @param connection
     */
    public void beginConnection(PeerConnection connection) {
        if (clientToPieceSet.containsKey(connection)) {
            throw new RuntimeException("[ERROR] Trying to reinstate an already established connection");
        }

        clientToPieceSet.put(connection, new ConnectionStatus());
    }

    /**
     * Used to tear down the connection with another peer
     *
     * To do this we need to remove all the pieces from the queue, and then remove the connection from the map that
     * stores connections to the pieces the connection is associated with
     *
     * @param connection - the connection to tear down
     */
    public void destroyConnection(PeerConnection connection) {
        Piece[] pieces = clientToPieceSet.get(connection).queuedPieces.toArray(new Piece[0]);
        for (Piece piece : pieces) {
            removePiece(connection, (int) piece.pieceIndex);
        }

        clientToPieceSet.remove(connection);
    }

    /**
     * Continues to add pieces from the current ongoing pieces queue to the connection with the peer until we
     * have reached the maximum size of the queue, at which point we stop adding
     *
     * Pieces are removed form the current queue as they are added to the connection (we move them from our class'
     * data structure into the connection status)
     *
     * @param maxPieces - the maximum size of the queue. We should not exceed this size to maintain some efficiency
     * @param connection - the connection to a specific Peer that we need to communicate with
     */
    public void addPieces(int maxPieces, PeerConnection connection) {
        Iterator<Piece> iter = currentQueue.iterator();
        ConnectionStatus status = clientToPieceSet.get(connection);
        while (iter.hasNext()) {
            Piece p = iter.next();
            if (status.queuedPieces.size() + 1 > maxPieces) {
                break;
            }

            if (connection.getPeerBitmap().hasPiece((int) p.pieceIndex)) {
                Piece piece = piecesToFinish.get((int) p.pieceIndex);
                if (piece == null) {
                    piece = p;
                }
                List<PeerConnection> connectionList = pieceToClients.get((int) piece.pieceIndex);
                if (connectionList == null) {
                    connectionList = new ArrayList<>();
                }
                connectionList.add(connection);
                pieceToClients.put(piece, connectionList);
                status.queuedPieces.add(piece);
                status.requestsToSend.addAll(piece.getAllBlocksLeft());
                iter.remove();
                piecesToFinish.put((int) p.pieceIndex, p);
            }
        }
    }

    /**
     * Getter method for returning the current queue of active pieces
     *
     * @return - currentQueue, which represents the queue of active pieces
     */
    public Set<Piece> getQueue() {
        return currentQueue;
    }

    /**
     * Reads in pieces from a connection, using the PieceOrganizer as a guide to understand which pieces have been read
     * and related data
     *
     * There is a chance we will have had overlapping pieces, so when we have finished downloading all the torrent file(s)
     * then we need to remove these duplicates to stop them from downloading again
     */
    public long readData(PeerConnection connection, PieceOrganizer organizer) {

        List<MessageResponse> responseList = connection.getPeerResponseBlocks();

        long bytesRead = 0;

        //If responseList is empty, nothing to read (return 0)
        if (responseList == null) {
            return bytesRead;
        }

        //Otherwise, visit each response in the response list and process it accordingly
        // along the way, add up the bytes read so that we know how many bytes we have downloaded
        for (MessageResponse response : responseList) {
            bytesRead += response.block.length;
            if (piecesToFinish.containsKey(response.index)) {
                if (piecesToFinish.get(response.index).addData(response.begin, response.block)) {
                    System.out.println("[COMPLETED] Piece #" + response.index);
                    Piece p = piecesToFinish.remove(response.index);
                    completedPieces.add(p);
                    removePiece(p);
                }
            } else {
                if (!otherFinishedPieces.containsKey(response.index)) {
                    otherFinishedPieces.put(response.index, organizer.createPiece(response.index));
                }

                if (otherFinishedPieces.get(response.index).addData(response.begin, response.block)) {
                    System.out.println("[COMPLETED] Piece #" + response.index);

                    completedPieces.add(otherFinishedPieces.remove(response.index));
                }
            }
        }

        return bytesRead;
    }


    /**
     * Returns a list containing all the pieces that were recently completed, and then clears out the list so that
     * we don't get duplicates
     *
     * This allows completedPieces to serve as a "buffer" only storing the most recently completed pieces. Once this
     * function is run, these pieces get processed and we can empty out the buffer (list) to restart the process
     *
     * @return - a list containing only the pieces most recently completed by this peer
     */
    public List<Piece> getCompletedPieces() {
        if (completedPieces.size() == 0) {
            return null;
        }

        List<Piece> plist = completedPieces;
        completedPieces = new ArrayList<>();
        return plist;
    }

    /**
     * Method for obtaining the pieces that are currently active for at least one peer
     *
     * @return - a Set of integers, where the integer represents the piece ordinal (index of the piece)
     */
    public Set<Integer> getActivePieces() {
        return piecesToFinish.keySet();
    }

    /**
     * Method for obtaining the pieces that are not currently active, but are buffered as MessageRequests to send to
     * peers when the current queue opens up
     *
     * This is how the PeerWorker thread will send new message request for pieces it doesn't have yet
     *
     * @param connection - the connection for which to get the pending MessageRequests
     * @return - a List of all the MessageRequests that still have to be sent for missing pieces
     */
    public List<MessageRequest> getPendingRequests(PeerConnection connection) {
        return clientToPieceSet.get(connection).requestsToSend;
    }

    /**
     * Returns the pieces that are currently actively queued
     *
     * @param connection - the peer connection for which to send the queued pieces
     * @return - an Array of pieces
     */
    public Piece[] getQueuedPieces(PeerConnection connection) {
        return clientToPieceSet.get(connection).queuedPieces.toArray(new Piece[0]);
    }

    /**
     * Removes a piece from everywhere it is trying to be finished
     *
     * This means visiting each peer connection that we have and removing the piece from their data structures
     *
     * This will stop the piece from being downloaded
     *
     * @param toRemove - the piece to remove
     */
    public void removePiece(Piece toRemove) {
        List<PeerConnection> connectionList = pieceToClients.get(toRemove);
        if (connectionList != null) {
            for (PeerConnection mc : connectionList) {
                ConnectionStatus cw = clientToPieceSet.get(mc);
                cw.queuedPieces.remove(toRemove);
                cw.requestsToSend.removeIf(r -> r.index == toRemove.pieceIndex);
                mc.cancelPiece(toRemove);
            }
        }
        pieceToClients.remove(toRemove);
    }

    /**
     * Removes a piece from a specific connection, as opposed to everywhere (overloaded with function above)
     * This does end up removing the piece completely, if the connection passed in is the only peer that we
     * transfer the piece with
     *
     * @param connection
     * @param toRemove
     */
    public void removePiece(PeerConnection connection, int toRemove) {
        Piece p = piecesToFinish.get(toRemove);
        if (p == null) {
            return;
        }

        List<PeerConnection> connectionList = pieceToClients.get(p);
        connectionList.remove(connection);

        //If the list size is 0, then we removed the only peer that was transferring the piece with us, which means
        // we no longer need it (and don't have access to it anymore) so remove the piece from everywhere else it appears
        // including in places that were not associated with this specific connection
        if (connectionList.size() == 0) {
            pieceToClients.remove(p);
            piecesToFinish.remove(toRemove);
        }

        //Remove the piece from where it appears associated to this connection, specifically
        ConnectionStatus cw = clientToPieceSet.get(connection);
        cw.queuedPieces.remove(p);
        cw.requestsToSend.removeIf(r -> r.index == p.pieceIndex);
        if (connection.getConnectionState() == ConnectionState.connected) {
            connection.cancelPiece(p);
        }
    }
}
