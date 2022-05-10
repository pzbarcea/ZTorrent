package edu.umd.cs.ztorrent.message;

public class PeerMessage {
    public final MessageType type;
    public byte[] bitfield;
    public long piece;
    public long index;
    public long begin;
    public long length;
    public byte[] block;
    public int extensionID;
    public byte[] extension;

    public PeerMessage(MessageType type) {
        this.type = type;
    }
}
