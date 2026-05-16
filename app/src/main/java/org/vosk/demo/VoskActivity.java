// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
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
import android.widget.ToggleButton;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;
    static private final int STATE_DOWNLOADING = 5;

    static private final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;
    private Spinner modelSelector;
    private Button downloadBtn;

    /** Current selected model name */
    private String currentModelId = "vosk-model-small-cn-0.22";

    /** Available models for download */
    static class ModelInfo {
        String id;
        String label;
        String url;
        ModelInfo(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }
    }

    private static final ModelInfo[] AVAILABLE_MODELS = new ModelInfo[]{
            new ModelInfo("vosk-model-small-cn-0.22",     "中文（小，66MB）",  "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
            new ModelInfo("vosk-model-small-en-us-0.15",   "英文（小，42MB）",  "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            new ModelInfo("vosk-model-cn-0.22",            "中文（大，2GB）",   "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"),
            new ModelInfo("vosk-model-en-us-0.22",         "英文（大，1.8GB）", "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"),
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        // Download button
        downloadBtn = findViewById(R.id.download_models);
        downloadBtn.setOnClickListener(view -> showModelChooser());

        // Model selector: only list installed models
        modelSelector = findViewById(R.id.model_selector);
        refreshModelList();
        modelSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                // selected format: "model-id|Label"
                String[] parts = selected.split("\\|", 2);
                String modelId = parts[0];
                if (!modelId.equals(currentModelId)) {
                    currentModelId = modelId;
                    if (speechService != null) {
                        speechService.stop();
                        speechService.shutdown();
                        speechService = null;
                    }
                    if (speechStreamService != null) {
                        speechStreamService.stop();
                        speechStreamService = null;
                    }
                    if (model != null) {
                        model = null;
                    }
                    setUiState(STATE_START);
                    initModel();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LibVosk.setLogLevel(LogLevel.INFO);

        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    /** Get the directory where downloaded models are stored */
    private File getModelsDir() {
        return new File(getFilesDir(), "vosk_models");
    }

    /** Check if a model is already downloaded and extracted */
    private boolean isModelInstalled(String modelId) {
        File modelDir = new File(getModelsDir(), modelId);
        return modelDir.exists() && new File(modelDir, "am/final.mdl").exists();
    }

    /** Refresh the spinner with currently installed models */
    private void refreshModelList() {
        File modelsDir = getModelsDir();
        String[] installed = new String[0];
        if (modelsDir.exists()) {
            installed = modelsDir.list();
        }
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        for (ModelInfo mi : AVAILABLE_MODELS) {
            if (isModelInstalled(mi.id)) {
                items.add(mi.id + "|" + mi.label);
            }
        }
        if (items.isEmpty()) {
            items.add("无可用模型|请点击下方「📥 下载模型」");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSelector.setAdapter(adapter);
    }

    /** Show model download chooser dialog */
    private void showModelChooser() {
        String[] names = new String[AVAILABLE_MODELS.length];
        boolean[] installed = new boolean[AVAILABLE_MODELS.length];
        for (int i = 0; i < AVAILABLE_MODELS.length; i++) {
            boolean isInst = isModelInstalled(AVAILABLE_MODELS[i].id);
            names[i] = AVAILABLE_MODELS[i].label + (isInst ? " ✓ 已安装" : "");
            installed[i] = isInst;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要下载的模型")
                .setItems(names, (dialog, which) -> {
                    if (!installed[which]) {
                        downloadModel(AVAILABLE_MODELS[which]);
                    } else {
                        resultView.setText("该模型已安装，请在顶部下拉框切换使用");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Download and extract a model */
    private void downloadModel(ModelInfo modelInfo) {
        setUiState(STATE_DOWNLOADING);
        resultView.setText("正在下载 " + modelInfo.label + " ...");

        new AsyncTask<Void, Integer, String>() {
            ProgressDialog progressDialog;

            @Override
            protected void onPreExecute() {
                progressDialog = new ProgressDialog(VoskActivity.this);
                progressDialog.setTitle("下载模型");
                progressDialog.setMessage("正在下载 " + modelInfo.label);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progressDialog.setMax(100);
                progressDialog.setCancelable(false);
                progressDialog.show();
            }

            @Override
            protected String doInBackground(Void... voids) {
                File modelsDir = getModelsDir();
                if (!modelsDir.exists()) modelsDir.mkdirs();

                File zipFile = new File(modelsDir, modelInfo.id + ".zip");
                File modelDir = new File(modelsDir, modelInfo.id);

                try {
                    // Download
                    URL url = new URL(modelInfo.url);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.connect();

                    int fileLength = conn.getContentLength();
                    InputStream input = new BufferedInputStream(conn.getInputStream());
                    OutputStream output = new FileOutputStream(zipFile);

                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        output.write(buffer, 0, count);
                        if (fileLength > 0) {
                            int percent = (int) (total * 100 / fileLength);
                            publishProgress(percent);
                        }
                    }
                    output.close();
                    input.close();

                    // Extract
                    publishProgress(-1); // signal: extracting
                    extractZip(zipFile.getAbsolutePath(), modelDir.getAbsolutePath());
                    zipFile.delete();

                    // Create uuid
                    java.util.UUID uuid = java.util.UUID.randomUUID();
                    File uuidFile = new File(modelDir, "uuid");
                    java.io.FileWriter fw = new java.io.FileWriter(uuidFile);
                    fw.write(uuid.toString());
                    fw.close();

                    return null; // success

                } catch (Exception e) {
                    if (zipFile.exists()) zipFile.delete();
                    if (modelDir.exists()) deleteDir(modelDir);
                    return "下载失败：" + e.getMessage();
                }
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                int v = values[0];
                if (v == -1) {
                    progressDialog.setMessage("正在解压...");
                    progressDialog.setIndeterminate(true);
                } else {
                    progressDialog.setProgress(v);
                }
            }

            @Override
            protected void onPostExecute(String error) {
                progressDialog.dismiss();
                if (error == null) {
                    resultView.setText(modelInfo.label + " 下载完成 ✓\n请在顶部下拉框切换使用");
                    refreshModelList();
                    // Auto-select the newly downloaded model
                    currentModelId = modelInfo.id;
                    if (model != null) {
                        model = null;
                    }
                    initModel();
                } else {
                    setErrorState(error);
                }
            }
        }.execute();
    }

    /** Extract a zip file to destination directory */
    private void extractZip(String zipPath, String destDir) throws IOException {
        File dest = new File(destDir);
        if (!dest.exists()) dest.mkdirs();

        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipPath)));
        ZipEntry entry;
        byte[] buffer = new byte[8192];

        while ((entry = zis.getNextEntry()) != null) {
            // Some zips have a top-level dir, skip it
            String entryName = entry.getName();
            // Remove top-level directory if zip contains one (e.g., "vosk-model-xxx/am/...")
            int slashIdx = entryName.indexOf('/');
            if (slashIdx > 0) {
                entryName = entryName.substring(slashIdx + 1);
            }
            if (entryName.isEmpty()) continue;

            File outFile = new File(dest, entryName);

            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    /** Recursively delete a directory */
    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private void initModel() {
        File modelDir = new File(getModelsDir(), currentModelId);
        if (!modelDir.exists() || !new File(modelDir, "am/final.mdl").exists()) {
            resultView.setText("模型未安装，请点击「📥 下载模型」按钮下载");
            findViewById(R.id.recognize_file).setEnabled(false);
            findViewById(R.id.recognize_mic).setEnabled(false);
            return;
        }
        try {
            model = new Model(modelDir.getAbsolutePath());
            setUiState(STATE_READY);
        } catch (Exception e) {
            setErrorState("加载模型失败：" + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }
        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            case STATE_DOWNLOADING:
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"one zero zero zero one\", " +
                        "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]");

                InputStream ais = getAssets().open(
                        "10001-90210-01803.wav");
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 16000);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

}
