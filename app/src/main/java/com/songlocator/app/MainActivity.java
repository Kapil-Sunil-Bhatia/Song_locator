package com.songlocator.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 7;
    private static final String INTRO =
            "Song Locator needs permission to see the music on your phone. "
                    + "It only reads song names and folders. Nothing is uploaded.";

    private final ArrayList<Song> all = new ArrayList<>();
    private final HashMap<String, Integer> dupCount = new HashMap<>();
    private SongAdapter adapter;

    private EditText search;
    private Button folderBtn;
    private Button refreshBtn;
    private Button grantBtn;
    private CheckBox dupCheck;
    private TextView status;
    private TextView emptyView;
    private TextView grantText;
    private View grantBox;
    private ListView list;

    private String folderFilter = null;
    private boolean dupOnly = false;
    private boolean openSettingsNext = false;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        search = findViewById(R.id.search);
        folderBtn = findViewById(R.id.folderBtn);
        refreshBtn = findViewById(R.id.refreshBtn);
        grantBtn = findViewById(R.id.grantBtn);
        dupCheck = findViewById(R.id.dupCheck);
        status = findViewById(R.id.status);
        emptyView = findViewById(R.id.emptyView);
        grantText = findViewById(R.id.grantText);
        grantBox = findViewById(R.id.grantBox);
        list = findViewById(R.id.list);

        adapter = new SongAdapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });

        folderBtn.setOnClickListener(v -> showFolderDialog());
        dupCheck.setOnCheckedChangeListener((v, checked) -> {
            dupOnly = checked;
            applyFilter();
        });
        refreshBtn.setOnClickListener(v -> {
            if (hasPerm()) {
                loadSongs();
            } else {
                showGrant(INTRO, "Allow access to music");
            }
        });
        grantBtn.setOnClickListener(v -> {
            if (openSettingsNext) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                requestPermissions(new String[]{perm()}, REQ_AUDIO);
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> play(adapter.items.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            copyPath(adapter.items.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loading) return;
        if (hasPerm()) {
            if (all.isEmpty()) loadSongs();
        } else if (grantBox.getVisibility() != View.VISIBLE) {
            showGrant(INTRO, "Allow access to music");
        }
    }

    // ----- permission -----

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPerm() {
        return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    private void showGrant(String message, String buttonText) {
        grantText.setText(message);
        grantBtn.setText(buttonText);
        grantBox.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        status.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSongs();
        } else {
            openSettingsNext = true;
            showGrant("Without access to your music, the app cannot list your songs. "
                    + "Open the app settings, tap Permissions, then allow Music and audio.",
                    "Open app settings");
        }
    }

    // ----- loading -----

    private void loadSongs() {
        loading = true;
        openSettingsNext = false;
        grantBox.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        status.setText("Reading your music library…");
        new Thread(() -> {
            final ArrayList<Song> out = new ArrayList<>();
            String err = null;
            try {
                query(out);
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fErr = err;
            runOnUiThread(() -> {
                loading = false;
                if (isDestroyed()) return;
                all.clear();
                all.addAll(out);
                prepare();
                applyFilter();
                if (fErr != null) status.setText("Could not read your music library: " + fErr);
            });
        }).start();
    }

    private void query(ArrayList<Song> out) {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        ArrayList<String> cols = new ArrayList<>(Arrays.asList(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA));
        boolean hasRel = Build.VERSION.SDK_INT >= 29;
        if (hasRel) cols.add("relative_path");

        try (Cursor c = getContentResolver().query(uri, cols.toArray(new String[0]), null, null, null)) {
            if (c == null) return;
            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int iRel = hasRel ? c.getColumnIndex("relative_path") : -1;

            while (c.moveToNext()) {
                String name = nz(c.getString(iName));
                String data = c.getString(iData);
                String parent;
                String full;
                if (data != null && data.length() > 0) {
                    full = data;
                    int slash = data.lastIndexOf('/');
                    parent = slash > 0 ? data.substring(0, slash) : "";
                    if (name.isEmpty()) name = slash >= 0 ? data.substring(slash + 1) : data;
                } else {
                    String rel = iRel >= 0 ? nz(c.getString(iRel)) : "";
                    parent = "/storage/emulated/0/" + trimSlashes(rel);
                    full = parent + "/" + name;
                }

                String low = parent.toLowerCase(Locale.ROOT);
                if (low.contains("/ringtones") || low.contains("/notifications") || low.contains("/alarms")) {
                    continue;
                }

                String base = name;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                String clean = base.replaceFirst("^\\s*\\d{1,3}\\s*[-._)]\\s*", "").replace('_', ' ').trim();
                if (clean.isEmpty()) clean = base;

                String title = nz(c.getString(iTitle)).trim();
                if (title.isEmpty()) title = clean;
                String artist = nz(c.getString(iArtist)).trim();
                if ("<unknown>".equals(artist)) artist = "";
                String album = nz(c.getString(iAlbum)).trim();

                Song s = new Song();
                s.id = c.getLong(iId);
                s.title = title;
                s.artist = artist;
                s.album = album;
                s.fileName = name;
                s.fullPath = full;
                s.folder = prettyFolder(parent);
                s.pathShown = s.folder + "/" + name;
                s.size = c.getLong(iSize);
                s.durationMs = c.getLong(iDur);
                s.key = fold(title).replaceAll("[^\\p{L}\\p{N}]+", "");
                s.hay = fold(title + " " + artist + " " + album + " " + s.pathShown);
                out.add(s);
            }
        }
    }

    private void prepare() {
        Collections.sort(all, (a, b) -> a.title.compareToIgnoreCase(b.title));
        HashMap<String, Integer> groups = new HashMap<>();
        for (Song s : all) {
            if (s.key.length() > 1) {
                Integer n = groups.get(s.key);
                groups.put(s.key, n == null ? 1 : n + 1);
            }
        }
        dupCount.clear();
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (e.getValue() > 1) dupCount.put(e.getKey(), e.getValue());
        }
        if (folderFilter != null) {
            boolean found = false;
            for (Song s : all) {
                if (s.folder.equals(folderFilter)) { found = true; break; }
            }
            if (!found) folderFilter = null;
        }
    }

    // ----- filtering -----

    private void applyFilter() {
        String raw = search.getText().toString().trim();
        ArrayList<String> ft = new ArrayList<>();
        for (String t : fold(raw).split("\\s+")) {
            if (!t.isEmpty()) ft.add(t);
        }
        ArrayList<String> hl = new ArrayList<>();
        for (String t : raw.split("\\s+")) {
            if (!t.isEmpty()) hl.add(t);
        }

        ArrayList<Song> res = new ArrayList<>();
        for (Song s : all) {
            if (folderFilter != null && !s.folder.equals(folderFilter)) continue;
            if (dupOnly && !dupCount.containsKey(s.key)) continue;
            boolean ok = true;
            for (String t : ft) {
                if (!s.hay.contains(t)) { ok = false; break; }
            }
            if (ok) res.add(s);
        }
        if (dupOnly) {
            Collections.sort(res, (a, b) -> {
                int k = a.key.compareTo(b.key);
                return k != 0 ? k : a.folder.compareToIgnoreCase(b.folder);
            });
        }

        adapter.items = res;
        adapter.hlTokens = hl.toArray(new String[0]);
        adapter.notifyDataSetChanged();
        list.setSelection(0);

        folderBtn.setText(folderFilter == null ? "All folders" : shortFolder(folderFilter));

        boolean filtered = folderFilter != null || dupOnly || !ft.isEmpty();
        String t = num(res.size()) + (res.size() == 1 ? " song" : " songs");
        if (filtered) {
            t += " of " + num(all.size());
        } else {
            int dupSongs = 0;
            for (Song s : all) if (dupCount.containsKey(s.key)) dupSongs++;
            if (dupSongs > 0) t += " · " + num(dupSongs) + " share a name with another file";
        }
        if (!loading && grantBox.getVisibility() != View.VISIBLE) status.setText(t);

        if (res.isEmpty() && !loading && grantBox.getVisibility() != View.VISIBLE) {
            emptyView.setText(all.isEmpty()
                    ? "No songs found. Songs inside folders that contain a .nomedia file are not listed."
                    : "No song matches. Try fewer words or part of the file name.");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void showFolderDialog() {
        TreeMap<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Song s : all) {
            Integer n = counts.get(s.folder);
            counts.put(s.folder, n == null ? 1 : n + 1);
        }
        if (counts.isEmpty()) return;
        final ArrayList<String> keys = new ArrayList<>(counts.keySet());
        String[] labels = new String[keys.size() + 1];
        labels[0] = "All folders (" + num(all.size()) + ")";
        for (int i = 0; i < keys.size(); i++) {
            labels[i + 1] = keys.get(i) + " (" + counts.get(keys.get(i)) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Show one folder")
                .setItems(labels, (d, which) -> {
                    folderFilter = which == 0 ? null : keys.get(which - 1);
                    applyFilter();
                })
                .show();
    }

    // ----- row actions -----

    private void play(Song s) {
        Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No music player found to open this song.", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyPath(Song s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Song path", s.fullPath));
            Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ----- helpers -----

    private static String nz(String s) { return s == null ? "" : s; }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String num(int n) { return String.format(Locale.getDefault(), "%,d", n); }

    private static String trimSlashes(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '/') a++;
        while (b > a && s.charAt(b - 1) == '/') b--;
        return s.substring(a, b);
    }

    private static String prettyFolder(String p) {
        String emu = "/storage/emulated/";
        if (p.startsWith(emu)) {
            int i = p.indexOf('/', emu.length());
            return "Phone storage" + (i < 0 ? "" : p.substring(i));
        }
        if (p.startsWith("/sdcard")) return "Phone storage" + p.substring(7);
        if (p.startsWith("/storage/")) {
            int i = p.indexOf('/', 9);
            return "SD card" + (i < 0 ? "" : p.substring(i));
        }
        return p;
    }

    private static String shortFolder(String f) {
        String[] parts = f.split("/");
        if (parts.length <= 2) return f;
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private static String sizeText(long b) {
        if (b <= 0) return "";
        if (b >= 1048576L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return Math.max(1L, b / 1024L) + " KB";
    }

    private static String durText(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000L;
        return (s / 60L) + ":" + String.format(Locale.ROOT, "%02d", s % 60L);
    }

    private static CharSequence highlight(String text, String[] toks) {
        if (toks.length == 0) return text;
        String low = text.toLowerCase(Locale.ROOT);
        if (low.length() != text.length()) return text;
        SpannableString sp = new SpannableString(text);
        for (String t : toks) {
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.isEmpty()) continue;
            int from = 0;
            while (true) {
                int i = low.indexOf(tl, from);
                if (i < 0) break;
                sp.setSpan(new BackgroundColorSpan(0x66FFC107), i, i + tl.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = i + tl.length();
            }
        }
        return sp;
    }

    // ----- list adapter -----

    private class SongAdapter extends BaseAdapter {
        ArrayList<Song> items = new ArrayList<>();
        String[] hlTokens = new String[0];

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.row_song, parent, false);
            Song s = items.get(position);

            TextView title = v.findViewById(R.id.rowTitle);
            TextView sub = v.findViewById(R.id.rowSub);
            TextView dup = v.findViewById(R.id.rowDup);
            TextView path = v.findViewById(R.id.rowPath);

            title.setText(highlight(s.title, hlTokens));

            ArrayList<String> bits = new ArrayList<>();
            if (!s.artist.isEmpty()) bits.add(s.artist);
            if (!s.album.isEmpty()) bits.add(s.album);
            String d = durText(s.durationMs);
            if (!d.isEmpty()) bits.add(d);
            String z = sizeText(s.size);
            if (!z.isEmpty()) bits.add(z);
            sub.setText(android.text.TextUtils.join(" · ", bits));

            Integer n = dupCount.get(s.key);
            if (n != null) {
                dup.setText("Same name in " + n + " places");
                dup.setVisibility(View.VISIBLE);
            } else {
                dup.setVisibility(View.GONE);
            }

            path.setText(highlight(s.pathShown, hlTokens));
            return v;
        }
    }
}
