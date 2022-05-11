package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;
import edu.umd.cs.ztorrent.message.MessageParser;
import edu.umd.cs.ztorrent.message.MessageParser.HandShake;
import edu.umd.cs.ztorrent.message.PeerMessage;
import edu.umd.cs.ztorrent.message.MessageRequest;
import edu.umd.cs.ztorrent.message.MessageResponse;
import edu.umd.cs.ztorrent.message.MessageType;

import java.io.IOException;
import java.net.*;
import java.util.*;

public class PeerConnection extends TorrentConnection {
    private final Set<MessageRequest> ourRequests;
    private int historySize = 0;
    private List<MessageResponse> peerSentBlocks;
    private long have = 0;
    private final MessageParser mp;
    private long download;
    private long upload;
    private PieceOrganizer peerPieceOrganizer;
    private int max_queued = 3;

    public PeerConnection(InetAddress ip, int port) {
        super(ip, port);
        download = upload = maintenance = 0;
        mp = new MessageParser();
        peerRequests = new HashSet<MessageRequest>();
        ourRequests = new HashSet<MessageRequest>();
        peerSentBlocks = new ArrayList<MessageResponse>();
        recvHandShake = false;
        conState = ConnectionState.uninitialized;
    }

    public PeerConnection(Socket s, HandShake hs) {
        this(s.getInetAddress(), s.getPort());
        recvHandShake = true;
        this.sock = s;
        this.version = hs.version;
        this.infoHash = hs.hashInfo;
        this.peerID = hs.peerID;
    }

    public void initializeConnection(byte[] announcedMap, Torrent t) {
        peerPieceOrganizer = new PieceOrganizer(t.totalBytes, t.pieceLength);
        torrentInfo = t.meta;
        if (conState != ConnectionState.uninitialized) {
            throw new RuntimeException("Invalid State Exception");
        }
        conState = ConnectionState.pending;
        if (sock == null) {
            sock = new Socket();

            new Thread() {
                @Override
                public void run() {
                    try {
                        sock.setKeepAlive(true);
                        sock.setSoTimeout(2 * 60 * 1000);
                        sock.setReceiveBufferSize(1024 * 1024 * 2);
                        sock.setSendBufferSize(1024 * 1024 * 2);
                        sock.connect(new InetSocketAddress(ip, port));
                        sockOut = sock.getOutputStream();
                        sockIn = sock.getInputStream();
                        if (sock.isConnected()) {
                            conState = ConnectionState.connected;
                        } else {
                            conState = ConnectionState.closed;
                        }
                    } catch (IOException e) {
                        System.out.println("[SKIP] Connection refused from: " + ip + ":" + port);
                        conState = ConnectionState.closed;
                    }
                }
            }.start();
        } else if (recvHandShake == true) {
            try {
                sockOut = sock.getOutputStream();
                sockIn = sock.getInputStream();
            } catch (IOException e) {
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

        if (announcedMap == null || announcedMap.length != peerPieceOrganizer.getLength()) {
            throw new RuntimeException("[WARNING] Invalid 'announced' bitmap. Either NULL or incorrect length!");
        }
        this.announcedMap = announcedMap;
    }

    @Override
    public void blindInitialize(TorrentInfo md) {
        throw new RuntimeException("[ERROR] Not allowed blindInitialize on PeerConnection.");
    }

    public PieceOrganizer getPeerBitmap() {
        return peerPieceOrganizer;
    }

    public boolean pushRequest(MessageRequest r) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send requests on 'connected' connections");
        } else if (peer_choking) {
            throw new RuntimeException("Can only send requests on unchoked connections");
        }
        if (ourRequests.contains(r.index)) {
            return false;
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

    public void cancelPiece(Piece p) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send requests on 'connected' connections");
        } else if (peer_choking) {
            return;
        }
        try {
            Iterator<MessageRequest> itor = ourRequests.iterator();
            while (itor.hasNext()) {
                MessageRequest r = itor.next();
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

    public void pushRequestResponse(MessageRequest r, byte[] block) {
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

    public List<MessageRequest> getPeerRequests() {
        List<MessageRequest> q = new ArrayList<MessageRequest>(peerRequests.size());
        for (MessageRequest r : peerRequests) {
            q.add(r);
        }
        return q;
    }

    public MessageRequest[] getActiveRequest() {
        return ourRequests.toArray(new MessageRequest[0]);
    }

    public List<MessageResponse> getPeerResponseBlocks() {
        if (peerSentBlocks.size() < 1) {
            return null;
        }
        List<MessageResponse> r = peerSentBlocks;
        peerSentBlocks = new ArrayList<MessageResponse>();
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
        if (pm.type == MessageType.CHOKE) {
            this.peer_choking = true;
            System.out.println("[STATUS] " + this + " choked us.");
            ourRequests.clear();
        } else if (pm.type == MessageType.UNCHOKE) {
            System.out.println("[STATUS] " + this + " unchoked us.");
            this.peer_choking = false;
        } else if (pm.type == MessageType.INTERESTED) {
            System.out.println("[STATUS] " + this + " intrested in us.");
            this.peer_interested = true;
        } else if (pm.type == MessageType.NOT_INTERESTED) {
            System.out.println("[STATUS] " + this + " not intrested in us.");
            this.peer_interested = false;
        } else if (pm.type == MessageType.HAVE) {
            System.out.println("[STATUS] " + this + " has " + pm.piece);
            if (!peerPieceOrganizer.hasPiece((int) pm.piece)) {
                peerPieceOrganizer.addPieceComplete(pm.piece);
                have++;
            }

        } else if (pm.type == MessageType.BIT_FILED) {
            System.out.println("[STATUS] Got bitmap from " + this);
            if (peerPieceOrganizer.getCompletedPieces() == 0) {
                peerPieceOrganizer.setBitMap(pm.bitfield);
                have = peerPieceOrganizer.getCompletedPieces();
                System.out.println("[STATUS] Has " + have + " of " + peerPieceOrganizer.getNumberOfPieces());
            } else {
                peerPieceOrganizer.setBitMap(pm.bitfield);
            }

        } else if (pm.type == MessageType.REQUEST) {
            peerRequests.add(new MessageRequest(pm.index, pm.begin, pm.length));
            if (am_choking) {
                System.out.println("[STATUS] Received choking request");
            }

        } else if (pm.type == MessageType.CANCEL) {
            peerRequests.remove(new MessageRequest(pm.index, pm.begin, pm.length));
        } else if (pm.type == MessageType.PIECE) {
            System.out.println("[STATUS] Got piece " + pm.index + " from " + this);
            MessageRequest r = new MessageRequest(pm.index, pm.begin, pm.block.length);
            MessageResponse rs = new MessageResponse(pm.index, pm.begin, pm.block);
            if (ourRequests.remove(r)) {
                historySize++;
                download += pm.block.length;
            } else {
                System.out.println("[STATUS] Recieved Piece " + pm.index + "," + pm.begin + "," + pm.length + " but didnt send request!");
            }
            peerSentBlocks.add(rs);
        } else if (pm.type == MessageType.EXTENSION) {
            doExtension(pm.extensionID, pm.extension);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof PeerConnection) {
            PeerConnection m = (PeerConnection) o;
            return m.ip.equals(ip) && m.port == port;
        }
        return false;
    }

    public enum ConnectionState {
        uninitialized,
        pending,
        connected,
        closed
    }
}
