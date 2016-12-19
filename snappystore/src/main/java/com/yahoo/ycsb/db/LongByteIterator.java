package com.yahoo.ycsb.db;


import com.yahoo.ycsb.ByteIterator;

public class LongByteIterator extends ByteIterator {
    private final long val;
    private int pos = 7;

    public LongByteIterator(long l) {
        val = l;
    }

    @Override
    public boolean hasNext() {
        return pos >= 0;
    }

    @Override
    public byte nextByte() {
        return (byte) (val >> (8 * pos--));
    }

    @Override
    public long bytesLeft() {
        return pos + 1;
    }

    public long toLong() {
        return val;
    }
}
