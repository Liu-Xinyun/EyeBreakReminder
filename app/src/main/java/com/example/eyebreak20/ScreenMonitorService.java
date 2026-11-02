package com.example.eyebreak20;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class ScreenMonitorService extends Service {
    public static final String PREFS = "eyebreak_settings";
    public static final String ENABLED = "enabled";
    public static final String AUTO_BOOT = "auto_boot";
    public static final String INTERVAL_MIN = "interval_minutes";
    public static final String MODE = "reminder_mode";
    public static final int MODE_POPUP = 0;
    public static final int MODE_STRICT = 1;
    public static final String SESSION_START = "session_start_elapsed";
    public static final String NEXT_DUE = "next_due_elapsed";
    public static final String PAUSED_AT = "paused_at_elapsed";
    public static final String BREAK_UNTIL = "break_until_elapsed";

    public static final String ACTION_START = "com.example.eyebreak20.START";
    public static final String ACTION_CONFIG = "com.example.eyebreak20.CONFIG";
    public static final String ACTION_TEST = "com.example.eyebreak20.TEST";
    public static final String ACTION_TEST_LOCK = "com.example.eyebreak20.TEST_LOCK";

    public static final long SHORT_LOCK_MS = 20_000L;
    public static final long STRICT_BREAK_MS = 20_000L;
    private static final String TAG = "EyeBreak20";
    private static final String ACTIVE_CHANNEL = "eyebreak_active";
    private static final String ALERT_CHANNEL = "eyebreak_alerts";
    private static final int ACTIVE_NOTIFICATION = 100;
    private static final int REMINDER_NOTIFICATION = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private SharedPreferences prefs;
    private DevicePolicyManager policyManager;
    private ComponentName adminComponent;

    // Timestamps use elapsedRealtime, including device sleep. The entire short screen-off
    // interval is subtracted by shifting start and due forward when the user returns.
    private long startedAt = 0L;
    private long nextDueAt = 0L;
    private long pausedAt = 0L;
    private long breakUntil = 0L;
    private boolean receiverRegistered = false;

    private final Runnable reminderTask = new Runnable() {
        @Override public void run() {
            if (breakUntil > 0 || startedAt == 0 || nextDueAt == 0 || pausedAt > 0) return;
            if (!isDeviceInteractive()) {
                pauseSession();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < nextDueAt) {
                scheduleReminder();
                return;
            }
            if (prefs.getInt(MODE, MODE_POPUP) == MODE_STRICT) {
                startStrictBreak();
            } else {
                showReminder();
                nextDueAt = now + intervalMillis();
                saveTiming();
                scheduleReminder();
            }
        }
    };

    private final Runnable graceExpiryTask = new Runnable() {
        @Override public void run() {
            if (pausedAt == 0 || breakUntil > 0) return;
            long gap = SystemClock.elapsedRealtime() - pausedAt;
            if (gap > SHORT_LOCK_MS) {
                endSession();
            } else {
                scheduleGraceExpiry();
            }
        }
    };

    private final Runnable breakEndTask = new Runnable() {
        @Override public void run() {
            if (breakUntil == 0) return;
            long remaining = breakUntil - SystemClock.elapsedRealtime();
            if (remaining > 0) {
                handler.postDelayed(this, remaining);
                return;
            }
            finishStrictBreak();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (breakUntil == 0) pauseSession();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (breakUntil > 0) {
                    if (TimingRules.isWithinStrictBreak(breakUntil, SystemClock.elapsedRealtime())) {
                        // Best effort: ordinary device admins cannot actually disable unlock.
                        // Re-lock after another SCREEN_ON broadcast within this fixed 20s window.
                        if (!lockDevice()) fallbackFromStrictBreak();
                    } else {
                        finishStrictBreak();
                    }
                } else {
                    resumeOrBeginSession();
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        policyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, EyeBreakDeviceAdminReceiver.class);
        createNotificationChannels();
        Notification notification = buildActiveNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ACTIVE_NOTIFICATION, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ACTIVE_NOTIFICATION, notification);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;

        // Restore only a plausible still-active break on service process restart.
        long savedBreak = prefs.getLong(BREAK_UNTIL, 0);
        long now = SystemClock.elapsedRealtime();
        if (prefs.getInt(MODE, MODE_POPUP) == MODE_STRICT &&
                savedBreak > now && savedBreak - now <= STRICT_BREAK_MS) {
            breakUntil = savedBreak;
            clearUsage();
            handler.postDelayed(breakEndTask, breakUntil - now);
            if (isDeviceInteractive() && !lockDevice()) fallbackFromStrictBreak();
        } else {
            prefs.edit().putLong(BREAK_UNTIL, 0).apply();
            if (isDeviceInteractive()) beginSession();
            else endSession();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        prefs.edit().putBoolean(ENABLED, true).apply();
        if (ACTION_TEST.equals(action)) {
            if (isDeviceInteractive() && breakUntil == 0) showReminder();
        } else if (ACTION_TEST_LOCK.equals(action)) {
            if (isDeviceInteractive() && breakUntil == 0 &&
                    prefs.getInt(MODE, MODE_POPUP) == MODE_STRICT) {
                startStrictBreak();
            }
        } else if (ACTION_CONFIG.equals(action)) {
            // Saving settings intentionally starts a fresh usage round.
            breakUntil = 0;
            handler.removeCallbacks(breakEndTask);
            prefs.edit().putLong(BREAK_UNTIL, 0).apply();
            if (isDeviceInteractive()) beginSession();
            else endSession();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(ACTIVE_NOTIFICATION, buildActiveNotification());
        } else if (breakUntil == 0 && startedAt == 0 && isDeviceInteractive()) {
            beginSession();
        }
        return START_STICKY;
    }

    private boolean isDeviceInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private long intervalMillis() {
        int minutes = Math.max(1, Math.min(180, prefs.getInt(INTERVAL_MIN, 20)));
        return minutes * 60_000L;
    }

    private void beginSession() {
        handler.removeCallbacks(reminderTask);
        handler.removeCallbacks(graceExpiryTask);
        dismissOverlay();
        startedAt = SystemClock.elapsedRealtime();
        nextDueAt = startedAt + intervalMillis();
        pausedAt = 0;
        saveTiming();
        scheduleReminder();
    }

    private void pauseSession() {
        if (breakUntil > 0 || startedAt == 0 || pausedAt != 0) return;
        pausedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(reminderTask);
        dismissOverlay();
        saveTiming();
        scheduleGraceExpiry();
    }

    private void scheduleGraceExpiry() {
        handler.removeCallbacks(graceExpiryTask);
        if (pausedAt > 0) {
            long time = pausedAt + SHORT_LOCK_MS + 1 - SystemClock.elapsedRealtime();
            handler.postDelayed(graceExpiryTask, Math.max(1, time));
        }
    }

    private void resumeOrBeginSession() {
        if (breakUntil > 0) return;
        handler.removeCallbacks(graceExpiryTask);
        if (startedAt == 0 || pausedAt == 0) {
            if (startedAt == 0) beginSession();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!TimingRules.isShortScreenOff(pausedAt, now, SHORT_LOCK_MS)) {
            beginSession();
        } else {
            startedAt = TimingRules.moveTimestampAfterPause(startedAt, pausedAt, now);
            nextDueAt = TimingRules.moveTimestampAfterPause(nextDueAt, pausedAt, now);
            pausedAt = 0;
            saveTiming();
            scheduleReminder();
        }
    }

    private void clearUsage() {
        handler.removeCallbacks(reminderTask);
        handler.removeCallbacks(graceExpiryTask);
        dismissOverlay();
        startedAt = 0;
        nextDueAt = 0;
        pausedAt = 0;
        saveTiming();
    }

    private void endSession() { clearUsage(); }

    private void saveTiming() {
        prefs.edit().putLong(SESSION_START, startedAt)
                .putLong(NEXT_DUE, nextDueAt)
                .putLong(PAUSED_AT, pausedAt).apply();
    }

    private void scheduleReminder() {
        handler.removeCallbacks(reminderTask);
        if (nextDueAt > 0 && pausedAt == 0 && breakUntil == 0) {
            handler.postDelayed(reminderTask,
                    Math.max(0, nextDueAt - SystemClock.elapsedRealtime()));
        }
    }

    private boolean hasDeviceAdmin() {
        return policyManager != null && policyManager.isAdminActive(adminComponent);
    }

    private boolean lockDevice() {
        if (!hasDeviceAdmin()) return false;
        try {
            policyManager.lockNow();
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Lock was rejected by Android", e);
            return false;
        }
    }

    private void startStrictBreak() {
        if (!hasDeviceAdmin()) {
            // Device admin may have been revoked while the service was running.
            // Use the non-locking reminder rather than silently failing.
            beginSession();
            showReminder();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        clearUsage();
        breakUntil = now + STRICT_BREAK_MS;
        prefs.edit().putLong(BREAK_UNTIL, breakUntil).apply();
        handler.removeCallbacks(breakEndTask);
        handler.postDelayed(breakEndTask, STRICT_BREAK_MS);
        sendBreakNotification();
        if (!lockDevice()) fallbackFromStrictBreak();
    }

    private void fallbackFromStrictBreak() {
        breakUntil = 0;
        handler.removeCallbacks(breakEndTask);
        prefs.edit().putLong(BREAK_UNTIL, 0).apply();
        if (isDeviceInteractive()) {
            beginSession();
            showReminder();
        } else {
            endSession();
            sendReminderNotification();
        }
    }

    private void finishStrictBreak() {
        breakUntil = 0;
        handler.removeCallbacks(breakEndTask);
        prefs.edit().putLong(BREAK_UNTIL, 0).apply();
        if (isDeviceInteractive()) beginSession();
        else endSession();
        // Intentionally never unlock programmatically. User unlocks normally.
    }

    private void snoozeFiveMinutes() {
        dismissOverlay();
        if (startedAt > 0 && pausedAt == 0 && isDeviceInteractive()) {
            nextDueAt = SystemClock.elapsedRealtime() + 5 * 60_000L;
            saveTiming();
            scheduleReminder();
        }
    }

    private void showReminder() {
        if (!isDeviceInteractive()) return;
        if (!Settings.canDrawOverlays(this)) {
            sendReminderNotification();
            return;
        }
        dismissOverlay();
        try {
            View root = LayoutInflater.from(this).inflate(R.layout.dialog_reminder, null);
            TextView headline = root.findViewById(R.id.reminderHeadline);
            headline.setText("已经连续使用手机 " + prefs.getInt(INTERVAL_MIN, 20) + " 分钟啦");
            root.findViewById(R.id.gotItButton).setOnClickListener(v -> dismissOverlay());
            root.findViewById(R.id.snoozeButton).setOnClickListener(v -> snoozeFiveMinutes());
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            windowManager.addView(root, params);
            overlayView = root;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not display overlay", e);
            overlayView = null;
            sendReminderNotification();
        }
    }

    private void dismissOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); }
            catch (IllegalArgumentException ignored) { }
            overlayView = null;
        }
    }

    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel active = new NotificationChannel(ACTIVE_CHANNEL,
                "护眼监测运行状态", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("监测亮屏时间的持续通知");
        nm.createNotificationChannel(active);
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL,
                "护眼休息提醒", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("提醒休息眼睛或严格模式锁屏");
        nm.createNotificationChannel(alerts);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private Notification buildActiveNotification() {
        String text = prefs.getInt(MODE, MODE_POPUP) == MODE_STRICT
                ? "严格模式：到时锁屏，20秒内尝试阻止提前亮屏"
                : "普通模式：到时弹窗提醒";
        return new Notification.Builder(this, ACTIVE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("护眼监测正在运行")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build();
    }

    private void sendReminderNotification() {
        Notification notification = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("该让眼睛休息一下啦")
                .setContentText("请看向约六米外，保持至少20秒。")
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(REMINDER_NOTIFICATION, notification);
    }

    private void sendBreakNotification() {
        Notification notification = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("严格模式：护眼休息20秒")
                .setContentText("已请求锁屏。20秒后可正常使用，仍需自行解锁。")
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(REMINDER_NOTIFICATION, notification);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(breakEndTask);
        breakUntil = 0;
        prefs.edit().putLong(BREAK_UNTIL, 0).apply();
        endSession();
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
