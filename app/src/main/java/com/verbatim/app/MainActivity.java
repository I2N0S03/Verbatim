package com.verbatim.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One screen: record speech, send it to Whisper large-v3 (hosted free by Groq),
 * show the text, save it as a .txt file through the Android file picker
 * (which includes Google Drive).
 */
public class MainActivity extends Activity {

    private static final String API_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL = "whisper-large-v3";
    // Groq's free plan accepts files up to 25 MB. At 32 kbit/s that is about 100 minutes.
    private static final long MAX_BYTES = 24L * 1024 * 1024;

    private static final int REQ_MIC = 1;
    private static final int REQ_SAVE = 2;

    private static final String[] LANG_LABELS = {"Auto", "English", "Türkçe", "Français"};
    private static final String[] LANG_CODES = {"", "en", "tr", "fr"};
    private static final int LANG_ID_BASE = 100;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RadioGroup langGroup;
    private Button recordButton;
    private Button retryButton;
    private TextView status;
    private EditText text;

    private MediaRecorder recorder;
    private File audioFile;
    private long startedAt;
    private boolean recording;
    private boolean busy;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            status.setText(String.format(Locale.US, "Recording  %d:%02d", s / 60, s % 60));
            ui.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("verbatim", MODE_PRIVATE);
        audioFile = new File(getCacheDir(), "recording.m4a");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Verbatim");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(31, 58, 95));
        root.addView(title, matchWrap());

