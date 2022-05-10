package edu.umd.cs.ztorrent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

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

    //Represents if the file is a temporary (partially completed) file or not (if it is a temp file, we need to only write some bytes and
    // manipulate the file pointer, so this matters a lot)
    private boolean isTemp;

    //Represents the length of file in Bytes (stored as Long bc this can be very large for big torrents)
    private final long length;

    //Represents the byte offset to use for writing to the file
    private final long offset;

    //Represents the index of the leftmost piece to write to the file (the next piece)
    private int leftmostPiece;
    //Represents the index of the rightmost piece to write to the file (the last piece)
    private int rightmostPiece;
    //Represents the length of each piece
    private int pieceLength;
    //Represents the number of pieces our file has been split into
    private int numPieces;

    //We need to keep track of what pieces go where, so this maps each piece to the byte offset in the file where it
    // should be written to. We could have also calculated this for each invidiual piece
    // with some formula, where the piecenumber is multiplied by the piece length, then added to the
    // byte offset (but this is more error prone, so we elected to just store it in memory)
    //TODO: consider improving storage efficiency by calculating this each time (but this will reduce time efficiency)
    private final Map<Integer, Long> pieceNumberToByteOffSet;

    /**
     * Constructor that takes in what was parsed from the information contained in the torrent file
     * @param folder - the folder to place the file in
     * @param filename - filename
     * @param length - length of the file in bytes
     * @param offset -  the offset to start writing the file, in the case that our torrent contains multiple
     */
    public FileResource(String folder, String filename, long length, long offset) {
        this.length = length;
        this.offset = offset;
        this.folder = folder;
        this.filename = filename;
        pieceNumberToByteOffSet = new HashMap<>();
    }

    /**
     *
     * @param pieceLength
     * @param organizer
     * @throws IOException
     */
    public void setup(int pieceLength, PieceOrganizer organizer) throws IOException {
        //Set the piece length of each piece
        this.pieceLength = pieceLength;

        //The index of the left piece is the offset divided by the piece length.
        // Example: If we are at location 10, but each piece is of length 10 bytes, then
        // we should be on piece with index 1, since bytes 0->9 were piece 0
        // Basically, calculate the piece index to write, starting with index 0
        this.leftmostPiece = (int) offset / pieceLength;

        //Similar to calculation above, but now we want to find the rightmost piece to write, which is calculated
        // by taking the byteoffset where we start writing, add the entire length of the file, and divide by the
        // piecelength, and subtract 1 because we are 0-indexed
        // Example:
        this.rightmostPiece = (int) Math.ceil((double) (offset + length) / pieceLength) - 1;

        //Simple calculation to get total number of pieces
        //TODO: make sure this matches with PieceOrganizer and other places where we store piece number
        //TODO: any way to import from another class or do we have to store this here?
        numPieces = rightmostPiece - leftmostPiece + 1;

        //Open the file to write (on disk)
        myFile = new File(folder + "\\" + filename);

        //If the file exists already (on disk, not the random access file)
        if (myFile.isFile()) {
            accessFile = new RandomAccessFile(myFile, "rw");
            int pStart = (int) offset / pieceLength;
            int pEnd = (int) Math.ceil((double) (offset + length) / pieceLength);
            for (int s = pStart; s < pEnd; s++) {
                long fp = getFilePointer(s);
                pieceNumberToByteOffSet.put(s, fp);
                organizer.addPieceComplete(s);
            }

            isTemp = false;
            return;
        }

        //If the File doesnt exist, then we only have a temporary file
        isTemp = true;

        //Try writing to the temp file instead
        myFile = new File(folder + "\\" + filename + ".temp");
        if (myFile.isFile()) {
            accessFile = new RandomAccessFile(myFile, "rw");

            //Set the filepointer back to 0 (the start of the tempfile)
            accessFile.seek(0);
            while (accessFile.getFilePointer() < accessFile.length()) {
                //Read the index from the temp file and mark it as complete
                int pIndex = accessFile.readInt();
                organizer.addPieceComplete(pIndex);

                //Store the start offset of the piece
                pieceNumberToByteOffSet.put(pIndex, accessFile.getFilePointer());

                //Move the file pointer forward
                accessFile.skipBytes(getPieceSize(pIndex));
            }

            return;
        }

        //If neither file was created yet (isFile() failed), then we need to make the parent folder and the files
        if (myFile.getParent() != null) {
            File theFile = new File(myFile.getParent());
            theFile.mkdirs();
        }

        accessFile = new RandomAccessFile(myFile, "rw");
    }

    /**
     * If we are done downloading the file, we need to write the temporary file out to a file that can actually be used
     * and represents the completed download
     */
    private void doneDownloading() throws IOException {
        //Rewrite the file if it already exists
        myFile = new File(folder + "\\" + filename);
        if (myFile.isFile()) {
            myFile.delete();
        }

        if (myFile.getParent() != null) {
            File dir = new File(myFile.getParent());
            dir.mkdirs();
        }

        //Write the data out to a file
        RandomAccessFile completedFile = new RandomAccessFile(myFile, "rw");
        long pieceStartLoc = offset / pieceLength;
        long pieceEndLoc = (long) Math.ceil((double) (offset + length) / pieceLength);

        //Set pointer back to 0 (start)
        completedFile.seek(0);
        for (long s = pieceStartLoc; s < pieceEndLoc; s++) {

            //First write the piece to a byte array, then write the byte array out to the file
            byte[] b = pieceFromFileToBytes((int) s);
            completedFile.write(b);
        }

        //Close the file and update accessFile
        accessFile.close();
        accessFile = completedFile;

        //We no longer have a temporary file, since we just wrote the completed file
        isTemp = false;
    }

    /**
     * Finds the location of the piece inside the file
     * Then, writes the data of the piece in the file to the byte Array passed in (arr)
     *
     * Returns the amount of bytes read from the file and written to the array
     *
     * @param pieceNum - index of the piece, starting at 0
     * @param off   -  the start offset in array arr at which the data is written
     * @param arr     - the array to write bytes from the file into
     * @return the amount of bytes read from the file and written into the byte array
     */
    public int pieceFromFileToBytes(int pieceNum, int off, byte[] arr) throws IOException {
        //If the index is between the leftmost and rightmost pieces, it is in our file
        if (leftmostPiece <= pieceNum && rightmostPiece >= pieceNum && pieceNumberToByteOffSet.containsKey(pieceNum)) {

            //get file offset
            long fileOffset = pieceNumberToByteOffSet.get(pieceNum);

            //Find where the piece should start
            long pieceStartLoc = pieceNum * (long) pieceLength;

            //Find overlapping bytes (start of piece - start of file)
            long fileStartLoc = pieceStartLoc - offset;
            long fileEndLoc = pieceStartLoc + pieceLength - offset;

            //Make sure piece doesn't go past the end of the file
            fileEndLoc = Math.min(length, fileEndLoc);

            //Make sure piece starts at least at the beginning of the file
            fileStartLoc = Math.max(0, fileStartLoc);

            //Move file pointer to the fileOffset location
            accessFile.seek(fileOffset);

            //Read from temp file into the byte array
            return accessFile.read(arr, off, (int) (fileEndLoc - fileStartLoc));
        }
        return 0;
    }

    /**
     * Same as above, but different number of parameters because we call this locally, so we know
     * the piece exists in the file, just need to read the piece into an array and return it
     * @param pieceNum - the piece to read into a byte array
     * @return - the byte array containing bytes read from the file
     */
    private byte[] pieceFromFileToBytes(int pieceNum) throws IOException {

        //get file offset
        long fileOffset = pieceNumberToByteOffSet.get(pieceNum);

        //Find where the piece should start
        long pieceStartLoc = pieceNum * (long) pieceLength;

        //Find overlapping bytes (start of piece - start of file)
        long fileStartLoc = pieceStartLoc - offset;
        long fileEndLoc = pieceStartLoc + pieceLength - offset;

        //Make sure piece doesn't go past the end of the file
        fileEndLoc = Math.min(length, fileEndLoc);

        //Make sure piece starts at least at the beginning of the file
        fileStartLoc = Math.max(0, fileStartLoc);

        //Move file pointer to the fileOffset location
        accessFile.seek(fileOffset);

        byte[] b = new byte[(int) (fileEndLoc - fileStartLoc)];
        accessFile.read(b);
        return b;
    }


    /**
     * Writes as much of the piece that exists in this file
     *
     * @param  toWrite - the piece to write to the file
     * @throws IOException
     */
    public void writePieceToFile(Piece toWrite) throws IOException {

        //If the piece is already in the map, it means we have already written it
        if (pieceNumberToByteOffSet.containsKey((int) toWrite.pieceIndex)) {
            throw new RuntimeException("[ERROR] Piece #" + toWrite.pieceIndex + " has already been written to the file");
        }

        //If the piece belongs in the file (it is between the left and rightmost pieces)
        if (leftmostPiece <= toWrite.pieceIndex && rightmostPiece >= toWrite.pieceIndex) {
            long pieceStartLoc = toWrite.pieceIndex * (long) pieceLength;
            long fileStartLoc = pieceStartLoc - offset;
            long fileEndLoc = pieceStartLoc + pieceLength - offset;

            int offset2 = 0;

            fileEndLoc = Math.min(length, fileEndLoc);

            if (fileStartLoc < 0) {
                offset2 = (int) Math.abs(fileStartLoc);
                fileStartLoc = 0;
            }

            //Move the file pointer
            accessFile.seek(accessFile.length());

            //Write the piece number before we write the data
            accessFile.writeInt((int) toWrite.pieceIndex);

            long pointer = accessFile.getFilePointer();
            accessFile.write(toWrite.finalPiece, offset2, (int) (fileEndLoc - fileStartLoc));

            //Mark the piece as being written (update the map to store this piece's offset/file pointer)
            pieceNumberToByteOffSet.put((int) toWrite.pieceIndex, pointer);

            //If we have written all the pieces, the file is done downloading
            if ( pieceNumberToByteOffSet.size() >= numPieces) {
                doneDownloading();
            }
        }
    }

    /**
     * Writes part of a file to a byte Array
     *
     * This allows us to "seed" a file by converting the bytes of a file to a "piece" that can be transferred between
     * peers
     *
     * @param files - array of files to read from
     * @param pieceNum - the piece to read from file
     * @param pieceLength - length of piece that needs to be read
     * @return - a Byte Array representing the bytes read from the file, representing a piece
     */
    public static byte[] pieceFromFile(FileResource[] files, int pieceNum, int pieceLength) throws IOException {
        byte[] bytesRead = new byte[pieceLength];
        int offset = 0;

        //For each file, update the pointer by reading the piece into the byte array
        for (FileResource f : files) {
            offset += f.pieceFromFileToBytes(pieceNum, offset, bytesRead);
        }

        //If we couldn't read enough bytes, just return null because we don't want to send corrupt or incomplete pieces
        if (offset != pieceLength) {
            return null;
        }

        //If we read properly, send the byte array that forms the piece
        return bytesRead;
    }

    /**
     * Returns the file pointer where the piece starts
     * @param pieceNum - the piece to get the file pointer for
     */
    private long getFilePointer(int pieceNum) {
        long pieceStartLoc = pieceNum * (long) pieceLength;

        long fileStartLoc = offset - pieceStartLoc;

        if (fileStartLoc < 0) {
            return (int) Math.abs(fileStartLoc);
        }

        return 0;
    }

    /**
     * Returns the size (distance between the start pointer and end of the file in bytes)
     * @param pieceNum - the piece to get the size for
     * @return
     */
    private int getPieceSize(int pieceNum) {
        long pieceStartLoc = pieceNum * (long) pieceLength;
        long fileStartLoc = pieceStartLoc - offset;
        long fileEndLoc = pieceStartLoc + pieceLength - offset;

        fileEndLoc = Math.min(length, fileEndLoc);

        fileStartLoc = Math.max(0, fileStartLoc);

        return (int) (fileEndLoc - fileStartLoc);
    }

    /**
     * Closes the accessFile and deletes myFile (the completed file)
     * Used by the TorrentClient to delete all the downloaded files when user wants to get rid of the data
     */
    public void deleteFile() throws IOException {
        accessFile.close();
        if (myFile != null) {
            myFile.delete();
        }
    }

    //Getter method
    public long getLength() {
        return length;
    }

    /**
     * Used by TorrentClient to close the file when we pause a torrent
     */
    public void closeFile() throws IOException {
        accessFile.close();
    }
}
