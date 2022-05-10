package edu.umd.cs.ztorrent;

import java.util.*;

/***
 * Keeps track of things related to our pieces, such as the total data, the size of each piece,
 * the number of total pieces there are, and the rarity of each individual piece
 */
public class PieceOrganizer {
    byte[] pieceToBytes;

    Map<Integer, Rarity> indexToRarity;
    ArrayList<Rarity> rarityList;
    short[] rarityMap;
    int pieceLength;
    long dataLength;
    int numPieces;
    ArrayList<PieceOrganizer> peerMaps;
    long piecesComplete = 0;

    public PieceOrganizer(long dataLength, int pieceLength) {
        this.dataLength = dataLength;
        this.pieceLength = pieceLength;

        numPieces = (int) Math.ceil((double) dataLength / pieceLength);
        pieceToBytes = new byte[(int) Math.ceil(numPieces / 8.0)];
        peerMaps = new ArrayList<>();
        rarityList = new ArrayList<>();
        indexToRarity = new HashMap<>();
        rarityMap = new short[numPieces];
        for (int i = 0; i < numPieces; i++) {
            Rarity r = new Rarity(i);
            indexToRarity.put(r.index, r);
            rarityList.add(r);
        }
    }


    public void addPieceComplete(long i) {
        if (!hasPiece((int) i)) {
            int index = (int) i;
            byte b = pieceToBytes[index / 8];
            b = (byte) (b | (1 << (7 - index % 8)));
            pieceToBytes[index / 8] = b;
            piecesComplete++;
        }
    }

    public boolean hasPiece(int index) {
        return (pieceToBytes[index / 8] & (1 << (7 - index % 8))) != 0;
    }

    public void recomputeRarity() {
        Arrays.fill(rarityMap, (short) 0);

        for (int i = 0; i < numPieces; i++) {
            for (PieceOrganizer b : peerMaps) {
                rarityMap[i] += b.hasPiece(i) ? 1 : 0;
            }
            indexToRarity.get(i).setValue(rarityMap[i]);
        }
        Collections.sort(rarityList);
    }

    public void setBitMap(byte[] bitmap) {
        if (bitmap.length != this.pieceToBytes.length) {
            throw new RuntimeException("[ERROR] PieceToByte Arrays are of Mismatched Length");
        }
        System.arraycopy(bitmap, 0, this.pieceToBytes, 0, bitmap.length);

        piecesComplete = 0;
        for (int i = 0; i < numPieces; i++) {
            if (hasPiece(i)) {
                piecesComplete++;
            } else {
                System.out.println("Missing Piece #" + i);
            }
        }
    }

    public byte[] getMapCopy() {
        return pieceToBytes.clone();
    }

    public int getPieceLength(int index) {
        int pSize = pieceLength;
        if (index == numPieces - 1) {
            pSize = (int) (dataLength % pieceLength);
            if (pSize == 0) {
                pSize = pieceLength;
            }
        }
        return pSize;
    }

    public Piece createPiece(int index) {
        int pSize = getPieceLength(index);
        if (index >= numPieces) {
            throw new RuntimeException("Index out of bounds at " + index);
        }

        return new Piece(index, pSize);
    }

    public void addPeerMap(PieceOrganizer bm) {
        if (!peerMaps.contains(bm)) {
            peerMaps.add(bm);
        }
    }


    public void removePeerMap(PieceOrganizer bm) {
        peerMaps.remove(bm);
    }

    public boolean isComplete() {
        return piecesComplete == numPieces;
    }

    public long getCompletedPieces() {
        return piecesComplete;
    }

    public int getNumberOfPieces() {
        return numPieces;
    }

    public int getLength() {
        return pieceToBytes.length;
    }

    public ArrayList<Rarity> getRarityList() {
        return rarityList;
    }


}
