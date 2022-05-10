"Along with your implementation, you must submit a report that details:"
1. List of supported features
2. Design and implementation choices that you made
3. Problems that you encountered (and if/how you addressed them)
4. Known bugs or issues in your implementation
5. Contributions made by each group member

### 1. List of supported features
* Compile, Run, and Run Tests in Maven
  * UnitTests in Maven run automatically on build
  * Have commands like mvn:exec java that make things easier
  * Can be configured in pom.xml
    * For example, running the CLI with specific arguments
* CLI and GUI
  * Basic support for CLI command options
  * Not many options yet, but the backbone is there
* DHT, HTTP, and UDP Tracker all supported
  * No HTTPS (bc not required by spec, but this causes some issues)
    * Cannot support all torrents, such as ubuntu.iso torrents
    * Cannot support all peers, such as those wanting secure connections
  * No magnet links (yet), wasn't working --> removed
* Can upload and download files at the same time
* Can connect to multiple peers, including both commercial clients and other ztorrent instances
* Relatively fast speeds (800 Mb/5min) <-**Check on this - harvey has stats**
* ... add more here

## 2. Design and Implementation choices
* First step: Choose a language
  * Project spec says speed is important, and so Java and C++ were candidates over Python since they 
are both compiled languages and I thought they would be faster. Go was also in consideration,
but we didn't know enough about it and decided it would take too long to learn. So we decided on Java. 
Java also has the benefit of being well-documented and having plenty of libraries,
so we thought this could be useful to us as well.
* Second step: Understand the protocol and begin designing the program
  * Protocol has a lot of moving parts, so we needed to understand how each part works and how
they should be expected to interoperate
  * Used wireshark to inspect uTorrent and other clients
  * Read through wikipedia entry
  * Read through Bencode protocol
  * Made a list of possible classes, functions we would need
  * Took a look at open source implementations to get ideas of how things work
    * tTorrent
      * https://github.com/mpetazzoni/ttorrent
    * http://www.kristenwidman.com/blog/33/how-to-write-a-bittorrent-client-part-1/
    * https://allenkim67.github.io/programming/2016/05/04/how-to-make-your-own-bittorrent-client.html
    * https://blog.jse.li/posts/torrent/
* Next: Begin writing code
* Need to first start by parsing, meaning we need the Bencode utilities
  * Create a Bencoder class with everything we need
    * Looked at references like https://stackoverflow.com/questions/1664124/bencoding-binary-data-in-java-strings
* ... add more here

## 3. Problems we encountered
* Add stuff here...

## 4. Known bugs and Issues
* Get nullPointerException if file is removed while GUI is working on it
* I don't think the .partial files are ever removed/cleaned
* No support for deleting the actual file(s) (not just removing .torrent from GUI)

## 5. Contributions
* Figure this out last
* Probably something like:
  * Maven setup - Paul
  * basic implement - Paul
  * Bencoding - Harvey
  * Magnet Links - Harvey (but didn't make it into the final implementation)
  * GUI - Harvey 
  * Rarity class and implementation (to be used for extension) - Harvey
