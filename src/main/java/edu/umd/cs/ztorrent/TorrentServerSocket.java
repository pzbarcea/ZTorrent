package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageParser;
import edu.umd.cs.ztorrent.message.MessageParser.HandShake;
import edu.umd.cs.ztorrent.protocol.PeerConnection;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/***
 * While initially created for testing purposes. This class
 * will eventually be used for incoming torrent connection
 * bound on the upnp port.
 *
 * Passes out managed connection with incoming handshake filled out.
 * This is then matched against torrent list. 
 *
 *
 * @author pzbarcea
 */
public class TorrentServerSocket {
    private List<PeerConnection> connections;
    private final ServerSocket tcpServer;
    private final ReentrantLock clientsLock = new ReentrantLock();
    boolean alive = true;
    Thread th;

    public TorrentServerSocket(int port) throws IOException {
        tcpServer = new ServerSocket(port);
        connections = new LinkedList<PeerConnection>();
        th = new Thread() {
            @Override
            public void run() {
                serverAcceptLoop();
            }
        };
        th.start();
    }

    public void serverAcceptLoop() {
        while (alive) {
            try {
                Socket socket = tcpServer.accept();
                socket.setSoTimeout(1000 * 2);
                try {
                    HandShake hs = MessageParser.readBlockingHandShake(socket.getInputStream());
                    PeerConnection mc = new PeerConnection(socket, hs);
                    clientsLock.lock();
                    connections.add(mc);
                    clientsLock.unlock();
                } catch (Exception e) {
                    e.printStackTrace();
                }//TODO: log error.


            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }

    public void close() {
        alive = false;
    }

    public List<PeerConnection> getNewConnections() {
        List<PeerConnection> r;
        clientsLock.lock();
        r = connections;
        connections = new LinkedList<PeerConnection>();
        clientsLock.unlock();
        return r;
    }

}
