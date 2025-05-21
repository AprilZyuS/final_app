package com.example.final_app;

import static java.lang.Math.atan2;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.databinding.DataBindingUtil;
import com.example.final_app.databinding.ActivityMainBinding;

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
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding; // 注意变量名改成小写开头
    private static final int REQUEST_CODE_CAMERA = 1001;
    private PoseDetector poseDetector;
    private ExecutorService cameraExecutor;
    private ImageProxy latestImageProxy;

    @OptIn(markerClass = ExperimentalGetImage.class) @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化 DataBinding
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setPoseText("准备识别中...");

        // 初始化 Pose Detector（流式）
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
        binding.getpose.setOnClickListener(v -> {
            if (latestImageProxy != null) {
                savePoseAnglesToCSV(latestImageProxy);
               // savePoseToCSV(latestImageProxy);
                Toast.makeText(this, "正在保存姿势数据...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "没有可用的图像数据", Toast.LENGTH_SHORT).show();

            }
        });
        binding.test.setOnClickListener(v -> testimage());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, new PoseAnalyzer());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                Log.d("Camera", "Camera initialized");

            } catch (Exception e) {
                Log.e("Camera", "Failed to start camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // 保存姿势数据到 CSV 文件
    @androidx.camera.core.ExperimentalGetImage
    private void savePoseToCSV(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            Log.e("CSV", "Image is null");
            imageProxy.close();
            return;
        }

        File csvFile = new File(getExternalFilesDir(null), "pose_data1.csv");
        try {
            InputImage lastImage = InputImage.fromMediaImage(mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees());

            // 获取最新的姿势信息
            poseDetector.process(lastImage)
                    .addOnSuccessListener(pose -> {
                        StringBuilder csvData = new StringBuilder();
                        pose.getAllPoseLandmarks().forEach(landmark -> {
                            csvData.append(landmark.getLandmarkType()).append(", ")
                                    .append(landmark.getPosition().x).append(", ")
                                    .append(landmark.getPosition().y).append("\n");
                        });



                        // 写入数据到文件
                        try (FileWriter writer = new FileWriter(csvFile, true)) {
                            // 写入 CSV 表头（如果文件为空）
                            if (csvFile.length() == 0) {
                                writer.append("Landmark, X, Y\n");
                            }
                            writer.append(csvData.toString());
                            writer.flush();
                        } catch (Exception e) {
                            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                            Log.e("CSV", "Failed to write pose data", e);
                        }
                    })
                    .addOnFailureListener(e -> Log.e("PoseDetection", "Failed to detect pose", e))
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            Log.e("CSV", "Failed to process image or write to CSV file", e);
            imageProxy.close();
        }
    }
//判断
public void testimage() {
    File csvFile = new File(getExternalFilesDir(null), "pose_data1.csv");
    if (!csvFile.exists()) {
        Toast.makeText(this, "CSV 文件不存在", Toast.LENGTH_SHORT).show();
        Log.e("CSV", "CSV 文件不存在: " + csvFile.getAbsolutePath());
        return;
    }

    try {
        // 读取 CSV 文件中的姿势数据
        List<String> savedPoseData = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            reader.readLine(); // 跳过表头
            while ((line = reader.readLine()) != null) {
                savedPoseData.add(line);
            }
        }catch (Exception e) {
            Toast.makeText(this, "读取 CSV 文件2失败", Toast.LENGTH_SHORT).show();
            Log.e("CSV", "Failed to read CSV file", e);
            return;
        }

        Log.d("CSV", "成功读取 CSV 文件: " + savedPoseData.size() + " 行数据");

        // 获取当前姿势数据
        if (latestImageProxy != null) {
            Image mediaImage = latestImageProxy.getImage();
            if (mediaImage != null) {
                InputImage currentImage = InputImage.fromMediaImage(mediaImage,
                        latestImageProxy.getImageInfo().getRotationDegrees());

                poseDetector.process(currentImage)
                        .addOnSuccessListener(pose -> {
                            List<String> currentPoseData = new ArrayList<>();
                            pose.getAllPoseLandmarks().forEach(landmark -> {
                                currentPoseData.add(landmark.getLandmarkType() + ", " +
                                        landmark.getPosition().x + ", " +
                                        landmark.getPosition().y);
                            });

                            // 比较姿势数据
                            if (isPoseSimilarByAngle(savedPoseData, currentPoseData, 5.0)) { // 允许误差为 5.0 度
                                Toast.makeText(this, "姿势一致", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "姿势不一致", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(this, "检测当前姿势失败", Toast.LENGTH_SHORT).show();
                            Log.e("PoseDetection", "Failed to detect current pose", e);
                        });
            } else {
                Toast.makeText(this, "没有可用的图像数据", Toast.LENGTH_SHORT).show();
                Log.e("PoseDetection", "MediaImage is null");
            }
        } else {
            Toast.makeText(this, "没有可用的图像数据", Toast.LENGTH_SHORT).show();
            Log.e("PoseDetection", "Latest ImageProxy is null");
        }
    } catch (Exception e) {
        Toast.makeText(this, "读取 CSV 文件失败", Toast.LENGTH_SHORT).show();
        Log.e("CSV", "Failed to read CSV file", e);
    }
}
    private boolean isPoseSimilarByAngle(List<String> savedPoseData, List<String> currentPoseData, double tolerance) {
        if (savedPoseData.size() != currentPoseData.size()) {
            return false; // 数据长度不一致
        }

        for (int i = 0; i < savedPoseData.size(); i++) {
            String[] savedParts = savedPoseData.get(i).split(", ");
            String[] currentParts = currentPoseData.get(i).split(", ");

            if (savedParts.length != 2 || currentParts.length != 2) {
                continue; // 跳过无效数据
            }

            try {
                double savedAngle = Double.parseDouble(savedParts[1]);
                double currentAngle = Double.parseDouble(currentParts[1]);

                // 比较角度误差
                if (Math.abs(savedAngle - currentAngle) > tolerance) {
                    return false; // 超出误差范围
                }
            } catch (NumberFormatException e) {
                Log.e("PoseComparison", "数据格式错误", e);
                return false;
            }
        }

        return true; // 所有角度都在误差范围内
    }
    private boolean isPoseSimilar(List<String> savedPoseData, List<String> currentPoseData, double tolerance) {
        if (savedPoseData.size() != currentPoseData.size()) {
            return false; // 数据长度不一致
        }

        for (int i = 0; i < savedPoseData.size(); i++) {
            String[] savedParts = savedPoseData.get(i).split(", ");
            String[] currentParts = currentPoseData.get(i).split(", ");

            if (savedParts.length != 3 || currentParts.length != 3) {
                continue; // 跳过无效数据
            }

            try {
                double savedX = Double.parseDouble(savedParts[1]);
                double savedY = Double.parseDouble(savedParts[2]);
                double currentX = Double.parseDouble(currentParts[1]);
                double currentY = Double.parseDouble(currentParts[2]);

                // 计算欧几里得距离
                double distance = Math.sqrt(Math.pow(savedX - currentX, 2) + Math.pow(savedY - currentY, 2));
                if (distance > tolerance) {
                    return false; // 超出误差范围
                }
            } catch (NumberFormatException e) {
                Log.e("PoseComparison", "数据格式错误", e);
                return false;
            }
        }

        return true; // 所有关键点都在误差范围内
    }
    static double getAngle(PoseLandmark firstPoint, PoseLandmark midPoint, PoseLandmark lastPoint) {
        double result =
                Math.toDegrees(
                        atan2(lastPoint.getPosition().y - midPoint.getPosition().y,
                                lastPoint.getPosition().x - midPoint.getPosition().x)
                                - atan2(firstPoint.getPosition().y - midPoint.getPosition().y,
                                firstPoint.getPosition().x - midPoint.getPosition().x));
        result = Math.abs(result); // Angle should never be negative
        if (result > 180) {
            result = (360.0 - result); // Always get the acute representation of the angle
        }
        return result;
    }
    private void savePoseAnglesToCSV(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            Log.e("CSV", "Image is null");
            imageProxy.close();
            return;
        }

        File csvFile = new File(getExternalFilesDir(null), "pose_angles.csv");
        try {
            InputImage lastImage = InputImage.fromMediaImage(mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees());

            poseDetector.process(lastImage)
                    .addOnSuccessListener(pose -> {
                        StringBuilder csvData = new StringBuilder();
                        List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();

                        // 计算角度并保存
                        for (int i = 0; i < landmarks.size() - 2; i++) {
                            PoseLandmark first = landmarks.get(i);
                            PoseLandmark mid = landmarks.get(i + 1);
                            PoseLandmark last = landmarks.get(i + 2);

                            double angle = getAngle(first, mid, last);
                            csvData.append(mid.getLandmarkType()).append(", ")
                                    .append(angle).append("\n");
                        }

                        try (FileWriter writer = new FileWriter(csvFile, true)) {
                            if (csvFile.length() == 0) {
                                writer.append("Landmark, Angle\n");
                            }
                            writer.append(csvData.toString());
                            writer.flush();
                        } catch (Exception e) {
                            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                            Log.e("CSV", "Failed to write pose angles", e);
                        }
                    })
                    .addOnFailureListener(e -> Log.e("PoseDetection", "Failed to detect pose", e))
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            Log.e("CSV", "Failed to process image or write to CSV file", e);
            imageProxy.close();
        }
    }
    private class PoseAnalyzer implements ImageAnalysis.Analyzer {

        @androidx.camera.core.ExperimentalGetImage
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            // 保存最新的 ImageProxy
            if (latestImageProxy != null) {
                latestImageProxy.close(); // 关闭之前的 ImageProxy
            }
            latestImageProxy = imageProxy;

            Image mediaImage = imageProxy.getImage();
            if (mediaImage != null) {
                InputImage image = InputImage.fromMediaImage(mediaImage,
                        imageProxy.getImageInfo().getRotationDegrees());

                poseDetector.process(image)
                        .addOnSuccessListener(pose -> {
                            int count = pose.getAllPoseLandmarks().size();
                            runOnUiThread(() -> binding.setPoseText("关键点数量：" + count));
                            Log.d("PoseDetection", "Landmarks detected: " + count);
                        })
                        .addOnFailureListener(e ->
                                Log.e("PoseDetection", "Detection failed", e))
                        .addOnCompleteListener(task -> imageProxy.close());

            } else {
                imageProxy.close();
            }

        }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (poseDetector != null) poseDetector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}