package co.frontyard.cordova.plugin.exoplayer;

import android.app.AlarmManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Owns radio playback and its sleep deadline after the Cordova activity leaves the screen. */
public final class PlaybackService extends MediaSessionService {
    static final String LOCAL_BIND_ACTION = "co.frontyard.cordova.plugin.exoplayer.BIND_PLAYER";
    private static final long MAX_DELAY_MS = 24L * 60 * 60 * 1000;
    private static final long MAX_FADE_MS = 60L * 1000;

    final class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final LocalBinder localBinder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaSession session;
    private AlarmManager alarms;
    private PowerManager.WakeLock fadeWakeLock;
    private boolean sleepActive;
    private boolean fading;
    private long fadeDurationMs;
    private long stopAtElapsedMs;
    private float gainBeforeFade = 1f;

    private final AlarmManager.OnAlarmListener fadeStartAlarm = this::startFade;
    private final AlarmManager.OnAlarmListener stopAlarm = this::finishSleep;
    private final Runnable fadeStep = new Runnable() {
        @Override
        public void run() {
            if (!sleepActive || !fading || player == null) {
                return;
            }
            long timeLeft = stopAtElapsedMs - SystemClock.elapsedRealtime();
            if (timeLeft <= 0) {
                finishSleep();
                return;
            }
            player.setVolume(gainBeforeFade * Math.min(1f, timeLeft / (float) fadeDurationMs));
            handler.postDelayed(this, 100);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        // The player keeps the CPU and network active while the radio plays.
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    cancelSleepTimer();
                }
            }
        });
        session = new MediaSession.Builder(this, player).build();
        // Local Binder clients do not enter through MediaSessionService.onBind.
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

    // delayMs is the time until the fade starts, not the time until audio stops.
    boolean setSleepTimer(long delayMs, long fadeMs) {
        if (player == null || !player.getPlayWhenReady() || delayMs < 0 || delayMs > MAX_DELAY_MS
                || fadeMs < 0 || fadeMs > MAX_FADE_MS) {
            return false;
        }
        cancelSleepTimer();
        sleepActive = true;
        gainBeforeFade = player.getVolume();
        fadeDurationMs = fadeMs;
        long fadeAt = SystemClock.elapsedRealtime() + delayMs;
        stopAtElapsedMs = fadeAt + fadeMs;
        try {
            // In-process alarm listeners do not need SCHEDULE_EXACT_ALARM.
            // Playback keeps this service alive. Process death also stops audio.
            if (fadeMs > 0) {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, fadeAt,
                        "Radio sleep fade", fadeStartAlarm, handler);
            }
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, stopAtElapsedMs,
                    "Radio sleep stop", stopAlarm, handler);
            return true;
        } catch (RuntimeException e) {
            cancelSleepTimer();
            return false;
        }
    }

    void cancelSleepTimer() {
        if (!sleepActive) {
            return;
        }
        sleepActive = false;
        alarms.cancel(fadeStartAlarm);
        alarms.cancel(stopAlarm);
        handler.removeCallbacks(fadeStep);
        if (fading && player != null) {
            player.setVolume(gainBeforeFade);
        }
        fading = false;
        releaseFadeWakeLock();
    }

    private void startFade() {
        if (!sleepActive || player == null) {
            return;
        }
        if (SystemClock.elapsedRealtime() >= stopAtElapsedMs) {
            finishSleep();
            return;
        }
        gainBeforeFade = player.getVolume();
        fading = true;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        fadeWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":sleepFade");
        fadeWakeLock.acquire(fadeDurationMs + 5000);
        fadeStep.run();
    }

    private void finishSleep() {
        if (!sleepActive || player == null) {
            return;
        }
        // Cancel before pausing: the player event can cause JavaScript to cancel the timer.
        sleepActive = false;
        alarms.cancel(fadeStartAlarm);
        alarms.cancel(stopAlarm);
        handler.removeCallbacks(fadeStep);
        fading = false;
        releaseFadeWakeLock();
        player.pause();
        player.stop();
        player.clearMediaItems();
        player.setVolume(gainBeforeFade);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseFadeWakeLock() {
        if (fadeWakeLock != null) {
            if (fadeWakeLock.isHeld()) {
                fadeWakeLock.release();
            }
            fadeWakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        cancelSleepTimer();
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
