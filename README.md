# zTorrent 
=============

Final Project of CMSC417 - Computer Networking

## Gettign started

Building

```sh
mvn clean install
```

Running:
```sh
java -jar target/cmsc417-1.0-SNAPSHOT.jar
```

or using `exec-maven-plugin`

```sh
mvn exec:java
```

# TODO

* Refactor packaging from `Main` to `edu.umd.cs.ztorrent`
* Read the homework appropriately.
* Release before submitting to teacher with `2022.0.1`
* Generate class diagram before submitting, also helps with documentation of the project:
![class diagram](/docs/images/class-diagram.png "Class diagram")
* Clean commented code.
* Refactor `Logic` package to something else, as doesn't mean anything.
* Refactor all 6 main classes and decide which ones are useful (to keep) and which ones to destroy.
```csh
ztorrent on  main [✘!] is 📦 v1.0-SNAPSHOT via ☕ v1.8.0 
❯ grep -nRI "void main(" --exclude-dir="target" . | grep "\.java" | grep -v "//" | awk -F':' '{print $1}' | sortrt -u
./src/main/java/Main/LionShare.java
./src/main/java/Main/SeedTest.java
./src/main/java/Main/UnitTests.java
./src/main/java/Primitives/Piece.java
./src/main/java/TorrentData/MagnetLink.java
./src/main/java/Trackers/DHTTracker.java
```
* Refactor `NetBase` package to something else
* Refactor `Primitives` to something else
* Refactor `TorrentData` to something else
* Maybe Refactor `Trackers` to something else.
* Refactor `Utils` to something else.
* Fix exception. This is maybe normal behavior when a peer-to-peer connection can't be established due to security limitations:
```log
java.net.ConnectException: Connection refused (Connection refused)
        at java.net.PlainSocketImpl.socketConnect(Native Method)
        at java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:350)
        at java.net.AbstractPlainSocketImpl.connectToAddress(AbstractPlainSocketImpl.java:206)
        at java.net.AbstractPlainSocketImpl.connect(AbstractPlainSocketImpl.java:188)
        at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:392)
        at java.net.Socket.connect(Socket.java:607)
        at java.net.Socket.connect(Socket.java:556)
        at NetBase.ManagedConnection$1.run(ManagedConnection.java:136)
GET /announce?info_hash=n%B0%B6F%C3%94T%2C%CA%FD%23%8D%F9%28w%A03%96%3A%F1&peer_id=aLBQqtFyFsFDmgjNErDT&uploaded=0&downloaded=0&left=0&compact=1&port=1010 HTTP/1.1
Host: tracker.raspberrypi.org
```
