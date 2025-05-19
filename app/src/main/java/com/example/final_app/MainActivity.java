package com.example.final_app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 1001;
    private PreviewView previewView;
    private PoseDetector poseDetector;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);

        // 初始化 Pose Detector（流式检测）
        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 预览
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 后置摄像头
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // 图像分析（绑定 analyzer）
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, new PoseAnalyzer());

                // 绑定生命周期和组件
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        (LifecycleOwner) this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                Log.d("Camera", "Camera initialized and bound successfully");

            } catch (Exception e) {
                Log.e("Camera", "Failed to start camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Log.e("Permission", "Camera permission denied");
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private class PoseAnalyzer implements ImageAnalysis.Analyzer {

        @androidx.camera.core.ExperimentalGetImage
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            Log.d("PoseAnalyzer", "analyze() called");

            Image mediaImage = imageProxy.getImage();
            if (mediaImage != null) {
                InputImage image = InputImage.fromMediaImage(
                        mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                poseDetector.process(image)
                        .addOnSuccessListener(pose -> {
                            int count = pose.getAllPoseLandmarks().size();
                            Log.d("PoseDetection", "Landmarks detected: " + count);
                        })
                        .addOnFailureListener(e ->
                                Log.e("PoseDetection", "Pose detection failed", e))
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                Log.e("PoseAnalyzer", "mediaImage is null");
                imageProxy.close();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (poseDetector != null) {
            poseDetector.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
