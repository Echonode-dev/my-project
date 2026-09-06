package com.unhook.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.unhook.app.R;
import com.unhook.app.UnhookApplication;
import com.unhook.app.coach.CoachEngine;
import com.unhook.app.core.SecurityChecks;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.detection.DetectionService;
import com.unhook.app.detection.UsageStatsDetector;
import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.interstitial.InterstitialActivity;
import com.unhook.app.ml.LogisticRegressionModel;
import com.unhook.app.onboarding.OnboardingActivity;
import com.unhook.app.privacy.Eraser;
import com.unhook.app.work.NightlyWorker;
import com.unhook.app.work.WeeklyReportWorker;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The entire dashboard (Core Feature D). Deliberately ONE screen, big
 * numbers, no feed — the 15-seconds-a-day contract (Pillar P2).
 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout content;
    private TextView chipSecurity, reclaimedValue, reclaimedWeek, todayTotal,
            todayPickups, todayLongest, stageValue, reportText;
    private LinearLayout warningBanner, pulseCard;
    private BarChartView chart;
    private Switch monitorSwitch;

    private final List<String> chartPkgs = new ArrayList<>();
    private SessionEntity pulseSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAsync();
    }

    // ------------------------------------------------------------------
    // UI skeleton (programmatic, no layout XML to drift out of sync)
    // ------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        content.setPadding(p, p, p, dp(32));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = big(getString(R.string.app_name), 26, true, R.color.unhook_ink);
        content.addView(title, wrap());
        chipSecurity = small("", 12, R.color.unhook_teal);
        chipSecurity.setBackgroundResource(R.drawable.chip_bg);
        chipSecurity.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams clp = wrap();
        clp.topMargin = dp(6);
        content.addView(chipSecurity, clp);

        warningBanner = new LinearLayout(this);
        warningBanner.setOrientation(LinearLayout.VERTICAL);
        warningBanner.setBackgroundColor(0xFFFFF7ED);
        warningBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView warn = small(getString(R.string.security_warning_generic), 14, R.color.unhook_amber);
        warningBanner.addView(warn);
        LinearLayout.LayoutParams wlp = match();
        wlp.topMargin = dp(16);
        content.addView(warningBanner, wlp);
        warningBanner.setVisibility(View.GONE);

        content.addView(section(getString(R.string.dash_reclaimed_title)));
        reclaimedValue = big("0 min", 34, true, R.color.unhook_teal);
        content.addView(reclaimedValue, wrap());
        reclaimedWeek = small("", 13, R.color.gray);
        content.addView(reclaimedWeek, wrap());

        content.addView(section(getString(R.string.dash_today_title)));
        todayTotal = row("dash_total");
        todayPickups = row("dash_pickups");
        todayLongest = row("dash_longest");

        content.addView(section(getString(R.string.dash_apps_title)));
        chart = new BarChartView(this);
        LinearLayout.LayoutParams clp2 = match();
        clp2.height = dp(6 * 30 + 4);
        content.addView(chart, clp2);

        content.addView(section(getString(R.string.dash_coach_title)));
        stageValue = small("", 14, R.color.unhook_ink);
        content.addView(stageValue, wrap());
        reportText = small("", 14, R.color.gray);
        content.addView(reportText, wrap());
        TextView pulseTitle = small(getString(R.string.pulse_title), 14, R.color.unhook_ink);
        LinearLayout.LayoutParams plp = wrap();
        plp.topMargin = dp(12);
        pulseCard = new LinearLayout(this);
        pulseCard.setOrientation(LinearLayout.VERTICAL);
        pulseCard.addView(pulseTitle);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView yes = chipButton(getString(R.string.pulse_yes));
        yes.setOnClickListener(v -> answerPulse(0));
        TextView no = chipButton(getString(R.string.pulse_not_really));
        no.setOnClickListener(v -> answerPulse(1));
        buttons.addView(yes);
        buttons.addView(no);
        pulseCard.addView(buttons);
        content.addView(pulseCard, plp);

        content.addView(section(getString(R.string.dash_settings_title)));
        monitorSwitch = new Switch(this);
        monitorSwitch.setText(getString(R.string.dash_monitor_toggle));
        monitorSwitch.setOnCheckedChangeListener((b, on) -> toggleMonitoring(on));
        content.addView(monitorSwitch, match());
        TextView triggers = link(getString(R.string.dash_pick_triggers));
        triggers.setOnClickListener(v -> showTriggerPicker());
        content.addView(triggers, wrap());
        TextView onboard = link(getString(R.string.dash_replay_onboarding));
        onboard.setOnClickListener(v -> startActivity(
                new Intent(this, OnboardingActivity.class)));
        content.addView(onboard, wrap());
        TextView erase = link(getString(R.string.dash_erase));
        erase.setTextColor(ContextCompat.getColor(this, R.color.unhook_amber));
        erase.setOnClickListener(v -> confirmErase());
        content.addView(erase, wrap());
    }

    private TextView row(String key) {
        TextView t = small("", 15, R.color.unhook_ink);
        t.setTag(key);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(6);
        content.addView(t, lp);
        return t;
    }

    private TextView section(String s) {
        TextView t = small(s.toUpperCase(), 12, R.color.gray);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(28);
        content.addView(t, lp);
        return t;
    }

    private TextView chipButton(String s) {
        TextView b = small(s, 13, R.color.unhook_teal);
        b.setBackgroundResource(R.drawable.chip_bg);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView link(String s) {
        TextView t = small(s, 14, R.color.unhook_teal);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView big(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(t.getTypeface(), bold ? android.graphics.Typeface.BOLD : 0);
        t.setTextColor(ContextCompat.getColor(this, color));
        return t;
    }

    private TextView small(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Data (all off-main; dashboard only paints results)
    // ------------------------------------------------------------------

    private static final class Snapshot {
        SecurityChecks.SelfIntegrity integrity = SecurityChecks.SelfIntegrity.FIRST_RUN;
        boolean dataCorrupted;
        boolean modelTampered;
        long reclaimedTodayMs;
        long reclaimedWeekMs;
        long totalTodayMs;
        int pickups;
        long longestMs;
        List<BarChartView.Entry> bars = Collections.emptyList();
        List<String> triggerPkgs = Collections.emptyList();
        boolean monitorEnabled;
        CoachEngine.Stage stage = CoachEngine.Stage.BASELINE;
        boolean softened;
        CoachEngine.Report report = new CoachEngine.Report();
        SessionEntity pulseCandidate;
    }

    private void loadAsync() {
        final UnhookApplication app = UnhookApplication.get();
        app.executors().diskIo().execute(() -> {
            final Snapshot s = gather(app);
            runOnUiThread(() -> refresh(s));
        });
    }

    private Snapshot gather(UnhookApplication app) {
        Snapshot s = new Snapshot();
        UnhookRepository repo = UnhookRepository.get();
        s.integrity = SecurityChecks.verifySelfIntegrity(this, app.crypto());
        s.dataCorrupted = repo.corruptedAggregateCount(7) > 0;
        s.modelTampered = LogisticRegressionModel.load(app).isTampered();

        long dayStart = UnhookRepository.dayStart(System.currentTimeMillis());
        long weekStart = dayStart - 6L * 24 * 3600 * 1000;
        s.reclaimedTodayMs = repo.timeReclaimedMs(dayStart);
        s.reclaimedWeekMs = repo.timeReclaimedMs(weekStart);

        s.pickups = repo.dao().countSessionsSince(dayStart);
        Long longest = repo.dao().maxSessionSince(dayStart);
        s.longestMs = longest == null ? 0 : longest;

        s.totalTodayMs = 0;
        List<BarChartView.Entry> bars = new ArrayList<>();
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    getSystemService(USAGE_STATS_SERVICE);
            if (usm != null) {
                List<UsageStats> stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, dayStart,
                        System.currentTimeMillis());
                if (stats != null) {
                    for (UsageStats st : stats) {
                        long t = st.getTotalTimeInForeground();
                        if (t <= 0 || st.getPackageName().equals(getPackageName())) {
                            continue;
                        }
                        s.totalTodayMs += t;
                        bars.add(new BarChartView.Entry(
                                labelFor(st.getPackageName()), t / 60000f));
                    }
                }
            }
        } catch (Exception ignored) {
            // Usage access not granted yet: dashboard still shows our numbers.
        }
        Collections.sort(bars, (a, b) -> Float.compare(b.minutes, a.minutes));
        if (bars.size() > 6) {
            bars = new ArrayList<>(bars.subList(0, 6));
        }
        s.bars = bars;

        List<AppRuleEntity> rules = repo.triggerRules();
        List<String> trig = new ArrayList<>();
        for (AppRuleEntity r : rules) {
            trig.add(r.pkg);
        }
        s.triggerPkgs = trig;
        s.monitorEnabled = !"0".equals(repo.getSetting("monitor_enabled"))
                && UsageStatsDetector.isUsageAccessGranted(this);
        s.stage = CoachEngine.stageOf(repo);
        s.softened = CoachEngine.isSoftened(repo);
        s.report = CoachEngine.buildWeeklyReport(repo.dao(), repo);

        List<SessionEntity> candidates =
                repo.dao().lastLongTriggerSession(dayStart - 7L * 24 * 3600 * 1000);
        s.pulseCandidate = candidates.isEmpty() ? null : candidates.get(0);

        return s;
    }

    private void refresh(Snapshot s) {
        if (s == null) {
            return;
        }
        boolean ok = s.integrity == SecurityChecks.SelfIntegrity.OK
                || s.integrity == SecurityChecks.SelfIntegrity.FIRST_RUN;
        chipSecurity.setText(ok
                ? getString(R.string.security_chip_ok)
                : getString(R.string.security_chip_bad));
        chipSecurity.setTextColor(ContextCompat.getColor(this, ok
                ? R.color.unhook_teal : R.color.unhook_amber));
        boolean warn = !ok || s.dataCorrupted || s.modelTampered;
        warningBanner.setVisibility(warn ? View.VISIBLE : View.GONE);

        reclaimedValue.setText(minutes(s.reclaimedTodayMs));
        reclaimedWeek.setText(getString(R.string.dash_reclaimed_week, minutes(s.reclaimedWeekMs)));

        todayTotal.setText(getString(R.string.dash_total, minutes(s.totalTodayMs)));
        todayPickups.setText(getString(R.string.dash_pickups, s.pickups));
        todayLongest.setText(getString(R.string.dash_longest, minutes(s.longestMs)));

        chart.setData(s.bars);

        stageValue.setText(getString(R.string.dash_stage, stageName(s.stage)));
        reportText.setText(reportLine(s));
        pulseCard.setVisibility(s.pulseCandidate == null ? View.GONE : View.VISIBLE);
        pulseSession = s.pulseCandidate;

        monitorSwitch.setOnCheckedChangeListener(null);
        monitorSwitch.setChecked(s.monitorEnabled);
        monitorSwitch.setOnCheckedChangeListener((b, on) -> toggleMonitoring(on));
    }

    private String reportLine(Snapshot s) {
        CoachEngine.Report r = s.report;
        int change = r.changePercent();
        return getString(R.string.dash_report_line,
                r.topAppOpens, minutes(r.reclaimedThisWeekMs),
                change > 0 ? "+" + change : String.valueOf(change))
                + (s.softened ? " " + getString(R.string.coach_softened) : "");
    }

    private String minutes(long ms) {
        long min = ms / 60000L;
        if (min >= 60) {
            return (min / 60) + " h " + (min % 60) + " min";
        }
        return min + " min";
    }

    private String stageName(CoachEngine.Stage st) {
        switch (st) {
            case BASELINE: return getString(R.string.stage_baseline);
            case GENTLE: return getString(R.string.stage_gentle);
            case STEADY: return getString(R.string.stage_steady);
            default: return getString(R.string.stage_full);
        }
    }

    private String labelFor(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            int dot = pkg.lastIndexOf('.');
            return dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void toggleMonitoring(boolean on) {
        UnhookRepository repo = UnhookRepository.get();
        repo.putSetting("monitor_enabled", on ? "1" : "0");
        if (on) {
            DetectionService.start(this);
            NightlyWorker.enqueue(this);
            WeeklyReportWorker.enqueue(this);
        } else {
            DetectionService.stop(this);
        }
    }

    private void answerPulse(int label) {
        if (pulseSession == null) {
            return;
        }
        final String pkg = pulseSession.pkg;
        final byte[] none = new byte[0];
        UnhookApplication.get().executors().diskIo().execute(() ->
                UnhookRepository.get().recordFeedback(
                        pkg, "PULSE", label, 0.5, none, System.currentTimeMillis()));
        pulseCard.setVisibility(View.GONE);
    }

    private void showTriggerPicker() {
        UnhookApplication.get().executors().diskIo().execute(() -> {
            // Load launchable apps + current selections off the UI thread.
            List<android.content.pm.ResolveInfo> ris = getPackageManager()
                    .queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER), 0);
            List<String> pkgs = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<Boolean> checked = new ArrayList<>();
            List<AppRuleEntity> rules = UnhookRepository.get().triggerRules();
            for (android.content.pm.ResolveInfo ri : ris) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || pkgs.contains(p)) {
                    continue;
                }
                pkgs.add(p);
                labels.add(String.valueOf(ri.loadLabel(getPackageManager())));
                boolean isTrig = false;
                for (AppRuleEntity r : rules) {
                    if (r.pkg.equals(p) && r.isTrigger) {
                        isTrig = true;
                        break;
                    }
                }
                checked.add(isTrig);
            }
            String[] names = labels.toArray(new String[0]);
            boolean[] sel = new boolean[checked.size()];
            for (int i = 0; i < checked.size(); i++) {
                sel[i] = checked.get(i);
            }
            List<String> finalPkgs = pkgs;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dash_pick_triggers))
                    .setMultiChoiceItems(names, sel, (d, which, isChecked) ->
                            sel[which] = isChecked)
                    .setPositiveButton(getString(android.R.string.ok), (d, w) -> {
                        final boolean[] finalSel = sel;
                        UnhookApplication.get().executors().diskIo().execute(() -> {
                            UnhookRepository repo = UnhookRepository.get();
                            for (int i = 0; i < finalPkgs.size(); i++) {
                                repo.setTrigger(finalPkgs.get(i), finalSel[i]);
                            }
                        });
                    })
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show());
        });
    }

    private void confirmErase() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.erase_title))
                .setMessage(getString(R.string.erase_body))
                .setPositiveButton(getString(R.string.erase_next), (d, w) ->
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.erase_title2))
                                .setMessage(getString(R.string.erase_body2))
                                .setPositiveButton(getString(R.string.erase_confirm), (d2, w2) -> {
                                    UnhookApplication.get().executors().diskIo().execute(() -> {
                                        Eraser.erase(getApplicationContext());
                                        runOnUiThread(() -> {
                                            Intent i = new Intent(this, OnboardingActivity.class);
                                            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                            startActivity(i);
                                            finishAffinity();
                                        });
                                    });
                                })
                                .setNegativeButton(getString(android.R.string.cancel), null)
                                .show())
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show();
    }
}
