package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.Bencoding;
import edu.umd.cs.ztorrent.Bencoding.Type;
import edu.umd.cs.ztorrent.HttpResponse;
import edu.umd.cs.ztorrent.HttpResponse.HeaderType;
import edu.umd.cs.ztorrent.Torrent;
import edu.umd.cs.ztorrent.TorrentParser;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HTTPTracker extends Tracker {
    public enum Event {started, stopped, completed, regular}

    URL url;
    int port;
    InetAddress address;
    String id;
    Event e;
    long wait;
    Torrent t;
    int total = 0;

    public HTTPTracker(String urlString) {
        try {
            wait = 1000;
            e = Event.started;
            url = new URL(urlString);
            port = url.getPort();
            if (port == -1) {
                port = 80;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void initialize(Torrent t) {
        this.t = t;
        work(t, Event.started);
    }

    @Override
    public void work() {
        work(t, e);
    }

    @Override
    protected long getWaitMS() {
        return wait;
    }

    @Override
    public void update(Torrent t) {
        return;
    }

    @Override
    public void close(Torrent t) {
        work(t, Event.stopped);
    }

    @Override
    public void complete(Torrent t) {
        work(t, Event.completed);
    }

    /***
     * REWORD: Updates torrent file with results from tracker
     *
     * Generates a GET request,
     */
    private void work(Torrent torrent, Event event) {
        try {
            String gq = url.getQuery();
            if (gq == null) {
                gq = "?";
            } else {
                gq = "?" + gq + "&";
            }

            String e;
            if (event == Event.started) {
                e = "&event=started";
            } else if (event == Event.stopped) {
                e = "&event=stopped";
            } else if (event == Event.completed) {
                e = "&event=completed";
            } else if (id != null) {
                e = "" + id;
            } else {
                e = "";
            }

            String getRequest = "GET " + url.getPath() + gq + "info_hash="
                    + TorrentParser.urlEncode(torrent.hashInfo) + "&peer_id=" + TorrentParser.urlEncode(torrent.peerID)
                    + "&uploaded=" + torrent.getUploaded() + "&downloaded="
                    + torrent.getDownloaded() + "&left=" + torrent.getLeftToDownload()
                    + "&compact=1" + "&port=" + torrent.uPnP_Port + e + " HTTP/1.1\r\nHost: " + url.getHost() + "\r\n\r\n";

            System.out.println(getRequest);
            Socket socket = new Socket();
            socket.setSoTimeout(8 * 1000);
            socket.connect(new InetSocketAddress(url.getHost(), port));
            OutputStreamWriter osw = new OutputStreamWriter(socket.getOutputStream());
            osw.write(getRequest);
            osw.flush();

            HttpResponse response = new HttpResponse(socket.getInputStream());
            socket.close();

            System.out.println("Response Status: " + response.status);
            System.out.println("Response length: " + response.contentSize);
            System.out.println("Actual length: " + response.body.length);
            System.out.println("ResponseOut:\n " + new String(response.body, StandardCharsets.UTF_8));
            System.out.println("Unknown:\n " + response.headerMap.get(HeaderType.UKNOWN));
            System.out.println("\n\n");
            if (response.status == 200) {
                Bencoding b;
                try {
                    b = new Bencoding(response.body);
                } catch (Exception ex) {
                    System.out.println("Tracker Connected. But response Not BENCODED?!");
                    ex.printStackTrace();
                    return;
                }

                if (b.dictionary.containsKey("failure reason")) {
                    System.out.println("Tracker Connected. But bencoded response failed with \"" + b.dictionary.get("failure reason").getString());
                } else {
                    System.out.println("Tracker Results: ");
                    wait = b.dictionary.get("interval").integer * 1000;
                    if (b.dictionary.get("peers").type == Type.String) {
                        byte[] peers = b.dictionary.get("peers").byteString;
                        for (int i = 0; i < peers.length / 6; i++) {
                            byte[] addr = new byte[4];
                            System.arraycopy(peers, i * 6, addr, 0, 4);
                            InetAddress ip = InetAddress.getByAddress(addr);
                            int port = ((peers[i * 6 + 4] & 0xFF) << 8 | (peers[i * 6 + 5] & 0xFF));
                            System.out.println("Peer: " + ip.getHostAddress() + ":" + port);
                            torrent.addPeer(ip, port, null);
                            total++;
                        }

                    } else if (b.dictionary.get("peers").type == Type.List) {
                        List<Bencoding> list = b.dictionary.get("peers").list;
                        for (Bencoding dict : list) {
                            InetAddress ip = InetAddress.getByName(new String(dict.dictionary.get("ip").byteString, StandardCharsets.UTF_8));
                            int port = new Integer(new String(dict.dictionary.get("ip").byteString, StandardCharsets.UTF_8));
                            torrent.addPeer(ip, port, null);
                        }
                    } else {
                        System.out.println("Peers is not dictionary or string.");
                    }

                    if (b.dictionary.containsKey("tracker id")) {
                        id = new String(b.dictionary.get("tracker id").byteString, StandardCharsets.UTF_8);
                        System.out.println("Trackerid:\"" + id + "\"");
                    }

                }

                this.e = Event.regular;

            } else {
                System.out.println("Response: " + response.status);
                System.out.println("ResponseOut:\n " + new String(response.body, StandardCharsets.UTF_8));
                this.workingTracker = false;
            }

        } catch (Exception e) {
            this.workingTracker = false;
        }

    }

    @Override
    public String toString() {
        return url.toString();
    }

    @Override
    public int totalPeersObtained() {
        return total;
    }


}
