package edu.umd.cs.ztorrent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Class representing a file to download
 *
 * Before a file is completed, it is represented as a partial file, storing the piece index (where the piece came from)
 * and then the piece data
 *
 * From the BitTorrent protocol implementation:
 * https://hackage.haskell.org/package/bittorrent-0.0.0.3/docs/Data-Torrent-Piece.html
 */
public class FileResource {
    //The file to write to. When we are completely done writing, the file should be complete with all bytes restored
    private File myFile;

    /**
     *FROM: https://docs.oracle.com/javase/7/docs/api/java/io/RandomAccessFile.html
     * A random access file behaves like a large array of bytes stored in the file system.
     * There is a kind of cursor, or index into the implied array, called the file pointer;
     * input operations read bytes starting at the file pointer and advance the file pointer past the bytes read.
     * If the random access file is created in read/write mode, then output operations are also available;
     * output operations write bytes starting at the file pointer and advance the file pointer past the bytes written.
     * Output operations that write past the current end of the implied array cause the array to be extended.
     * The file pointer can be read by the getFilePointer method and set by the seek method.
     *
     * Basically, we use this as a way to write bytes (the pieces) to a file in cached memory, then when we are ready we write
     * it out to the actual file, myFile
     */
    private RandomAccessFile accessFile;

    //String representing the filepath that comes before the filename, in case we want to write or read to a directory
    // that is different than the one we are in (useful bc our Maven will generate a target directory that gets wiped and
    // rebuilt anyway, so storing stuff there is bad)
    private final String folder;

    //String representing the filename (what comes after the folder path) this then gets appended with the folder to
    // create the entire filepath to use for the file object (myFile
    private final String filename;

    //Represents if the file is a partial file or not (if it is a partial file, we need to only write some bytes and
    // manipulate the file pointer, so this matters a lot)
    private boolean isPartial;

    //Represents the length of file in Bytes (stored as Long bc this can be very large for big torrents)
    private final long length;

    //Represents the byteOffset to use
    private final long byteOffSet;
    private int leftPiece, rightPiece, pieceLength;
    private int numPieces;

    private final Map<Integer, Long> pieceIndexToOffSet;


    public FileResource(String folder, String filename, long length, long byteOff) {//shit parsed from torrent file
        this.length = length;
        this.byteOffSet = byteOff;
        this.folder = folder;
        this.filename = filename;
        pieceIndexToOffSet = new HashMap<Integer, Long>();
    }

    public void initialize(int pieceLength, PieceOrganizer bm) throws IOException {
        this.pieceLength = pieceLength;
        this.leftPiece = (int) (byteOffSet / pieceLength);
        this.rightPiece = (int) Math.ceil((double) (byteOffSet + length) / pieceLength) - 1;
        numPieces = rightPiece - leftPiece + 1;
        myFile = new File(folder + "\\" + filename);
        if (myFile.isFile()) {
            //file exists.
            //Act as if its all there.
            //(we check more in depth later.)
            accessFile = new RandomAccessFile(myFile, "rw");
            int pStart = (int) byteOffSet / pieceLength;
            int pEnd = (int) Math.ceil((double) (byteOffSet + length) / pieceLength);
            for (int s = pStart; s < pEnd; s++) {
                long fp = getIdeaFilePoint(s);
                pieceIndexToOffSet.put(s, fp);
                bm.addPieceComplete(s);
            }
            isPartial = false;
            return;
        }
        isPartial = true;
        //ok file isnt here. is the .partial there?
        myFile = new File(folder + "\\" + filename + ".partial");
        if (myFile.isFile()) {
            accessFile = new RandomAccessFile(myFile, "rw");
            //read through
            accessFile.seek(0);
            while (accessFile.getFilePointer() < accessFile.length()) {
                int pIndex = accessFile.readInt();
                bm.addPieceComplete(pIndex);
                pieceIndexToOffSet.put(pIndex, accessFile.getFilePointer());
                int i = getSizeFrom(pIndex);
                accessFile.skipBytes(i);
            }

            return;
        }

        //ok neither exist. lets make this now.
        if (myFile.getParent() != null) {
            File fp = new File(myFile.getParent());
            fp.mkdirs();
        }
        accessFile = new RandomAccessFile(myFile, "rw");
    }

    public long getOffSet() {
        return byteOffSet;
    }

    public long getLength() {
        return length;
    }

    /**
     * Writes piece to array.
     * Amount of bytes written is returned.
     * the byte array is expected to be large enough
     *
     * @param index - piece index to be gotten
     * @param off   - off set int bye array
     * @param b     - byte array retults be written to
     * @return amount of bytes written
     * @throws IOException
     * @author pzbarcea
     */
    public int getPiece(int index, int off, byte[] b) throws IOException {
        if (leftPiece <= index && rightPiece >= index && pieceIndexToOffSet.containsKey(index)) {
            //***We can assume file contains it.***
            //***So either parly contained or fully.***

            //get piece section size
            long fileOff = pieceIndexToOffSet.get(index);
            long pStart = index * (long) pieceLength;//byte at which piece starts
            //get the bytes that overlap.
            long fStart = pStart - byteOffSet; //file start - pieceStart
            long fEnd = pStart + pieceLength - byteOffSet;
            //Does piece go past end of file?
            if (fEnd > length) {
                fEnd = length;
            }
            //Does piece begin in file?
            if (fStart < 0) {//doesnt start in here. so copy what does.
                fStart = 0;
            }

            accessFile.seek(fileOff);
            return accessFile.read(b, off, (int) (fEnd - fStart));//hot stuff
        }
        return 0;
    }


