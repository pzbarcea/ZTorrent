package edu.umd.cs.ztorrent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class TorrentInfo {

    boolean complete;
    boolean started = false;
    Piece p;
    long size;
    int pieces;
    byte[] metaData;
    Set<Integer> piecesLeft;

    public TorrentInfo() {
        complete = false;
        size = -1;
    }

    public TorrentInfo(Bencoder infoDic) throws FileNotFoundException {
        complete = true;
        metaData = infoDic.toByteArray();
    }

    public boolean isComplete() {
        return complete;
    }

    public void setSize(int bytes) {
        if (!started) {
            started = true;
            p = new Piece(-1, bytes);
            complete = false;
            size = bytes;
            pieces = (int) Math.ceil(size / (1024 * 16.0));
            metaData = new byte[(int) size];
            piecesLeft = new HashSet<>();
            for (int i = 0; i < pieces; i++) {
                piecesLeft.add(i);
            }
        }
    }


    public byte[] getPiece(int i) throws IOException {
        if (complete) {
            int len = 1024 * 16;
            if (len / (1024 * 16) == pieces - 1) {
                len = (int) (size % (1024 * 16));
                if (len == 0) {
                    len = 1024 * 16;
                }
            }
            byte[] r = new byte[len];
            System.arraycopy(metaData, i * 1024 * 16, r, 0, len);
            return r;
        }
        return null;
    }

    public void add(int piece, byte[] data) {
        if (!complete) {
            p.addData(1024L * 16 * piece, data);
            piecesLeft.remove(piece);
        }
    }

}
