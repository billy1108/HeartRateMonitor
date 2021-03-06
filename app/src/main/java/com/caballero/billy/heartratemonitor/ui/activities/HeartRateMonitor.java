package com.caballero.billy.heartratemonitor.ui.activities;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Vibrator;

import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.caballero.billy.heartratemonitor.R;
import com.caballero.billy.heartratemonitor.helper.ImageProcessing;

import butterknife.Bind;
import butterknife.ButterKnife;

/**
 * Created by Billy on 16/03/2016.
 */
public class HeartRateMonitor extends Activity {
    private static final String TAG = HeartRateMonitor.class.getSimpleName();
    private static final AtomicBoolean processing = new AtomicBoolean(false);

    private static SurfaceHolder previewHolder = null;
    private static Camera camera = null;
    private static String beatsPerMinuteValue="";
    private static WakeLock wakeLock = null;
    private static int averageIndex = 0;
    private static final int averageArraySize = 4;
    private static final int[] averageArray = new int[averageArraySize];
    private static Context parentReference = null;

    @Bind(R.id.sfPreview) SurfaceView preview;
    @Bind(R.id.tvStopWatch) TextView mTxtVwStopWatch;
    @Bind(R.id.hbvImage) View image;
    @Bind(R.id.tvResult) TextView text;

    public static enum TYPE {
        GREEN, RED
    };

    private static TYPE currentType = TYPE.GREEN;

    public static TYPE getCurrent() {
        return currentType;
    }

    private static int beatsIndex = 0;
    private static final int beatsArraySize = 3;
    private static final int[] beatsArray = new int[beatsArraySize];
    private static double beats = 0;
    private static long startTime = 0;
    private static Vibrator v ;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        ButterKnife.bind(this);
        initElements();
    }

    public void initElements(){
        v = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
        parentReference=this;
        previewHolder = preview.getHolder();
        previewHolder.addCallback(surfaceCallback);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "DoNotDimScreen");
        prepareCountDownTimer();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onResume() {
        super.onResume();
        wakeLock.acquire();
        camera = Camera.open();
        startTime = System.currentTimeMillis();
    }

    @Override
    public void onPause() {
        super.onPause();
        wakeLock.release();
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        text.setText("---");
        camera = null;
    }

    private PreviewCallback previewCallback = new PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null) throw new NullPointerException();
            Camera.Size size = cam.getParameters().getPreviewSize();
            if (size == null) throw new NullPointerException();

            if (!processing.compareAndSet(false, true)) return;

            int width = size.width;
            int height = size.height;

            int imgAvg = ImageProcessing.decodeYUV420SPtoRedAvg(data.clone(), height, width);
            // Log.i(TAG, "imgAvg="+imgAvg);
            if (imgAvg == 0 || imgAvg == 255) {
                processing.set(false);
                return;
            }

            int averageArrayAvg = 0;
            int averageArrayCnt = 0;
            for (int i = 0; i < averageArray.length; i++) {
                if (averageArray[i] > 0) {
                    averageArrayAvg += averageArray[i];
                    averageArrayCnt++;
                }
            }

            int rollingAverage = (averageArrayCnt > 0) ? (averageArrayAvg / averageArrayCnt) : 0;
            TYPE newType = currentType;
            if (imgAvg < rollingAverage) {
                newType = TYPE.RED;
                if (newType != currentType) {
                    beats++;
                    // Log.d(TAG, "BEAT!! beats="+beats);
                }
            } else if (imgAvg > rollingAverage) {
                newType = TYPE.GREEN;
            }

            if (averageIndex == averageArraySize) averageIndex = 0;
            averageArray[averageIndex] = imgAvg;
            averageIndex++;

            // Transitioned from one state to another to the same
            if (newType != currentType) {
                currentType = newType;
                image.postInvalidate();
            }

            long endTime = System.currentTimeMillis();
            double totalTimeInSecs = (endTime - startTime) / 1000d;
            if (totalTimeInSecs >= 10) {
                double bps = (beats / totalTimeInSecs);
                int dpm = (int) (bps * 60d);
                if (dpm < 30 || dpm > 180) {
                    startTime = System.currentTimeMillis();
                    beats = 0;
                    processing.set(false);
                    return;
                }

                // Log.d(TAG,
                // "totalTimeInSecs="+totalTimeInSecs+" beats="+beats);

                if (beatsIndex == beatsArraySize) beatsIndex = 0;
                beatsArray[beatsIndex] = dpm;
                beatsIndex++;

                int beatsArrayAvg = 0;
                int beatsArrayCnt = 0;
                for (int i = 0; i < beatsArray.length; i++) {
                    if (beatsArray[i] > 0) {
                        beatsArrayAvg += beatsArray[i];
                        beatsArrayCnt++;
                    }
                }
                int beatsAvg = (beatsArrayAvg / beatsArrayCnt);
                text.setText(String.valueOf(beatsAvg));
                beatsPerMinuteValue=String.valueOf(beatsAvg);
                makePhoneVibrate();
                showReadingCompleteDialog();
                startTime = System.currentTimeMillis();
                beats = 0;
            }
            processing.set(false);
        }
    };


    private void makePhoneVibrate(){
        v.vibrate(500);
    }

    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        public void surfaceCreated(SurfaceHolder holder) {
            try {
                camera.setPreviewDisplay(previewHolder);
                camera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.v("PreviewDemo", t.toString());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters parameters = camera.getParameters();
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            Camera.Size size = getSmallestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                Log.d(TAG, "Using width=" + size.width + " height=" + size.height);
            }
            camera.setParameters(parameters);
            camera.startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Ignore
        }
    };

    private Camera.Size getSmallestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea < resultArea) result = size;
                }
            }
        }

        return result;
    }

    private void prepareCountDownTimer(){
        mTxtVwStopWatch.setText("---");
        new CountDownTimer(10000, 1000) {

            public void onTick(long millisUntilFinished) {
                mTxtVwStopWatch.setText("seconds remaining: " + (millisUntilFinished) / 1000);
            }

            public void onFinish() {
                mTxtVwStopWatch.setText("done!");
            }
        }.start();
    }

    private void showReadingCompleteDialog(){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Heart Rate Monitor");
        builder.setMessage("Reading taken Succesfully at- "+beatsPerMinuteValue+" beats per minute.")
                .setCancelable(false)
                .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                       ///this.finish();
                    }
                })
                .setNegativeButton("Take Another", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        text.setText("---");
                        prepareCountDownTimer();
                        dialog.cancel();
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }


}
