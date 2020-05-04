package com.dji.videostreamdecoding;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;

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
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnClickListener;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.dji.videostreamdecoding.fastcom.ImagePublisher;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
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
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

import static org.opencv.imgproc.Imgproc.cvtColor;

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

    private Semaphore mMutex = new Semaphore(1);
    private static boolean mInitOpenCV = false;
    private Mat mMatImage;
    private ImagePublisher mPublisher = null;

    private int mSVal = 0;

    private TextView titleTv;
    private Button mBtVisual;
    private Button mBtMSX;
    private Button mBtThermal;
    private Button mBtSend;
    private SeekBar mSbMSX;

    private ImageView mScreen;

    // 666 : https://github.com/opencv/opencv/tree/master/samples/android/face-detection/src/org/opencv/samples/facedetect
    // https://stackoverflow.com/questions/33764245/android-opencv-couldnt-load-detection-based-tracker
    private File mCascadeFile;
    private CascadeClassifier mJavaDetector;
    private static final Scalar FACE_RECT_COLOR     = new Scalar(0, 255, 0, 255);

    private float mRelativeFaceSize;
    private int   mAbsoluteFaceSize;

    private RectF mNormalizedFaceRect;


//    static {
//        System.loadLibrary("opencv_java");
//    }

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
                    Boolean detectorInicializated = configureMultiscaleFaceDetector();
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

    private Boolean configureMultiscaleFaceDetector(){
        // Configure face relative size
        mRelativeFaceSize       = 0.4f;
        mAbsoluteFaceSize       = 0;

        Boolean success = false;
        try {
                // load cascade file from application resources
                InputStream is = getResources().openRawResource(R.raw.lbpcascade_frontalface);
                File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                mCascadeFile = new File(cascadeDir, "lbpcascade_frontalface.xml");
                FileOutputStream os = new FileOutputStream(mCascadeFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                is.close();
                os.close();

                mJavaDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                if (mJavaDetector.empty()) {
                    Log.e(TAG, "Failed to load cascade classifier");
                    mJavaDetector = null;
                } else
                    Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());

                cascadeDir.delete();
                success = true;

        } catch (IOException e) {
            e.printStackTrace();
        Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
        }

        return success;
    }

    private Boolean detectFace(Mat source, MatOfRect detectedFaces){
        if (mAbsoluteFaceSize == 0) {
            int height = source.rows();
            if (Math.round(height * mRelativeFaceSize) > 0) {
                mAbsoluteFaceSize = Math.round(height * mRelativeFaceSize);
            }
        }

        // Detect faces
        if (mJavaDetector != null)
            mJavaDetector.detectMultiScale(source, detectedFaces, 1.05, 4, 2, // TODO: objdetect.CV_HAAR_SCALE_IMAGE
                    new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());

        // Check if a face has been detected
        if (detectedFaces.toArray().length > 0)
            return true;
        else
            return false;
    }

    private void updateDetection(org.opencv.core.Rect faceDetected){
        // Normalize detection
        float x_norm = faceDetected.x / mWidth;
        float y_norm = faceDetected.y / mHeight;
        float width_norm = faceDetected.width / mWidth;
        float height_norm = faceDetected.height / mHeight;

        mNormalizedFaceRect = new RectF(x_norm,y_norm,width_norm,height_norm);
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyStatusChange();
        createCodec();
//        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback);
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
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cleanCodecManager();
        // TODO method for disconnect socket publisher
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        mScreen = findViewById(R.id.img_view_display);

        mBtVisual.setOnClickListener(this);
        mBtMSX.setOnClickListener(this);
        mBtThermal.setOnClickListener(this);
        mBtSend.setOnClickListener(this);

        if( mPublisher == null){
            mPublisher = new ImagePublisher(8888);
        }

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
//            configureVisualImage();
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

                                if (bytes.length < width * height) {
                                    // TODO if NOT decoded...
                                }else{
                                    mImgDecode = newYuvData420P(bytes, width, height);

//                                    int colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
//                                    switch (colorFormat) {
//                                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
//                                            //NV12
//                                            if (Build.VERSION.SDK_INT <= 23) {
//                                                mImgDecode = oldYuvData(bytes, width, height);
//                                            } else {
//                                                mImgDecode = yuvData(bytes, width, height);
//                                            }
//                                            break;
//                                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
//                                            //YUV420P
//                                            mImgDecode = newYuvData420P(bytes, width, height);
//                                            break;
//                                        default:
//                                            break;
//                                    }

                                    Mat myuv = new Mat(height + height / 2, width, CvType.CV_8UC1);
                                    myuv.put(0,0, mImgDecode);

                                    Mat pic = new Mat(height, width, CvType.CV_8UC4);
                                    cvtColor(myuv, pic, Imgproc.COLOR_YUV2BGRA_NV12);

                                    mMatImage = pic.clone();

                                    // Detect faces in gray image
                                    Mat image_gray = new Mat();
                                    cvtColor(mMatImage, image_gray, Imgproc.COLOR_BGRA2GRAY);


                                    MatOfRect faces = new MatOfRect();
                                    org.opencv.core.Rect[] facesArray;
                                    if (detectFace(image_gray, faces)) {
                                        facesArray = faces.toArray();

                                        // Print faces in mMatImage
                                        for (int i = 0; i < facesArray.length; i++)
                                            Imgproc.rectangle(mMatImage, facesArray[i].tl(), facesArray[i].br(), FACE_RECT_COLOR, 3);

                                        updateDetection(facesArray[0]);
                                    }
                                    // TODO CHECK IF DISABLE IMAGE VIEW DECREASES LAG
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Bitmap bmp = Bitmap.createBitmap(mMatImage.width(), mMatImage.height(), Bitmap.Config.ARGB_8888);
                                            Utils.matToBitmap(mMatImage, bmp);
                                            mScreen.setImageBitmap(bmp);
                                        }
                                    });

                                    // TODO CHECK IMAGE CONVERSION
                                    Mat sendImage = mMatImage.clone();
                                    cvtColor(sendImage, sendImage, Imgproc.COLOR_BGRA2BGR);
                                    mPublisher.publish(sendImage);
                                }
                            } catch (InterruptedException e) {

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

    // For android API <= 23
    private byte[] oldYuvData(byte[] yuvFrame, int width, int height){
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
        return yuvFrame;
    }

    private byte[] yuvData(byte[] yuvFrame, int width, int height) {
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

        return yuvFrame;
    }

    private byte[] newYuvData420P(byte[] yuvFrame, int width, int height) {
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

                camera.setThermalMeasurementMode(ThermalMeasurementMode.AREA_METERING, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {

                        if (error == null) {
                            // 666
                        } else {
                            showToast(error.getDescription());
                        }
                    }
                });

                camera.setThermalMeteringArea(mNormalizedFaceRect, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {

                        if (error == null) {
                            // 666
                        } else {
                            showToast(error.getDescription());
                        }
                    }

                });

                camera.setThermalAreaTemperatureAggregationsCallback(new ThermalAreaTemperatureAggregations.Callback(){

                    @Override
                    public void onUpdate(/*@NonNull*/ ThermalAreaTemperatureAggregations thermalAreaMasurement) {
                        float avgTemperature = thermalAreaMasurement.getAverageAreaTemperature();
                        showToast("AVG TEMP IN DETECTION: " + avgTemperature);
                    }
                });

                camera.setThermalTemperatureCallback(new Camera.TemperatureDataCallback() {
                    @Override
                    public void onUpdate(float temperature) {
                        showToast("Temperature in image center: " + temperature);
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
