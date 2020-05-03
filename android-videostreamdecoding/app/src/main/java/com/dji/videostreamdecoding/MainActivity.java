package com.dji.videostreamdecoding;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnClickListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

public class MainActivity extends Activity implements OnClickListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;

    private DJICodecManager mCodecManager = null;
    private DJICodecManager.YuvDataCallback mFrameListener = null;
    private int mCount;
    private byte[] mImgDecode;
    private int mWidth;
    private int mHeight;

    private int mSVal = 0;

    private Semaphore mMutex = new Semaphore(1);

    private TextView titleTv;
    private Button mBtVisual;
    private Button mBtMSX;
    private Button mBtThermal;
    private Button mBtSend;
    private SeekBar mSbMSX;

    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        notifyStatusChange();
        createCodec();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cleanCodecManager();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUi();
    }

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

    private void initUi() {
        mBtVisual = (Button) findViewById(R.id.btn_visual);
        mBtMSX = (Button) findViewById(R.id.btn_msx);
        mBtThermal = (Button) findViewById(R.id.btn_thermal);
        mBtSend = (Button) findViewById(R.id.btn_send);

        mSbMSX = (SeekBar) findViewById(R.id.seekbar_msx);

        titleTv = (TextView) findViewById(R.id.title_tv);

        mBtVisual.setOnClickListener(this);
        mBtMSX.setOnClickListener(this);
        mBtThermal.setOnClickListener(this);
        mBtSend.setOnClickListener(this);

        mSbMSX.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSVal = progress;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                //write custom code to on start progress
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                showToast("Val: " + mSVal);
            }
        });

    }

    private void notifyStatusChange() {

        final BaseProduct product = VideoDecodingApplication.getProductInstance();

        if (product != null && product.isConnected() && product.getModel() != null) {
            updateTitle(product.getModel().name() + " Connected ");
        } else {
            updateTitle("Disconnected");
        }

        if (product == null || !product.isConnected()) {
            showToast("Disconnected");
        } else {
            configureVisualImage();
        }
    }

    private void createCodec(){
        if(mCodecManager == null){
            if(mFrameListener == null){
                mFrameListener = new DJICodecManager.YuvDataCallback() {
                    @Override
                    public void onYuvDataReceived(MediaFormat mediaFormat, ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
                        if (mCount++ % 30 == 0 && yuvFrame != null) {
                            final byte[] bytes = new byte[dataSize];

                            yuvFrame.get(bytes);

                            try {
                                mMutex.acquire();
                                mImgDecode = new byte[bytes.length];
                                mWidth = width;
                                mHeight = height;

                                mImgDecode = yuvDataDecode(bytes, bytes.length, width, height);

                                if (mImgDecode.length < width * height) {
                                    // TODO if NOT decoded...
                                }else{
                                    // TODO if decoded...
                                }

                            } catch (InterruptedException e) {
                                // TODO
                            } finally {
                                mMutex.release();
                            }
                        }
                    }
                };
            }

            mCodecManager = new DJICodecManager(getApplicationContext(), null, 0, 0, UsbAccessoryService.VideoStreamSource.Camera);
            mCodecManager.enabledYuvData(true);
            mCodecManager.setYuvDataCallback(mFrameListener);

        }
    }

    private void cleanCodecManager(){
        if (mCodecManager != null) {
            mCodecManager.enabledYuvData(false);
            mCodecManager.setYuvDataCallback(null);

            mCodecManager.cleanSurface();
            mCodecManager.destroyCodec();
        }
    }

    /* ************************************************** Decode and utils ************************************************** */

    private byte[] yuvDataDecode(byte[] yuvFrame, int dataSize, int width, int height) {
        if (yuvFrame.length < width * height) {
            byte[] result = new byte[0];
            return result;
        }

        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];

        for (int i = 0; i < u.length; i ++) {
            u[i] = yuvFrame[length + i];
            v[i] = yuvFrame[length + u.length + i];
        }

        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = v[i];
            yuvFrame[length + 2 * i + 1] = u[i];
        }

        return yuvFrame;
    }

    private void screenShot(byte[] buf, String shotDir, int width, int height) {
        File dir = new File(shotDir);
        if (!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
        }
        YuvImage yuvImage = new YuvImage(buf,
                ImageFormat.NV21,
                width,
                height,
                null);
        OutputStream outputFile;
        final String path = dir + "/ScreenShot_" + System.currentTimeMillis() + ".jpg";
        try {
            outputFile = new FileOutputStream(new File(path));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "test screenShot: new bitmap output file error: " + e);
            return;
        }
        if (outputFile != null) {
            yuvImage.compressToJpeg(new Rect(0,
                    0,
                    width,
                    height), 100, outputFile);
        }
        try {
            outputFile.close();
        } catch (IOException e) {
            Log.e(TAG, "test screenShot: compress yuv image error: " + e);
            e.printStackTrace();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run(){
            }
        });
    }

    /* ************************************************** Click and Configure Camera ************************************************** */

    private void configureThermalImage(){
        BaseProduct product = VideoDecodingApplication.getProductInstance();

        if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {

            Camera camera = product.getCameras().get(1);

            if (camera.isThermalCamera()) {
                if (camera != null) {
                    camera.setDisplayMode(SettingsDefinitions.DisplayMode.THERMAL_ONLY, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {

                            if (error == null) {
                                showToast("Switch to thermal Succeeded");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });

//                camera.setThermalMeasurementMode(ThermalMeasurementMode.SPOT_METERING, new CommonCallbacks.CompletionCallback() {
//                    @Override
//                    public void onResult(DJIError error) {
//
//                        if (error == null) {
//                            // 666
//                        } else {
//                            showToast(error.getDescription());
//                        }
//                    }
//                });
//
//                PointF center = new PointF();
//                center.x = 0.5f;
//                center.y = 0.5f;
//                camera.setThermalSpotMeteringTargetPoint(center, new CommonCallbacks.CompletionCallback() {
//                    @Override
//                    public void onResult(DJIError error) {
//
//                        if (error == null) {
//                            // 666
//                        } else {
//                            showToast(error.getDescription());
//                        }
//                    }
//                });
//
//                camera.setThermalTemperatureCallback(new Camera.TemperatureDataCallback() {
//                    @Override
//                    public void onUpdate(float temperature) {
//                        showToast("Temperature in image center: " + temperature);
//                    }
//                });

                }else{
                    showToast("Camera object is null !");
                }
            }else{
                showToast("Camera 1 is not thermal.");
            }
        }
    }

    private void configureMSXImage(){
        BaseProduct product = VideoDecodingApplication.getProductInstance();

        if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {

            Camera camera = product.getCameras().get(1);

            if (camera.isThermalCamera()){
                if (camera != null){
                    camera.setDisplayMode(SettingsDefinitions.DisplayMode.MSX, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {

                            if (error == null) {
                                showToast("Switch to MSX Succeeded");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });
                }else{
                    showToast("Camera object is null !");
                }
            }else{
                showToast("Camera 1 is not thermal.");
            }
        }
    }

    private void configureVisualImage(){
        BaseProduct product = VideoDecodingApplication.getProductInstance();

        if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {

            Camera camera = product.getCameras().get(1);

            if (camera != null){
                camera.setDisplayMode(SettingsDefinitions.DisplayMode.VISUAL_ONLY, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {

                        if (error == null) {
                            showToast("Switch to visual Succeeded");
                        } else {
                            showToast(error.getDescription());
                        }
                    }
                });
            }else{
                showToast("Camera object is null !");
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_visual:{
                configureVisualImage();
                break;
            }
            case R.id.btn_msx:{
                configureMSXImage();
                break;
            }

            case R.id.btn_thermal:{
                configureThermalImage();
                break;
            }
            case R.id.btn_send:{
                try {
                    mMutex.acquire();
                    showToast("Take Screenshot");
                    screenShot(mImgDecode, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", mWidth, mHeight);
                } catch (InterruptedException e) {
                    // TODO
                } finally {
                    mMutex.release();
                }
                break;
            }
            default:
                break;
        }
    }

}
