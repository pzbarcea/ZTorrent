package edu.umd.cs.ztorrent;


public class ByteUtils {

    public static long readUnsignedInt(byte[] data, int offset) {
        return (((long) data[offset++] & 0xFF) << 24) |
                (((long) data[offset++] & 0xFF) << 16) |
                (((long) data[offset++] & 0xFF) << 8) |
                ((long) data[offset++] & 0xFF);
    }

    public static int readInt(byte[] data, int offset) {
        return ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);
    }

    public static short readShort(byte[] data, int offset) {
        return (short) ((short) ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF));
    }
}
