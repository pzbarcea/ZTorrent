package edu.umd.cs.ztorrent.message;

public enum MessageType {
        CHOKE,
        UNCHOKE,
        INTERESTED,
        NOT_INTERESTED,
        HAVE,
        BIT_FILED,
        REQUEST,
        PIECE,
        CANCEL,
        EXTENSION
}