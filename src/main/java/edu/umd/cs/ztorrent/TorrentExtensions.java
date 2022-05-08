package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.Bencoding.Type;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Message constructors and parsers for torrent extensions
 * -Magnet links
 * - DHT
 *
 * @author pzbarcea
 */
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
    public final static byte[] constructExtention(byte id, byte[] block) {
        ByteBuffer b = ByteBuffer.allocate(6 + block.length);
        b.putInt(block.length + 2);
        b.put((byte) 20);
        b.put(id);
        b.put(block);
        return b.array();
    }

    /////////////////// "Extension for Peers to Send Metadata Files" /////////////////////
    public final static byte[] getMetaDataPiece(byte id, int piece) {
        Bencoding b = new Bencoding();
        b.type = Type.Dictionary;
        b.dictionary = new HashMap<String, Bencoding>();
        b.dictionary.put("msg_type", new Bencoding(0));
        b.dictionary.put("piece", new Bencoding(piece));
        return constructExtention(id, b.toByteArray());
    }

    public final static byte[] pushMetaDataPiece(byte id, int piece, byte[] block) {
        Bencoding b = new Bencoding();
        b.type = Type.Dictionary;
        b.dictionary = new HashMap<String, Bencoding>();
        b.dictionary.put("msg_type", new Bencoding(1));
        b.dictionary.put("piece", new Bencoding(piece));
        b.dictionary.put("total_size", new Bencoding(block.length));
        byte[] dic = b.toByteArray();
        byte[] r = new byte[dic.length + block.length];
        System.arraycopy(dic, 0, r, 0, dic.length);
        System.arraycopy(block, 0, r, dic.length, block.length);
        return constructExtention(id, b.toByteArray());//quite wasteful. but im lazy :-)
    }

    public final static byte[] rejectMetaDataPiece(byte id, int piece) {
        Bencoding b = new Bencoding();
        b.type = Type.Dictionary;
        b.dictionary = new HashMap<String, Bencoding>();
        b.dictionary.put("msg_type", new Bencoding(2));
        b.dictionary.put("piece", new Bencoding(piece));
        return constructExtention(id, b.toByteArray());
    }
    //\\\\\\\\\\\\\\\\ "Extension for Peers to Send Metadata Files" \\\\\\\\\\\\\\\\\\\\


}
