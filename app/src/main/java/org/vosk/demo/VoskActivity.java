// Copyright 2026 — 巴巴塔
// 语音识别 App：支持模型下载、录音保存、录音播放、录音文件管理

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoskActivity extends Activity {

    static private final int PERMISSIONS_REQUEST_AUDIO = 1;

    // UI
    private TextView resultView;
    private TextView statusView;
    private Spinner modelSelector;
    private Button micBtn;
    private ToggleButton pauseBtn;
    private Button recordingsBtn;

    // Vosk
    private Model model;
    private Recognizer recognizer;
    private String currentModelId = "vosk-model-small-cn-0.22";

    // Recording
    private boolean isRecording = false;
    private Thread recordThread;
    private AudioRecord audioRecord;
    private File recordFile;            // the .wav file being written
    private MediaPlayer mediaPlayer;

    // Model info
    static class ModelInfo {
        String id, label, url;
        ModelInfo(String id, String label, String url) {
            this.id = id; this.label = label; this.url = url;
        }
    }
    private static final ModelInfo[] AVAILABLE_MODELS = new ModelInfo[]{
            new ModelInfo("vosk-model-small-cn-0.22",     "中文（小，66MB）",  "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
            new ModelInfo("vosk-model-small-en-us-0.15",   "英文（小，42MB）",  "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            new ModelInfo("vosk-model-cn-0.22",            "中文（大，2GB）",   "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"),
            new ModelInfo("vosk-model-en-us-0.22",         "英文（大，1.8GB）", "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"),
    };

    // ───────────────────────── 生命周期 ─────────────────────────

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        resultView = findViewById(R.id.result_text);
        statusView = findViewById(R.id.status_text);
        resultView.setMovementMethod(new ScrollingMovementMethod());
        resultView.setText("");

        statusView.setText("正在准备···");

        micBtn = findViewById(R.id.recognize_mic);
        pauseBtn = findViewById(R.id.pause);
        recordingsBtn = findViewById(R.id.btn_recordings);
        modelSelector = findViewById(R.id.model_selector);
        Button downloadBtn = findViewById(R.id.download_models);

        micBtn.setOnClickListener(v -> toggleRecording());
        pauseBtn.setOnCheckedChangeListener((v, checked) -> { /* pause is UI only */ });
        recordingsBtn.setOnClickListener(v -> showRecordingsDialog());
        downloadBtn.setOnClickListener(v -> showModelChooser());

        modelSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String sel = (String) p.getItemAtPosition(pos);
                String[] parts = sel.split("\\|", 2);
                String mid = parts[0];
                if (!mid.equals(currentModelId)) {
                    stopRecording();
                    releaseModel();
                    currentModelId = mid;
                    initModel();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        refreshModelList();

        LibVosk.setLogLevel(LogLevel.INFO);

        // 权限检查
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                allGranted = false;
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, perms, PERMISSIONS_REQUEST_AUDIO);
        } else {
            initModel();
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();
        releaseModel();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSIONS_REQUEST_AUDIO) initModel();
    }

    // ───────────────────────── 模型管理 ─────────────────────────

    private File getModelsDir() {
        return new File(getFilesDir(), "vosk_models");
    }

    private boolean isModelInstalled(String id) {
        return new File(getModelsDir(), id + "/am/final.mdl").exists();
    }

    private void refreshModelList() {
        ArrayList<String> items = new ArrayList<>();
        for (ModelInfo mi : AVAILABLE_MODELS) {
            if (isModelInstalled(mi.id))
                items.add(mi.id + "|" + mi.label);
        }
        if (items.isEmpty()) items.add("无可用模型|请先下载");
        ArrayAdapter<String> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSelector.setAdapter(adp);
    }

    private void initModel() {
        File dir = new File(getModelsDir(), currentModelId);
        if (!dir.exists() || !new File(dir, "am/final.mdl").exists()) {
            statusView.setText("模型未安装，请点击「📥 下载模型」");
            return;
        }
        try {
            model = new Model(dir.getAbsolutePath());
            statusView.setText("就绪（" + currentModelId + "）");
            micBtn.setEnabled(true);
        } catch (Exception e) {
            statusView.setText("加载模型失败：" + e.getMessage());
            micBtn.setEnabled(false);
        }
    }

    private void releaseModel() {
        if (model != null) { model = null; }
        if (recognizer != null) { recognizer = null; }
    }

    // ───────────────────────── 模型下载 ─────────────────────────

    private void showModelChooser() {
        String[] names = new String[AVAILABLE_MODELS.length];
        boolean[] inst = new boolean[AVAILABLE_MODELS.length];
        for (int i = 0; i < AVAILABLE_MODELS.length; i++) {
            inst[i] = isModelInstalled(AVAILABLE_MODELS[i].id);
            names[i] = AVAILABLE_MODELS[i].label + (inst[i] ? " ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("下载模型")
                .setItems(names, (d, w) -> {
                    if (!inst[w]) downloadModel(AVAILABLE_MODELS[w]);
                    else statusView.setText("已安装，请在顶部切换");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadModel(ModelInfo mi) {
        statusView.setText("正在下载 " + mi.label + " ...");
        new AsyncTask<Void, Integer, String>() {
            ProgressDialog pd;
            @Override protected void onPreExecute() {
                pd = new ProgressDialog(VoskActivity.this);
                pd.setTitle("下载模型"); pd.setMessage("正在下载 " + mi.label);
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMax(100); pd.setCancelable(false); pd.show();
            }
            @Override protected String doInBackground(Void... v) {
                File dir = getModelsDir(); if (!dir.exists()) dir.mkdirs();
                File zip = new File(dir, mi.id + ".zip");
                File mod = new File(dir, mi.id);
                try {
                    URL url = new URL(mi.url);
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.connect();
                    int len = c.getContentLength();
                    InputStream is = new BufferedInputStream(c.getInputStream());
                    FileOutputStream os = new FileOutputStream(zip);
                    byte[] buf = new byte[8192]; long total = 0; int n;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n); total += n;
                        if (len > 0) publishProgress((int)(total * 100 / len));
                    }
                    os.close(); is.close();
                    publishProgress(-1);
                    extractZip(zip.getAbsolutePath(), mod.getAbsolutePath());
                    zip.delete();
                    java.util.UUID uuid = java.util.UUID.randomUUID();
                    java.io.FileWriter fw = new java.io.FileWriter(new File(mod, "uuid"));
                    fw.write(uuid.toString()); fw.close();
                    return null;
                } catch (Exception e) {
                    if (zip.exists()) zip.delete();
                    if (mod.exists()) deleteDir(mod);
                    return "下载失败：" + e.getMessage();
                }
            }
            @Override protected void onProgressUpdate(Integer... v) {
                if (v[0] == -1) { pd.setMessage("正在解压..."); pd.setIndeterminate(true); }
                else pd.setProgress(v[0]);
            }
            @Override protected void onPostExecute(String err) {
                pd.dismiss();
                if (err == null) {
                    statusView.setText(mi.label + " 下载完成 ✓");
                    refreshModelList();
                    currentModelId = mi.id;
                    releaseModel(); initModel();
                } else {
                    statusView.setText(err);
                }
            }
        }.execute();
    }

    private void extractZip(String zipPath, String destDir) throws IOException {
        File dest = new File(destDir); if (!dest.exists()) dest.mkdirs();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipPath)));
        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            int si = name.indexOf('/');
            if (si > 0) name = name.substring(si + 1);
            if (name.isEmpty()) continue;
            File f = new File(dest, name);
            if (entry.isDirectory()) { f.mkdirs(); }
            else {
                f.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(f);
                int n; while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private void deleteDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) { for (File f : fs) { if (f.isDirectory()) deleteDir(f); f.delete(); } }
        dir.delete();
    }

    // ───────────────────────── 录音 ─────────────────────────

    private File getRecordingsDir() {
        File d = new File(getFilesDir(), "recordings");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (model == null) {
            Toast.makeText(this, "模型未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建输出文件
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        recordFile = new File(getRecordingsDir(), ts + ".wav");
        recognizer = new Recognizer(model, 16000.0f);
        isRecording = true;

        resultView.setText("");
        micBtn.setText("⏹ 停止录音");
        statusView.setText("🎤 录音中···");

        final int SAMPLE_RATE = 16000;
        final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, BUFFER_SIZE * 4);

            // WAV 文件头
            final FileOutputStream fos = new FileOutputStream(recordFile);
            // 先写44字节占位，停止时重写
            fos.write(new byte[44]);
            final long[] totalBytes = {44}; // header offset

            audioRecord.startRecording();

            recordThread = new Thread(() -> {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;

                while (isRecording) {
                    bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        // 写文件
                        try { fos.write(buffer, 0, bytesRead); totalBytes[0] += bytesRead; } catch (Exception e) {}
                        // 喂给 Vosk
                        if (recognizer != null) {
                            recognizer.acceptWaveForm(buffer, bytesRead);
                            String partial = recognizer.getPartialResult();
                            String text = extractText(partial);
                            if (!text.isEmpty()) {
                                String finalText = text;
                                runOnUiThread(() -> {
                                    resultView.setText("💬 " + finalText);
                                });
                            }
                        }
                    }
                }

                // 停止后写 WAV 文件头
                try {
                    long dataLen = totalBytes[0] - 44;
                    fos.close();
                    // 重写 WAV header
                    writeWavHeader(recordFile, SAMPLE_RATE, 16, 1, (int)(totalBytes[0] - 44));
                } catch (Exception e) {}

                // 获取最终结果
                if (recognizer != null) {
                    String finalResult = recognizer.getFinalResult();
                    String text = extractText(finalResult);
                    // 保存识别结果到 .txt 文件
                    if (!text.isEmpty()) {
                        try {
                            File txtFile = new File(recordFile.getAbsolutePath().replace(".wav", ".txt"));
                            FileOutputStream fosTxt = new FileOutputStream(txtFile);
                            fosTxt.write(text.getBytes());
                            fosTxt.close();
                        } catch (Exception e) {}
                    }
                    final String displayText = text.isEmpty() ? "未识别到语音" : text;
                    runOnUiThread(() -> {
                        resultView.setText("📝 " + displayText + "\n\n📁 已保存：" + recordFile.getName());
                        statusView.setText("录音完成 ✓ 共 " + (totalBytes[0] - 44) / 32000 + " 秒");
                        micBtn.setText("🎤 开始录音");
                    });
                }
            });
            recordThread.start();
        } catch (Exception e) {
            statusView.setText("启动录音失败：" + e.getMessage());
            isRecording = false;
            micBtn.setText("🎤 开始录音");
        }
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception e) {}
            audioRecord = null;
        }
        if (recordThread != null) {
            try { recordThread.join(2000); } catch (Exception e) {}
            recordThread = null;
        }
        if (recognizer != null) {
            // 如果还没取 finalResult（线程可能还没跑完），这里不重复取
            recognizer = null;
        }
        micBtn.setText("🎤 开始录音");
    }

    /** 写入标准 WAV 文件头（覆盖前44字节） */
    private void writeWavHeader(File file, int sampleRate, int bitsPerSample, int channels, int dataSize) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int totalSize = 36 + dataSize;

        RandomAccessFileWav writer = new RandomAccessFileWav(file);
        writer.write(RIFF, 0, 4);
        writer.writeIntLE(totalSize);
        writer.write(WAVE, 0, 4);
        writer.write(fmt_, 0, 4);
        writer.writeIntLE(16);
        writer.writeShortLE(1); // PCM
        writer.writeShortLE(channels);
        writer.writeIntLE(sampleRate);
        writer.writeIntLE(byteRate);
        writer.writeShortLE(blockAlign);
        writer.writeShortLE(bitsPerSample);
        writer.write(data, 0, 4);
        writer.writeIntLE(dataSize);
        writer.close();
    }

    // 辅助：WAV header 常量
    private static final byte[] RIFF = "RIFF".getBytes();
    private static final byte[] WAVE = "WAVE".getBytes();
    private static final byte[] fmt_ = "fmt ".getBytes();
    private static final byte[] data = "data".getBytes();

    /** 从 JSON 中提取纯文本部分 */
    private String extractText(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            // 简单解析：找 "text" : "..."
            int idx = json.indexOf("\"text\"");
            if (idx < 0) return "";
            int colon = json.indexOf(':', idx);
            int start = json.indexOf('"', colon + 1);
            int end = json.indexOf('"', start + 1);
            if (start < 0 || end < 0) return "";
            return json.substring(start + 1, end);
        } catch (Exception e) {
            return json;
        }
    }

    // ───────────────────────── 录音文件管理 ─────────────────────────

    private void showRecordingsDialog() {
        File dir = getRecordingsDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".wav"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "暂无录音文件", Toast.LENGTH_SHORT).show();
            return;
        }
        // 按时间排序（最新的在前）
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            long sec = files[i].length() / 32000;
            names[i] = files[i].getName() + "  （" + sec + "秒）";
        }

        final File[] finalFiles = files;
        new AlertDialog.Builder(this)
                .setTitle("录音文件")
                .setItems(names, (d, w) -> {
                    showFileActionsDialog(finalFiles[w]);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showFileActionsDialog(File file) {
        String[] actions = {"▶ 播放", "📋 复制识别结果", "🗑 删除"};
        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(actions, (d, w) -> {
                    switch (w) {
                        case 0: playRecording(file); break;
                        case 1: copyTranscript(file); break;
                        case 2: deleteRecording(file); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void playRecording(File file) {
        // 先检查是否有已保存的 .txt，有的话直接显示
        File txtFile = new File(file.getAbsolutePath().replace(".wav", ".txt"));
        if (txtFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(txtFile);
                byte[] buf = new byte[(int)txtFile.length()];
                fis.read(buf); fis.close();
                resultView.setText("📝 " + new String(buf));
                statusView.setText("📄 已加载识别文本");
            } catch (Exception e) {}
        }

        // 播放录音
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            statusView.setText("▶ 正在播放：" + file.getName());
            mediaPlayer.setOnCompletionListener(mp -> {
                statusView.setText("播放完成");
            });
        } catch (Exception e) {
            Toast.makeText(this, "播放失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        // 如果没有 .txt，播放同时在后台识别
        if (!txtFile.exists() && model != null) {
            new AsyncTask<Void, Void, String>() {
                @Override protected String doInBackground(Void... v) {
                    Recognizer rec = new Recognizer(model, 16000.0f);
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        byte[] buf = new byte[(int)file.length()];
                        fis.read(buf); fis.close();
                        int offset = 44;
                        int chunkSize = 8000;
                        byte[] chunk = new byte[chunkSize];
                        while (offset < buf.length) {
                            int copyLen = Math.min(chunkSize, buf.length - offset);
                            System.arraycopy(buf, offset, chunk, 0, copyLen);
                            rec.acceptWaveForm(chunk, copyLen);
                            offset += copyLen;
                        }
                        String result = rec.getFinalResult();
                        rec = null;
                        String text = extractText(result);
                        if (!text.isEmpty()) {
                            try {
                                FileOutputStream fos = new FileOutputStream(txtFile);
                                fos.write(text.getBytes()); fos.close();
                            } catch (Exception e) {}
                        }
                        return text;
                    } catch (Exception e) {
                        return "识别失败：" + e.getMessage();
                    }
                }
                @Override protected void onPostExecute(String text) {
                    if (text != null && !text.isEmpty() && !text.startsWith("识别失败")) {
                        resultView.setText("📝 " + text);
                        statusView.setText("▶ 播放中 · 识别完成 ✓");
                    } else if (text != null) {
                        statusView.setText(text);
                    }
                }
            }.execute();
        }
    }

    private void copyTranscript(File file) {
        // 显示该录音的文本内容（从保存的 .txt 文件或重新识别）
        File txtFile = new File(file.getAbsolutePath().replace(".wav", ".txt"));
        if (txtFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(txtFile);
                byte[] buf = new byte[(int)txtFile.length()];
                fis.read(buf); fis.close();
                resultView.setText("📝 " + new String(buf));
                statusView.setText("已加载识别结果");
            } catch (Exception e) {
                statusView.setText("读取失败：" + e.getMessage());
            }
        } else {
            // 如果没存 .txt，重新识别
            resultView.setText("正在重新识别···");
            statusView.setText("识别中···");
            new AsyncTask<Void, Void, String>() {
                @Override protected String doInBackground(Void... v) {
                    if (model == null) return "模型未就绪";
                    Recognizer rec = new Recognizer(model, 16000.0f);
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        byte[] buf = new byte[(int)file.length()];
                        fis.read(buf); fis.close();
                        // 跳过 WAV header，分段喂给 Vosk
                        int offset = 44;
                        int chunkSize = 8000;
                        byte[] chunk = new byte[chunkSize];
                        while (offset < buf.length) {
                            int copyLen = Math.min(chunkSize, buf.length - offset);
                            System.arraycopy(buf, offset, chunk, 0, copyLen);
                            rec.acceptWaveForm(chunk, copyLen);
                            offset += copyLen;
                        }
                        String result = rec.getFinalResult();
                        rec = null;
                        String text = extractText(result);
                        // 保存 .txt
                        try {
                            FileOutputStream fos = new FileOutputStream(new File(file.getAbsolutePath().replace(".wav", ".txt")));
                            fos.write(text.getBytes()); fos.close();
                        } catch (Exception e) {}
                        return text;
                    } catch (Exception e) {
                        return "识别失败：" + e.getMessage();
                    }
                }
                @Override protected void onPostExecute(String text) {
                    if (text != null && !text.startsWith("识别失败") && !text.startsWith("模型")) {
                        resultView.setText("📝 " + text);
                        statusView.setText("识别完成");
                    } else {
                        statusView.setText(text);
                    }
                }
            }.execute();
        }
    }

    private void deleteRecording(File file) {
        File txt = new File(file.getAbsolutePath().replace(".wav", ".txt"));
        file.delete(); txt.delete();
        statusView.setText("已删除：" + file.getName());
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    // ───────────────────────── WAV 文件头写入工具 ─────────────────────────

    private static class RandomAccessFileWav {
        private final java.io.RandomAccessFile raf;
        RandomAccessFileWav(File f) throws IOException {
            raf = new java.io.RandomAccessFile(f, "rw");
        }
        void write(byte[] b, int off, int len) throws IOException { raf.write(b, off, len); }
        void writeIntLE(int v) throws IOException {
            raf.write(v & 0xff); raf.write((v >> 8) & 0xff);
            raf.write((v >> 16) & 0xff); raf.write((v >> 24) & 0xff);
        }
        void writeShortLE(int v) throws IOException {
            raf.write(v & 0xff); raf.write((v >> 8) & 0xff);
        }
        void close() throws IOException { raf.close(); }
    }
}
