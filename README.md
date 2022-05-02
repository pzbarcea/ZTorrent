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

# About zTorrent

## Features

> ***Communicate with the tracker (with support for compact format).*** 

The Tracker class seems to be responsible with that. Not sure what Compact Format is.

more: [BitTorrent Tracker](https://en.wikipedia.org/wiki/BitTorrent_tracker)

> ***Download a file from other instances of your client.***

In order to do that, we need to setup a torrent server ourselves and have 2 instances connecting to the torrent server, and try to download content from a client to another. This connectivity needs to be documented.

Steps:

1. Run `opentracker` (using [official docker image](https://hub.docker.com/r/lednerb/opentracker-docker)):

```shell
docker run -d --name opentracker -p 6969:6969/udp -p 6969:6969 lednerb/opentracker-docker
```

2. Create a `.torrent` file:
```shell
❯ mktorrent -v -p -a http://192.168.1.176:6969/announce -o platon-cave.torrent ~/Downloads/Platon\ Cave.pdf
```

3. Start client and load torrent. You should see 1 peer and downloading. This is how you test that you can download from one client to another.

more: [hosting your own remote private torrent tracker](http://troydm.github.io/blog/2013/04/24/hosting-your-own-remote-private-torrent-tracker/)

> ***Implement optimistic unchoking*** (see “Choking and Optimistic Unchoking”)

Done. 

# Problem encountered

* Needed to set up a `Makefile` setup equivalent for Java. For this I used `mvn` - Apache Maven
* 

# FAQ

- What is the UI built in ?

It is built using Java AWT with the IntelliJ UI Builder.

# TODO

## Homework

1. Performance Test as UnitTest

> You need to devise experiments to demonstrate that your client’s performance is ‘fast enough’ and ‘stable’ in comparison to the ref-erence BitTorrent client.

This implies that a `TestUnit` should be created to evaluate performance.

2. Document with the following details:

>
> * List of supported features
> * Design and implementation choices that you made
>   * Class diagram
>   * Main class
>   * CLI/UI
> * Problems encountered (and if/how you addressed them)
> * Known bugs or issues in your implementation
> * Contributions made by each group member

## Cosmetics

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
* Review and rephrase the documentation in `ManagedConnection.java`