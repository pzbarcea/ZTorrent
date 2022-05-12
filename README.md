# ztorrent

Group bittorrent assignment
by Paul Zbarcea and Harvey Sun

## Getting started

Building

```sh
mvn clean install
```

Running:
```sh
java -jar target/ztorrent-1.0-SNAPSHOT.jar
```

or using `exec-maven-plugin`

```sh
mvn exec:java
```

## Features

> ***Communicate with the tracker (with support for compact format).*** 

Supported

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
mktorrent -v -p -a http://172.28.95.207:6969/announce -o testFile.torrent /var/testFile.txt
```

3. Start client and load torrent. You should see 1 peer and downloading. This is how you test that you can download from one client to another.

more: [hosting your own remote private torrent tracker](http://troydm.github.io/blog/2013/04/24/hosting-your-own-remote-private-torrent-tracker/)
