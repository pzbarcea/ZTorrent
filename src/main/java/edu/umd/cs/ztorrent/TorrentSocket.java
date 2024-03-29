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

public class TorrentSocket {
    private List<PeerConnection> connections;
    private ServerSocket tcpServer;
    private ReentrantLock clientsLock = new ReentrantLock();
    boolean alive = true;
    Thread th;

    public TorrentSocket(int port) throws IOException {
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
                }


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
        connections = new LinkedList<>();
        clientsLock.unlock();
        return r;
    }

}
