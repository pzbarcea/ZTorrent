package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;
import edu.umd.cs.ztorrent.message.MessageParser;
import edu.umd.cs.ztorrent.message.MessageParser.HandShake;
import edu.umd.cs.ztorrent.message.PeerMessage;
import edu.umd.cs.ztorrent.message.MessageRequest;
import edu.umd.cs.ztorrent.message.MessageType;
import edu.umd.cs.ztorrent.protocol.PeerConnection.ConnectionStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;

public class TorrentConnection {
    public int port;
    public InetAddress ip;
    public boolean am_choking = true;
    public boolean am_interested = false;
    public boolean peer_choking = true;
    public boolean peer_interested = false;
    public byte[] peerID;
    public byte[] version;
    public byte[] infoHash;
    public ConnectionStatus connectionStatus;
    public boolean recvHandShake;
    public boolean sentHandShake;
    public MessageParser messageParser;
    public OutputStream sockOut;
    public InputStream sockIn;
    public Socket sock;
    public long maintenance;
    public byte[] announcedMap;
    public Set<MessageRequest> peerRequests;
    public Map<String, Integer> extensions;
    public Map<Integer, String> revExtensions;
    public int metaSize = -1;
    public String name = "STRANGER";
    public boolean connectionSupportsMeta = false;
    public boolean connectionSupportsMetaMeta = false;
    public TorrentInfo torrentInfo;
    public int max_reported = -1;
    private boolean isMetaConnection = false;
    private Set<Integer> mRequests = new HashSet<>();

    public TorrentConnection(InetAddress ip, int port) {
        this.ip = ip;
        this.port = port;
        messageParser = new MessageParser();
        recvHandShake = false;
        sentHandShake = false;
        connectionStatus = ConnectionStatus.uninitialized;
        maintenance = 0;
    }

