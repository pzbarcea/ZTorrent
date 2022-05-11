package edu.umd.cs.ztorrent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Class that manages files and pieces.
 * Should manage piece and file interactions.
 * Cache-ing?
 * <p>
 * Working with big files can get complicated....
 * #files = pieces / 3k
 * for reference # pieces for huge torrent[120GB with 64KB piece]:
 * ~1.8 million pieces
 * ~600 files
 * Ok so thats bad. Instead we will keep map
 * of piece to file offset. This will have to be enough.
 * <p>
 * Goal here is to beable to fit on 256MB of ram fyi.
 * Maps in worse case[1.8 mil] will use 6.2% of this
 * <p>
 * Our cache is LRU cahce
 * from LinkedHashMap java doc:
 * "This kind of map is well-suited to building LRU caches."
 * :-) ^.^ ^_^ O_o !! woot.
 * <p>
 * So either we have the completed files
 * Or we the "partial" version of the file
 */
public class PieceManager {
    //TODO: private Set<Long> skippedPieces;, actually should keeps chunks of this.
    LinkedHashMap<Long, Piece> indexToPiece;//Subset of completed pieces, quick LRU cache
    List<Piece> writeToDisk;//set that still needs to be written to disk.
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
        writeToDisk = new ArrayList<Piece>();
        readsFromDisk = new ArrayList<Integer>();

        this.cacheSize = actualChunks;
        this.files = files;

        //Java's pretty awesome....
        indexToPiece = new LinkedHashMap<Long, Piece>(actualChunks + 2, 1, true) {//true->access order
            private static final long serialVersionUID = -1418159831489090492L;

            protected boolean removeEldestEntry(Map.Entry<Long, Piece> eldest) {
                return size() > cacheSize;
            }
        };
    }

    /**
     * Returns true if has the piece.
     *
     * @param pieceIndex
     * @return
     */
    public boolean hasPiece(int pieceIndex) {
        return pieceOrganizer.hasPiece(pieceIndex);
    }

    /***
     * @param p-  a new piece to be placed into structure
     * @return false if it already existed.
     * May change to throw error... I dunno.
     *
     */
    public boolean putPiece(Piece p) {
        boolean b = !pieceOrganizer.hasPiece((int) p.pieceIndex);
        if (b && validPiece(p)) {
            indexToPiece.put(p.pieceIndex, p);
            pieceOrganizer.addPieceComplete(p.pieceIndex);
            writeToDisk.add(p);
        }
        return b;
    }


    /***
     * Gets piece from structure.
     * May have to read from disk.
     * Assume hasPiece is called before pieceFromFileToBytes
     * Throws error if piece not completed
     * Will return null when not loaded on cahce.
     * It will be enqueued for cache if returns null.
     * @param pieceIndex
     * @return
     */
    public Piece getPiece(long pieceIndex) {
        //get piece must do puts so we can keep
        //stuff being used at top
        if (!pieceOrganizer.hasPiece((int) pieceIndex)) {
            throw new RuntimeException("Cant get piece that hasnt been completed.");
        }
        if (indexToPiece.containsKey(pieceIndex)) {
            return indexToPiece.get(pieceIndex);
        }
        readsFromDisk.add((int) pieceIndex);
        return null;
    }


    /**
     * Requests that we couldn't immediately do
     * get put into a block queue.
     *
     * @throws IOException
     */
    public void processBlocking() throws IOException {
        //do all our writes and reads here.
        //for now we will store into our own .Partial format
        //Write then open for read
        //RandomAccessFile raf = new RandomAccessFile(partialFile,"rw");
        //Awesome ^.^ -> raf.getFilePointer()
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

        //Our work here is done.
        writeToDisk.clear();
        readsFromDisk.clear();
    }

    //TODO:  counter?
    public long getCompletedBytes() {
        return pieceOrganizer.getCompletedPieces() * pieceLength;
    }

    public void checkFiles() throws IOException {
        //for every piece that we have......
        //Files already loaded up them selves in the bitmap
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
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new RuntimeException("SHA-1 Problems.");
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
            // TODO Auto-generated catch block
            e.printStackTrace();
            return false;
        }
    }

}
