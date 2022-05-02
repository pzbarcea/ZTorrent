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

# FAQ

- What is the UI built in ?

It is built using Java AWT with the IntelliJ UI Builder.

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
```
java.net.ConnectException: Connection refused (Connection refused)
```
