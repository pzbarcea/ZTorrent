package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;
import edu.umd.cs.ztorrent.MessageParser.HandShake;
import edu.umd.cs.ztorrent.MessageParser.PeerMessage;
import edu.umd.cs.ztorrent.MessageParser.Request;
import edu.umd.cs.ztorrent.MessageParser.Response;

import java.io.IOException;
import java.net.*;
import java.util.*;

/***
 * Let this be the managedConnection for the peer
 * Let it handle things like communication
 * Let this be the Socket of the connection
 *
 * Put simply ManagedConnection will follow the rules of the protocol.
 * It will take in and attempt to push out data as is available by the rules.
 *
 * This class is very much the state of the connection.
 * TODO: announced map control for under reporting?
 * We store long because 4 byte UNSIGNED indexing is used.
 *
 * By the specs:
 *
 * CHOKED: 
 *  When a peer chokes the client, it is a notification that no requests will be answered until the client is unchoked. 
 *  The client should not attempt to send requests for blocks, and it should consider all pending (unanswered) requests to be discarded by the remote peer.
 *  -may send have (if peer interested)
 *  -may send blocks (if we aren't peer not choked)
 *
 * Interested:
 *  Whether or not the remote peer is interested in something this client has to offer.
 *  This is a notification that the remote peer will begin requesting blocks when the client unchokes them.
 *  -may send blocks(if we aren't peer not choked)
 * 	-may send requests (if peer don't have us choked)
 *
 *  This class composed of 90% boiler plate for good api interface.
 *  @Warning: 2MB output stream is set. If this gets fully utilized it will block.
 *  //TODO: make block safe. boolean perhaps? need to do something if buffer becomes filled.
 */
public class ManagedConnection extends MetaConnection {
    //Requests fromUs
    //Requests fromPeer
    private final Set<Request> ourRequests; // from us

    private int historySize = 0;
    private List<Response> peerSentBlocks;
    private long have = 0;
    private final MessageParser mp;
    private long download;
    private long upload;
    private BitMap peerBitMap;
    private int max_queued = 3;

    public ManagedConnection(InetAddress ip, int port) {
        super(ip, port);
        download = upload = maintenance = 0;
        mp = new MessageParser();
        peerRequests = new HashSet<Request>();
        ourRequests = new HashSet<Request>();
        peerSentBlocks = new ArrayList<Response>();
        recvHandShake = false;
        conState = ConnectionState.uninitialized;
    }

    public ManagedConnection(Socket s, HandShake hs) {
        this(s.getInetAddress(), s.getPort());
        recvHandShake = true;
        this.sock = s;
        this.version = hs.version;
        this.infoHash = hs.hashInfo;
        this.peerID = hs.peerID;
    }

