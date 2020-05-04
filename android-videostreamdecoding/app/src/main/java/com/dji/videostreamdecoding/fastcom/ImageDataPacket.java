package com.dji.videostreamdecoding.fastcom;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class ImageDataPacket implements Byteable {
    public int PACKET_SIZE = 1024;
    public Boolean isFirst = false;
    public int packetId = 0;
    public int numPackets = 0;
    public int totalSize = 0;
    public int packetSize = 0;
    public byte[] buffer = new byte[1024];

    private byte[] createBytesArray(byte [] inputArray, int targetIndex, int lenghtData, int data){
        for(int i = 0 ; i < lenghtData; i++)
            inputArray[i+targetIndex] = (byte) (data >> 8*i);

        return inputArray;
    }

    @Override
    public byte[] getBytes() {
        byte [] bytes = new byte [24+buffer.length];

        bytes = createBytesArray(bytes, 0, 4, PACKET_SIZE);

        int iIsFirst = isFirst? 1:0;
        bytes = createBytesArray(bytes, 4, 4, iIsFirst);
        bytes = createBytesArray(bytes, 8, 4, packetId);
        bytes = createBytesArray(bytes, 12, 4, numPackets);
        bytes = createBytesArray(bytes, 16, 4, totalSize);
        bytes = createBytesArray(bytes, 20, 4, packetSize);
        System.arraycopy(buffer,0,bytes,24,buffer.length);
//        Log.d("ImageDataPacket"," buffer lenght: " + buffer.length + " buffer: " + buffer);

        return bytes;
    }

    @Override
    public int getSize() {
        return      4   // Int with size of packet
                +   4   // This should be a boolean, but packet is more efficient with ints, so faking it with int as it comes from c
                +   4   // Int Packet id
                +   4   // int num packets
                +   4   // int total size
                +   4   // int packet size
                +   1024;   // real buffer
    }

    @Override
    public void parse(byte[] _data) {
        ByteBuffer buf = ByteBuffer.wrap(_data);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        PACKET_SIZE = 12;
        PACKET_SIZE = buf.getInt();
        isFirst =  !(buf.getInt() == 0);
        packetId = buf.getInt();       /*hardcoding*/ isFirst = packetId == 0;
        numPackets = buf.getInt();
        totalSize = buf.getInt();
        packetSize = buf.getInt();
        buffer = Arrays.copyOfRange(buf.array(), buf.position(), buf.limit());
    }
}