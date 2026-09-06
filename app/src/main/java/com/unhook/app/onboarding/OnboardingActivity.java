package com.unhook.app.onboarding;

import android.Manifest;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.unhook.app.R;
import com.unhook.app.UnhookApplication;
import com.unhook.app.core.CryptoManager;
import com.unhook.app.core.SecurityChecks;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.detection.DetectionService;
import com.unhook.app.detection.UsageStatsDetector;
import com.unhook.app.ui.MainActivity;
import com.unhook.app.work.NightlyWorker;
import com.unhook.app.work.WeeklyReportWorker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Permission & privacy onboarding (deliverable #2, final form).
 *
 * CONTRACT: every system permission is requested ONLY after its own
 * plain-English "why we need this" screen. Each screen says exactly what we
 * can and cannot see, and that permission can be revoked at any time.
 * There is no dark pattern: every step has a Skip except Usage access,
 * without which the product simply cannot work (and we say so).
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 41;

    private int step = 0;
    private LinearLayout content;
    private SecurityChecks.SelfIntegrity integrity = SecurityChecks.SelfIntegrity.FIRST_RUN;
    private boolean integrityChecked;
    private final List<String> pickPkgs = new ArrayList<>();
    private final List<CheckBox> pickBoxes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
        render(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == 2) {
            updateUsageChip();
        } else if (step == 3) {
            updateOverlayChip();
        } else if (step == 4) {
            updateNotifChip();
        } else if (step == 5 && !integrityChecked) {
            runIntegrityCheck();
        }
    }

    // ------------------------------------------------------------------

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void render(int s) {
        step = s;
        content.removeAllViews();
        pickBoxes.clear();
        if (s == 0) {
            welcome();
        } else if (s == 1) {
            pickApps();
        } else if (s == 2) {
            usageAccess();
        } else if (s == 3) {
            overlay();
        } else if (s == 4) {
            notifications();
        } else {
            securityAndStart();
        }
    }

    // ---------------- step 0: welcome + pillars ----------------

    private void welcome() {
        title(getString(R.string.ob_welcome_title));
        body(getString(R.string.ob_welcome_body));
        bullet(getString(R.string.ob_pillar_privacy));
        bullet(getString(R.string.ob_pillar_screen));
        bullet(getString(R.string.ob_pillar_offline));
        button(getString(R.string.ob_begin), v -> render(1));
    }

    // ---------------- step 1: pick trigger apps ----------------

    private void pickApps() {
        title(getString(R.string.ob_triggers_title));
        body(getString(R.string.ob_triggers_body));

        UnhookApplication.get().executors().diskIo().execute(() -> {
            List<ResolveInfo> ris = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            final UnhookRepository repo = UnhookRepository.get();
            List<String> current = new ArrayList<>();
            for (com.unhook.app.data.entity.AppRuleEntity r : repo.triggerRules()) {
                current.add(r.pkg);
            }
            List<String[]> apps = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || contains(apps, p)) {
                    continue;
                }
                apps.add(new String[]{p,
                        String.valueOf(ri.loadLabel(getPackageManager()))});
            }
            sortByName(apps);
            runOnUiThread(() -> {
                for (String[] app : apps) {
                    CheckBox cb = new CheckBox(this);
                    cb.setText(app[1]);
                    cb.setTextSize(14f);
                    cb.setChecked(containsCurrent(current, app[0]));
                    pickPkgs.add(app[0]);
                    pickBoxes.add(cb);
                    content.addView(cb, wrap());
                }
                button(getString(R.string.ob_save_continue), v -> {
                    UnhookApplication.get().executors().diskIo().execute(() -> {
                        UnhookRepository r = UnhookRepository.get();
                        for (int i = 0; i < pickPkgs.size(); i++) {
                            r.setTrigger(pickPkgs.get(i), pickBoxes.get(i).isChecked());
                        }
                    });
                    render(2);
                });
                skipLink(getString(R.string.ob_triggers_skip), v -> render(2));
            });
        });
    }

    // ---------------- step 2: usage access (required) ----------------

    private void usageAccess() {
        title(getString(R.string.ob_usage_title));
        body(getString(R.string.ob_usage_body));
        bullet(getString(R.string.ob_usage_see));
        bullet(getString(R.string.ob_usage_never));
        bullet(getString(R.string.ob_usage_revoke));
        final TextView chip = chip();
        content.addView(chip, wrap());
        button(getString(R.string.ob_usage_open), v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        skipLink(getString(R.string.ob_later), v -> render(3));
        updateUsageChip(chip);
    }

    private void updateUsageChip() {
        // Re-render path when returning from Settings.
        content.removeAllViews();
        render(2);
    }

    private void updateUsageChip(TextView chip) {
        boolean ok = UsageStatsDetector.isUsageAccessGranted(this);
        setChip(chip, ok ? R.string.ob_status_granted : R.string.ob_status_waiting, ok);
    }

    // ---------------- step 3: overlay ----------------

    private void overlay() {
        title(getString(R.string.ob_overlay_title));
        body(getString(R.string.ob_overlay_body));
        bullet(getString(R.string.ob_overlay_does));
        bullet(getString(R.string.ob_overlay_never));
        final TextView chip = chip();
        content.addView(chip, wrap());
        button(getString(R.string.ob_overlay_open), v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        skipLink(getString(R.string.ob_overlay_skip), v -> render(Build.VERSION.SDK_INT >= 33 ? 4 : 5));
        updateOverlayChip(chip);
    }

    private void updateOverlayChip() {
        content.removeAllViews();
        render(3);
    }

    private void updateOverlayChip(TextView chip) {
        boolean ok = Settings.canDrawOverlays(this);
        setChip(chip, ok ? R.string.ob_status_granted : R.string.ob_status_waiting, ok);
    }

    // ---------------- step 4: notifications (API 33+) ----------------

    private void notifications() {
        title(getString(R.string.ob_notif_title));
        body(getString(R.string.ob_notif_body));
        final TextView chip = chip();
        content.addView(chip, wrap());
        button(getString(R.string.ob_notif_allow), v ->
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        skipLink(getString(R.string.ob_later), v -> render(5));
        updateNotifChip(chip);
    }

    private void updateNotifChip() {
        content.removeAllViews();
        render(Build.VERSION.SDK_INT >= 33 ? 4 : 5);
    }

    private void updateNotifChip(TextView chip) {
        if (Build.VERSION.SDK_INT < 33) {
            setChip(chip, R.string.ob_status_granted, true);
            return;
        }
        boolean ok = ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        setChip(chip, ok ? R.string.ob_status_granted : R.string.ob_status_waiting, ok);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIF) {
            content.removeAllViews();
            render(4);
        }
    }

    // ---------------- step 5: security + start ----------------

    private void securityAndStart() {
        title(getString(R.string.ob_security_title));
        body(getString(R.string.ob_security_body));
        final TextView chip = chip();
        content.addView(chip, wrap());
        if (!integrityChecked) {
            runIntegrityCheck(chip);
        } else {
            paintIntegrity(chip);
        }
        button(getString(R.string.ob_start), v -> finishOnboarding());
    }

    private void runIntegrityCheck() {
        runIntegrityCheck(null);
    }

    private void runIntegrityCheck(final TextView chip) {
        integrityChecked = true;
        UnhookApplication.get().executors().diskIo().execute(() -> {
            integrity = SecurityChecks.verifySelfIntegrity(this,
                    UnhookApplication.get().crypto());
            runOnUiThread(() -> {
                if (chip != null) {
                    paintIntegrity(chip);
                }
            });
        });
    }

    private void paintIntegrity(TextView chip) {
        if (integrity == SecurityChecks.SelfIntegrity.TAMPERED) {
            setChip(chip, R.string.ob_security_tampered, false);
        } else {
            setChip(chip, R.string.ob_security_ok, true);
        }
    }

    private void finishOnboarding() {
        UnhookApplication.get().executors().diskIo().execute(() -> {
            UnhookRepository repo = UnhookRepository.get();
            repo.getInstallTs(); // creates install_ts on first run
            repo.putSetting("onboarding_done", "1");
            repo.putSetting("monitor_enabled", "1");
            DetectionService.start(this);
            NightlyWorker.enqueue(this);
            WeeklyReportWorker.enqueue(this);
            runOnUiThread(() -> {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        });
    }

    // ---------------- shared widgets ----------------

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.unhook_ink));
        content.addView(t, wrap());
    }

    private void body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15f);
        t.setTextColor(ContextCompat.getColor(this, R.color.unhook_ink));
        t.setPadding(0, dp(10), 0, 0);
        content.addView(t, wrap());
    }

    private void bullet(String s) {
        TextView t = new TextView(this);
        t.setText("•  " + s);
        t.setTextSize(14f);
        t.setTextColor(ContextCompat.getColor(this, R.color.gray));
        t.setPadding(0, dp(6), 0, 0);
        content.addView(t, wrap());
    }

    private TextView chip() {
        TextView t = new TextView(this);
        t.setTextSize(13f);
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(16);
        t.setLayoutParams(lp);
        return t;
    }

    private void setChip(TextView chip, int textRes, boolean good) {
        chip.setText(textRes);
        chip.setTextColor(ContextCompat.getColor(this,
                good ? R.color.unhook_teal : R.color.unhook_amber));
    }

    private void button(String s, android.view.View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(15f);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(ContextCompat.getColor(this, R.color.unhook_teal_dark));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.btn_round);
        int p = dp(14);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        content.addView(b, lp);
    }

    private void skipLink(String s, android.view.View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(ContextCompat.getColor(this, R.color.gray));
        t.setPadding(0, dp(14), 0, 0);
        t.setOnClickListener(l);
        content.addView(t, wrap());
    }

    // ---------------- helpers ----------------

    private boolean contains(List<String[]> apps, String pkg) {
        for (String[] a : apps) {
            if (a[0].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCurrent(List<String> list, String pkg) {
        return list.contains(pkg);
    }

    private void sortByName(final List<String[]> apps) {
        Collections.sort(apps, (a, b) -> a[1].compareToIgnoreCase(b[1]));
    }

    @SuppressWarnings("unused")
    private static boolean usageSupported() {
        return true; // UsageStatsManager exists since API 21 (our minSdk 24)
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
