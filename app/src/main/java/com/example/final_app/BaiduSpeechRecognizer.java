package com.example.final_app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BaiduSpeechRecognizer {

    private static final String TAG = "BaiduSpeechRecognizer";

    // 百度千帆配置
    private static final String API_KEY = "FdQwfRsWMm3d5da1XV21ZwgT";
    private static final String SECRET_KEY = "w9VZeUarlNm8nJglKp2fRcRasSUljqGB";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String SPEECH_URL = "https://vop.baidu.com/server_api";

    // 音频录制参数
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    private Context context;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private String accessToken;
    private OkHttpClient httpClient;
    private SpeechRecognitionListener listener;

    // 回调接口
    public interface SpeechRecognitionListener {
        void onRecognitionStart();
        void onRecognitionResult(String result);
        void onRecognitionError(String error);
        void onRecognitionEnd();
    }

    public BaiduSpeechRecognizer(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // 初始化时获取访问令牌
        getAccessToken();
    }

    public void setSpeechRecognitionListener(SpeechRecognitionListener listener) {
        this.listener = listener;
    }

    /**
     * 获取百度API访问令牌
     */
    private void getAccessToken() {
        String tokenUrl = TOKEN_URL + "?grant_type=client_credentials" +
                "&client_id=" + API_KEY +
                "&client_secret=" + SECRET_KEY;

        Request request = new Request.Builder()
                .url(tokenUrl)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "获取访问令牌失败", e);
                if (listener != null) {
                    listener.onRecognitionError("获取访问令牌失败: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String responseBody = response.body().string();
                        JSONObject jsonObject = new JSONObject(responseBody);
                        accessToken = jsonObject.getString("access_token");
                        Log.d(TAG, "访问令牌获取成功");
                    } catch (JSONException e) {
                        Log.e(TAG, "解析访问令牌响应失败", e);
                        if (listener != null) {
                            listener.onRecognitionError("解析访问令牌失败");
                        }
                    }
                } else {
                    Log.e(TAG, "获取访问令牌HTTP错误: " + response.code());
                    if (listener != null) {
                        listener.onRecognitionError("获取访问令牌HTTP错误: " + response.code());
                    }
                }
            }
        });
    }

    /**
     * 开始录音和语音识别
     */
    public void startRecording() {
        if (accessToken == null || accessToken.isEmpty()) {
            if (listener != null) {
                listener.onRecognitionError("访问令牌未准备就绪，请稍后重试");
            }
            return;
        }

        if (isRecording) {
            Log.w(TAG, "已经在录音中");
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败");
                if (listener != null) {
                    listener.onRecognitionError("音频录制初始化失败");
                }
                return;
            }

            isRecording = true;
            audioRecord.startRecording();

            if (listener != null) {
                listener.onRecognitionStart();
            }

            // 在新线程中录音
            new Thread(this::recordAudio).start();

        } catch (SecurityException e) {
            Log.e(TAG, "录音权限被拒绝", e);
            if (listener != null) {
                listener.onRecognitionError("录音权限被拒绝");
            }
        } catch (Exception e) {
            Log.e(TAG, "开始录音失败", e);
            if (listener != null) {
                listener.onRecognitionError("开始录音失败: " + e.getMessage());
            }
        }
    }

    /**
     * 停止录音
     */
    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "停止录音失败", e);
            }
        }
    }

    /**
     * 录音线程
     */
    private void recordAudio() {
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            // 录音5秒
            long startTime = System.currentTimeMillis();
            long maxRecordTime = 5000; // 5秒

            while (isRecording && (System.currentTimeMillis() - startTime) < maxRecordTime) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead);
                } else {
                    Log.w(TAG, "读取音频数据失败: " + bytesRead);
                }
            }

            // 自动停止录音
            stopRecording();

            // 发送音频数据进行识别
            byte[] audioData = audioBuffer.toByteArray();
            if (audioData.length > 0) {
                sendAudioForRecognition(audioData);
            } else {
                if (listener != null) {
                    listener.onRecognitionError("没有录制到音频数据");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "录音过程中发生错误", e);
            if (listener != null) {
                listener.onRecognitionError("录音过程中发生错误: " + e.getMessage());
            }
        } finally {
            try {
                audioBuffer.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭音频缓冲区失败", e);
            }
        }
    }

    /**
     * 发送音频数据进行语音识别
     */
    private void sendAudioForRecognition(byte[] audioData) {
        try {
            // 将音频数据转换为Base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // 构建请求JSON
            JSONObject requestJson = new JSONObject();
            requestJson.put("format", "pcm");
            requestJson.put("rate", SAMPLE_RATE);
            requestJson.put("channel", 1);
            requestJson.put("cuid", "android_app");
            requestJson.put("token", accessToken);
            requestJson.put("speech", audioBase64);
            requestJson.put("len", audioData.length);

            // 发送POST请求
            RequestBody requestBody = RequestBody.create(
                    requestJson.toString(),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(SPEECH_URL)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "语音识别请求失败", e);
                    if (listener != null) {
                        listener.onRecognitionError("语音识别请求失败: " + e.getMessage());
                        listener.onRecognitionEnd();
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body().string();
                        Log.d(TAG, "语音识别响应: " + responseBody);

                        JSONObject responseJson = new JSONObject(responseBody);
                        int errNo = responseJson.getInt("err_no");

                        if (errNo == 0) {
                            // 识别成功
                            if (responseJson.has("result")) {
                                String result = responseJson.getJSONArray("result").getString(0);
                                if (listener != null) {
                                    listener.onRecognitionResult(result);
                                }
                            } else {
                                if (listener != null) {
                                    listener.onRecognitionError("识别结果为空");
                                }
                            }
                        } else {
                            // 识别失败
                            String errMsg = responseJson.optString("err_msg", "未知错误");
                            if (listener != null) {
                                listener.onRecognitionError("识别失败: " + errMsg);
                            }
                        }

                    } catch (JSONException e) {
                        Log.e(TAG, "解析识别响应失败", e);
                        if (listener != null) {
                            listener.onRecognitionError("解析识别响应失败");
                        }
                    } finally {
                        if (listener != null) {
                            listener.onRecognitionEnd();
                        }
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "构建识别请求失败", e);
            if (listener != null) {
                listener.onRecognitionError("构建识别请求失败");
                listener.onRecognitionEnd();
            }
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopRecording();
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    /**
     * 检查是否正在录音
     */
    public boolean isRecording() {
        return isRecording;
    }
}