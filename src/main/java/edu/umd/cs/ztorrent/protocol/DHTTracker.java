package edu.umd.cs.ztorrent.protocol;

import edu.umd.cs.ztorrent.BencodeType;
import edu.umd.cs.ztorrent.Bencoder;
import edu.umd.cs.ztorrent.Torrent;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Explanation from https://forum.utorrent.com/topic/75810-whats-dht/
 * DHT (Distributed "sloppy" Hash Table, technical explanation) is an addition to certain BitTorrent clients that allows them to work without a tracker. What this means is that your client will be able to find peers even when the tracker is down, or doesn't even exist anymore. It allows the swarm to continue as normal without a tracker. You can also host torrents without a tracker.
 *
 * This means, your ratio wont get updated if you enable DHT from your torrent client (ie: BitComet, uTorrent, Azureus) when downloading from xxxxxxxxxx tracker. Nowadays, most new torrent client come with DHT, so pls disable it unless you are downloading from other websites that do not implement ratio method.
 *
 * In most clients, you have the option to enable or disable DHT.
 *
 * Implementing DHT, allows us to use "Magnet Links"
 */
public class DHTTracker extends Tracker {
    Thread thread;
    ID id;
    int total = 0;

    Map<ID, Node> idToNode;
    Map<ID, Map<String, Request>> idToRequestsO;

    Queue<Packet> requests;
    Queue<Packet> responses;
    DatagramSocket clientSocket;

    private long lastRoot = 0;
    private boolean havePeers = false;
    private byte[] infoHash;
    private List<Node> nodesToRemove = new ArrayList<>(0);
    private List<String> packetCleaner = new ArrayList<>(0);
    private List<TorrentConnection> potentialPeers = new ArrayList<>();
    private long startTime = System.currentTimeMillis();

