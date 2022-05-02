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

* Release before submitting to teacher with `2022.0.1`
* Refactor packaging from `Main` to `edu.umd.cs.ztorrent`
* Refactor `Logic` package to something else, as doesn't mean anything.
* Refactor all 6 main classes and decide which ones are useful (to keep) and which ones to destroy.
* Refactor `NetBase` package to something else
* Refactor `Primitives` to something else
* Refactor `TorrentData` to something else
* Maybe Refactor `Trackers` to something else.
* Refactor `Utils` to something else.