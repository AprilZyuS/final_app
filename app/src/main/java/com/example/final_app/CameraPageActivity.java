package com.example.final_app;

import android.os.Bundle;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;

public class CameraPageActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_page);

        ImageView imageView = findViewById(R.id.imageView);
        imageView.setOnClickListener(v -> finish()); // 点击图片返回上一页面
    }
}