package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;
import edu.umd.cs.ztorrent.message.MessageParser;
import edu.umd.cs.ztorrent.message.MessageParser.HandShake;
import edu.umd.cs.ztorrent.message.PeerMessage;
import edu.umd.cs.ztorrent.message.MessageRequest;
import edu.umd.cs.ztorrent.message.MessageType;
import edu.umd.cs.ztorrent.protocol.PeerConnection.ConnectionState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

/**
 * Connection for sending metadata across peers
 */
public class TorrentConnection {
    public final int port;
    public final InetAddress ip;
    protected boolean am_choking = true;
    protected boolean am_interested = false;
    protected boolean peer_choking = true;
    protected boolean peer_interested = false;
    protected byte[] peerID;
    protected byte[] version;
    protected byte[] infoHash;
    protected ConnectionState conState;
    protected boolean recvHandShake;
    protected boolean sentHandShake;
    protected MessageParser mp;
    protected OutputStream sockOut;
    protected InputStream sockIn;
    protected Socket sock;
    protected long maintenance;
    protected byte[] announcedMap;
    protected Set<MessageRequest> peerRequests;
    protected Map<String, Integer> extensions;
    protected Map<Integer, String> revExtensions;
    protected int metaSize = -1;
    protected String name = "Unknown";
    protected boolean connectionSupportsMeta = false;
    protected boolean connectionSupportsMetaMeta = false;
    protected MetaData metaData;
    protected int max_reported = -1;
    private boolean isMetaConnection = false;
    private final Set<Integer> metaMetaRequests = new HashSet<Integer>();

    public TorrentConnection(InetAddress ip, int port) {
        this.ip = ip;
        this.port = port;
        mp = new MessageParser();
        recvHandShake = false;
        sentHandShake = false;
        conState = ConnectionState.uninitialized;
        maintenance = 0;
    }

    public void blindInitialize(MetaData md) {
        this.metaData = md;
        isMetaConnection = true;
        conState = ConnectionState.pending;
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
                    e.printStackTrace();
                    conState = ConnectionState.closed;
                }
            }
        }.start();
    }


    public ConnectionState getConnectionState() {
        return conState;
    }

    public final void doWork(Torrent t) throws IOException {
        if (conState == ConnectionState.closed || conState == ConnectionState.uninitialized) {
            throw new RuntimeException("Invalid request. Cant do work on closed/uninitialized connections");
        }

        if (conState == ConnectionState.pending) {
            return;
        }
        if (conState == ConnectionState.connected && sock.isClosed()) {
            conState = ConnectionState.closed;
        }
        try {
            if (!sentHandShake) {
                sentHandShake = true;
                mp.sendHandShake(sockOut, t.hashInfo, t.peerID);
                System.out.println("Sent hand shake");
            }

            if (!recvHandShake) {

                HandShake hs = mp.readHandShake(sockIn);
                if (hs != null) {
                    if (peerID != null && !Arrays.equals(hs.peerID, peerID)) {
                        System.out.println("WARNING: PRE CONNECTION PEER-ID MISMATCH!");
                    }

                    infoHash = hs.hashInfo;
                    version = hs.version;
                    peerID = hs.peerID;


                    if (!Arrays.equals(hs.hashInfo, t.hashInfo)) {
                        System.out.println("INFO HASH DONT MATCH!");
                        conState = ConnectionState.closed;
                        sock.close();
                        return;
                    } else {
                        conState = ConnectionState.connected;

                    }

                    if ((version[5] & 0x10) > 0) {
                        connectionSupportsMeta = true;
                    }

                    if (!isMetaConnection) {
                        mp.bitfield(sockOut, announcedMap);
                    }
                    recvHandShake = true;
                    System.out.println("GOT HAND SHAKE!");
                }
            } else {
                mp.parseMessages(sockIn);
                while (mp.hasMessage()) {
                    PeerMessage pm = mp.getNext();
                    doDataIn(pm);
                }

            }
        } catch (Exception e) {
            conState = ConnectionState.closed;
            e.printStackTrace();
        }
    }

    protected void doExtension(int id, byte[] msg) {
        try {

            if (id == 0) {
                Bencoder b = new Bencoder(msg);
                Bencoder m = b.dictionary.get("m");
                extensions = new HashMap<String, Integer>();
                revExtensions = new HashMap<Integer, String>();
                for (String s : m.dictionary.keySet()) {
                    System.out.println(s + ":" + (int) (long) m.dictionary.get(s).integer);
                    extensions.put(s, (int) (long) m.dictionary.get(s).integer);
                    revExtensions.put((int) (long) m.dictionary.get(s).integer, s);
                }

                if (b.dictionary.containsKey("metadata_size")) {
                    metaSize = (int) (long) b.dictionary.get("metadata_size").integer;
                    if (metaSize <= 0) {
                        metaSize = -1;
                    } else {
                        connectionSupportsMetaMeta = true;
                        metaData.setSize(metaSize);
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
                        if (metaData.isComplete()) {
                            byte[] p = metaData.getPiece(piece);
                            maintenance += p.length;
                            mp.extension(sockOut, TorrentExtensions.pushMetaDataPiece(ourExtension, piece, p));
                        } else {
                            maintenance += 20;
                            mp.extension(sockOut, TorrentExtensions.rejectMetaDataPiece(ourExtension, piece));
                        }
                    //Represents a response
                    } else if (i == 1) {
                        metaMetaRequests.remove(piece);
                        metaData.add(piece, left);
                    } else {
                        connectionSupportsMetaMeta = false;
                    }
                } else {
                    System.out.println("Unsupported protocol message: " + e);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Couldnt decode bencoded dictionary!");
        }

    }

    protected void doDataIn(PeerMessage pm) {
        if (pm.type == MessageType.CHOKE) {
            this.peer_choking = true;
            System.out.println(this + " choked us.");
        } else if (pm.type == MessageType.UNCHOKE) {
            System.out.println(this + " unchoked us.");
            this.peer_choking = false;
        } else if (pm.type == MessageType.INTERESTED) {
            System.out.println(this + " interested in us.");
            this.peer_interested = true;
        } else if (pm.type == MessageType.NOT_INTERESTED) {
            System.out.println(this + " not interested in us.");
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
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send choke state on 'connected' connections");
        }
        try {
            if (am_choking != t) {
                if (t) {
                    mp.choke(sockOut);
                    if (peerRequests != null)
                        peerRequests.clear();
                } else {
                    mp.unchoke(sockOut);
                }
                am_choking = t;
                maintenance += 5;
            }
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
    }

    public void setAmInterested(boolean t) {
        if (conState != ConnectionState.connected) {
            throw new RuntimeException("Can only send choke on 'connected' connections");
        }
        try {

            if (am_interested != t) {
                if (t) {
                    mp.interested(sockOut);

                } else {
                    mp.not_interested(sockOut);
                }
                maintenance += 5;
                am_interested = t;
            }
        } catch (IOException e) {
            conState = ConnectionState.closed;
        }
    }

    public void tearDown() throws IOException {
        sock.close();
        conState = ConnectionState.closed;
    }

    @Override
    /***
     * Returns a string representation of the connection, depending on if we have a peerID
     */
    public String toString() {
        if (peerID != null) {
            BigInteger bi = new BigInteger(1, peerID);
            return String.format("%0" + (peerID.length << 1) + "X", bi);
        } else {
            return (ip.toString() + ":" + port);
        }
    }
}
