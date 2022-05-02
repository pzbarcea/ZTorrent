package edu.umd.cs.ztorrent;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;

/**
 *Quick test. Opens up torrent file in a different path (so it wont load the files thats already
 * there). This assumes your running the lion share client, and it's seeing "ubuntu.torrent".
 * 
 * Either lion share seeds this or this seeds lion share set it up however you'd like.
 * either way it should work.
 * 
 *@author wiselion
 *
 */
public class SeedTest {
	public static void main(String [] args) throws NoSuchAlgorithmException, IOException, InterruptedException{
		System.out.println(new File("").getAbsolutePath().toString()+"java");
		Torrent t = TorrentParser.parseTorrentFile(new File("").getAbsolutePath().toString()+"/src/test/resources/ubuntu-22.04-live-server-amd64.iso.torrent");
		BasicPeerLogic bpl = new BasicPeerLogic();
		t.addPeer(InetAddress.getByName("127.0.0.1"),6881, null);
		while(true){
			bpl.doWork(t);
			Thread.sleep(10);
		}
	}
}