package com.clipper.video;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private EditText serverUrl;
    private TextView selectedName, status;
    private Button chooseButton, makeButton;
    private LinearLayout results;
    private Uri videoUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> picker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 28, 24, 28);
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("CLIPPER AI");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ubah video panjang menjadi klip pendek otomatis");
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 20);
        page.addView(subtitle);

        TextView serverLabel = new TextView(this);
        serverLabel.setText("Alamat server AI (HTTPS)");
        page.addView(serverLabel);
        serverUrl = new EditText(this);
        serverUrl.setSingleLine(true);
        serverUrl.setHint("https://alamat-server-anda");
        serverUrl.setText(getPreferences(MODE_PRIVATE).getString("server_url", ""));
        page.addView(serverUrl);

        TextView limit = new TextView(this);
        limit.setText("Batas versi awal: video maksimal 10 menit. AI menganalisis ucapan, memilih hingga 5 bagian, lalu membuat klip vertikal 9:16 dan subtitle.");
        limit.setPadding(0, 12, 0, 12);
        page.addView(limit);

        chooseButton = new Button(this);
        chooseButton.setText("PILIH VIDEO");
        page.addView(chooseButton);
        selectedName = new TextView(this);
        selectedName.setText("Belum ada video dipilih.");
        selectedName.setPadding(0, 8, 0, 8);
        page.addView(selectedName);

        makeButton = new Button(this);
        makeButton.setText("BUAT KLIP OTOMATIS DENGAN AI");
        page.addView(makeButton);

        status = new TextView(this);
        status.setText("Masukkan alamat server AI setelah server dipasang.");
        status.setPadding(0, 16, 0, 16);
        page.addView(status);

        TextView resultTitle = new TextView(this);
        resultTitle.setText("HASIL KLIP");
        resultTitle.setTextSize(18);
        resultTitle.setTypeface(null, 1);
        page.addView(resultTitle);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);

        setContentView(scroll);
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                videoUri = uri;
                selectedName.setText(getDisplayName(uri));
                status.setText("Video dipilih. Tekan tombol untuk mulai.");
            }
        });
        chooseButton.setOnClickListener(v -> picker.launch("video/*"));
        makeButton.setOnClickListener(v -> startJob());
    }

    private String getDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "Video dipilih";
    }

    private void startJob() {
        if (videoUri == null) { status.setText("Pilih video terlebih dahulu."); return; }
        String base = serverUrl.getText().toString().trim().replaceAll("/+$", "");
        if (!base.startsWith("https://")) { status.setText("Alamat server harus diawali https://"); return; }
        getPreferences(MODE_PRIVATE).edit().putString("server_url", base).apply();
        chooseButton.setEnabled(false); makeButton.setEnabled(false);
        results.removeAllViews();
        status.setText("Mengunggah video ke server AI...");
        executor.execute(() -> {
            try {
                String jobId = uploadVideo(base);
                updateStatus("Video diterima. AI sedang menganalisis ucapan dan memilih momen terbaik...");
                JSONObject job = null;
                for (int i = 0; i < 360; i++) {
                    Thread.sleep(5000);
                    job = getJson(base + "/api/jobs/" + jobId);
                    String state = job.optString("status");
                    if ("completed".equals(state)) break;
                    if ("failed".equals(state)) throw new IOException(job.optString("message", "Analisis gagal."));
                    updateStatus("AI sedang bekerja... Status: " + state);
                }
                if (job == null || !"completed".equals(job.optString("status")))
                    throw new IOException("Proses terlalu lama. Coba cek server atau ulangi nanti.");
                JSONArray clips = job.optJSONArray("clips");
                if (clips == null || clips.length() == 0) throw new IOException("AI belum menghasilkan klip.");
                for (int i = 0; i < clips.length(); i++) {
                    JSONObject clip = clips.getJSONObject(i);
                    String video = clip.optString("video_url");
                    String subtitle = clip.optString("subtitle_url");
                    String label = clip.optString("title", "Klip " + (i+1));
                    addClip(label, clip.optString("transcript"), video, subtitle);
                }
                updateStatus(job.optString("message", "Selesai!"));
            } catch (Exception e) {
                updateStatus("Gagal: " + e.getMessage());
            } finally {
                runOnUiThread(() -> { chooseButton.setEnabled(true); makeButton.setEnabled(true); });
            }
        });
    }

    private String uploadVideo(String base) throws Exception {
        String boundary = "----ClipperBoundary" + System.currentTimeMillis();
        URL url = new URL(base + "/api/clip?base_url=" + URLEncoder.encode(base, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream());
             InputStream in = getContentResolver().openInputStream(videoUri)) {
            if (in == null) throw new IOException("Tidak bisa membaca video.");
            String filename = getDisplayName(videoUri).replace("\"", "_");
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"video\"; filename=\"" + filename + "\"\r\nContent-Type: video/mp4\r\n\r\n");
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            write(out, "\r\n--" + boundary + "--\r\n");
            out.flush();
        }
        int code = conn.getResponseCode();
        InputStream response = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(response);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Server menolak video: " + body);
        return new JSONObject(body).getString("job_id");
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        int code = c.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException(body);
        return new JSONObject(body);
    }

    private void addClip(String title, String transcript, String videoUrl, String subtitleUrl) {
        runOnUiThread(() -> {
            TextView t = new TextView(this);
            t.setText(title + "\n" + transcript);
            t.setPadding(0, 12, 0, 6);
            results.addView(t);
            Button download = new Button(this);
            download.setText("UNDUH " + title.toUpperCase());
            results.addView(download);
            download.setOnClickListener(v -> {
                download.setEnabled(false);
                executor.execute(() -> {
                    try {
                        saveRemoteVideo(videoUrl);
                        runOnUiThread(() -> Toast.makeText(this, title + " tersimpan di Movies/Clipper", Toast.LENGTH_LONG).show());
                    } catch (Exception e) { updateStatus("Gagal mengunduh: " + e.getMessage()); }
                    finally { runOnUiThread(() -> download.setEnabled(true)); }
                });
            });
            Button subtitleButton = new Button(this);
            subtitleButton.setText("UNDUH SUBTITLE (.SRT)");
            results.addView(subtitleButton);
            subtitleButton.setOnClickListener(v -> executor.execute(() -> {
                try { saveSubtitle(subtitleUrl, title.replace(" ", "_") + ".srt"); }
                catch (Exception e) { updateStatus("Gagal mengunduh subtitle: " + e.getMessage()); }
            }));
        });
    }

    private void saveRemoteVideo(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(120000);
        if (c.getResponseCode() != 200) throw new IOException("Tidak bisa mengunduh klip.");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "Clipper_AI_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clipper");
        Uri dest = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (dest == null) throw new IOException("Tidak bisa membuat file tujuan.");
        try (InputStream in = c.getInputStream(); OutputStream out = getContentResolver().openOutputStream(dest)) {
            byte[] b = new byte[64*1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
        }
        c.disconnect();
    }

    private void saveSubtitle(String address, String filename) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        if (c.getResponseCode() != 200) throw new IOException("Subtitle tidak tersedia.");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Clipper");
        Uri dest = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (dest == null) throw new IOException("Tidak bisa menyimpan subtitle.");
        try (InputStream in = c.getInputStream(); OutputStream out = getContentResolver().openOutputStream(dest)) {
            byte[] b = new byte[8192]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
        }
        c.disconnect();
        runOnUiThread(() -> Toast.makeText(this, "Subtitle tersimpan di Download/Clipper", Toast.LENGTH_LONG).show());
    }

    private void updateStatus(String value) { runOnUiThread(() -> status.setText(value)); }
    private static void write(OutputStream out, String s) throws IOException { out.write(s.getBytes("UTF-8")); }
    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192]; int n; while ((n=in.read(data))!=-1) b.write(data,0,n);
            return b.toString("UTF-8");
        }
    }
    @Override protected void onDestroy() { executor.shutdown(); super.onDestroy(); }
}
