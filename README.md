# ZTorrent

BitTorrent Notes, Tracker, and Torrents
We’ll use this post for on-going notes about the BitTorrent assignment.

Extra Credit Rubric

The maximum extra credit you can get is 30% of the project grade. The points will be given based on how much work has been done. DO NOT do the bonus tasks before the core functionality is working.

Points for each task:

UDP Tracker - 5%
Optimistic Unchoking - 5%
Rarest-first strategy - 5%
Endgame mode - 5%
BitTyrant - 10%
PropShare - 10%
No bonus points will be given if the core functionality is incomplete.

Tracker
We’re running an HTTP and UDP tracker on the VM for testing purposes.

Be sure to checkout what compact does to tracker responses.

Torrents
I’ve attached two torrents that you may use for testing.

odyssey.txt.torrent: A text file holding all of Homer’s Odyssey. About 596KB.
2022-04-04-raspios-bullseye-armhf.img.xz.torrent: From Raspberry Pi’s site: Raspberry Pi OS with Desktop. About 837MB.
Made from transmission (will be a different swarm due to different info hashes)

 odyssey-transmission.txt.torrent
 2022-04-04-raspios-bullseye-armhf-transmission.img.xz.torrent
Using These Torrents
Recall that your classmates will be using these torrents as well. Meaning you’re likely to run into peers that are misbehaving, not uploading, not downloading, etc. So, just be aware of this when testing with these.

Using Other Torrents
We’d recommend looking at software distributions, such as Linux distros and other educational or open source materials for torrents.

Note that since you’re likely not implementing other features such as encryption, many other peers may not share with you - sorry.

Wireshark
You can filter Wireshark for BitTorrent messages only via the bittorrent filter.

BitTorrent Clients
Note When using a standard BitTorrent client, such as Transmission, you may have encryption enabled. In the preferences > Peers uncheck “Prefer encrypted peers”. So if you didn’t see messages in Wireshark before hand, you should now. You should also uncheck uTP (micro transport protocol which utilizes UDP).

Similar options will be in other BitTorrent clients as well.

You can turn off DHT, PeX, and LSD - at least when using the class torrents. I’ve made the torrents we provided as private, so it shouldn’t share them with a DHT.

Bencode
Feel free to share if the originally listed bencoders don’t work, or if your language didn’t have one listed and you found one that worked. As other students will have similar issues. So, we might as well crowdsource the libraries we like/know work.

Python: I believe I’ve used this one but don’t 100% recall if there were any issues. If you have others that work for you, feel free to share.