        langGroup = new RadioGroup(this);
        langGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < LANG_LABELS.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(LANG_ID_BASE + i);
            b.setText(LANG_LABELS[i]);
            langGroup.addView(b);
        }
        langGroup.check(LANG_ID_BASE + prefs.getInt("lang", 0));
        langGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                prefs.edit().putInt("lang", checkedId - LANG_ID_BASE).apply();
            }
        });
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        root.addView(langGroup, lp);

        recordButton = new Button(this);
        recordButton.setTextSize(18);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) stopRecording();
                else startRecording();
            }
        });
        lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(8);
        root.addView(recordButton, lp);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status, matchWrap());

        retryButton = new Button(this);
        retryButton.setText("Try the transcription again");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transcribe();
            }
        });
        root.addView(retryButton, matchWrap());

        text = new EditText(this);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setHint("Your transcription appears here. You can edit it before saving.");
        text.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setTextSize(16);
        text.setText(prefs.getString("draft", ""));
        root.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }), weighted());
        row.addView(smallButton("Copy", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }), weighted());
        row.addView(smallButton("Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        }), weighted());
        row.addView(smallButton("Key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askForKey();
            }
        }), weighted());
        root.addView(row, matchWrap());

        setContentView(root);
        refresh("Tap Record and speak.");
        if (apiKey().isEmpty()) askForKey();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep whatever is on screen, so nothing is lost if the app is closed.
        prefs.edit().putString("draft", text.getText().toString()).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseRecorder();
    }

    private void refresh(String message) {
        recordButton.setEnabled(!busy);
        recordButton.setText(recording ? "■  Stop" : "●  Record");
        recordButton.setTextColor(recording ? Color.rgb(198, 40, 40) : Color.rgb(31, 58, 95));
        if (message != null) status.setText(message);
    }

    // ------------------------------------------------------------- recording

    private void startRecording() {
        if (apiKey().isEmpty()) {
            askForKey();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setMaxFileSize(MAX_BYTES);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        stopRecording();
                    }
                }
            });
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            releaseRecorder();
            refresh("The microphone could not start: " + e.getMessage());
            return;
        }
        recording = true;
        startedAt = SystemClock.elapsedRealtime();
        retryButton.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh(null);
        ui.post(tick);
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false; // happens when the recording is shorter than a blink
        }
        releaseRecorder();
        if (!ok || audioFile.length() == 0) {
            refresh("That recording was too short. Try again.");
            return;
        }
        transcribe();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            refresh("Verbatim needs the microphone permission to hear you.");
        }
    }

    // --------------------------------------------------------- transcription

    private void transcribe() {
        if (busy) return;
        busy = true;
        retryButton.setVisibility(View.GONE);
        refresh("Transcribing…");
        final String key = apiKey();
        final String lang = LANG_CODES[Math.max(0, langGroup.getCheckedRadioButtonId() - LANG_ID_BASE)];
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = null;
                String error = null;
                try {
                    result = upload(key, lang);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String r = result;
                final String err = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (r != null) {
                            String old = text.getText().toString().trim();
                            text.setText(old.isEmpty() ? r : old + "\n\n" + r);
                            text.setSelection(text.getText().length());
                            refresh("Done. Record again to add more, or Save.");
                        } else {
                            retryButton.setVisibility(View.VISIBLE);
                            refresh("Not transcribed: " + err + "\nYour recording is kept.");
                        }
                    }
                });
            }
        }).start();
    }

    /** Sends the recording as a web form with a file attached, and returns the text. */
    private String upload(String key, String lang) throws Exception {
        String boundary = "----verbatim" + System.currentTimeMillis();
        StringBuilder head = new StringBuilder();
        addField(head, boundary, "model", MODEL);
        addField(head, boundary, "response_format", "json");
        addField(head, boundary, "temperature", "0");
        if (!lang.isEmpty()) addField(head, boundary, "language", lang);
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\n")
                .append("Content-Type: audio/mp4\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(headBytes.length + audioFile.length() + tailBytes.length);

            try (OutputStream out = c.getOutputStream();
                 InputStream in = new FileInputStream(audioFile)) {
                out.write(headBytes);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.write(tailBytes);
            }

            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code == 401) throw new Exception("the API key was refused. Tap Key and paste it again.");
            if (code == 429) throw new Exception("the free limit is reached for now. Wait a little and retry.");
            if (code >= 400) {
                String msg = body;
                try {
                    msg = new JSONObject(body).getJSONObject("error").getString("message");
                } catch (Exception ignored) {
                }
                throw new Exception("server answered " + code + ": " + msg);
            }
            return new JSONObject(body).getString("text").trim();
        } finally {
            c.disconnect();
        }
    }

    private static void addField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- saving

    /** Opens Android's own "save file" screen. Google Drive is one of the places it offers. */
    private void save() {
        if (text.getText().toString().trim().isEmpty()) {
            toast("There is nothing to save yet.");
            return;
        }
        String name = "Transcript "
                + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new Date()) + ".txt";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        final byte[] bytes = text.getText().toString().getBytes(StandardCharsets.UTF_8);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "Saved.";
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("the file could not be opened");
                    out.write(bytes);
                } catch (Exception e) {
                    message = "Not saved: " + e.getMessage();
                }
                final String m = message;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                    }
                });
            }
        }).start();
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Transcript", text.getText().toString()));
        toast("Copied.");
    }

    private void confirmClear() {
        if (text.getText().length() == 0) return;
        new AlertDialog.Builder(this)
                .setMessage("Erase the text on screen?")
                .setPositiveButton("Erase", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        text.setText("");
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // ------------------------------------------------------------------- key

    private String apiKey() {
        return prefs.getString("key", "").trim();
    }

    private void askForKey() {
        final EditText input = new EditText(this);
        input.setHint("gsk_…");
        input.setSingleLine(true);
        input.setText(apiKey());
        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("Groq API key")
                .setMessage("Free, no bank card: sign in at console.groq.com/keys, "
                        + "create a key, and paste it here. It is stored only on this phone.")
                .setView(box)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        prefs.edit().putString("key", input.getText().toString().trim()).apply();
                    }
                })
                .setNeutralButton("Open the site", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://console.groq.com/keys")));
                        } catch (Exception e) {
                            toast("No browser found.");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------------------------------------------- helpers

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
