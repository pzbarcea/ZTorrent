package edu.umd.cs.ztorrent.message;

public class MessageRequest {
    public final int index, begin, len;
    public long timeSent;

    public MessageRequest(long i, long b, long l) {
        this.index = (int) i;
        this.begin = (int) b;
        this.len = (int) l;
    }

    public boolean equals(Object o) {
        return o instanceof MessageRequest && ((MessageRequest) o).index == index && ((MessageRequest) o).begin == begin && ((MessageRequest) o).len == len;
    }

    @Override
    //TODO: is it okay to multiply like this? Maybe our numbers will come out too big
    //TODO: test hashCode with larger values to see if it breaks
    public int hashCode() {
        return 2*index + 3*begin + 5*len;
    }
}
