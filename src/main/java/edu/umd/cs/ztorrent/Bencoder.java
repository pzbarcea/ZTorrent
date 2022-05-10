package edu.umd.cs.ztorrent;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * From Bencode wikipedia page:
 * Bencode (pronounced like Ben-code) is the encoding used by the peer-to-peer file sharing system BitTorrent for storing and transmitting loosely structured data.
 *
 * This class holds functions used for encoding and decoding(parsing) messages to and from torrent clients
 * A lot of parsing is involved in processing the messages
 *
 * The format/algorithm is given in the wikipedia page
 * <a href="https://en.wikipedia.org/wiki/Bencode">https://en.wikipedia.org/wiki/Bencode</a>
 *
 * References:
 * https://github.com/dampcake/bencode
 * https://wiki.theory.org/BitTorrentSpecification#Bencoding
 * https://stackoverflow.com/questions/1664124/bencoding-binary-data-in-java-strings
 */
public class Bencoder {
    public BencodeType type;
    public List<Bencoder> list;
    public Map<String, Bencoder> dictionary;
    public byte[] byteString;
    public Long integer;
    byte[] tmp;

    public Bencoder() {
    }

	public Bencoder(String s)  {
        this.type = BencodeType.String;
        this.byteString = s.getBytes(StandardCharsets.UTF_8);
    }

    public Bencoder(long i) {
        this.type = BencodeType.Integer;
        this.integer = i;
    }

    public static Bencoder cBS(byte[] b) {
        Bencoder ben = new Bencoder();
        ben.byteString = b;
        ben.type = BencodeType.String;
        return ben;
    }

    public Bencoder(byte[] data) {
        try {
            if (data.length < 2) {
                throw new Exception("Data is not bencoded properly");
            }


            getBencoding(data, 0, data.length, this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int getBencoding(byte[] data, int start, int len, Bencoder r) throws Exception {
        if (data[start] == 'i') {
            int dist = -1;
            for (int i = start + 1; i < len; i++) {
                if (data[i] == 'e') {
                    dist = i;
                    break;
                }
            }
            if (dist == -1) {
                throw new Exception("[ERROR] Could not bencode integer");
            }
            byte[] integer = new byte[dist - 1 - start];
            //src dst
            System.arraycopy(data, start + 1, integer, 0, dist - 1 - start);
            String str = new String(integer, 0, integer.length, StandardCharsets.UTF_8);
            r.integer = Long.valueOf(str);
            r.type = BencodeType.Integer;
            r.tmp = new byte[dist + 1 - start];
            System.arraycopy(data, start, r.tmp, 0, dist + 1 - start);
            return dist + 1 - start;//distance 'consumed'
        } else if (data[start] == 'l' || data[start] == 'd') {
			/* List: l<item1><item2>e
	           Dict: d<string1><item1><string2><item2>e*/
            boolean dict = false;
            r.type = BencodeType.List;
            int cur = start + 1;
            if (data[start] == 'd') {
                dict = true;
                r.type = BencodeType.Dictionary;
                r.dictionary = new LinkedHashMap<>();
            } else {
                r.list = new LinkedList<>();
            }

            while (cur < len && data[cur] != 'e') {
                if (dict) {
                    //get string
                    Bencoder string = new Bencoder();
                    cur += getBencoding(data, cur, len, string);
                    //get element
                    Bencoder elem = new Bencoder();
                    cur += getBencoding(data, cur, len, elem);
                    //put!
                    r.dictionary.put(string.getString(), elem);
                } else {
                    Bencoder elem = new Bencoder();
                    cur += getBencoding(data, cur, len, elem);
                    r.list.add(elem);
                }
            }
            r.tmp = new byte[cur + 1 - start];
            System.arraycopy(data, start, r.tmp, 0, cur + 1 - start);
            return cur + 1 - start;
        } else {//string
            int dist = -1;
            for (int i = start; i < len; i++) {
                if (data[i] == ':') {
                    dist = i;
                    break;
                }
            }
            if (dist == -1) {
                throw new Exception("[ERROR] Could not bencode String");
            }
            byte[] integer = new byte[dist - start];
            //src dst
            System.arraycopy(data, start, integer, 0, dist - start);
            int slen = Integer.parseInt(new String(integer, 0, integer.length, StandardCharsets.UTF_8));
            byte[] string = new byte[slen];
            System.arraycopy(data, dist + 1, string, 0, slen);
            r.byteString = string;
            r.type = BencodeType.String;
            r.tmp = new byte[dist + slen + 1 - start];
            System.arraycopy(data, start, r.tmp, 0, dist + slen + 1 - start);
            return dist + slen + 1 - start;
        }

    }

    public byte[] toByteArray() {
        byte[] out = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (this.type == BencodeType.Dictionary) {
                ((OutputStream) bo).write('d');
                for (Entry<String, Bencoder> s : dictionary.entrySet()) {
                    byte[] bytes = s.getKey().getBytes(StandardCharsets.UTF_8);
                    String l = Long.toString(bytes.length);
                    bo.write(l.getBytes(StandardCharsets.UTF_8));
                    ((OutputStream) bo).write(':');
                    bo.write(bytes);
                    bo.write(s.getValue().toByteArray());
                }
                ((OutputStream) bo).write('e');
            } else if (this.type == BencodeType.Integer) {
                ((OutputStream) bo).write('i');
                String s = integer.toString();
                bo.write(s.getBytes(StandardCharsets.UTF_8));
                ((OutputStream) bo).write('e');
            } else if (this.type == BencodeType.List) {
                ((OutputStream) bo).write('l');
                for (Bencoder b : list) {
                    bo.write(b.toByteArray());
                }
                ((OutputStream) bo).write('e');
            } else {
                byte[] bytes = byteString;
                String l = Long.toString(bytes.length);
                bo.write(l.getBytes(StandardCharsets.UTF_8));
                ((OutputStream) bo).write(':');
                bo.write(bytes);
            }

            ((OutputStream) bo).close();
            bo.flush();
            out = bo.toByteArray();

        } catch (Exception e) {
            e.printStackTrace();
        }
        return out;
    }
    
    public String getString() {
        return new String(byteString, 0, byteString.length, StandardCharsets.UTF_8);
    }
}
