package co.frontyard.cordova.plugin.exoplayer;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Owns radio playback after the Cordova activity leaves the screen. */
public final class PlaybackService extends MediaSessionService {
    static final String LOCAL_BIND_ACTION = "co.frontyard.cordova.plugin.exoplayer.BIND_PLAYER";

    final class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final LocalBinder localBinder = new LocalBinder();
    private ExoPlayer player;
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        session = new MediaSession.Builder(this, player).build();
        // Local Binder clients do not enter through MediaSessionService.onBind.
        // Register the session so the service owns its playback notification.
        addSession(session);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (LOCAL_BIND_ACTION.equals(intent.getAction())) {
            return localBinder;
        }
        return super.onBind(intent);
    }

    @Override
    @Nullable
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    ExoPlayer getPlayer() {
        return player;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
