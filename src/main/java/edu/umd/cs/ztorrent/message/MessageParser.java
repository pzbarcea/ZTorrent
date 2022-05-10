package edu.umd.cs.ztorrent.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

public class MessageParser {

    private static final int UNINITIALIZED = -1;
    private static final int KEEPALIVE = 0;
    private static final int LENGTH_PREFIX_SIZE = 4;

    byte[] sizeBuf = new byte [LENGTH_PREFIX_SIZE];
    private final Queue<PeerMessage> queue = new LinkedList<>();
    private byte[] buffer;
    private int off;
    private int size = UNINITIALIZED;

    /**
     * The handshake is a required message and must be the first message transmitted by the client.
     * It is (49+len(pstr)) bytes long.
     * https://wiki.theory.org/index.php/BitTorrentSpecification
     *
     * @param in
     * @return null if handshake wasn't received in full, new HandShake if completed
     *
     * @throws IOException
     */
    public static HandShake readBlockingHandShake(InputStream in) throws IOException {
        boolean finished = false;
        HandShake hs = null;
        int size = -1;
        byte[] buffer = null;
        int readBytes = 0;

        while (!finished) {
            if (size == -1) {
                size = in.read();
                buffer = new byte[48 + size];
                readBytes = 0;
            } else {
                readBytes += in.read(buffer, readBytes, 48 + size - readBytes);
                if (readBytes == 48 + size) {
                    byte[] version = Arrays.copyOfRange(buffer, 0, size);
                    byte[] reserved = Arrays.copyOfRange(buffer, size, size + 8);
                    byte[] hashInfo = Arrays.copyOfRange(buffer, size + 8, size + 8 + 20);
                    byte[] peerID = Arrays.copyOfRange(buffer, size + 8 + 20, size + 8 + 20 + 20);
                    hs = new HandShake(peerID, hashInfo, version, reserved);
                    finished = true;
                }
            }
        }
        return hs;
    }