    /**
     * Takes in the byte map for announcing
     *
     * @param announcedMap
     * @throws IOException
     */
    public void initalizeConnection(byte[] announcedMap, Torrent t) {
        peerBitMap = new BitMap(t.totalBytes, t.pieceLength);
        metaData = t.meta;//must be initialized
        if (conState != ConnectionState.uninitialized) {
            throw new RuntimeException("Invalid State Exception");
        }
        conState = ConnectionState.pending;
        if (sock == null) { //Outbound connection.
            sock = new Socket();

            new Thread() {
                @Override
                public void run() {
                    try {
                        sock.setKeepAlive(true);
                        sock.setSoTimeout(2 * 60 * 1000);//2 min timeout
                        sock.setReceiveBufferSize(1024 * 1024 * 2);
                        sock.setSendBufferSize(1024 * 1024 * 2);
                        sock.connect(new InetSocketAddress(ip, port));//BLOCKS!
                        sockOut = sock.getOutputStream();
                        sockIn = sock.getInputStream();
                        if (sock.isConnected()) {
                            conState = ConnectionState.connected;
                        } else {
                            //should have thrown error.
                            conState = ConnectionState.closed;
                        }
                    } catch (IOException e) {
                        System.out.println("[SKIP] Connection refused from: " + ip + ":" + port);
                        conState = ConnectionState.closed;
                    }
                }
            }.start();
        } else if (recvHandShake == true) { //Inbound connection
            try {
                sockOut = sock.getOutputStream();
                sockIn = sock.getInputStream();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            conState = ConnectionState.connected;
            sentHandShake = true;
            try {
                mp.sendHandShake(sockOut, t.getInfoHash(), t.getPeerID());
                mp.bitfield(sockOut, announcedMap);
            } catch (IOException io) {
                conState = ConnectionState.closed;
            }
        } else {
            throw new RuntimeException("[WARNING] Connection state: UNKNOWN");
        }

        if (announcedMap == null || announcedMap.length != peerBitMap.getLength()) {
            throw new RuntimeException("[WARNING] Invalid 'announced' bitmap. Either NULL or incorrect length!");
        }
        this.announcedMap = announcedMap;
    }

    @Override
    public void blindInitialize(MetaData md) {
        throw new RuntimeException("[ERROR] Not allowed blindInitialize on ManagedConnection.");
    }

    //TODO: Consider if it might be a good idea to only pass out if connected.
    public BitMap getPeerBitmap() {
        return peerBitMap;
    }

    /***
     * Adds a request for block.
     * If requests already exists will do
     * nothing.
     * These are hard sends! [data will be written on call]
     * The upload and maintenance counter will be updated.
     * @param r
     */
    public boolean pushRequest(Request r) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send requests on 'connected' connections");
        } else if (peer_choking) {
            throw new RuntimeException("Can only send requests on unchoked connections");
        }
        if (ourRequests.contains(r.index)) {
            return false;//already contained.
        }
        if (ourRequests.size() + 1 > max_queued) {
            throw new RuntimeException("Over enqued max size set to: " + max_queued);
        }

        ourRequests.add(r);
        try {
            maintenance += 17;
            mp.request(sockOut, r.index, r.begin, r.len);
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
        return true;
    }

