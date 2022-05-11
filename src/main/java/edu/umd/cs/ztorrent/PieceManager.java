package edu.umd.cs.ztorrent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class PieceManager {

    LinkedHashMap<Long, Piece> indexToPiece;
    List<Piece> writeToDisk;
    List<Integer> readsFromDisk;
    int cacheSize;
    int pieceLength;
    FileResource[] files;
    public PieceOrganizer pieceOrganizer;
    private Bencoder shaPieces;

    public PieceManager(FileResource[] files, int cacheBytes, int pieceLength, long totalBytes, Bencoder b)
            throws FileNotFoundException {
        this.shaPieces = b;
        this.pieceLength = pieceLength;

        int actualChunks = cacheBytes / pieceLength;
        if (actualChunks < 2) {
            actualChunks = 2;
        }

        pieceOrganizer = new PieceOrganizer(totalBytes, pieceLength);
        writeToDisk = new ArrayList<>();
        readsFromDisk = new ArrayList<>();

        this.cacheSize = actualChunks;
        this.files = files;

        indexToPiece = new LinkedHashMap<Long, Piece>(actualChunks + 2, 1, true) {
            private static final long serialVersionUID = -1418159831489090492L;

            protected boolean removeEldestEntry(Map.Entry<Long, Piece> eldest) {
                return size() > cacheSize;
            }
        };
    }

    public boolean hasPiece(int pieceIndex) {
        return pieceOrganizer.hasPiece(pieceIndex);
    }


    public boolean putPiece(Piece p) {
        boolean b = !pieceOrganizer.hasPiece((int) p.pieceIndex);
        if (b && validPiece(p)) {
            indexToPiece.put(p.pieceIndex, p);
            pieceOrganizer.addPieceComplete(p.pieceIndex);
            writeToDisk.add(p);
        }
        return b;
    }


    public Piece getPiece(long pieceIndex) {

        if (!pieceOrganizer.hasPiece((int) pieceIndex)) {
            throw new RuntimeException("[ERROR] Piece not yet completed");
        }
        if (indexToPiece.containsKey(pieceIndex)) {
            return indexToPiece.get(pieceIndex);
        }
        readsFromDisk.add((int) pieceIndex);
        return null;
    }


    public void processBlocking() throws IOException {
        for (Piece p : writeToDisk) {
            for (FileResource d : files) {
                d.writePieceToFile(p);
            }
        }

        for (Integer pIndex : readsFromDisk) {
            byte[] p = FileResource.pieceFromFile(files, pIndex, pieceOrganizer.getPieceLength(pIndex));
            Piece piece = new Piece(pIndex, p);
            indexToPiece.put(piece.pieceIndex, piece);
        }

        writeToDisk.clear();
        readsFromDisk.clear();
    }

    public long getCompletedBytes() {
        return pieceOrganizer.getCompletedPieces() * pieceLength;
    }

    public void checkFiles() throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (int i = 0; i < pieceOrganizer.getNumberOfPieces(); i++) {
                if (pieceOrganizer.hasPiece(i)) {
                    byte[] p = FileResource.pieceFromFile(files, i, pieceOrganizer.getPieceLength(i));
                    byte[] sha = Arrays.copyOfRange(shaPieces.byteString, i * 20, i * 20 + 20);
                    byte[] b = md.digest(p);
                    if (!Arrays.equals(b, sha)) {
                        System.out.println("[ERROR] Corruption detected, index:" + i);
                    }
                }
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new RuntimeException("[ERROR] Bad Hash");
        }

    }

    public boolean validPiece(Piece p) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(p.getCompleted());
            byte[] sha = Arrays.copyOfRange(shaPieces.byteString, (int) p.pieceIndex * 20, (int) p.pieceIndex * 20 + 20);
            return Arrays.equals(b, sha);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }
    }

}
