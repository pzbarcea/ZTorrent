package edu.umd.cs.ztorrent.message;

public class MessageResponse {
    public final int index, begin;
    public final byte[] block;

    public MessageResponse(int index, int begin, byte[] block) {
        this.index = index;
        this.begin = begin;
        this.block = block;
    }

    public MessageResponse(long index, long begin, byte[] block) {
        this.index = (int) index;
        this.begin = (int) begin;
        this.block = block;
    }
}
