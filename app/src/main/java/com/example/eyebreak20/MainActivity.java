package com.example.eyebreak20;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView status, countdown, permissionStatus, modeExplanation;
    private EditText minutesField;
    private Button toggleButton, testButton, testLockButton;
    private RadioGroup modeGroup;
    private SharedPreferences prefs;
    private DevicePolicyManager policyManager;
    private ComponentName adminComponent;

    private final Runnable updateUi = new Runnable() {
        @Override public void run() {
            refreshUi();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(ScreenMonitorService.PREFS, MODE_PRIVATE);
        policyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, EyeBreakDeviceAdminReceiver.class);

        status = findViewById(R.id.status);
        countdown = findViewById(R.id.countdown);
        permissionStatus = findViewById(R.id.permissionStatus);
        modeExplanation = findViewById(R.id.modeExplanation);
        minutesField = findViewById(R.id.minutesField);
        toggleButton = findViewById(R.id.toggleButton);
        testButton = findViewById(R.id.testButton);
        testLockButton = findViewById(R.id.testLockButton);
        modeGroup = findViewById(R.id.modeGroup);
        CheckBox autoBoot = findViewById(R.id.autoBoot);

        minutesField.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesField.setText(String.valueOf(prefs.getInt(ScreenMonitorService.INTERVAL_MIN, 20)));
        autoBoot.setChecked(prefs.getBoolean(ScreenMonitorService.AUTO_BOOT, false));
        autoBoot.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(ScreenMonitorService.AUTO_BOOT, checked).apply());

        boolean strict = prefs.getInt(ScreenMonitorService.MODE,
                ScreenMonitorService.MODE_POPUP) == ScreenMonitorService.MODE_STRICT;
        modeGroup.check(strict ? R.id.strictMode : R.id.popupMode);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = checkedId == R.id.strictMode
                    ? ScreenMonitorService.MODE_STRICT : ScreenMonitorService.MODE_POPUP;
            boolean changed = mode != prefs.getInt(ScreenMonitorService.MODE,
                    ScreenMonitorService.MODE_POPUP);
            prefs.edit().putInt(ScreenMonitorService.MODE, mode).apply();
            if (changed && prefs.getBoolean(ScreenMonitorService.ENABLED, false)) {
                sendServiceAction(ScreenMonitorService.ACTION_CONFIG);
            }
            if (mode == ScreenMonitorService.MODE_STRICT && !hasAdmin()) {
                Toast.makeText(this, "严格模式需要授权设备管理员锁屏权限", Toast.LENGTH_LONG).show();
            }
            refreshUi();
        });

        findViewById(R.id.overlayButton).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        findViewById(R.id.adminButton).setOnClickListener(v -> {
            if (hasAdmin()) {
                new AlertDialog.Builder(this)
                        .setTitle("锁屏权限已开启")
                        .setMessage("如需撤销，请前往系统设置中的设备管理员应用，关闭「护眼严格模式锁屏」。")
                        .setPositiveButton("知道了", null)
                        .show();
            } else {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "只申请立即锁定屏幕的权限。严格模式每达到设置的时间时自动锁屏，20秒内若亮屏会尝试再次锁屏。部分手机重新解锁需要密码。此权限可随时撤销。");
                startActivity(intent);
            }
        });

        findViewById(R.id.notifyButton).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
            } else {
                Toast.makeText(this, "通知权限已允许或无需额外授权", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.saveButton).setOnClickListener(v -> {
            if (!saveInterval()) return;
            if (prefs.getBoolean(ScreenMonitorService.ENABLED, false)) {
                sendServiceAction(ScreenMonitorService.ACTION_CONFIG);
            }
            Toast.makeText(this, "已保存；监测中会重新开始累计亮屏时间", Toast.LENGTH_SHORT).show();
            refreshUi();
        });

        toggleButton.setOnClickListener(v -> {
            if (prefs.getBoolean(ScreenMonitorService.ENABLED, false)) {
                prefs.edit().putBoolean(ScreenMonitorService.ENABLED, false)
                        .putLong(ScreenMonitorService.SESSION_START, 0L)
                        .putLong(ScreenMonitorService.NEXT_DUE, 0L)
                        .putLong(ScreenMonitorService.PAUSED_AT, 0L)
                        .putLong(ScreenMonitorService.BREAK_UNTIL, 0L).apply();
                stopService(new Intent(this, ScreenMonitorService.class));
            } else {
                int mode = prefs.getInt(ScreenMonitorService.MODE,
                        ScreenMonitorService.MODE_POPUP);
                if (mode == ScreenMonitorService.MODE_STRICT && !hasAdmin()) {
                    Toast.makeText(this, "请先点击下方按钮，启用设备管理员锁屏权限", Toast.LENGTH_LONG).show();
                    return;
                }
                if (mode == ScreenMonitorService.MODE_POPUP && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    return;
                }
                if (!saveInterval()) return;
                Intent i = new Intent(this, ScreenMonitorService.class);
                i.setAction(ScreenMonitorService.ACTION_START);
                try {
                    startForegroundService(i);
                    prefs.edit().putBoolean(ScreenMonitorService.ENABLED, true).apply();
                } catch (RuntimeException ex) {
                    Toast.makeText(this, "启动失败：" + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            refreshUi();
        });

        testButton.setOnClickListener(v -> sendServiceAction(ScreenMonitorService.ACTION_TEST));
        testLockButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认测试严格锁屏？")
                .setMessage("确认后立即锁屏，并在20秒内尝试对提前亮屏再次锁屏。请确保记得设备密码。20秒结束后不会自动解锁。")
                .setNegativeButton("取消", null)
                .setPositiveButton("锁屏测试", (d, which) ->
                        sendServiceAction(ScreenMonitorService.ACTION_TEST_LOCK))
                .show());
        refreshUi();
    }

    private boolean hasAdmin() {
        return policyManager != null && policyManager.isAdminActive(adminComponent);
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, ScreenMonitorService.class);
        i.setAction(action);
        try {
            startService(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean saveInterval() {
        try {
            int min = Integer.parseInt(minutesField.getText().toString().trim());
            if (min < 1 || min > 180) throw new NumberFormatException();
            prefs.edit().putInt(ScreenMonitorService.INTERVAL_MIN, min).apply();
            return true;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入1–180分钟之间的整数", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void refreshUi() {
        if (prefs == null) return;
        boolean enabled = prefs.getBoolean(ScreenMonitorService.ENABLED, false);
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notify = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean admin = hasAdmin();
        boolean strict = prefs.getInt(ScreenMonitorService.MODE,
                ScreenMonitorService.MODE_POPUP) == ScreenMonitorService.MODE_STRICT;
        permissionStatus.setText("悬浮窗：" + (overlay ? "已允许 ✓" : "未允许") +
                "\n锁屏设备管理员：" + (admin ? "已允许 ✓" : "未允许") +
                "\n通知：" + (notify ? "已允许 ✓" : "未允许"));
        modeExplanation.setText(strict
                ? "严格模式：用满设定时间后锁屏20秒。若提前唤醒屏幕，将尝试再次锁屏；不能保证阻止解锁。不会自动解锁，可能要求输入PIN或密码。" +
                    (admin ? "" : "  ⚠ 尚未授权锁屏；请点击下方授权按钮。")
                : "普通模式：到时弹窗提醒，可以选择知道了或5分钟后提醒。");
        if (!enabled) {
            status.setText("○ 护眼监测未开启");
        } else if (strict && !admin) {
            status.setText("● 正在监测 · 严格模式未授权（将回退弹窗）");
        } else {
            status.setText("● 护眼监测已开启（" +
                    (strict ? "严格模式" : "普通模式") + "）");
        }
        toggleButton.setText(enabled ? "停止监测" : "开始监测");
        testButton.setEnabled(enabled && overlay);
        testLockButton.setEnabled(enabled && strict && admin);

        long breakUntil = prefs.getLong(ScreenMonitorService.BREAK_UNTIL, 0);
        long now = SystemClock.elapsedRealtime();
        if (!enabled) {
            countdown.setText("启动后自动监测亮屏时间");
        } else if (breakUntil > now) {
            countdown.setText(String.format(Locale.CHINA,
                    "严格模式休息中 · 剩余 %d 秒（不会自动解锁）",
                    (breakUntil - now + 999) / 1000));
        } else {
            long start = prefs.getLong(ScreenMonitorService.SESSION_START, 0);
            long due = prefs.getLong(ScreenMonitorService.NEXT_DUE, 0);
            long pausedAt = prefs.getLong(ScreenMonitorService.PAUSED_AT, 0);
            if (start == 0 || due == 0) {
                countdown.setText("等待下一次亮屏");
            } else {
                long clock = pausedAt > 0 ? pausedAt : now;
                long usedSec = Math.max(0, (clock - start) / 1000);
                long leftSec = Math.max(0, (due - clock + 999) / 1000);
                String label = pausedAt > 0 ? "已暂停（短暂锁屏保留累计）" : "累计亮屏";
                countdown.setText(String.format(Locale.CHINA,
                        "%s %02d:%02d · 距提醒 %02d:%02d",
                        label, usedSec / 60, usedSec % 60, leftSec / 60, leftSec % 60));
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(updateUi);
        uiHandler.post(updateUi);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(updateUi);
        super.onPause();
    }
}
