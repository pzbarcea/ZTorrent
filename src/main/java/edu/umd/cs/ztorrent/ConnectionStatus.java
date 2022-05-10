package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageRequest;

import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;

/**
 * Represents the status of a connection
 * For each connection, we have a set of pieces that are currently queued (in use)
 * and a list of pieces that are "buffered"
 * <p>
 * Buffered pieces are stored as MessageRequests for specific pieces that we will send later, once we have
 * more space in the queue to add these new pieces
 */
class ConnectionStatus {
    Set<Piece> queuedPieces = new TreeSet<>();
    LinkedList<MessageRequest> requestsToSend = new LinkedList<>();
}
