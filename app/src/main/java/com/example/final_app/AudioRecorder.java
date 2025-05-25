package com.example.final_app;

import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;


public class AudioRecorder {
    private AudioRecord recorder;
    private boolean isRecording = false;
    private File outputFile;

    public AudioRecorder(File file) {
        this.outputFile = file;
    }

    public void startRecording() {
        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);
        recorder.startRecording();
        isRecording = true;

        new Thread(() -> {
            try (FileOutputStream os = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[bufferSize];
                while (isRecording) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        os.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                Log.e("AudioRecorder", "录音写入错误", e);
            }
        }).start();
    }

    public void stopRecording() {

        isRecording = false;
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }
    }
}