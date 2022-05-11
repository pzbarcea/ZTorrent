package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageRequest;
import java.util.*;

public class Piece implements Comparable<Piece> {
    public boolean complete;
    public static int idealSize = 16 * 1024;
    public long pieceIndex;
    public byte[] finalPiece;
    Set<Fragment> data;

    public Piece(long index, int size) {
        finalPiece = new byte[size];
        this.pieceIndex = index;
        data = new TreeSet<Fragment>();
        complete = false;
    }

    public Piece(long index, byte[] d) {
        finalPiece = d;
        this.pieceIndex = index;
        complete = true;
    }

    private void completePiece() {
        Iterator<Fragment> itor = data.iterator();
        int last = 0;
        while (itor.hasNext()) {
            Fragment s = itor.next();
            if (last == s.begin) {
                System.arraycopy(s.data, 0, finalPiece, s.begin, s.data.length);
            } else {
                throw new RuntimeException("[ERROR]: RuntimeException in Piece.java");
            }
            last += s.data.length;
        }

        data.clear();
        data = null;
        complete = true;
    }

    /**
     * Adds a block to a piece.  Returns true if the piece is now complete, false otherwise.
     */
    public boolean addData(long begin, byte[] block) {
        if (complete) {
            throw new RuntimeException("[ERROR]: RuntimeException in Piece.java addData");
        }
        data.add(new Fragment(begin, block));
        Iterator<Fragment> itor = data.iterator();
        int last = 0;
        while (itor.hasNext()) {
            Fragment s = itor.next();
            if (last == s.begin) {
                if (s.begin + s.data.length == finalPiece.length) {
                    completePiece();
                    return true;
                }
            } else {
                return false;
            }
            last = s.begin + s.data.length;
        }

        return false;
    }

    /**
     * Returns null if complete. If not, returns a MessageRequest for the next block in the piece.
     *
     * @return
     */
    public MessageRequest getNextBlock() {
        if (complete) {
            return null;
        }
        Iterator<Fragment> itor = data.iterator();
        Fragment last = null;
        int max = idealSize;
        if (max > finalPiece.length) {
            max = finalPiece.length;
        }

        while (itor.hasNext()) {
            if (last == null) {
                last = itor.next();
            } else {
                Fragment curr = itor.next();
                if (curr.begin > last.begin + last.data.length) {
                    int m = curr.begin - (last.begin + last.data.length);
                    m = m > max ? m : max;
                    return new MessageRequest(pieceIndex, last.begin + last.data.length, m);
                }
                last = curr;
            }
        }


        if (last == null) {
            return new MessageRequest(pieceIndex, 0, max);
        } else {
            int m = finalPiece.length - (last.begin + last.data.length) > max ? max : finalPiece.length - last.begin + last.data.length;
            if (m != 0) {
                return new MessageRequest(pieceIndex, last.begin + last.data.length, max);
            }
        }
        return null;
    }

    public List<MessageRequest> getAllBlocksLeft() {
        if (complete) {
            return null;
        }

        List<MessageRequest> list = new ArrayList<>();
        Fragment last = null;

        int i = 0;
        Iterator<Fragment> itor = data.iterator();
        while (itor.hasNext()) {
            Fragment sp = itor.next();
            if (sp.begin != i) {
                int bytes = sp.begin - i;
                for (int z = 0; z < bytes; z += idealSize) {
                    if (z + idealSize > bytes) {
                        list.add(new MessageRequest(pieceIndex, i + z, bytes - z));
                    } else {
                        list.add(new MessageRequest(pieceIndex, i + z, idealSize));
                    }
                }
            }
            last = sp;
            i += sp.data.length;
        }
        if (last == null) {
            last = new Fragment(0, new byte[0]);
        }
        for (i = last.data.length + last.begin; i < finalPiece.length; i += idealSize) {
            if (i + idealSize > finalPiece.length) {
                list.add(new MessageRequest(pieceIndex, i, finalPiece.length - i));
            } else {
                list.add(new MessageRequest(pieceIndex, i, idealSize));
            }
        }

        return list;
    }

    @Override
    public int compareTo(Piece arg0) {
        return (int) (this.pieceIndex - arg0.pieceIndex);
    }

    public boolean isComplete() {
        return complete;
    }

    public byte[] getCompleted() {
        return finalPiece;
    }

    public byte[] getFromComplete(int offx, int len) {
        byte[] b = new byte[len];
        System.arraycopy(finalPiece, offx, b, 0, len);
        return b;
    }

    public static class Fragment implements Comparable<Fragment> {
        int begin;
        byte[] data;

        public Fragment(long begin, byte[] data) {
            this.begin = (int) begin;
            this.data = data;
        }

        @Override
        public int compareTo(Fragment sp) {
            return begin - sp.begin;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Fragment) {
                Fragment sp = (Fragment) o;
                return sp.begin == begin && data.length == sp.data.length;
            }
            return false;
        }

    }
}



