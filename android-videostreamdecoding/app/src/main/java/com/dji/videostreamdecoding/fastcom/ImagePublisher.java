package com.dji.videostreamdecoding.fastcom;

import java.io.ByteArrayInputStream;
import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfByte;

import org.opencv.imgcodecs.Imgcodecs;

public class ImagePublisher {

    private static final String TAG = "FASTCOM-Image Publisher";
    private Publisher<ImageDataPacket> packetPublisher_;
    private int imagePacketSize_ = 1024;

    private MatOfInt params_;
    private boolean isFirst_ = true;
    private int packetId_ = -1;

    public ImagePublisher(int _port){
        packetPublisher_ = new Publisher<>(_port);
        params_ = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 20);
        Log.d(TAG,"Image publisher created");
    }

    public void publish(Mat _image){

        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".jpg",_image, buffer, params_);
        byte [] array = buffer.toArray();
        ByteArrayInputStream inputBuffer = new ByteArrayInputStream(array);

        Log.d(TAG,"Buffer image size: " + inputBuffer.available());

        int numPackets = inputBuffer.available()/imagePacketSize_ + 1;
        int totalSize = inputBuffer.available();

        for(int i=0 ; i < numPackets; i++){
            ImageDataPacket packet = new ImageDataPacket();
            packet.packetId = i;
            packet.isFirst = (i == 0) ? true : false;
            packet.numPackets = numPackets;
            packet.totalSize = totalSize;
            packet.packetSize = (inputBuffer.available() > imagePacketSize_) ? imagePacketSize_ : inputBuffer.available();
            // packet.buffer = new byte [packet.packetSize];
            if (inputBuffer.read(packet.buffer, 0, packet.packetSize ) != -1) {
//                Log.d(TAG, "Packet :" + String.valueOf(packet.packetId + 1) + "/" + String.valueOf(packet.numPackets) +
//                        ". Size: " + String.valueOf(packet.packetSize) +
//                        ". buffer available: " + String.valueOf(inputBuffer.available()) + "/" + String.valueOf(packet.totalSize));
                packetPublisher_.publish(packet);
            }
        }
        return;
    }

}