    private static int bytesToInt(byte[] data, int offset) {
        return ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);
    }

    /**
     * Parse input stream into messages, append messages to message queue.
     * @throws IOException
     */
    public void parseMessages(InputStream in) throws IOException {
        while (in.available() > 0) {
            if (size == -1) {
                off += in.read(sizeBuf, off, 4 - off);
                if (off == 4) {
                    off = 0;
                    size = bytesToInt(sizeBuf, 0);
                    buffer = new byte[size];

                    if (size > 1024 * 1024 * 10 || size < 0) {
                        throw new IOException("[ERROR]: IOException");
                    }
                }
            } else {
                try {
                    off += in.read(buffer, off, size - off);
                } catch (Exception e) {
                    throw new IOException("[ERROR]: IOException");
                }

                if (off == size) {
                    // If received a valid message that isn't keep-alive, append message to queue
                    if (size != KEEPALIVE) {
                        PeerMessage pm = buildFromByte(buffer);
                        if (pm != null)
                            queue.add(pm);
                    }
                    off = 0;
                    size = -1;
                }
            }
        }
    }

    /**
     * Reads an incoming Handshake until complete.
     * @param in
     * @return null if not yet complete header
     * 		   HandShake if complete.
     * @throws IOException
     */
    public HandShake readHandShake(InputStream in) throws IOException {
        while (in.available() > 0) {
            if (size == -1) {
                size = in.read();
                buffer = new byte[48 + size];
                off = 0;
            } else {
                off += in.read(buffer, off, 48 + size - off);
                if (off == 48 + size) {
                    byte[] version = Arrays.copyOfRange(buffer, 0, size);
                    byte[] reserved = Arrays.copyOfRange(buffer, size, size + 8);
                    byte[] hashInfo = Arrays.copyOfRange(buffer, size + 8, size + 8 + 20);
                    byte[] peerID = Arrays.copyOfRange(buffer, size + 8 + 20, size + 8 + 20 + 20);
                    HandShake hs = new HandShake(peerID, hashInfo, version, reserved);
                    off = 0;
                    size = -1;
                    return hs;
                }
            }
        }
        return null;
    }

    private static long bytesToUnsignedInt(byte[] data, int offset) {
        return (((long) data[offset++] & 0xFF) << 24) |
                (((long) data[offset++] & 0xFF) << 16) |
                (((long) data[offset++] & 0xFF) << 8) |
                ((long) data[offset++] & 0xFF);
    }

    /**
     * keep-alive: <len=0000>
     *     The keep-alive message is a message with zero bytes, specified with the length prefix set to zero.
     *     There is no message ID and no payload.
     * choke: <len=0001><id=0>
     * unchoke: <len=0001><id=1>
     * interested: <len=0001><id=2>
     * not interested: <len=0001><id=3>
     * have: <len=0005><id=4><piece index>
     *     The have message is fixed length.
     *     The payload is the zero-based index of a piece that has just been successfully downloaded and verified via the hash.
     * bitfield: <len=0001+X><id=5><bitfield>
     *     The bitfield message is variable length, where X is the length of the bitfield.
     *     The payload is a bitfield representing the pieces that have been successfully downloaded.
     *     The high bit in the first byte corresponds to piece index 0.
     *     Bits that are cleared indicated a missing piece, and set bits indicate a valid and available piece.
     *     Spare bits at the end are set to zero.
     * request: <len=0013><id=6><index><begin><length>
     *     index: integer specifying the zero-based piece index
     *     begin: integer specifying the zero-based byte offset within the piece
     *     length: integer specifying the requested length.
     * piece: <len=0009+X><id=7><index><begin><block>
     *     index: integer specifying the zero-based piece index
     *     begin: integer specifying the zero-based byte offset within the piece
     *     block: block of data, which is a subset of the piece specified by index.
     * cancel: <len=0013><id=8><index><begin><length>
     * port: <len=0003><id=9><listen-port>
     *
     * @param buffer2
     * @return
     */
    private PeerMessage buildFromByte(byte[] buffer2) {
        PeerMessage PM;
        switch (buffer2[0]) {
            case 0:
                return PM = new PeerMessage(MessageType.CHOKE);
            case 1:
                return PM = new PeerMessage(MessageType.UNCHOKE);
            case 2:
                return PM = new PeerMessage(MessageType.INTERESTED);
            case 3:
                return PM = new PeerMessage(MessageType.NOT_INTERESTED);
            case 4:
                PM = new PeerMessage(MessageType.HAVE);

                PM.piece = bytesToUnsignedInt(buffer2, 1);
                return PM;
            case 5:
                PM = new PeerMessage(MessageType.BIT_FILED);
                PM.bitfield = Arrays.copyOfRange(buffer2, 1, buffer2.length);
                return PM;
            case 6:
                PM = new PeerMessage(MessageType.REQUEST);
                PM.index = bytesToUnsignedInt(buffer2, 1);
                PM.begin = bytesToUnsignedInt(buffer2, 5);
                PM.length = bytesToUnsignedInt(buffer2, 9);
                return PM;
            case 7:
                PM = new PeerMessage(MessageType.PIECE);
                PM.index = bytesToUnsignedInt(buffer2, 1);
                PM.begin = bytesToUnsignedInt(buffer2, 5);
                PM.block = Arrays.copyOfRange(buffer2, 9, buffer2.length);
                return PM;
            case 8:
                PM = new PeerMessage(MessageType.CANCEL);
                PM.index = bytesToUnsignedInt(buffer2, 1);
                PM.begin = bytesToUnsignedInt(buffer2, 5);
                PM.length = bytesToUnsignedInt(buffer2, 9);
                return PM;
            case 20:
                PM = new PeerMessage(MessageType.EXTENSION);
                PM.extensionID = buffer2[1];
                PM.extension = Arrays.copyOfRange(buffer2, 2, buffer2.length);
                return PM;
            default:
                throw new RuntimeException("[ERROR]: RuntimeException, unknown id received in message parser: " + buffer2[0]);
        }
    }

    public boolean hasMessage() {
        return !queue.isEmpty();
    }

    public PeerMessage getNext() {
        return queue.poll();
    }

    /**
     * keep-alive: <len=0000>
     * The keep-alive message is a message with zero bytes, specified with the length prefix set to zero.
     * There is no message ID and no payload.
     * Peers may closeFile a connection if they receive no messages (keep-alive or any other message) for a certain period of time,
     * so a keep-alive message must be sent to maintain the connection alive if no command have been sent for a given amount of time.
     * This amount of time is generally two minutes.
     * @param os
     * @param infoHash
     * @param peerID
     * @throws IOException
     */
    public void sendHandShake(OutputStream os, byte[] infoHash, byte[] peerID) throws IOException {
        byte[] sptr = "BitTorrent protocol".getBytes(StandardCharsets.UTF_8);
        byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};//eight (8) reserved bytes. All current implementations use all zeroes
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(19);
        bo.write(sptr);
        bo.write(reserved);
        bo.write(infoHash);
        bo.write(peerID);
        byte[] o = bo.toByteArray();
        if (o.length != 68) {
            throw new RuntimeException("Length incorrect.");
        }
        os.write(o);
    }

    /*
     *
     * All of the remaining messages in the protocol take the form of <length prefix><message ID><payload>.
     * The length prefix is a four byte big-endian value.
     * The message ID is a single decimal byte. The payload is message dependent.
     * https://wiki.theory.org/index.php/BitTorrentSpecification
     *
     */

    /**
     * choke: <len=0001><id=0>
     * capacity: 4 + 1
     *
     * @param os
     * @throws IOException
     */
    public void choke(OutputStream os) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.putInt(1);
        b.put((byte) 0);
        os.write(b.array());
    }

    /**
     * unchoke: <len=0001><id=1>
     * capacity: 4 + 1
     *
     * @param os
     * @throws IOException
     */
    public void unchoke(OutputStream os) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.putInt(1);
        b.put((byte) 1);
        os.write(b.array());
    }

     /**
     * interested: <len=0001><id=2>
     * capacity: 4 + 1
     *
     * @param os
     * @throws IOException
     */
    public void interested(OutputStream os) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.putInt(1);
        b.put((byte) 2);
        os.write(b.array());
    }

    /**
     * not interested: <len=0001><id=3>
     * capacity: 4 + 1
     *
     * @param os
     * @throws IOException
     */
    public void not_interested(OutputStream os) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.putInt(1);
        b.put((byte) 3);
        os.write(b.array());
    }

    /**
     * have: <len=0005><id=4><piece index>
     * capacity: 4 + 1 + 4
     *
     * @param os
     * @param index
     * @throws IOException
     */
    public void have(OutputStream os, long index) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(9);
        b.putInt(5);
        b.put((byte) 4);
        b.putInt((int) index);
        os.write(b.array());
    }

    /**
     * bitfield: <len=0001+X><id=5><bitfield>
     * capacity: 4 + 1 + bitfield.length
     *
     * @param os       - the OutputStream to be written to
     * @param bitfield
     * @throws IOException
     */
    public void bitfield(OutputStream os, byte[] bitfield) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5 + bitfield.length);
        b.putInt(1 + bitfield.length);
        b.put((byte) 5);
        b.put(bitfield);
        os.write(b.array());
    }

    /**
     * request: <len=0013><id=6><index><begin><length>
     * capacity: 4 + 1 + 4 + 4 + 4 = 17
     */
    public void request(OutputStream os, long index, long begin, long length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(17);
        b.putInt(13);
        b.put((byte) 6);
        b.putInt((int) index);
        b.putInt((int) begin);
        b.putInt((int) length);

        os.write(b.array());
    }

    /**
     * piece: <len=0009+X><id=7><index><begin><block>
     * capacity: 4 + 1 + 4 + 4 = 13
     *
     * @param os
     * @param index
     * @param begin
     * @param block
     * @throws IOException
     */
    public void piece(OutputStream os, long index, long begin, byte[] block) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(13 + block.length);
        b.putInt(9 + block.length);
        b.put((byte) 7);
        b.putInt((int) index);
        b.putInt((int) begin);
        b.put(block);
        os.write(b.array());
    }

    /**
     * cancel: <len=0013><id=8><index><begin><length>
     * capacity: 4 + 1 + 4 + 4 + 4
     *
     * @param os
     * @param index
     * @param begin
     * @param length
     * @throws IOException
     */
    public void cancel(OutputStream os, long index, long begin, long length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(17);
        b.putInt(13);
        b.put((byte) 8);
        b.putInt((int) index);
        b.putInt((int) begin);
        b.putInt((int) length);
        os.write(b.array());
    }

    public void extension(OutputStream os, byte[] pushIT) throws IOException {
        os.write(pushIT);
    }

    public static class HandShake {
        public final byte[] peerID, hashInfo, version, reserved;

        public HandShake(byte[] peerID, byte[] hashInfo, byte[] version, byte[] reserved) {
            this.peerID = peerID;
            this.hashInfo = hashInfo;
            this.version = version;
            this.reserved = reserved;
        }
    }
}
