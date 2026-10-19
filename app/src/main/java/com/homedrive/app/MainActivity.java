package com.homedrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String[] VIDEO = {"mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv", "3gp"};
    private static final String[] AUDIO = {"mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma"};
    static final String[] SUBTITLES = {"srt", "vtt", "ass", "ssa"};

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Smb.Entry> entries = new ArrayList<>();
    // Subtitle files in the current folder: hidden from the list, offered with matching videos.
    private final List<Smb.Entry> subtitles = new ArrayList<>();

    private SharedPreferences prefs;
    private SharedPreferences positions;
    private ListView list;
    private EntryAdapter adapter;
    private TextView pathView;
    private View status;
    private View spinner;
    private TextView statusText;
    private String path = "";
    // Row to reselect after going back up, so the remote lands where it left off.
    private String reselectName;
    private boolean connectionFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Smb.prefs(this);
        positions = getSharedPreferences("positions", MODE_PRIVATE);

        pathView = findViewById(R.id.path);
        status = findViewById(R.id.status);
        spinner = findViewById(R.id.spinner);
        statusText = findViewById(R.id.status_text);
        findViewById(R.id.settings).setOnClickListener(v -> showConnectionDialog());

        list = findViewById(R.id.list);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> open(entries.get(pos)));

        // First launch: pre-fill from build defaults (defaults.properties), then ask for the rest.
        if (!prefs.contains("server")) {
            prefs.edit()
                    .putString("server", BuildConfig.DEFAULT_SERVER)
                    .putString("share", BuildConfig.DEFAULT_SHARE)
                    .putString("username", BuildConfig.DEFAULT_USERNAME)
                    .apply();
            showConnectionDialog();
        } else {
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.notifyDataSetChanged(); // refresh "Resume at" labels after watching
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (path.isEmpty()) {
            super.onBackPressed();
            return;
        }
        int slash = path.lastIndexOf('/');
        reselectName = path.substring(slash + 1);
        path = slash < 0 ? "" : path.substring(0, slash);
        load();
    }

    private void load() {
        String target = path;
        pathView.setText(target.isEmpty() ? prefs.getString("share", "Drive") : target.replace("/", "  ›  "));
        showStatus(true, "Loading…");
        bg.execute(() -> {
            try {
                List<Smb.Entry> result = Smb.list(this, target);
                Collator collator = Collator.getInstance();
                // Folders first, then files, each alphabetically
                Collections.sort(result, (a, b) -> a.directory != b.directory
                        ? (a.directory ? -1 : 1)
                        : collator.compare(a.name, b.name));
                ui.post(() -> show(target, result));
            } catch (Exception e) {
                Smb.disconnect();
                ui.post(() -> showError(e));
            }
        });
    }

    private void show(String target, List<Smb.Entry> result) {
        if (!target.equals(path)) return;
        connectionFailed = false;
        entries.clear();
        subtitles.clear();
        for (Smb.Entry e : result) {
            if (!e.directory && contains(SUBTITLES, extension(e.name))) subtitles.add(e);
            else entries.add(e);
        }
        adapter.notifyDataSetChanged();
        if (entries.isEmpty()) {
            showStatus(false, "This folder is empty");
            return;
        }
        status.setVisibility(View.GONE);
        int select = 0;
        for (int i = 0; reselectName != null && i < entries.size(); i++) {
            if (entries.get(i).name.equals(reselectName)) select = i;
        }
        reselectName = null;
        list.requestFocus();
        list.setSelection(select);
    }

    private void showError(Exception e) {
        connectionFailed = true;
        entries.clear();
        adapter.notifyDataSetChanged();
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (reason.contains("STATUS_LOGON_FAILURE")) reason = "Wrong username or password";
        showStatus(false, "Couldn't open the drive\n" + reason + "\n\nSelect CONNECTION to check the settings");
        findViewById(R.id.settings).requestFocus();
    }

    private void showStatus(boolean loading, String text) {
        status.setVisibility(View.VISIBLE);
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusText.setText(text);
    }

    private void open(Smb.Entry entry) {
        if (entry.directory) {
            path = entry.path;
            load();
            return;
        }
        String ext = extension(entry.name);
        if (!contains(VIDEO, ext) && !contains(AUDIO, ext)) {
            Toast.makeText(this, "Can't play ." + ext + " files", Toast.LENGTH_SHORT).show();
            return;
        }
        // "Movie.srt", "Movie.en.srt", "Movie.Spanish.srt" all belong to "Movie.mp4"
        String base = baseName(entry.name).toLowerCase(Locale.ROOT);
        ArrayList<String> matches = new ArrayList<>();
        for (Smb.Entry sub : subtitles) {
            String subBase = baseName(sub.name).toLowerCase(Locale.ROOT);
            if (subBase.equals(base) || subBase.startsWith(base + ".")) matches.add(sub.path);
        }
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_PATH, entry.path)
                .putExtra(PlayerActivity.EXTRA_TITLE, entry.name)
                .putStringArrayListExtra(PlayerActivity.EXTRA_SUBTITLES, matches));
    }

    private void showConnectionDialog() {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_connection, null);
        EditText server = form.findViewById(R.id.server);
        EditText share = form.findViewById(R.id.share);
        EditText username = form.findViewById(R.id.username);
        EditText password = form.findViewById(R.id.password);
        server.setText(prefs.getString("server", ""));
        share.setText(prefs.getString("share", ""));
        username.setText(prefs.getString("username", ""));
        password.setText(prefs.getString("password", ""));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Drive connection")
                .setView(form)
                .setPositiveButton("Connect", (d, w) -> {
                    prefs.edit()
                            .putString("server", server.getText().toString().trim())
                            .putString("share", share.getText().toString().trim())
                            .putString("username", username.getText().toString().trim())
                            .putString("password", password.getText().toString())
                            .apply();
                    Smb.disconnect();
                    path = "";
                    load();
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    if (entries.isEmpty() && !connectionFailed) load();
                })
                .show();
        // Enter on the password field connects, instead of leaving the keyboard up.
        password.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_GO && !enter) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        // The dialog focuses its first field on show; jump to the first empty one instead.
        for (EditText field : new EditText[]{server, share, username, password}) {
            if (field.length() == 0) {
                field.post(field::requestFocus);
                break;
            }
        }
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean contains(String[] list, String value) {
        for (String s : list) if (s.equals(value)) return true;
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int pos) {
            return entries.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(MainActivity.this).inflate(R.layout.row, parent, false);
            Smb.Entry e = entries.get(pos);
            String ext = extension(e.name);
            String icon = e.directory ? "📁" : contains(VIDEO, ext) ? "🎬" : contains(AUDIO, ext) ? "🎵" : "📄";
            String detail;
            long resume = positions.getLong(e.path, 0);
            if (e.directory) detail = "";
            else if (resume > 0) detail = "Resume at " + formatTime(resume);
            else detail = formatSize(e.size);
            ((TextView) view.findViewById(R.id.icon)).setText(icon);
            ((TextView) view.findViewById(R.id.name)).setText(e.name);
            ((TextView) view.findViewById(R.id.detail)).setText(detail);
            return view;
        }
    }
}
