package com.dji.videostreamdecoding.fastcom;

public interface Byteable{
    byte[] getBytes();
    int getSize();
    void parse(byte[] _data);
}