    public DHTTracker(byte[] infoHash, byte[] PeerID) throws SocketException {
        this.infoHash = infoHash;
        clientSocket = new DatagramSocket();
        idToNode = new HashMap<>();
        idToRequestsO = new HashMap<>();
        requests = new ConcurrentLinkedQueue<>();
        responses = new ConcurrentLinkedQueue<>();
        id = new ID(PeerID);
        thread = new Thread() {
            boolean running = true;
            DatagramPacket myPacket = new DatagramPacket(new byte[65000], 65000);

            @Override
            public void run() {
                while (running) {
                    try {
                        clientSocket.receive(myPacket);
                        byte[] d = new byte[myPacket.getLength()];
                        System.arraycopy(myPacket.getData(), myPacket.getOffset(), d, 0, myPacket.getLength());
                        Bencoder b = new Bencoder(d);
                        ID i = null;
                        if (b.dictionary.containsKey("r") && b.dictionary.get("r").dictionary.containsKey("id")) {
                            i = new ID(b.dictionary.get("r").dictionary.get("id").byteString);
                        } else if (b.dictionary.containsKey("a") && b.dictionary.get("a").dictionary.containsKey("id")) {
                            i = new ID(b.dictionary.get("a").dictionary.get("id").byteString);
                        } else {
                            System.out.println("[ERROR] Invalid in message");
                            return;
                        }

                        Node n = new Node(i, myPacket.getPort(), myPacket.getAddress());
                        String s = b.dictionary.get("y").getString();
                        if (s.equals("e")) {
                            System.out.println("[ERROR] Invalid dictionary");
                            return;
                        } else if (s.equals("r")) {
                            System.out.println("[RESPONSE] Response from " + n);
                            responses.add(new Packet(b, n));
                        } else if (s.equals("a")) {
                            System.out.println("[REQUEST] Request from " + n);
                            requests.add(new Packet(b, n));
                        } else {
                            continue;
                        }

                        Thread.sleep(10);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (RuntimeException r) {
                        System.out.println("[ERROR] RuntimeException DHTTracker");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        running = false;
                    }

                }
            }
        };
        thread.start();

    }

    private byte[] getClosest(final byte[] target) {
        List<Node> nodes = new ArrayList<Node>();
        for (Node node : idToNode.values()) {
            nodes.add(node);
        }
        //The smaller one will be closer
        Collections.sort(nodes, (o1, o2) -> {
            BigInteger a = kademliaAlgorithm(o1.nodeId.id, target);
            BigInteger b = kademliaAlgorithm(o2.nodeId.id, target);
            return a.compareTo(b);
        });
        int size = nodes.size() >= 8 ? 8 * 26 : nodes.size() * 26;

        byte[] bytes = new byte[size];

        for (int i = 0; (i < 8 && i < nodes.size()); i++) {
            Node n0 = nodes.get(i);
            System.arraycopy(n0.nodeId.id, 0, bytes, i * 26, 20);
            System.arraycopy(n0.ip.getAddress(), 0, bytes, i * 26 + 20, 4);
            bytes[i * 26 + 20 + 4] = (byte) ((n0.port >> 8) & 0xFF);
            bytes[i * 26 + 20 + 5] = (byte) (n0.port & 0xFF);
        }

        return bytes;
    }

    @Override
    protected void work() {
        try {
            if (idToNode.size() == 0 && (System.currentTimeMillis() - lastRoot > 3 * 1000)) {
                byte[] ping = constructPing(id, "ZZ");
                DatagramPacket dp0 = new DatagramPacket(ping, ping.length, InetAddress.getByName("dht.transmissionbt.com"), 6881);
                DatagramPacket dp1 = new DatagramPacket(ping, ping.length, InetAddress.getByName("router.utorrent.com"), 6881);
                DatagramPacket dp2 = new DatagramPacket(ping, ping.length, InetAddress.getByName("router.bittorrent.com"), 6881);
                clientSocket.send(dp0);
                clientSocket.send(dp1);
                clientSocket.send(dp2);
                lastRoot = System.currentTimeMillis();
            }

            processRequests();

            processResponses();


            // http://bittorrent.org/beps/bep_0005.html (DHT protocol explained here, including Kademlia algo)
            // Search or send requests
            Node nearestNode = null;
            BigInteger i = new BigInteger(1, new byte[]{-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1});//large.
            for (Node n : idToNode.values()) {
                if (!n.canTalkToNode()) {
                    continue;
                }
                BigInteger diff = kademliaAlgorithm(infoHash, n.nodeId.id);
                if (diff.compareTo(i) <= 0) {
                    nearestNode = n;
                    i = diff;
                }

            }

            // Limit number of requests (every 3 seconds)
            if (nearestNode != null && nearestNode.canTalkToNode()) {
                nearestNode.lastQuery = nextFromLast(nearestNode.lastQuery);

                byte[] findNode = get_peers(id, nearestNode.lastQuery, infoHash);
                System.out.println("[CONTACTING] Requesting peers from: " + nearestNode);

                DatagramPacket packet = new DatagramPacket(findNode, findNode.length, nearestNode.ip, nearestNode.port);
                clientSocket.send(packet);

                nearestNode.timeSinceLastSent = System.currentTimeMillis();
                Map<String, Request> requestMap = idToRequestsO.get(nearestNode.nodeId);


                if (requestMap == null) {
                    requestMap = new HashMap<>();
                }

                requestMap.put(nearestNode.lastQuery, new Request(RequestType.get_peers));

                idToRequestsO.put(nearestNode.nodeId, requestMap);

                nearestNode.drops++;
            }

            nodesToRemove.clear();
            for (Node n : idToNode.values()) {
                if (n.drops > 7 || System.currentTimeMillis() - n.timeSinceLastRecv > 15 * 60 * 1000) {
                    nodesToRemove.add(n);
                }
            }
            for (Node n : nodesToRemove) {
                System.out.println("[CLEANING] Cleaning Node: " + n.toString());
                idToNode.remove(n.nodeId);
                idToRequestsO.remove(n.nodeId);
            }
            nodesToRemove.clear();

            // Keep closest nodes (right now only top 40 nodes are kept)
            if (idToNode.size() > 40) {
                List<Node> allNodes = new ArrayList<>(idToNode.values());
                Collections.sort(allNodes, (o1, o2) -> {
                    BigInteger a = kademliaAlgorithm(o1.nodeId.id, infoHash);
                    BigInteger b = kademliaAlgorithm(o2.nodeId.id, infoHash);
                    return a.compareTo(b);
                });
                for (int z = 40; z < allNodes.size(); z++) {
                    nodesToRemove.add(allNodes.get(z));
                }
            }

            for (Node n : nodesToRemove) {
                //If n gave us client list, then it is still useful to us and don't drop it (yet)
                if (n.gaveClients) {
                    continue;
                }

                System.out.println("[CLEANING] Cleaning node: " + n);

                idToNode.remove(n.nodeId);
                idToRequestsO.remove(n.nodeId);
            }

            // Clean "active" requests that dropped (requests that are labelled as active but really are dead)
            for (ID conId : idToRequestsO.keySet()) {
                Map<String, Request> requestMap = idToRequestsO.get(conId);
                if (requestMap != null) {

                    packetCleaner.clear();

                    for (String str : requestMap.keySet()) {
                        //If we have exceeded 1 minute timeout, then we drop the request
                        if (System.currentTimeMillis() - requestMap.get(str).timeCreated > 60 * 1000) {
                            System.out.println("[REQUEST TIMEOUT] Request " + str + " to peer " + conId.toString() + " timed out");
                            packetCleaner.add(str);
                        }
                    }


                    for (String s : packetCleaner) {
                        requestMap.remove(s);
                    }
                }
            }

        } catch (IOException e) {
            this.workingTracker = false;
        }

    }

    BigInteger kademliaAlgorithm(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new RuntimeException("[ERROR] Cannot Compare Different Sized Byte Arrays");
        }
        byte[] c = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = (byte) (a[i] ^ b[i]);
        }
        return new BigInteger(1, c);
    }

    private Bencoder msgBase(ID id, String q, boolean r) throws UnsupportedEncodingException {
        Bencoder b = new Bencoder();
        b.type = BencodeType.Dictionary;
        b.dictionary = new HashMap<>();
        Bencoder a = new Bencoder();
        a.type = BencodeType.Dictionary;
        a.dictionary = new HashMap<>();
        Bencoder i = new Bencoder();
        i.byteString = id.id;
        i.type = BencodeType.String;


        b.dictionary.put("t", new Bencoder(q));
        if (!r) {
            b.dictionary.put("y", new Bencoder("q"));
            b.dictionary.put("a", a);
        } else {
            b.dictionary.put("y", new Bencoder("r"));
            b.dictionary.put("r", a);
        }
        a.dictionary.put("id", i);

        return b;
    }

    byte[] constructPing(ID id, String q) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, false);
        b.dictionary.put("q", new Bencoder("ping"));
        return b.toByteArray();
    }

    byte[] constructNodeResponse(ID id, String q, byte[] nodes) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, true);
        b.dictionary.get("r").dictionary.put("nodes", Bencoder.encodeBytes(nodes));
        return b.toByteArray();
    }

    byte[] find_node(ID id, String q, byte[] target) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, false);
        b.dictionary.put("q", new Bencoder("find_node"));
        Bencoder btarget = new Bencoder();
        btarget.byteString = target;
        btarget.type = BencodeType.String;
        b.dictionary.get("a").dictionary.put("target", btarget);
        return b.toByteArray();
    }

    byte[] constructPeersResponseN(ID id, String q, byte[] nodes, byte[] token) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, true);
        b.dictionary.get("r").dictionary.put("token", Bencoder.encodeBytes(token));
        b.dictionary.get("r").dictionary.put("nodes", Bencoder.encodeBytes(nodes));
        return b.toByteArray();
    }

    byte[] constructPeerResponseP(ID id, String q, Bencoder peers, byte[] token) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, true);
        b.dictionary.get("r").dictionary.put("token", Bencoder.encodeBytes(token));
        b.dictionary.get("r").dictionary.put("values", peers);
        return b.toByteArray();
    }

    // From https://www.bittorrent.org/beps/bep_0005.html
    // A query for peers needs to return a token --
    // The BitTorrent implementation uses the SHA1 hash of the IP address concatenated
    // onto a secret that changes every five minutes and tokens up to ten minutes old are accepted.
    byte[] get_peers(ID id, String q, byte[] infoHash) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, false);
        b.dictionary.put("q", new Bencoder("get_peers"));
        Bencoder btarget = new Bencoder();
        btarget.byteString = infoHash;
        btarget.type = BencodeType.String;
        b.dictionary.get("a").dictionary.put("info_hash", btarget);
        return b.toByteArray();
    }

    byte[] constructAnnounceResponse(ID id, String q) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, true);
        return b.toByteArray();
    }

    byte[] announce_peer(ID id, String q, byte[] infoHash, byte[] token, boolean isport, int port) throws UnsupportedEncodingException {
        Bencoder b = msgBase(id, q, false);
        b.dictionary.put("q", new Bencoder("announce_peer"));
        Bencoder btarget = new Bencoder();
        btarget.byteString = infoHash;
        btarget.type = BencodeType.String;
        b.dictionary.get("a").dictionary.put("info_hash", btarget);
        Bencoder btoken = new Bencoder();
        btoken.byteString = token;
        btoken.type = BencodeType.String;
        b.dictionary.get("a").dictionary.put("token", btoken);
        b.dictionary.get("a").dictionary.put("port", new Bencoder("" + port));
        if (isport) {
            b.dictionary.get("a").dictionary.put("implied_port", new Bencoder("1"));
        } else {
            b.dictionary.get("a").dictionary.put("implied_port", new Bencoder("0"));
        }
        return b.toByteArray();
    }

    private void placeNodes(byte[] nodes) throws UnknownHostException {
        if (nodes.length % 26 != 0) {
            throw new RuntimeException("[MISMATCH] Got Nodes of differing Length");
        }

        int count = 0;

        for (int i = 0; i < nodes.length; i += 26) {
            ID id = new ID(Arrays.copyOfRange(nodes, i, i + 20));
            InetAddress ip = InetAddress.getByAddress(Arrays.copyOfRange(nodes, i + 20, i + 24));
            int port = ((nodes[i + 24] & 0xFF) << 8 | (nodes[i + 24 + 1] & 0xFF));
            if (!idToNode.containsKey(id)) {
                Node n = new Node(id, port, ip);
                idToNode.put(id, n);
                count++;
            }
        }
        System.out.println("[ADDED NODES] Nodes Added: " + count);
    }

    private void getPeers(byte[] peers) throws UnknownHostException {
        for (int i = 0; i < peers.length / 6; i++) {
            byte[] addr = new byte[4];
            System.arraycopy(peers, i * 6, addr, 0, 4);
            InetAddress ip = InetAddress.getByAddress(addr);
            int port = ((peers[i * 6 + 4] & 0xFF) << 8 | (peers[i * 6 + 5] & 0xFF));
            System.out.println("[PEER] " + ip.getHostAddress() + ":" + port);
            if (!Arrays.equals(ip.getAddress(), new byte[]{0, 0, 0, 0})) {
                potentialPeers.add(new TorrentConnection(ip, port));
            }

            total++;
        }
    }

    private String nextFromLast(String last) {
        char c0 = last.charAt(0);
        char c1 = (char) (last.charAt(0) + 1);
        if (c1 > 'z') {
            c1 = 'a';
            c0 += 1;
        }
        if (c0 > 'z') {
            c0 = 'a';
        }
        return new String(new char[]{c0, c1});
    }

    @Override
    protected long getDelayMS() {
        if (havePeers) {
            return 20000;
        }
        return 1000;
    }

    @Override
    public void update(Torrent t) {
        for (TorrentConnection mc : potentialPeers) {
            t.addConnection(mc.toManagedConnection(t));
        }
        potentialPeers.clear();
    }

    @Override
    public void close(Torrent t) {
        thread.stop();
    }

    private void processResponses() throws IOException {
        while (!responses.isEmpty()) {
            Packet p = responses.poll();
            if (idToRequestsO.containsKey(p.n.nodeId)) {
                Node actual = idToNode.get(p.n.nodeId);
                actual.in++;
                actual.recved = true;
                Map<String, Request> rMap = idToRequestsO.get(actual.nodeId);
                String s = p.b.dictionary.get("t").getString();
                Request request = rMap.remove(s);
                actual.drops = 0;
                if (request == null) {
                    continue;
                }
                if (request.rt == RequestType.ping) {
                    actual.timeSinceLastRecv = System.currentTimeMillis();
                } else if (request.rt == RequestType.get_peers) {
                    Bencoder r = p.b.dictionary.get("r");
                    if (r.dictionary.containsKey("values")) {
                        actual.gaveClients = true;
                        for (Bencoder b : r.dictionary.get("values").list) {
                            getPeers(b.byteString);
                        }
                        havePeers = true;
                    }

                    if (r.dictionary.containsKey("nodes")) {
                        placeNodes(r.dictionary.get("nodes").byteString);
                    }

                    if (r.dictionary.containsKey("token")) {
                        actual.lastToken = r.dictionary.get("token").byteString;
                    }
                } else if (request.rt == RequestType.find_node) {
                    actual.recved = true;
                    Bencoder nodes = p.b.dictionary.get("nodes");
                    placeNodes(nodes.byteString);
                } else if (request.rt == RequestType.announce_peer) {
                    actual.recved = true;
                    actual.announcedInto = true;
                }

            } else {
                if (!idToNode.containsKey(p.n.nodeId)) {
                    System.out.println("[NEW NODE]");
                    idToNode.put(p.n.nodeId, p.n);
                } else {
                    System.out.println("[ERROR] Invalid response (unsupported tracker)");
                }
                p.n.timeSinceLastRecv = System.currentTimeMillis();
            }
        }
    }

    private void processRequests() throws IOException {
        while (!requests.isEmpty()) {
            Packet p = requests.poll();
            Node n = idToNode.get(p.n.nodeId);
            if (n != null) {
                n.out++;
                if (n.in > 100) {
                    idToNode.remove(n);
                    idToRequestsO.remove(n.nodeId);
                }

                String s = p.b.dictionary.get("q").getString();
                if (!p.b.dictionary.containsKey("t")) {
                    continue;
                }
                Map<String, Bencoder> args = p.b.dictionary.get("a").dictionary;
                String t = p.b.dictionary.get("t").getString();

                byte[] payload;
                if (s.equals("ping")) {
                    payload = constructPing(id, t);
                } else if (s.equals("find_nodes")) {
                    if (!args.containsKey("target")) {
                        continue;
                    }
                    byte[] target = args.get("target").byteString;

                    byte[] nodes = getClosest(target);
                    payload = constructNodeResponse(id, t, nodes);
                } else if (s.equals("get_peers")) {
                    if (!args.containsKey("info_hash")) {
                        continue;
                    }
                    byte[] target = args.get("info_hash").byteString;
                    byte[] nodes = getClosest(target);
                    payload = constructPeersResponseN(id, t, nodes, new byte[]{65, 65, 65, 65});

                } else if (s.equals("announce_peer")) {
                    payload = constructAnnounceResponse(id, t);
                } else {
                    payload = constructPing(id, t);
                }

                DatagramPacket packet = new DatagramPacket(payload, payload.length, n.ip, n.port);
                clientSocket.send(packet);
            }

        }
    }

    public void restart() {
        idToNode.clear();
        idToRequestsO.clear();
        requests.clear();
        responses.clear();
    }

    @Override
    public String toString() {
        return "DHTTracker:" + id.toString();
    }

    @Override
    public int totalPeersObtained() {
        return total;
    }

    //https://www.bittorrent.org/beps/bep_0005.html
    // Different requests types
    private enum RequestType {
        ping, get_peers, find_node, announce_peer
    }

    private static class Packet {

        Bencoder b;
        Node n;

        public Packet(Bencoder b, Node n) {
            this.b = b;
            this.n = n;
        }
    }

    private static class Request {
        RequestType rt;
        long timeCreated = System.currentTimeMillis();

        public Request(RequestType rt) {
            this.rt = rt;
        }
    }

    private class ID {
        public int hash;
        public byte[] id;

        public ID(byte[] id) {
            this.id = id;
            int h = 0;
            for (int i = 0; i < id.length; i++) {
                h += Math.abs(id[i]);
            }
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ID) {
                ID i = (ID) o;
                return Arrays.equals(i.id, id);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            BigInteger big = new BigInteger(1, id);
            return String.format("%0" + (id.length << 1) + "X", big);
        }
    }

    private class Node {
        ID nodeId;
        int port;
        InetAddress ip;
        long timeSinceLastRecv = System.currentTimeMillis();
        long timeSinceLastSent = 0;
        byte[] lastToken;
        int drops = 0;
        boolean gaveClients = false;
        boolean announcedInto = false;
        int in = 1;
        int out = 1;
        boolean recved = false;
        String lastQuery = "zz";

        public Node(ID nodeId, int port, InetAddress ip) {
            super();
            this.nodeId = nodeId;
            this.port = port;
            this.ip = ip;
        }

        @Override
        public String toString() {
            BigInteger big = new BigInteger(1, nodeId.id);
            return String.format("%0" + (nodeId.id.length << 1) + "X", big);
        }

        private boolean canTalkToNode() {
            long now = System.currentTimeMillis();
            if (!recved) {
                return (now - timeSinceLastSent) > (3 * 1000);
            } else if (gaveClients) {
                return (now - timeSinceLastSent) > (15 * 60 * 1000);
            } else {
                return (now - timeSinceLastSent) > (2 * 60 * 1000);
            }
        }

    }
}
