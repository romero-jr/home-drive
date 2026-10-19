package com.homedrive.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

@UnstableApi
public class PlayerActivity extends Activity {
    static final String EXTRA_PATH = "path";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUBTITLES = "subtitles";

    // Resume points are kept only when they're worth returning to.
    private static final long MIN_RESUME_MS = 30_000;
    private static final long END_MARGIN_MS = 120_000;

    private PlayerView playerView;
    private ExoPlayer player;
    private SharedPreferences positions;
    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player);
        positions = getSharedPreferences("positions", MODE_PRIVATE);
        path = getIntent().getStringExtra(EXTRA_PATH);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Software fallback decoders help with formats the box can't decode in hardware.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new SmbDataSource(this)))
                .build();
        applySavedTrackPreferences();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "Can't play this file: " + error.getErrorCodeName(),
                        Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                saveTrackPreferences(tracks);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    positions.edit().remove(path).apply();
                    finish();
                }
            }
        });

        MediaItem item = new MediaItem.Builder()
                .setUri(SmbDataSource.uriFor(path))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(getIntent().getStringExtra(EXTRA_TITLE)).build())
                .setSubtitleConfigurations(subtitleConfigurations())
                .build();
        long resume = positions.getLong(path, 0);
        player.setMediaItem(item, resume);
        player.prepare();
        player.play();
        if (resume > 0) Toast.makeText(this, "Resuming at " + MainActivity.formatTime(resume), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long duration = player.getDuration();
        if (player.getPlaybackState() != Player.STATE_ENDED) {
            boolean worthSaving = pos > MIN_RESUME_MS && (duration <= 0 || pos < duration - END_MARGIN_MS);
            if (worthSaving) positions.edit().putLong(path, pos).apply();
            else positions.edit().remove(path).apply();
        }
        player.release();
        player = null;
    }

    // Subtitle files found next to the video, e.g. "Movie.en.srt" -> English.
    private List<MediaItem.SubtitleConfiguration> subtitleConfigurations() {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_SUBTITLES);
        if (paths == null) return configs;
        String videoBase = MainActivity.baseName(fileName(path));
        for (String subPath : paths) {
            String name = fileName(subPath);
            String ext = MainActivity.extension(name);
            String mime = ext.equals("vtt") ? MimeTypes.TEXT_VTT
                    : ext.equals("ass") || ext.equals("ssa") ? MimeTypes.TEXT_SSA
                    : MimeTypes.APPLICATION_SUBRIP;
            String base = MainActivity.baseName(name);
            String tag = base.length() > videoBase.length() ? base.substring(videoBase.length() + 1) : "";
            String language = languageCode(tag);
            String label = language != null
                    ? new Locale(language).getDisplayLanguage()
                    : (tag.isEmpty() ? "Subtitles" : tag);
            configs.add(new MediaItem.SubtitleConfiguration.Builder(SmbDataSource.uriFor(subPath))
                    .setMimeType(mime)
                    .setLanguage(language)
                    .setLabel(label)
                    .build());
        }
        return configs;
    }

    // Accepts "en", "eng" or a language name like "Spanish"; null if unrecognised.
    private static String languageCode(String tag) {
        if (tag.isEmpty()) return null;
        String t = tag.toLowerCase(Locale.ROOT);
        if (t.length() == 2 || t.length() == 3) {
            String name = new Locale(t).getDisplayLanguage(Locale.ENGLISH);
            if (!name.equalsIgnoreCase(t)) return t;
        }
        for (Locale l : Locale.getAvailableLocales()) {
            if (l.getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(t)) return l.getLanguage();
        }
        return null;
    }

    private static String fileName(String p) {
        return p.substring(p.lastIndexOf('/') + 1);
    }

    // The audio language and captions choice carry over to the next video.
    private void applySavedTrackPreferences() {
        SharedPreferences prefs = getSharedPreferences("tracks", MODE_PRIVATE);
        String text = prefs.getString("text", null); // null = captions off, "und" = unlabeled captions
        TrackSelectionParameters.Builder params = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(prefs.getString("audio", null))
                .setPreferredTextLanguage(text == null || text.equals("und") ? null : text)
                .setSelectUndeterminedTextLanguage(text != null);
        player.setTrackSelectionParameters(params.build());
    }

    private void saveTrackPreferences(Tracks tracks) {
        if (tracks.isEmpty()) return;
        String audio = null;
        String text = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (!group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                String lang = group.getTrackFormat(i).language;
                if (group.getType() == C.TRACK_TYPE_AUDIO && lang != null && !lang.equals("und")) audio = lang;
                if (group.getType() == C.TRACK_TYPE_TEXT) text = lang != null ? lang : "und";
            }
        }
        SharedPreferences.Editor edit = getSharedPreferences("tracks", MODE_PRIVATE).edit();
        if (audio != null) edit.putString("audio", audio);
        if (text != null) edit.putString("text", text);
        else edit.remove("text");
        edit.apply();
    }

    // Let the player's on-screen controls handle the D-pad and media keys.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
}
