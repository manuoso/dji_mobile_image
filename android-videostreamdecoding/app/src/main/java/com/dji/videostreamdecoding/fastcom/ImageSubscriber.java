package com.dji.videostreamdecoding.fastcom;

import android.renderscript.Matrix3f;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class ImageSubscriber {

    private Subscriber<ImageDataPacket> packetSubscriber_;

    private boolean isFirst_ = true;
    private int packetId_ = -1;
    private ByteArrayOutputStream totalBuffer_ = new ByteArrayOutputStream();

    public Vector<Callable> callbacks_ = new Vector<Callable>();

    public ImageSubscriber(String _ip, int _port){
        packetSubscriber_ = new Subscriber<>(_ip, _port, new ImageDataPacket());

        packetSubscriber_.registerCallback(new Callable<ImageDataPacket>() {
            @Override
            public void run(ImageDataPacket _data) {
                if(isFirst_ == _data.isFirst){
                    isFirst_ = false;

                    if(packetId_+1 == _data.packetId){
                        totalBuffer_.write(_data.buffer,0,_data.packetSize);
                        packetId_++;
                        //Log.d("EAGLE_STREAMER", "Packet :" +String.valueOf(_data.packetId)+"/"+String.valueOf(_data.numPackets)+
                        //        ". Size: " +String.valueOf(_data.packetSize)+
                        //        ". buffer: "+String.valueOf(totalBuffer_.size())+"/"+String.valueOf(_data.totalSize));

                        if(totalBuffer_.size() == _data.totalSize){
                            // Decode buffer
                            Mat encodedImg = new Mat(1, totalBuffer_.size(), CvType.CV_8U);
                            encodedImg.put(0, 0, totalBuffer_.toByteArray());
                            Mat image = Imgcodecs.imdecode(encodedImg, 1);
                            Imgproc.cvtColor(image, image, Imgproc.COLOR_RGB2BGR);
                            // Call callbacks
                            for (Callable cb:callbacks_) {
                                cb.run(image);
                            }

                            // Reset data
                            isFirst_ = true;
                            packetId_ = -1;
                            totalBuffer_.reset();
                        }
                    }
                }
            }
        });
    }

}


