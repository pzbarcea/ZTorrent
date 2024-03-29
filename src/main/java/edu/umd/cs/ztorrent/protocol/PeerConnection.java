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
    private long completed = 0;
    private final MessageParser mp;
    private long download;
    private long upload;
    private PieceOrganizer peerPieceOrganizer;
    private int max_queued = 3;

    public PeerConnection(InetAddress ip, int port) {
        super(ip, port);
        download = upload = maintenance = 0;
        mp = new MessageParser();
        peerRequests = new HashSet<>();
        ourRequests = new HashSet<>();
        peerSentBlocks = new ArrayList<>();
        recvHandShake = false;
        connectionStatus = ConnectionStatus.uninitialized;
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
        torrentInfo = t.info;
        if (connectionStatus != ConnectionStatus.uninitialized) {
            throw new RuntimeException("[ERROR] Currently Uninitialized");
        }
        connectionStatus = ConnectionStatus.waiting;
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
                            connectionStatus = ConnectionStatus.connected;
                        } else {
                            connectionStatus = ConnectionStatus.closed;
                        }
                    } catch (IOException e) {
                        System.out.println("[REFUSED] Connection refused from: " + ip + ":" + port);
                        e.printStackTrace();
                        connectionStatus = ConnectionStatus.closed;
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

            connectionStatus = ConnectionStatus.connected;
            sentHandShake = true;
            try {
                mp.sendHandShake(sockOut, t.getInfoHash(), t.getPeerID());
                mp.bitfield(sockOut, announcedMap);
            } catch (IOException io) {
                connectionStatus = ConnectionStatus.closed;
            }
        } else {
            throw new RuntimeException("[ERROR] Connection state: UNKNOWN");
        }

        if (announcedMap == null || announcedMap.length != peerPieceOrganizer.getLength()) {
            throw new RuntimeException("[WARNING] BAD peerorganizer - from announce");
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
        if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] Cannot send if not connected");
        } else if (peer_choking) {
            throw new RuntimeException("[ERROR] Cannot send to choked connection");
        }
        if (ourRequests.contains(r.index)) {
            return false;
        }
        if (ourRequests.size() + 1 > max_queued) {
            throw new RuntimeException("[ERROR] Queue too big ");
        }

        ourRequests.add(r);
        try {
            maintenance += 17;
            mp.request(sockOut, r.index, r.begin, r.len);
        } catch (IOException e) {
            connectionStatus = ConnectionStatus.closed;
        }
        return true;
    }

    public void cancelPiece(Piece p) {
        if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] Cant send request if not connected");
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
            connectionStatus = ConnectionStatus.closed;
        }
    }

    public void pushRequestResponse(MessageRequest r, byte[] block) {
        if (!peerRequests.remove(r)) {
            throw new RuntimeException("[ERROR] Unrequested Block");
        } else if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] Invalid Connection");
        } else if (peer_choking) {
            throw new RuntimeException("[ERROR] Peer Choking");
        }
        try {
            maintenance += 13;
            upload += block.length;
            mp.piece(sockOut, r.index, r.begin, block);
        } catch (IOException e) {
            connectionStatus = ConnectionStatus.closed;
        }
    }

    public void pushHave(int index) {
        if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] Trying to send on unconnected");
        } else if (!peer_interested) {
            throw new RuntimeException("[ERROR] Trying to send to uninterested peer");
        }
        try {
            maintenance += 10;
            mp.have(sockOut, index);
        } catch (IOException e) {
            connectionStatus = ConnectionStatus.closed;
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
        if (connectionStatus == ConnectionStatus.uninitialized) {
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
        long i = completed;
        completed = 0;
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
            System.out.println("[CHOKED] " + this + " has choked us");
            ourRequests.clear();
        } else if (pm.type == MessageType.UNCHOKE) {
            System.out.println("[UNCHOKED] " + this + " has unchoked us");
            this.peer_choking = false;
        } else if (pm.type == MessageType.INTERESTED) {
            System.out.println("[INTERESTED] " + this + " is interested");
            this.peer_interested = true;
        } else if (pm.type == MessageType.NOT_INTERESTED) {
            System.out.println("[DISINTERESTED] " + this + " is NOT interested");
            this.peer_interested = false;
        } else if (pm.type == MessageType.HAVE) {
            System.out.println("[PIECE NOTIFY] " + this + " has Piece #" + pm.piece);
            if (!peerPieceOrganizer.hasPiece((int) pm.piece)) {
                peerPieceOrganizer.addPieceComplete(pm.piece);
                completed++;
            }

        } else if (pm.type == MessageType.BIT_FILED) {
            System.out.println("[STATUS] Received PieceOrganizer from " + this);
            if (peerPieceOrganizer.getCompletedPieces() == 0) {
                peerPieceOrganizer.setOrganizer(pm.bytes);
                completed = peerPieceOrganizer.getCompletedPieces();
                System.out.println("[UPDATE] Completed " + completed + " of " + peerPieceOrganizer.getNumberOfPieces());
            } else {
                peerPieceOrganizer.setOrganizer(pm.bytes);
            }

        } else if (pm.type == MessageType.REQUEST) {
            peerRequests.add(new MessageRequest(pm.index, pm.begin, pm.length));
            if (am_choking) {
                System.out.println("[STATUS] Received choking request");
            }

        } else if (pm.type == MessageType.CANCEL) {
            peerRequests.remove(new MessageRequest(pm.index, pm.begin, pm.length));
        } else if (pm.type == MessageType.PIECE) {
            System.out.println("[RECEIVED] Piece #" + pm.index + " from peer:" + this);
            MessageRequest r = new MessageRequest(pm.index, pm.begin, pm.block.length);
            MessageResponse rs = new MessageResponse(pm.index, pm.begin, pm.block);
            if (ourRequests.remove(r)) {
                historySize++;
                download += pm.block.length;
            } else {
                System.out.println("[RECV NO REQUEST] Received Piece #" + pm.index);
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

    public enum ConnectionStatus {
        uninitialized,
        waiting,
        connected,
        closed
    }
}
