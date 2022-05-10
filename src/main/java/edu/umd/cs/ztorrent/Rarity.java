package edu.umd.cs.ztorrent;

public class Rarity implements Comparable<Rarity> {
    private short value;
    public final int index;

    public Rarity(int index) {
        this.index = index;
    }

    @Override
    public int compareTo(Rarity o) {
        return o.value - value;
    }

    public short getValue() {
        return value;
    }

    public void setValue(short v) {
        this.value = v;
    }
}
