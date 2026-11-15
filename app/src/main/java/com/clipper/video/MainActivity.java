package com.clipper.video;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.content.Intent;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

public class MainActivity extends ComponentActivity {
    private VideoView videoView;
    private EditText startTime, endTime;
    private Button selectButton, exportButton;
    private TextView status;
    private Uri videoUri;
    private Transformer activeTransformer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("CLIPPER VIDEO");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Potong video dengan mudah");
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle);

        videoView = new VideoView(this);
        layout.addView(videoView, new LinearLayout.LayoutParams(-1, 240));

        selectButton = new Button(this);
        selectButton.setText("PILIH VIDEO");
        layout.addView(selectButton);

        startTime = new EditText(this);
        startTime.setHint("Waktu mulai (detik), contoh: 0");
        startTime.setInputType(8194);
        layout.addView(startTime);

        endTime = new EditText(this);
        endTime.setHint("Waktu selesai (detik), contoh: 15");
        endTime.setInputType(8194);
        layout.addView(endTime);

        exportButton = new Button(this);
        exportButton.setText("POTONG & SIMPAN MP4");
        layout.addView(exportButton);

        status = new TextView(this);
        status.setText("Pilih video untuk mulai.");
        status.setPadding(0, 16, 0, 0);
        layout.addView(status);
        setContentView(layout);

        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                videoUri = uri;
                videoView.setVideoURI(uri);
                videoView.setOnPreparedListener(mp -> {
                    mp.setOnVideoSizeChangedListener((player, width, height) -> {});
                    videoView.start();
                });
                status.setText("Video siap dipotong.");
            }
        });

        selectButton.setOnClickListener(v -> picker.launch("video/*"));
        exportButton.setOnClickListener(v -> exportVideo());
    }

    private void exportVideo() {
        if (videoUri == null) {
            status.setText("Pilih video terlebih dahulu.");
            return;
        }

        final long startMs;
        final long endMs;
        try {
            String startText = startTime.getText().toString().trim();
            String endText = endTime.getText().toString().trim();
            if (startText.isEmpty() || endText.isEmpty()) {
                throw new NumberFormatException();
            }
            startMs = Long.parseLong(startText) * 1000L;
            endMs = Long.parseLong(endText) * 1000L;
        } catch (NumberFormatException e) {
            status.setText("Isi waktu mulai dan selesai dengan angka detik.");
            return;
        }

        if (startMs < 0 || endMs <= startMs) {
            status.setText("Waktu tidak valid. Waktu selesai harus lebih besar.");
            return;
        }

        exportButton.setEnabled(false);
        selectButton.setEnabled(false);
        status.setText("Sedang memotong video...");

        executor.execute(() -> {
            File output = new File(getCacheDir(), "clipper_output.mp4");
            if (output.exists()) output.delete();

            MediaItem item = new MediaItem.Builder()
                    .setUri(videoUri)
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(startMs)
                                    .setEndPositionMs(endMs)
                                    .build())
                    .build();

            EditedMediaItem edited = new EditedMediaItem.Builder(item).build();

            runOnUiThread(() -> {
                activeTransformer = new Transformer.Builder(this)
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult result) {
                                saveVideo(output);
                            }

                            @Override
                            public void onError(Composition composition, ExportResult result,
                                                ExportException exception) {
                                runOnUiThread(() -> {
                                    status.setText("Gagal memproses: " + exception.getMessage());
                                    setButtonsEnabled(true);
                                });
                            }
                        })
                        .build();
                try {
                    activeTransformer.start(edited, output.getAbsolutePath());
                } catch (Exception e) {
                    status.setText("Gagal memulai: " + e.getMessage());
                    setButtonsEnabled(true);
                }
            });
        });
    }

    private void saveVideo(File file) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    "Clipper_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clipper");

            Uri savedUri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (savedUri == null) throw new IOException("Tidak bisa membuat file tujuan.");

            try (InputStream in = new FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                if (out == null) throw new IOException("Tidak bisa membuka file tujuan.");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }

            runOnUiThread(() -> {
                status.setText("BERHASIL! Video tersimpan di Movies/Clipper.");
                setButtonsEnabled(true);
                Toast.makeText(this, "Video berhasil disimpan", Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                status.setText("Gagal menyimpan: " + e.getMessage());
                setButtonsEnabled(true);
            });
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        exportButton.setEnabled(enabled);
        selectButton.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
