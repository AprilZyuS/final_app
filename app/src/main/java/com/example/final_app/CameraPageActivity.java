package com.example.final_app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;

public class CameraPageActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_page);

        // 初始化 VideoView
        VideoView videoView = findViewById(R.id.videoView);

        // 设置视频路径
        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/raw/endv");
        videoView.setVideoURI(videoUri);

        // 设置视频播放完成监听器
        videoView.setOnCompletionListener(mp -> {

        });

        // 设置点击事件监听器
        videoView.setOnClickListener(v -> {
            // 返回原页面
            finish();
        });

        // 开始播放
        videoView.start();
    }
}