package com.dji.videostreamdecoding;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;

import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.graphics.Rect;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnClickListener;

import com.dji.videostreamdecoding.fastcom.ImagePublisher;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.concurrent.Semaphore;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.ThermalAreaTemperatureAggregations;
import dji.common.camera.ThermalMeasurementMode;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.codec.DJICodecManager;
import dji.thirdparty.afinal.core.AsyncTask;

import static org.opencv.imgproc.Imgproc.cvtColor;

public class MainActivity extends Activity implements OnClickListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;

    private long mLastTime = System.nanoTime();

    DecimalFormat mDF = new DecimalFormat("#.#####");

    private Camera mCamera = null;
    private TextView mTemp, mTime, mAvgTmp, mMaxTmp, mMinTmp;
    private EditText mPtCx, mPtCy;
    private boolean mChangeMode = false;

    private DJICodecManager mCodecManager = null;
    private DJICodecManager.YuvDataCallback mFrameListener = null;
    private int mCount;
    private byte[] mImgDecode;
    private int mWidth;
    private int mHeight;

    private Thread mThreadScreen, mThreadSend, mThreadDetect;

    private Semaphore mMutex = new Semaphore(1);
    private static boolean mInitOpenCV = false;
    private Mat mMatImage = null;
    private Mat mImage = null;
    private ImagePublisher mPublisher = null;

    private int mSValMSX = 0;
    private int mSValRect = 50;

    private TextView titleTv;
    private Button mBtVisual, mBtMSX, mBtThermal, mBtTkSC, mBtSpot, mBtnDisMeasure, mBtRect, mBtFV;
    private SeekBar mSbMSX, mSbRect;
    private ImageView mScreen;

    private static final Scalar RECT_COLOR = new Scalar(0, 255, 0, 255);

    private boolean mShowRect = false;
    private boolean mShowPoint = false;
    private boolean mIsThermal = false;
    private boolean mIsMSX = false;

    Point mPointSpot = new Point();
    Point mP1Rect = new Point();
    Point mP2Rect = new Point();

    static {
        mInitOpenCV = OpenCVLoader.initDebug();
    }

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

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {

                }
                    break;
                default:
                {
                    super.onManagerConnected(status);
                }
                    break;
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        notifyStatusChange();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cleanCodecManager();
        // TODO method for disconnect socket publisher
        closeThreads();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (OpenCVLoader.initDebug()) {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        } else {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        initUi();

        createCodec();
        createThreads();
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
        mBtTkSC = (Button) findViewById(R.id.btn_sc);
        mBtSpot = (Button) findViewById(R.id.btn_spot);
        mBtnDisMeasure = (Button) findViewById(R.id.btn_dis_meas);
        mBtRect = (Button) findViewById(R.id.btn_rect);

        mSbRect = (SeekBar) findViewById(R.id.seekbar_rect);
        mSbMSX = (SeekBar) findViewById(R.id.seekbar_msx);

        titleTv = (TextView) findViewById(R.id.title_tv);

        mScreen = findViewById(R.id.img_view_display);

        mBtFV = (Button) findViewById(R.id.btn_switch_fv);

        mTime = (TextView) findViewById(R.id.text_view_time);
        mAvgTmp = (TextView) findViewById(R.id.text_view_avg_tmp);
        mMaxTmp = (TextView) findViewById(R.id.text_view_max_tmp);
        mMinTmp = (TextView) findViewById(R.id.text_view_min_tmp);

        mTemp = (TextView) findViewById(R.id.txt_view_tmp);
        mPtCx = (EditText) findViewById(R.id.edit_txt_cx);
        mPtCy = (EditText) findViewById(R.id.edit_txt_cy);

        mBtVisual.setOnClickListener(this);
        mBtMSX.setOnClickListener(this);
        mBtThermal.setOnClickListener(this);
        mBtTkSC.setOnClickListener(this);
        mSbRect.setOnClickListener(this);
        mBtSpot.setOnClickListener(this);
        mBtnDisMeasure.setOnClickListener(this);
        mBtRect.setOnClickListener(this);

        mBtFV.setOnClickListener(this);

        if( mPublisher == null){
            mPublisher = new ImagePublisher(8888);
        }

        mSbRect.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSValRect = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                showToast("Val Rect: " + mSValRect);
            }
        });

        mSbMSX.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSValMSX = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                showToast("Val MSX: " + mSValMSX);
                if (mCamera != null){
                    if (mCamera.isThermalCamera()) {
                        if(mIsMSX){
                            mCamera.setMSXLevel(mSValMSX, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError == null) {
                                        showToast("Set MSX Level");
                                    } else {
                                        showToast(djiError.getDescription());
                                    }
                                }
                            });
                        }else{
                            showToast("Put in Thermal mode");
                        }
                    }else{
                        showToast("Camera 1 is not thermal");
                    }
                }else{
                    showToast("Camera object is null");
                }
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
            mCamera = null;
            showToast("Disconnected");
        } else {
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                mCamera = product.getCameras().get(1);
                if (mCamera != null) {
                    mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError == null) {
                                showToast("Photo mode");
                            } else {
                                showToast(djiError.getDescription());
                            }
                        }
                    });
                }
            }
        }
    }

    private void createCodec(){
        if(mCodecManager == null){
            if(mFrameListener == null){
                mFrameListener = new DJICodecManager.YuvDataCallback() {
                    @Override
                    public void onYuvDataReceived(MediaFormat mediaFormat, ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
                        if (mCount++ % 30 == 0 && yuvFrame != null) {
                            mCount = 0;

                            final byte[] bytes = new byte[dataSize];
                            yuvFrame.get(bytes);

                            AsyncTask.execute(new Runnable() {
                                @Override
                                public void run() {
                                    int colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                                    switch (colorFormat) {
                                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                                            //NV12
                                            if (Build.VERSION.SDK_INT <= 23) {
                                                oldYuvData(bytes, width, height);
                                            } else {
                                                yuvData(bytes, width, height);
                                            }
                                            break;
                                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                                            //YUV420P
                                            newYuvData420P(bytes, width, height);
                                            break;
                                        default:
                                            break;
                                    }
                                    try {
                                        mMutex.acquire();
                                        mWidth = width;
                                        mHeight = height;
                                        mImgDecode = new byte[bytes.length];
                                        mImgDecode = bytes;

                                        Mat myuv = new Mat(height + height / 2, width, CvType.CV_8UC1);
                                        myuv.put(0,0, mImgDecode);

                                        Mat pic = new Mat(height, width, CvType.CV_8UC4);
                                        cvtColor(myuv, pic, Imgproc.COLOR_YUV2BGRA_NV12);

                                        mMatImage = pic.clone();

                                        double incT = (System.nanoTime()-mLastTime)*10e-9;
                                        mLastTime = System.nanoTime();
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                mTime.setText("Time: " + mDF.format(incT));
                                            }
                                        });
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    } finally {
                                        mMutex.release();
                                    }
                                }
                            });
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

    private void createThreads(){

        Runnable runnableScreen = new Runnable() {
            public void run() {
                while(true){
                    try {
                        mMutex.acquire();
                        if(mImage != null){
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Bitmap bmp = Bitmap.createBitmap(mImage.width(), mImage.height(), Bitmap.Config.ARGB_8888);
                                    Utils.matToBitmap(mImage, bmp);
                                    mScreen.setImageBitmap(bmp);
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        mMutex.release();
                    }

                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        Runnable runnableSend = new Runnable() {
            public void run() {
                while(true){
                    try {
                        mMutex.acquire();
                        if(mMatImage != null){
                            Mat sendImage = mMatImage.clone();
                            cvtColor(sendImage, sendImage, Imgproc.COLOR_RGBA2BGR);
                            mPublisher.publish(sendImage);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        mMutex.release();
                    }

                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        Runnable runnableDetect = new Runnable() {
            public void run() {
                while(true){
                    try {
                        mMutex.acquire();
                        if(mMatImage != null){
                            if(mShowPoint){
                                Imgproc.circle(mMatImage, mPointSpot, 10, RECT_COLOR, -1);
                            }

                            if(mShowRect){
                                Imgproc.rectangle(mMatImage, mP1Rect, mP2Rect, RECT_COLOR, 3);
                            }

                            mImage = mMatImage.clone();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        mMutex.release();
                    }

                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        mThreadScreen = new Thread(runnableScreen);
        mThreadSend = new Thread(runnableSend);
        mThreadDetect = new Thread(runnableDetect);

        mThreadDetect.start();
        mThreadScreen.start();
        mThreadSend.start();

    }

    private void closeThreads(){
        mThreadScreen.interrupt();
        mThreadSend.interrupt();
    }

    /* ************************************************** Decode and utils ************************************************** */

    // For android API <= 23
    private void oldYuvData(byte[] yuvFrame, int width, int height){
        if (yuvFrame.length < width * height) {
            return;
        }

        byte[] y = new byte[width * height];
        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        byte[] nu = new byte[width * height / 4]; //
        byte[] nv = new byte[width * height / 4];

        System.arraycopy(yuvFrame, 0, y, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[y.length + 2 * i];
            u[i] = yuvFrame[y.length + 2 * i + 1];
        }
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        for (int j = 0; j < uvWidth / 2; j++) {
            for (int i = 0; i < uvHeight / 2; i++) {
                byte uSample1 = u[i * uvWidth + j];
                byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                nu[2 * (i * uvWidth + j)] = uSample1;
                nu[2 * (i * uvWidth + j) + 1] = uSample1;
                nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                nv[2 * (i * uvWidth + j)] = vSample1;
                nv[2 * (i * uvWidth + j) + 1] = vSample1;
                nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
            }
        }
        //nv21test
        byte[] bytes = new byte[yuvFrame.length];
        System.arraycopy(y, 0, bytes, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            bytes[y.length + (i * 2)] = nv[i];
            bytes[y.length + (i * 2) + 1] = nu[i];
        }
    }

    private void yuvData(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            return;
        }

        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];

        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[length + 2 * i];
            u[i] = yuvFrame[length + 2 * i + 1];
        }

        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = u[i];
            yuvFrame[length + 2 * i + 1] = v[i];
        }
    }

    private void newYuvData420P(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            return;
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
            e.printStackTrace();
        }
    }

    /* ************************************************** Click and Configure Camera ************************************************** */

    private void configureModeCamera(){
        if (mCamera != null) {
            if(!mChangeMode) {
                mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError == null) {
                            showToast("Photo mode");
                        } else {
                            showToast(djiError.getDescription());
                        }
                    }
                });
            }else{
                mCamera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError == null) {
                            showToast("Video mode");
                        } else {
                            showToast(djiError.getDescription());
                        }
                    }
                });
            }
        }
    }

    private void configureThermalImage(){
        if (mCamera != null) {
            if (mCamera.isThermalCamera()) {
                mCamera.setDisplayMode(SettingsDefinitions.DisplayMode.THERMAL_ONLY, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (error == null) {
                            showToast("Switch to thermal Succeeded");
                        } else {
                            showToast(error.getDescription());
                        }
                    }
                });
                mIsThermal = true;
                mIsMSX = false;
            }else{
                showToast("Camera 1 is not thermal");
            }
        }else{
            showToast("Camera object is null");
        }
    }

    private void configureMSXImage(){
        if (mCamera != null){
            if (mCamera.isThermalCamera()){
                mCamera.setDisplayMode(SettingsDefinitions.DisplayMode.MSX, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (error == null) {
                            showToast("Switch to MSX Succeeded");
                        } else {
                            showToast(error.getDescription());
                        }
                    }
                });
                mIsThermal = false;
                mIsMSX = true;
            }else{
                showToast("Camera 1 is not thermal");
            }
        }else{
            showToast("Camera object is null");
        }
    }

    private void configureVisualImage(){
        if (mCamera != null){
            mCamera.setDisplayMode(SettingsDefinitions.DisplayMode.VISUAL_ONLY, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("Switch to visual Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
            mIsThermal = false;
            mIsMSX = false;
        }else{
            showToast("Camera object is null");
        }
    }

    private boolean setMeasurePoint(PointF point){
        if (mCamera != null) {
            if (mCamera.isThermalCamera()) {
                if(mIsThermal || mIsMSX) {
                    mCamera.setThermalMeasurementMode(ThermalMeasurementMode.SPOT_METERING, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("Spot Metering");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });

                    mCamera.setThermalSpotMeteringTargetPoint(point, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("Set PT");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });

                    mCamera.setThermalTemperatureCallback(new Camera.TemperatureDataCallback() {
                        @Override
                        public void onUpdate(float temperature) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mTemp.setText("Temp: " + mDF.format(temperature));
                                }
                            });
                        }
                    });
                    return true;
                }else{
                    showToast("Put in Thermal mode");
                    disMeasureTemp();
                    mShowRect = false;
                    mShowPoint = false;
                    return false;
                }
            }else{
                showToast("Camera 1 is not thermal");
                return false;
            }
        }else{
            showToast("Camera object is null");
            return false;
        }
    }

    private boolean setMeasureRect(RectF rect){
        if (mCamera != null) {
            if (mCamera.isThermalCamera()) {
                if(mIsThermal || mIsMSX) {
                    mCamera.setThermalMeasurementMode(ThermalMeasurementMode.AREA_METERING, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("Area Metering");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });

                    mCamera.setThermalMeteringArea(rect, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("Set Rect");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    });

                    mCamera.setThermalAreaTemperatureAggregationsCallback(new ThermalAreaTemperatureAggregations.Callback() {
                        @Override
                        public void onUpdate(/*@NonNull*/ ThermalAreaTemperatureAggregations thermalAreaMasurement) {
                            float avgTemperature = thermalAreaMasurement.getAverageAreaTemperature();
                            float maxTemperature = thermalAreaMasurement.getMaxAreaTemperature();
                            float minTemperature = thermalAreaMasurement.getMinAreaTemperature();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mAvgTmp.setText("Avg Temp: " + mDF.format(avgTemperature));
                                    mMaxTmp.setText("Max Temp: " + mDF.format(maxTemperature));
                                    mMinTmp.setText("Min Temp: " + mDF.format(minTemperature));
                                }
                            });
                        }
                    });
                    return true;
                }else{
                    showToast("Put in Thermal mode");
                    disMeasureTemp();
                    mShowRect = false;
                    mShowPoint = false;
                    return false;
                }
            }else{
                showToast("Camera 1 is not thermal");
                return false;
            }
        }else{
            showToast("Camera object is null");
            return false;
        }
    }

    private void disMeasureTemp(){
        if (mCamera != null) {
            if (mCamera.isThermalCamera()) {
                mCamera.setThermalMeasurementMode(ThermalMeasurementMode.DISABLED, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (error == null) {
                            showToast("Disable Metering");
                        } else {
                            showToast(error.getDescription());
                        }
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mTemp.setText("Temp: ");
                        mAvgTmp.setText("Avg Temp: ");
                        mMaxTmp.setText("Max Temp: ");
                        mMinTmp.setText("Min Temp: ");
                    }
                });
            }else{
                showToast("Camera 1 is not thermal");
            }
        }else{
            showToast("Camera object is null");
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_switch_fv:
            {
                mChangeMode = !mChangeMode;
                configureModeCamera();
            }
                break;
            case R.id.btn_visual:
            {
                configureVisualImage();
            }
                break;
            case R.id.btn_msx:
            {
                configureMSXImage();
            }
                break;
            case R.id.btn_thermal:
            {
                configureThermalImage();
            }
                break;
            case R.id.btn_sc:
            {
                try {
                    mMutex.acquire();
                    showToast("Take Screenshot");
                    screenShot(mImgDecode, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", mWidth, mHeight);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    mMutex.release();
                }
            }
                break;
            case R.id.btn_spot:
            {
                PointF point = new PointF();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        point.x = Float.parseFloat(mPtCx.getText().toString());
                        point.y = Float.parseFloat(mPtCy.getText().toString());
                    }
                });

                mPointSpot.x = point.x * mWidth;
                mPointSpot.y = point.y * mHeight;

                if(setMeasurePoint(point)) {
                    mShowPoint = true;
                    mShowRect = false;
                }
            }
                break;
            case R.id.btn_rect:
            {
                mP1Rect.x = (int) (mWidth/2) - (mSValRect*2);
                mP1Rect.y = (int) (mHeight/2) - (mSValRect*2);
                float width = (mSValRect*2)*2;
                float height = (mSValRect*2)*2;

                mP2Rect.x = mP1Rect.x + width;
                mP2Rect.y = mP1Rect.y + height;

                float x_norm = (float) mP1Rect.x / mWidth;
                float y_norm = (float) mP1Rect.y / mHeight;
                float width_norm = (float) x_norm + (width / mWidth);
                float height_norm = (float) y_norm + (height / mHeight);

                RectF mNormDefaultRect = new RectF(x_norm, y_norm, width_norm, height_norm);

                if(setMeasureRect(mNormDefaultRect)) {
                    mShowRect = true;
                    mShowPoint = false;
                }
            }
                break;
            case R.id.btn_dis_meas:
            {
                disMeasureTemp();
                mShowRect = false;
                mShowPoint = false;
            }
                break;
            default:
                break;
        }
    }

}