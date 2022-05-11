package edu.umd.cs.ztorrent.cli;

import edu.umd.cs.ztorrent.Torrent;
import edu.umd.cs.ztorrent.TorrentClient;
import edu.umd.cs.ztorrent.ParserTorrentFile;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.security.NoSuchAlgorithmException;

/**
 * Provides a way to run from the command line, with additional options
 */
public class TorrentCli {
    private static void usage(PrintStream s) {
        s.println("usage: Client [options] <torrent>");
        s.println();
        s.println("Available options:");
        s.println("  -h,--help                  Show this help and exit.");
        s.println();
    }

    public static void main(String[] args) {

        CmdLineParser parser = new CmdLineParser();
        CmdLineParser.Option help = parser.addBooleanOption('h', "help");

        try {
            parser.parse(args);
        } catch (CmdLineParser.OptionException oe) {
            System.err.println(oe.getMessage());
            usage(System.err);
            System.exit(1);
        }

        // Display help if requested
        if (Boolean.TRUE.equals((Boolean) parser.getOptionValue(help))) {
            usage(System.out);
            System.exit(0);
        }

        String[] otherArgs = parser.getRemainingArgs();
        if (otherArgs.length != 1) {
            usage(System.err);
            System.exit(1);
        }

        TorrentClient client = new TorrentClient();
        try {
            String fileName = otherArgs[0];
            System.out.println("Using .torrent file: " + fileName);
            File file = new File(fileName);

            Torrent t = ParserTorrentFile.parseTorrentFile(file.getAbsolutePath());
            client.addTorrent(t);

            client.mainLoop();
        }
        catch (InterruptedException | IOException | NoSuchAlgorithmException e) {
            System.out.println("File provided is not a VALID .torrent file");
            System.exit(3);
        }
    }
}
