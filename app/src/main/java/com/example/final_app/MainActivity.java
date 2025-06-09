package com.example.final_app;

import static java.lang.Math.atan2;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class MainActivity extends AppCompatActivity implements BaiduSpeechRecognizer.SpeechRecognitionListener {

    private ActivityMainBinding binding; // 注意变量名改成小写开头
    private static final int REQUEST_CODE_CAMERA = 1001;
    private static final int REQUEST_CODE_AUDIO = 1002;
    private static final int REQUEST_CODE_PERMISSIONS = 1003;

    private PoseDetector poseDetector;
    private ExecutorService cameraExecutor;
    private ImageProxy latestImageProxy;

    private Handler handler;
    private Runnable runnable;
    private Handler handler1;
    private Runnable runnable1;
    private int currentLevel = 0; // 当前关卡
    private final List<String> targetPoses = new ArrayList<>(); // 每关的目标姿势数据
    private final List<String> StrData = new ArrayList<>();
    private String cur_result = ""; //
    private String anglesFileName; // 用于保存角度数据的文件名

    // 语音识别相关
    private BaiduSpeechRecognizer speechRecognizer;
    private boolean isListeningForVoiceCommands = false;

    @OptIn(markerClass = ExperimentalGetImage.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentLevel = 0;

        // 初始化目标姿势数据（示例）
        targetPoses.add("pos1.csv"); // 第一关目标姿势文件
        targetPoses.add("pos2.csv"); // 第二关目标姿势文件
        targetPoses.add("pos3.csv"); // 第三关目标姿势文件
        StrData.add("你好"); // 第一关目标字符串
        StrData.add("哈哈"); // 第二关目标字符串
        StrData.add("不行"); // 第三关目标字符串
        anglesFileName = targetPoses.get(currentLevel); // 初始化为第一关的目标姿势文件名

        // 初始化 DataBinding
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setPoseText("准备识别中...");

        // 初始化 Pose Detector（流式）
        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 初始化语音识别
        initSpeechRecognizer();

        // 权限检查
        checkAndRequestPermissions();

        //文本检测更新
        textUpdate();

        binding.getpose.setOnClickListener(v -> {
            if (latestImageProxy != null) {
                savePoseAnglesToCSV(latestImageProxy);
                savePoseToCSV(latestImageProxy);
            }
        });

        binding.test.setOnClickListener(v -> testimage(anglesFileName));
        // 定时器：每2秒调用一次testimage
        handler1 = new Handler(Looper.getMainLooper());
        runnable1 = new Runnable() {
            @Override
            public void run() {
                testimage(anglesFileName);
                handler1.postDelayed(this, 2000); // 2秒后再次执行
            }
        };
        handler1.post(runnable1); // 启动定时器


        binding.voiceButton.setOnClickListener(v -> {
            if (speechRecognizer != null) {
                if (speechRecognizer.isRecording()) {
                    speechRecognizer.stopRecording();
                } else {
                    startVoiceRecognition();
                }
            }
        });

    }

    /**
     * 初始化语音识别器
     */
    private void initSpeechRecognizer() {
        speechRecognizer = new BaiduSpeechRecognizer(this);
        speechRecognizer.setSpeechRecognitionListener(this);
    }

    /**
     * 检查并请求所需权限
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        } else {
            startCamera();
            // 可以在这里启动语音监听
            // startVoiceRecognition();
        }
    }

    /**
     * 开始语音识别
     */
    private void startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show();
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.startRecording();
            isListeningForVoiceCommands = true;
        }
    }

    // 语音识别回调方法
    @Override
    public void onRecognitionStart() {
        runOnUiThread(() -> {
            Toast.makeText(this, "请喊出口令", Toast.LENGTH_SHORT).show();
            // 可以更新UI显示录音状态
            // binding.voiceStatus.setText("正在录音...");
        });
    }

    @Override
    public void onRecognitionResult(String result) {
        runOnUiThread(() -> {
            Log.d("VoiceRecognition", "识别结果: " + result);
           // Toast.makeText(this, "识别结果: " + result, Toast.LENGTH_LONG).show();

            // 处理语音命令
            cur_result = result;
            if(StrComparison(cur_result,StrData.get(currentLevel)))
            {
                    NextLevel();
            }
            //handleVoiceCommand(result);
        });
    }

    @Override
    public void onRecognitionError(String error) {
        runOnUiThread(() -> {
            Log.e("VoiceRecognition", "识别错误: " + error);
            Toast.makeText(this, "语音识别错误: " + error, Toast.LENGTH_SHORT).show();
            isListeningForVoiceCommands = false;
        });
    }

    @Override
    public void onRecognitionEnd() {
        runOnUiThread(() -> {
            Log.d("VoiceRecognition", "语音识别结束");
            isListeningForVoiceCommands = false;
            // 可以更新UI
            // binding.voiceStatus.setText("点击开始录音");
        });
    }

    /**
     * 处理语音命令
     */
    private void handleVoiceCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }

        String lowerCommand = command.toLowerCase().trim();

        // 处理不同的语音命令
        if (lowerCommand.contains("保存") || lowerCommand.contains("记录") || lowerCommand.contains("拍照")) {
            // 保存当前姿势
            if (latestImageProxy != null) {
                savePoseAnglesToCSV(latestImageProxy);
                savePoseToCSV(latestImageProxy);
                Toast.makeText(this, "已保存当前姿势", Toast.LENGTH_SHORT).show();
            }
        } else if (lowerCommand.contains("测试") || lowerCommand.contains("检测") || lowerCommand.contains("比较")) {
            // 测试姿势匹配
            testimage(anglesFileName);
        } else if (lowerCommand.contains("下一关") || lowerCommand.contains("下一个")) {
            // 切换到下一关
            if (currentLevel < targetPoses.size() - 1) {
                currentLevel++;
                anglesFileName = targetPoses.get(currentLevel);
                Toast.makeText(this, "切换到第" + (currentLevel + 1) + "关", Toast.LENGTH_SHORT).show();
                // 更新文本框显示
                EditText editText = findViewById(R.id.editText);
                if (editText != null) {
                    editText.setText(anglesFileName);
                }
            } else {
                Toast.makeText(this, "已经是最后一关了", Toast.LENGTH_SHORT).show();
            }
        } else if (lowerCommand.contains("上一关") || lowerCommand.contains("返回")) {
            // 切换到上一关
            if (currentLevel > 0) {
                currentLevel--;
                anglesFileName = targetPoses.get(currentLevel);
                Toast.makeText(this, "切换到第" + (currentLevel + 1) + "关", Toast.LENGTH_SHORT).show();
                // 更新文本框显示
                EditText editText = findViewById(R.id.editText);
                if (editText != null) {
                    editText.setText(anglesFileName);
                }
            } else {
                Toast.makeText(this, "已经是第一关了", Toast.LENGTH_SHORT).show();
            }
        } else if (lowerCommand.contains("重新开始") || lowerCommand.contains("重置")) {
            // 重置到第一关
            currentLevel = 0;
            anglesFileName = targetPoses.get(currentLevel);
            Toast.makeText(this, "重置到第一关", Toast.LENGTH_SHORT).show();
            // 更新文本框显示
            EditText editText = findViewById(R.id.editText);
            if (editText != null) {
                editText.setText(anglesFileName);
            }
        } else {
            Toast.makeText(this, "未识别的命令: " + command, Toast.LENGTH_SHORT).show();
        }
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
                        try (FileWriter writer = new FileWriter(csvFile, false)) {
                            // 写入 CSV 表头（如果文件为空）
                            if (csvFile.length() == 0) {
                                writer.append("Landmark, X, Y\n");
                            }
                            writer.append(csvData.toString());
                            writer.flush();
                        } catch (Exception e) {
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
    public void testimage(String path) {
        File csvFile = new File(getExternalFilesDir(null), path);
        if (!csvFile.exists()) {
            Log.e("CSV", "CSV 文件不存在: " + csvFile.getAbsolutePath());
            return;
        }
        anglesFileName = targetPoses.get(currentLevel); // 获取当前关卡的目标姿势文件名
        try {
            // 读取 CSV 文件中的姿势数据
            List<String> savedPoseData = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (lineNumber == 1 && line.contains("Landmark, Angle")) {
                        // 跳过表头
                        continue;
                    }
                    if (!line.trim().isEmpty() && line.contains(",")) {
                        savedPoseData.add(line);
                    } else {
                        Log.w("CSV", "跳过无效行: " + lineNumber + " 内容: " + line);
                    }
                }
            }

            if (savedPoseData.isEmpty()) {
                Log.e("CSV", "CSV 文件没有有效数据");
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
                                List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();
                                // 计算当前角度数据
                                for (int i = 0; i < landmarks.size() - 2; i++) {
                                    PoseLandmark first = landmarks.get(i);
                                    PoseLandmark mid = landmarks.get(i + 1);
                                    PoseLandmark last = landmarks.get(i + 2);

                                    double angle = getAngle(first, mid, last);
                                    currentPoseData.add(mid.getLandmarkType() + ", " + angle);
                                }
                                // 比较姿势数据
                                if (isPoseSimilarByAngle(savedPoseData, currentPoseData, 20.0)) { // 允许误差为 20.0 度

                                }
                            })
                            .addOnFailureListener(e -> {
                                Log.e("PoseDetection", "Failed to detect current pose", e);
                            });
                } else {
                    Log.e("PoseDetection", "MediaImage is null");
                }
            } else {
                Log.e("PoseDetection", "Latest ImageProxy is null");
            }
        } catch (Exception e) {
            Log.e("CSV", "Failed to read CSV file", e);
        }
    }

    private boolean isPoseSimilarByAngle(List<String> savedPoseData, List<String> currentPoseData, double tolerance) {
        if (savedPoseData.size() != currentPoseData.size()) {
            return false; // 数据长度不一致
        }

        int matchCount = 0; // 符合误差范围的角度数量
        int totalCount = savedPoseData.size();

        for (int i = 0; i < totalCount; i++) {
            String[] savedParts = savedPoseData.get(i).split(", ");
            String[] currentParts = currentPoseData.get(i).split(", ");

            if (savedParts.length != 2 || currentParts.length != 2) {
                continue; // 跳过无效数据
            }

            try {
                double savedAngle = Double.parseDouble(savedParts[1]);
                double currentAngle = Double.parseDouble(currentParts[1]);

                // 比较角度误差
                if (Math.abs(savedAngle - currentAngle) <= tolerance) {
                    matchCount++; // 符合误差范围
                }
            } catch (NumberFormatException e) {
                Log.e("PoseComparison", "数据格式错误", e);
            }
        }
        //开始成功语言识别
         if(matchCount>= totalCount*0.85) {
             if (speechRecognizer != null) {
                 if (speechRecognizer.isRecording()) {

                 } else {
                     startVoiceRecognition();
                 }
             }
           }
        // 判断符合误差范围的角度是否达到 85%
        return matchCount >= totalCount * 0.85;
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

        File csvFile = new File(getExternalFilesDir(null), anglesFileName);
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

                        try (FileWriter writer = new FileWriter(csvFile,false)) {
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

    public void textUpdate() {
        EditText editText = findViewById(R.id.editText);
        SharedPreferences sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // 初始化文本框内容
        editText.setText(anglesFileName);

        // 监听文本框输入变化
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                anglesFileName = s.toString();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

    }
    /**
     * 字符串比较方法
     * @param str1 第一个字符串
     * @param str2 答案字符串
     * @return 如果 str1 包含 str2，返回 true；否则返回 false
     */
    public boolean StrComparison(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return false; // 如果任一字符串为 null，返回 false
        }
        return str1.contains(str2);
    }

    /**
     * 下一关
     */
    private void NextLevel() {
        ImageView imageView = findViewById(R.id.imageView4);

        if (currentLevel < targetPoses.size() - 1) {
            currentLevel++; // 进入下一关
            Toast.makeText(this, "姿势一致，进入下一关" + currentLevel, Toast.LENGTH_SHORT).show();
            anglesFileName = targetPoses.get(currentLevel);

            // 更新 ImageView 图片
            switch (currentLevel) {
                case 0:
                    imageView.setImageResource(R.drawable.l1);
                    break;
                case 1:
                    imageView.setImageResource(R.drawable.l2);
                    break;
                case 2:
                    imageView.setImageResource(R.drawable.l3);
                    break;
            }

            // 更新文本框显示
            EditText editText = findViewById(R.id.editText);
            if (editText != null) {
                editText.setText(anglesFileName);
            }
        } else {
            currentLevel = 0; // 重置关卡
            cur_result = " ";
         //   Toast.makeText(this, "restart", Toast.LENGTH_SHORT).show();
            anglesFileName = targetPoses.get(currentLevel);

            // 重置 ImageView 图片
            imageView.setImageResource(R.drawable.l1);

            // 更新文本框显示
            EditText editText = findViewById(R.id.editText);
            if (editText != null) {
                editText.setText(anglesFileName);
            }
            Intent intent = new Intent(this, CameraPageActivity.class);
            startActivity(intent);
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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean cameraGranted = false;
            boolean audioGranted = false;

            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.CAMERA) &&
                        grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO) &&
                        grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioGranted = true;
                }
            }

            if (cameraGranted) {
                startCamera();
            } else {
                Log.e("Permission", "Camera permission denied");
            }

            if (!audioGranted) {
                Log.e("Permission", "Audio permission denied");
                Toast.makeText(this, "语音功能需要录音权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (poseDetector != null) poseDetector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable); // 停止计时器
        }
        // 释放语音识别资源
        if (speechRecognizer != null) {
            speechRecognizer.release();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停时停止语音识别
        if (speechRecognizer != null && speechRecognizer.isRecording()) {
            speechRecognizer.stopRecording();
        }
    }

    /**
     * 提供给外部调用的语音识别启动方法
     */
    public void startVoiceCommand() {
        startVoiceRecognition();
    }

    /**
     * 提供给外部调用的语音识别停止方法
     */
    public void stopVoiceCommand() {
        if (speechRecognizer != null) {
            speechRecognizer.stopRecording();
        }
    }

    /**
     * 检查是否正在进行语音识别
     */
    public boolean isListeningForVoice() {
        return isListeningForVoiceCommands;
    }

}