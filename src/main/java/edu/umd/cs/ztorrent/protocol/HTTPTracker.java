package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;

import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HTTPTracker extends Tracker {
    Torrent torrent;
    int port;
    String peerID;

    URL url;
    Event e;
    int total = 0;
    long delay;


    public HTTPTracker(String urlString) {
        try {
            delay = 1000;
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
        this.torrent = t;
        process(t, Event.started);
    }

    @Override
    public void work() {
        process(torrent, e);
    }

    @Override
    protected long getDelayMS() {
        return delay;
    }

    @Override
    public void update(Torrent t) {
        return;
    }

    @Override
    public void close(Torrent t) {
        process(t, Event.stopped);
    }

    @Override
    public void complete(Torrent t) {
        process(t, Event.completed);
    }

    private void process(Torrent torrent, Event event) {
        try {
            String query = url.getQuery();
            if (query == null) {
                query = "?";
            } else {
                query = "?" + query + "&";
            }

            String e;
            if (event == Event.started) {
                e = "&event=started";
            } else if (event == Event.stopped) {
                e = "&event=stopped";
            } else if (event == Event.completed) {
                e = "&event=completed";
            } else if (peerID != null) {
                e = "" + peerID;
            } else {
                e = "";
            }

            String getRequest = "GET " + url.getPath() + query + "info_hash=" + ParserTorrentFile.urlEncode(torrent.hashInfo) + "&peer_id=" + ParserTorrentFile.urlEncode(torrent.peerID) + "&uploaded=" + torrent.getUploaded()
                    + "&downloaded=" + torrent.getDownloaded() + "&left=" + torrent.getLeftToDownload() + "&compact=1" + "&port=" + torrent.torrentPort + e + " HTTP/1.1\nHost: " + url.getHost() + "\n\n";

            Socket socket = new Socket();
            socket.setSoTimeout(8 * 1000);
            socket.connect(new InetSocketAddress(url.getHost(), port));
            OutputStreamWriter osw = new OutputStreamWriter(socket.getOutputStream());
            osw.write(getRequest);
            osw.flush();

            HTTPResponse response = new HTTPResponse(socket.getInputStream());
            socket.close();

            System.out.println("[HTTPRESPONSE] Response Status: " + response.status);
            System.out.println("[HTTPRESPONSE] Response Body:\n " + new String(response.body, StandardCharsets.UTF_8));
            System.out.println("\n\n");
            if (response.status == 200) {
                Bencoder b;
                try {
                    b = new Bencoder(response.body);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return;
                }

                if (b.dictionary.containsKey("failure reason")) {
                    System.out.println("[ERROR] Bencoding response failure");
                } else {
                    System.out.println("[TRACKER] Tracker Results: ");
                    delay = b.dictionary.get("interval").integer * 1000;
                    if (b.dictionary.get("peers").type == BencodeType.String) {
                        byte[] peers = b.dictionary.get("peers").byteString;
                        for (int i = 0; i < peers.length / 6; i++) {
                            byte[] addr = new byte[4];
                            System.arraycopy(peers, i * 6, addr, 0, 4);
                            InetAddress ip = InetAddress.getByAddress(addr);
                            int port = ((peers[i * 6 + 4] & 0xFF) << 8 | (peers[i * 6 + 5] & 0xFF));
                            System.out.println("[PEER] " + ip.getHostAddress() + ":" + port);
                            torrent.addPeer(ip, port, null);
                            total++;
                        }

                    } else if (b.dictionary.get("peers").type == BencodeType.List) {
                        List<Bencoder> list = b.dictionary.get("peers").list;
                        for (Bencoder dict : list) {
                            InetAddress ip = InetAddress.getByName(new String(dict.dictionary.get("ip").byteString, StandardCharsets.UTF_8));
                            int port = new Integer(new String(dict.dictionary.get("ip").byteString, StandardCharsets.UTF_8));
                            torrent.addPeer(ip, port, null);
                        }
                    } else {
                        System.out.println("[UNKNOWN PEER] Peers is not dictionary or string.");
                    }

                    if (b.dictionary.containsKey("tracker id")) {
                        peerID = new String(b.dictionary.get("tracker id").byteString, StandardCharsets.UTF_8);
                        System.out.println("[TRACKER] \"" + peerID + "\"");
                    }

                }

                this.e = Event.regular;

            } else {
                System.out.println("[HTTPRESPONSE] Response: " + response.status);
                System.out.println("[HTTPRESPONSE] Response Body:\n " + new String(response.body, StandardCharsets.UTF_8));
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


    public enum Event {started, stopped, completed, regular}
}
