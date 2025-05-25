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

import okhttp3.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

import java.io.*;

@OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class MainActivity extends AppCompatActivity {

    private AudioRecorder audioRecorder;
    private File pcmFile;

    private ActivityMainBinding binding; // 注意变量名改成小写开头
    private static final int REQUEST_CODE_CAMERA = 1001;
    private PoseDetector poseDetector;
    private ExecutorService cameraExecutor;
    private ImageProxy latestImageProxy;
    private File outputFile;
    private static final int REQUEST_CODE_AUDIO = 1002;

    public static final String API_KEY = "KvxFTc7dONouid47RKfqnGk8";
    public static final String SECRET_KEY = "gk6AQwC4Rm7EIpI7Sv0L5piTlC7ni3zs";

    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient().newBuilder().readTimeout(300, TimeUnit.SECONDS).build();

    @OptIn(markerClass = ExperimentalGetImage.class) @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化 DataBinding
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setPoseText("准备识别中...");

        pcmFile = new File(getExternalFilesDir(null), "test_audio.pcm");


        // 初始化 Pose Detector（流式）
        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        audioRecorder = new AudioRecorder(pcmFile);

        // camera权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
        } else {
            startCamera();
        }
        //audio权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_AUDIO);
        } else {
            audioRecorder.startRecording(); // 权限已通过，开始录音
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

    public interface RecognitionCallback {
        void onResult(String result);
    }

    public static void CheckAuthority(File pcmFile, RecognitionCallback callback) {
        try {
            String accessToken = getAccessToken();
            byte[] audioData = readFileToBytes(pcmFile);
            String speechBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("format", "pcm");
            jsonObject.put("rate", 16000);
            jsonObject.put("channel", 1);
            jsonObject.put("token", accessToken);
            jsonObject.put("cuid", "device-android");
            jsonObject.put("len", audioData.length);
            jsonObject.put("speech", speechBase64);

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"), jsonObject.toString());

            Request request = new Request.Builder()
                    .url("https://vop.baidu.com/server_api")
                    .post(body)
                    .build();

            Response response = HTTP_CLIENT.newCall(request).execute();

            if (response.isSuccessful()) {
                String result = response.body().string();
                JSONObject resultJson = new JSONObject(result);
                if (resultJson.has("result")) {
                    String recognizedText = resultJson.getJSONArray("result").getString(0);
                    callback.onResult(recognizedText);
                } else {
                    callback.onResult("识别失败：" + resultJson.toString());
                }
            } else {
                callback.onResult("请求失败：" + response.code());
            }

        } catch (Exception e) {
            Log.e("BaiduSpeech", "识别异常", e);
            callback.onResult("异常: " + e.getMessage());
        }
    }

    private static byte[] readFileToBytes(File file) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toByteArray();
        }
    }


    @NonNull
    static String getAccessToken() throws IOException {
        MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
        RequestBody body = RequestBody.create(mediaType, "grant_type=client_credentials&client_id=" + API_KEY
                + "&client_secret=" + SECRET_KEY);
        Request request = new Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token")
                .method("POST", body)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();
        Response response = HTTP_CLIENT.newCall(request).execute();
        return new JSONObject(response.body().string()).getString("access_token");
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "未授权相机权限", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                audioRecorder.startRecording();
            } else {
                Toast.makeText(this, "未授权录音权限", Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (poseDetector != null) poseDetector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}