    /**
     * Writes as much of the piece that exists in this file
     * returns error if file is complete
     *
     * @param piece to be written
     * @throws IOException
     */
    public void writePiece(Piece p) throws IOException {
        //if(!isPartial){throw new RuntimeException("Invalid use. I'm complete");}
        if (pieceIndexToOffSet.containsKey((int) p.pieceIndex)) {
            throw new RuntimeException("Invalid piece is already contained.");
        }
        if (leftPiece <= p.pieceIndex && rightPiece >= p.pieceIndex) {
            long pStart = p.pieceIndex * (long) pieceLength;//byte at which piece starts
            long fStart = pStart - byteOffSet; //file start - pieceStart
            long fEnd = pStart + pieceLength - byteOffSet;
            int offX = 0;
            if (fEnd > length) {
                fEnd = length;
            }
            if (fStart < 0) {
                offX = (int) Math.abs(fStart);
                fStart = 0;
            }

            accessFile.seek(accessFile.length());
            accessFile.writeInt((int) p.pieceIndex);
            long pt = accessFile.getFilePointer();
            accessFile.write(p.finalPiece, offX, (int) (fEnd - fStart));
            //check if complete. If it is change status.
            pieceIndexToOffSet.put((int) p.pieceIndex, pt);
            if (numPieces == pieceIndexToOffSet.size()) {
                //Complete.
                //Do our close out.
                doCloseOut();
            }
        }
    }


    public void close() throws IOException {
        accessFile.close();
    }

    public boolean isComplete() {
        return !isPartial;
    }

    public static byte[] pieceFromFile(FileResource[] files, int pieceIndex, int pieceLength) throws IOException {
        byte[] piece = new byte[pieceLength];
        int off = 0;
        for (FileResource f : files) {
            off += f.getPiece(pieceIndex, off, piece);
        }
        if (off != pieceLength) {
            return null;
        }
        return piece;
    }


    /////////////////// Private Methods aka junk. //////////////////////
    private byte[] getPiece(int index) throws IOException {
        // ***We can assume file contains it.***
        // ***So either parly contained or fully.***
        // get piece section size
        long fileOff = pieceIndexToOffSet.get(index);
        long pStart = index * (long) pieceLength;// byte at which piece
        // starts
        // get the bytes that overlap.
        long fStart = pStart - byteOffSet; //file start - pieceStart
        long fEnd = pStart + pieceLength - byteOffSet;
        // Does piece go past end of file?
        if (fEnd > length) {
            fEnd = length;
        }
        // Does piece begin in file?
        if (fStart < 0) {// doesnt start in here. so copy what does.
            fStart = 0;
        }

        accessFile.seek(fileOff);
        byte[] b = new byte[(int) (fEnd - fStart)];
        accessFile.read(b);
        return b;// hot stuff
    }

    private void doCloseOut() throws IOException {
        isPartial = false;
        //check if some how the file exists. If does DELETE IT!
        myFile = new File(folder + "\\" + filename);
        if (myFile.isFile()) {
            myFile.delete();
        }
        if (myFile.getParent() != null) {//Make dir's if needed
            File dir = new File(myFile.getParent());
            dir.mkdirs();
        }

        RandomAccessFile finalFile = new RandomAccessFile(myFile, "rw");//Open file
        long l = ((byteOffSet + length) % pieceLength);
        if (l == 0) {
            l = pieceLength;
        }
        long pStart = byteOffSet / pieceLength;
        long pEnd = (long) Math.ceil((double) (byteOffSet + length) / pieceLength);
        finalFile.seek(0);
        for (long s = pStart; s < pEnd; s++) {
            //now we start writing ^.^
            byte[] b = getPiece((int) s);
            finalFile.write(b);
        }
        accessFile.close();
        accessFile = finalFile;
    }

    private long getIdeaFilePoint(int pieceNum) {
        long pStart = pieceNum * (long) pieceLength;//byte at which piece starts
        long fStart = byteOffSet - pStart; //file start - pieceStart
        long fEnd = pStart + pieceLength - byteOffSet;
        int offX = 0;
        if (fEnd > length) {
            fEnd = length;
        }
        if (fStart < 0) {
            offX = (int) Math.abs(fStart);
            fStart = 0;
        }

        return offX;
    }

    private int getSizeFrom(int pieceNum) {
        long pStart = pieceNum * (long) pieceLength;//byte at which piece starts
        long fStart = pStart - byteOffSet; //file start - pieceStart
        long fEnd = pStart + pieceLength - byteOffSet;
        if (fEnd > length) {
            fEnd = length;
        }
        if (fStart < 0) {
            fStart = 0;
        }
        return (int) (fEnd - fStart);
    }


    private static final Random r = new Random();

    public static byte[] generateSessionKey(int length) throws UnsupportedEncodingException {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        int n = alphabet.length();
        String result = "";
        for (int i = 0; i < length; i++)
            result = result + alphabet.charAt(r.nextInt(n));

        return result.getBytes(StandardCharsets.UTF_8);
    }

    public void delete() throws IOException {
        accessFile.close();
        if (myFile != null) {
            myFile.delete();
        }
    }


}
