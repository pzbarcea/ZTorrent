package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.*;
import edu.umd.cs.ztorrent.HTTPResponse.HeaderType;

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
        process(t, Event.started);
    }

    @Override
    public void work() {
        process(t, e);
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
            } else if (id != null) {
                e = "" + id;
            } else {
                e = "";
            }

            String getRequest = "GET " + url.getPath() + query + "info_hash="
                    + ParserTorrentFile.urlEncode(torrent.hashInfo) + "&peer_id=" + ParserTorrentFile.urlEncode(torrent.peerID)
                    + "&uploaded=" + torrent.getUploaded() + "&downloaded="
                    + torrent.getDownloaded() + "&left=" + torrent.getLeftToDownload()
                    + "&compact=1" + "&port=" + torrent.uPnP_Port + e + " HTTP/1.1\nHost: " + url.getHost() + "\n\n";

            Socket socket = new Socket();
            socket.setSoTimeout(8 * 1000);
            socket.connect(new InetSocketAddress(url.getHost(), port));
            OutputStreamWriter osw = new OutputStreamWriter(socket.getOutputStream());
            osw.write(getRequest);
            osw.flush();

            HTTPResponse response = new HTTPResponse(socket.getInputStream());
            socket.close();

            System.out.println("[HTTPRESPONSE] Response Status: " + response.status);
            System.out.println("[HTTPRESPONSE] Response length: " + response.contentSize);
            System.out.println("[HTTPRESPONSE] Actual length: " + response.body.length);
            System.out.println("[HTTPRESPONSE] ResponseOut:\n " + new String(response.body, StandardCharsets.UTF_8));
            System.out.println("[HTTPRESPONSE] Unknown:\n " + response.headerMap.get(HeaderType.UNKNOWN));
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
                    System.out.println("[STATUS] Tracker Results: ");
                    wait = b.dictionary.get("interval").integer * 1000;
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
                        System.out.println("[WARNING] Peers is not dictionary or string.");
                    }

                    if (b.dictionary.containsKey("tracker id")) {
                        id = new String(b.dictionary.get("tracker id").byteString, StandardCharsets.UTF_8);
                        System.out.println("[TRACKER] \"" + id + "\"");
                    }

                }

                this.e = Event.regular;

            } else {
                System.out.println("[HTTPRESPONSE] Response: " + response.status);
                System.out.println("[HTTPRESPONSE] ResponseOut:\n " + new String(response.body, StandardCharsets.UTF_8));
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
