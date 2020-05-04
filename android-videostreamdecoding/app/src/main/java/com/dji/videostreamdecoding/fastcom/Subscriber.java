package com.dji.videostreamdecoding.fastcom;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Vector;



public class Subscriber<T_ extends  Byteable>  {

    private DatagramSocket socket_;
    private Thread listenThread_;
    private T_ mother_;
    private String ip_;
    private int port_;
    public Boolean run_;

    public Vector<Callable> callbacks_ = new Vector<Callable>();

    public Subscriber(String _ip, int _port, T_ _mother){
        mother_ = _mother;
        ip_ = _ip;
        port_ = _port;
        run_ = true;

        try {
            socket_ = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }

        // Send query connection
        byte[] queryPacket = new byte[1];
        InetAddress addr = null;
        try {
            addr = InetAddress.getByName(_ip);
            DatagramPacket receivePacket = new DatagramPacket(queryPacket, queryPacket.length, addr, _port);
            socket_.send(receivePacket);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }catch (IOException e) {
            e.printStackTrace();
        }

        listenThread_ = new Thread(new Runnable() {
            @Override
            public void run() {
                while(run_){
                    byte[] recvBuf = new byte[mother_.getSize()];
                    DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
                    try {
                        socket_.receive(receivePacket);
                    } catch (IOException e) {
                        run_ = false;
                        e.printStackTrace();
                        continue;
                    }

                    T_ child = mother_;

                    child.parse(recvBuf);

                    for (Callable cb:callbacks_) {
                        cb.run(child);
                    }

                }
            }
        });

        listenThread_.start();
    }

    public void registerCallback(Callable<T_> _cb){
        callbacks_.add(_cb);
    }
}