    public void blindInitialize(TorrentInfo md) {
        this.torrentInfo = md;
        isMetaConnection = true;
        connectionStatus = ConnectionStatus.waiting;
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
                    e.printStackTrace();
                    connectionStatus = ConnectionStatus.closed;
                }
            }
        }.start();
    }


    public ConnectionStatus getConnectionState() {
        return connectionStatus;
    }

    public final void doWork(Torrent t) throws IOException {
        if (connectionStatus == ConnectionStatus.closed || connectionStatus == ConnectionStatus.uninitialized) {
            throw new RuntimeException("[ERROR] Connection CLOSED  or UNINITIALIZED");
        }

        if (connectionStatus == ConnectionStatus.waiting) {
            return;
        }
        if (connectionStatus == ConnectionStatus.connected && sock.isClosed()) {
            connectionStatus = ConnectionStatus.closed;
        }
        try {
            if (!sentHandShake) {
                sentHandShake = true;
                messageParser.sendHandShake(sockOut, t.hashInfo, t.peerID);
                System.out.println("[HANDSHAKE] Handshake SENT");
            }

            if (!recvHandShake) {

                HandShake hs = messageParser.readHandShake(sockIn);
                if (hs != null) {
                    if (peerID != null && !Arrays.equals(hs.peerID, peerID)) {
                        System.out.println("[MISMATCH] Peer ID mismatch");
                    }

                    infoHash = hs.hashInfo;
                    version = hs.version;
                    peerID = hs.peerID;


                    if (!Arrays.equals(hs.hashInfo, t.hashInfo)) {
                        System.out.println("[MISMATCH] Info hash doesn't match");
                        connectionStatus = ConnectionStatus.closed;
                        sock.close();
                        return;
                    } else {
                        connectionStatus = ConnectionStatus.connected;

                    }

                    if ((version[5] & 0x10) > 0) {
                        connectionSupportsMeta = true;
                    }

                    if (!isMetaConnection) {
                        messageParser.bitfield(sockOut, announcedMap);
                    }
                    recvHandShake = true;
                    System.out.println("[HANDSHAKE] Handshake RECEIVED");
                }
            } else {
                messageParser.parseMessages(sockIn);
                while (messageParser.hasMessage()) {
                    PeerMessage pm = messageParser.getNext();
                    doDataIn(pm);
                }

            }
        } catch (Exception e) {

            connectionStatus = ConnectionStatus.closed;
            e.printStackTrace();
        }
    }

    protected void doExtension(int id, byte[] msg) {
        try {

            if (id == 0) {
                Bencoder b = new Bencoder(msg);
                Bencoder m = b.dictionary.get("m");
                extensions = new HashMap<>();
                revExtensions = new HashMap<>();
                for (String s : m.dictionary.keySet()) {
                    extensions.put(s, (int) (long) m.dictionary.get(s).integer);
                    revExtensions.put((int) (long) m.dictionary.get(s).integer, s);
                }

                if (b.dictionary.containsKey("metadata_size")) {
                    metaSize = (int) (long) b.dictionary.get("metadata_size").integer;
                    if (metaSize <= 0) {
                        metaSize = -1;
                    } else {
                        connectionSupportsMetaMeta = true;
                        torrentInfo.setSize(metaSize);
                    }
                }
                if (extensions.containsKey("v")) {
                    name = b.dictionary.get("v").getString();
                }

                if (extensions.containsKey("reqq")) {
                    max_reported = (int) (long) b.dictionary.get("reqq").integer;
                }

            } else {
                String e = revExtensions.get(id);
                if (e.equals("ut_metadata")) {
                    byte ourExtension = 3;
                    Bencoder b = new Bencoder();
                    int off = Bencoder.getBencoding(msg, 0, msg.length, b);
                    long i = b.dictionary.get("msg_type").integer;
                    int piece = (int) (long) b.dictionary.get("piece").integer;
                    byte[] left = new byte[msg.length - off];
                    System.arraycopy(msg, off, left, 0, left.length);

                    //Represents a request
                    if (i == 0) {
                        if (torrentInfo.isComplete()) {
                            byte[] p = torrentInfo.getPiece(piece);
                            maintenance += p.length;
                            messageParser.extension(sockOut, TorrentHelper.pushPiece(ourExtension, piece, p));
                        } else {
                            maintenance += 20;
                            messageParser.extension(sockOut, TorrentHelper.rejectPiece(ourExtension, piece));
                        }
                    //Represents a response
                    } else if (i == 1) {
                        mRequests.remove(piece);
                        torrentInfo.add(piece, left);
                    } else {
                        connectionSupportsMetaMeta = false;
                    }
                } else {
                    System.out.println("[ERROR] Protocol UNSUPPORTED: " + e);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("[ERROR] Couldn't decode bencoded dictionary!");
        }

    }

    protected void doDataIn(PeerMessage pm) {
        if (pm.type == MessageType.CHOKE) {
            this.peer_choking = true;
            System.out.println("[CHOKED]" + this + " choked us.");
        } else if (pm.type == MessageType.UNCHOKE) {
            System.out.println("[UNCHOKED]" + this + " unchoked us.");
            this.peer_choking = false;
        } else if (pm.type == MessageType.INTERESTED) {
            System.out.println("[INTERESTED]" + this + " interested in us.");
            this.peer_interested = true;
        } else if (pm.type == MessageType.NOT_INTERESTED) {
            System.out.println("[DISINTERESTED]" + this + " not interested in us.");
            this.peer_interested = false;
        } else if (pm.type == MessageType.EXTENSION) {
            doExtension(pm.extensionID, pm.extension);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TorrentConnection) {
            TorrentConnection m = (TorrentConnection) o;
            return m.ip.equals(ip) && m.port == port;
        }
        return false;
    }

    public PeerConnection toManagedConnection(Torrent t) {
        return null;
    }

    public boolean amChoking() {
        return am_choking;
    }

    public boolean amInterested() {
        return am_interested;
    }

    public boolean peerChoking() {
        return peer_choking;
    }

    public boolean peerInterested() {
        return peer_interested;
    }

    public void setAmChoking(boolean t) {
        if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] only established connection can send CHOKE message");
        }
        try {
            if (am_choking != t) {
                if (t) {
                    messageParser.choke(sockOut);
                    if (peerRequests != null)
                        peerRequests.clear();
                } else {
                    messageParser.unchoke(sockOut);
                }
                am_choking = t;
                maintenance += 5;
            }
        } catch (IOException e) {
            connectionStatus = ConnectionStatus.closed;
        }
    }

    public void setAmInterested(boolean t) {
        if (connectionStatus != ConnectionStatus.connected) {
            throw new RuntimeException("[ERROR] only established connection can send INTERESTED message");
        }
        try {

            if (am_interested != t) {
                if (t) {
                    messageParser.interested(sockOut);

                } else {
                    messageParser.not_interested(sockOut);
                }
                maintenance += 5;
                am_interested = t;
            }
        } catch (IOException e) {
            connectionStatus = ConnectionStatus.closed;
        }
    }

    public void tearDown() throws IOException {
        sock.close();
        connectionStatus = ConnectionStatus.closed;
    }

    @Override
    public String toString() {
        if (peerID != null) {
            BigInteger bi = new BigInteger(1, peerID);
            return String.format("%0" + (peerID.length << 1) + "X", bi);
        } else {
            return (ip.toString() + ":" + port);
        }
    }

    public static class TorrentHelper {
        public static byte[] pushPiece(byte id, int piece, byte[] block) {
            Bencoder b = new Bencoder();
            b.type = BencodeType.Dictionary;
            b.dictionary = new HashMap<String, Bencoder>();
            b.dictionary.put("msg_type", new Bencoder(1));
            b.dictionary.put("piece", new Bencoder(piece));
            b.dictionary.put("total_size", new Bencoder(block.length));
            byte[] dic = b.toByteArray();
            byte[] r = new byte[dic.length + block.length];
            System.arraycopy(dic, 0, r, 0, dic.length);
            System.arraycopy(block, 0, r, dic.length, block.length);
            return buildExtension(id, b.toByteArray());//quite wasteful. but im lazy :-)
        }

        public static byte[] rejectPiece(byte id, int piece) {
            Bencoder b = new Bencoder();
            b.type = BencodeType.Dictionary;
            b.dictionary = new HashMap<String, Bencoder>();
            b.dictionary.put("msg_type", new Bencoder(2));
            b.dictionary.put("piece", new Bencoder(piece));
            return buildExtension(id, b.toByteArray());
        }
        public static byte[] buildExtension(byte id, byte[] block) {
            ByteBuffer b = ByteBuffer.allocate(6 + block.length);
            b.putInt(block.length + 2);
            b.put((byte) 20);
            b.put(id);
            b.put(block);
            return b.array();
        }
    }
}
