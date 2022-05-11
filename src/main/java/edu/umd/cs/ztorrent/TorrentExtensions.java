package edu.umd.cs.ztorrent;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class TorrentExtensions {

    /**
     * Fast Peers Extensions
     * <len:0002 + X><id=20><extensionId=id><block>
     * capacity: 4 + 1 + 1
     *
     * @param id
     * @param block
     * @return
     * @TODO: Test Extension
     */
    public static byte[] constructExtention(byte id, byte[] block) {
        ByteBuffer b = ByteBuffer.allocate(6 + block.length);
        b.putInt(block.length + 2);
        b.put((byte) 20);
        b.put(id);
        b.put(block);
        return b.array();
    }

    public static byte[] pushMetaDataPiece(byte id, int piece, byte[] block) {
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
        return constructExtention(id, b.toByteArray());//quite wasteful. but im lazy :-)
    }

    public static byte[] rejectMetaDataPiece(byte id, int piece) {
        Bencoder b = new Bencoder();
        b.type = BencodeType.Dictionary;
        b.dictionary = new HashMap<String, Bencoder>();
        b.dictionary.put("msg_type", new Bencoder(2));
        b.dictionary.put("piece", new Bencoder(piece));
        return constructExtention(id, b.toByteArray());
    }


}