    /**
     * Removes any matching requests to this piece.
     * Connection must be connected.
     *
     * @param p
     */
    public void cancelPiece(Piece p) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send requests on 'connected' connections");
        } else if (peer_choking) {
            return;
        }
        try {
            Iterator<Request> itor = ourRequests.iterator();
            while (itor.hasNext()) {
                Request r = itor.next();
                if (r.index == p.pieceIndex) {
                    itor.remove();
                    maintenance += 17;
                    mp.cancel(sockOut, r.index, r.begin, r.len);
                }
            }
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
    }

    /**
     * Takes block response, will throw error if response doesnt
     * exist in peerRequest set.
     * These are hard sends! [data will be written on call]
     * The upload and maintenance counter will be updated.
     *
     * @param r
     * @param block
     */
    public void pushRequestResponse(Request r, byte[] block) {
        if (!peerRequests.remove(r)) {
            throw new RuntimeException("Giving unrequested block! But whyyyy! =(");
        } else if (conState != ConnectionState.connected) {
            throw new RuntimeException("This is invalid connection state isn't in connected mode!");
        } else if (peer_choking) {
            throw new RuntimeException("We are being choked! Not valid to send silly!");
        }
        try {
            maintenance += 13;
            upload += block.length;
            mp.piece(sockOut, r.index, r.begin, block);
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
    }

    public void pushHave(int index) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send have on 'connected' connections");
        } else if (!peer_interested) {
            throw new RuntimeException("Can only send have on interested connections");
        }
        try {
            maintenance += 10;
            mp.have(sockOut, index);
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
    }

    /**
     * Returns list of active requests.
     *
     * @return
     * @author pzbarcea
     */
    public List<Request> getPeerRequests() {
        List<Request> q = new ArrayList<Request>(peerRequests.size());
        for (Request r : peerRequests) {
            q.add(r);
        }
        return q;
    }

    public Request[] getActiveRequest() {//TODO bug here.
        return ourRequests.toArray(new Request[0]);
    }

    /**
     * List of blocks gotten since last call.
     * Note will return null if size 0.
     *
     * @return
     */
    public List<Response> getPeerResponseBlocks() {
        if (peerSentBlocks.size() < 1) {
            return null;
        }
        List<Response> r = peerSentBlocks;
        peerSentBlocks = new ArrayList<Response>();
        return r;
    }

    public void setPreConnectionPeerID(byte[] peerID) {
        if (conState == ConnectionState.uninitialized) {
            this.peerID = peerID;
        } else {
            throw new RuntimeException("Invalid Use error!");
        }
    }

    public int activeRequests() {
        return ourRequests.size();
    }

    public int getMaxRequests() {
        return max_queued;
    }

    public void setMaxRequests(int i) {
        max_queued = i;
    }

    public byte[] getInfoHash() {
        return infoHash;
    }

    public long haveSinceLastCall() {
        long i = have;
        have = 0;
        return i;
    }

    public int getHistorySize() {
        return historySize;
    }

    public void resetHistory() {
        historySize = 0;
    }

    @Override
    protected void doDataIn(PeerMessage pm) {
        if (pm.type == PeerMessage.Type.CHOKE) {
            this.peer_choking = true;
            //Drop our requests. They aint gana get done.
            System.out.println(this + " choked us.");
            ourRequests.clear();
        } else if (pm.type == PeerMessage.Type.UNCHOKE) {
            System.out.println(this + " unchoked us.");
            this.peer_choking = false;
        } else if (pm.type == PeerMessage.Type.INTERESTED) {
            System.out.println(this + " intrested in us.");
            this.peer_interested = true;
        } else if (pm.type == PeerMessage.Type.NOT_INTERESTED) {
            System.out.println(this + " not intrested in us.");
            this.peer_interested = false;
        } else if (pm.type == PeerMessage.Type.HAVE) {
            System.out.println(this + " has " + pm.piece);
            if (!am_interested) {
                System.out.println("Client sent us have! But WE AIN'T EVEN interested.");
            }
            if (!peerBitMap.hasPiece((int) pm.piece)) {
                peerBitMap.addPieceComplete(pm.piece);
                have++;
            } else {
                System.out.println("Bugs. Maybe it was " + pm.piece);
            }

        } else if (pm.type == PeerMessage.Type.BIT_FILED) {
            System.out.println("Got bitmap from " + this);
            if (peerBitMap.getCompletedPieces() == 0) {
                peerBitMap.setBitMap(pm.bitfield);
                have = peerBitMap.getCompletedPieces();
                System.out.println("Has " + have + " of " + peerBitMap.getNumberOfPieces());
            } else {
                peerBitMap.setBitMap(pm.bitfield);
                System.out.println("This wasn't first time. Playing games?" + this);
            }

        } else if (pm.type == PeerMessage.Type.REQUEST) {
            peerRequests.add(new Request(pm.index, pm.begin, pm.length));
            if (am_choking) {
                System.out.println("Recieved request but choking request!");
            }

        } else if (pm.type == PeerMessage.Type.CANCEL) {
            peerRequests.remove(new Request(pm.index, pm.begin, pm.length));//should work
        } else if (pm.type == PeerMessage.Type.PIECE) {
            //get piece
            System.out.println("Got piece " + pm.index + " from " + this);
            Request r = new Request(pm.index, pm.begin, pm.block.length);
            Response rs = new Response(pm.index, pm.begin, pm.block);
            if (ourRequests.remove(r)) {
                historySize++;
                download += pm.block.length;
            } else {
                //For now this isnt a shutdownable event.
                System.out.println("Recieved Piece " + pm.index + "," + pm.begin + "," + pm.length + " but didnt send request!");
            }
            peerSentBlocks.add(rs);
        } else if (pm.type == PeerMessage.Type.EXTENSION) {
            doExtension(pm.extensionID, pm.extension);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ManagedConnection) {
            ManagedConnection m = (ManagedConnection) o;
            return m.ip.equals(ip) && m.port == port;
        }
        return false;
    }


    /***
     * State of this connection.
     * uninitialized -> meaning no connection attempt made
     * closed -> meaning socket disconnect, was requested, or error occured.
     * pending -> socket connected but handshake not yet completed.
     */
    public enum ConnectionState {
        uninitialized,
        pending,
        connected,
        closed
    }
}
