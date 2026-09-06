package com.unhook.app.ui;

import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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
import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.detection.DetectionService;
import com.unhook.app.detection.UsageStatsDetector;
import com.unhook.app.interstitial.InterstitialActivity;
import com.unhook.app.ml.LogisticRegressionModel;
import com.unhook.app.onboarding.OnboardingActivity;
import com.unhook.app.privacy.Eraser;
import com.unhook.app.work.NightlyWorker;
import com.unhook.app.work.WeeklyReportWorker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Main dashboard.
 *
 * Entire UI is built programmatically.
 * Views are added to their parent exactly once.
 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout content;

    private TextView chipSecurity;
    private TextView reclaimedValue;
    private TextView reclaimedWeek;
    private TextView todayTotal;
    private TextView todayPickups;
    private TextView todayLongest;
    private TextView stageValue;
    private TextView reportText;

    private LinearLayout warningBanner;
    private LinearLayout pulseCard;

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
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {

        ScrollView scroll = new ScrollView(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(20);

        content.setPadding(
                padding,
                padding,
                padding,
                dp(32)
        );

        scroll.addView(content);

        setContentView(scroll);

        // --------------------------------------------------------------
        // Header
        // --------------------------------------------------------------

        TextView title = big(
                getString(R.string.app_name),
                26,
                true,
                R.color.unhook_ink
        );

        content.addView(title, wrap());

        chipSecurity = small(
                "",
                12,
                R.color.unhook_teal
        );

        chipSecurity.setBackgroundResource(R.drawable.chip_bg);

        chipSecurity.setPadding(
                dp(10),
                dp(4),
                dp(10),
                dp(4)
        );

        LinearLayout.LayoutParams chipParams = wrap();
        chipParams.topMargin = dp(6);

        content.addView(chipSecurity, chipParams);

        // --------------------------------------------------------------
        // Warning
        // --------------------------------------------------------------

        warningBanner = new LinearLayout(this);
        warningBanner.setOrientation(LinearLayout.VERTICAL);

        warningBanner.setBackgroundColor(0xFFFFF7ED);

        warningBanner.setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
        );

        TextView warn = small(
                getString(R.string.security_warning_generic),
                14,
                R.color.unhook_amber
        );

        warningBanner.addView(warn);

        LinearLayout.LayoutParams warningParams = match();
        warningParams.topMargin = dp(16);

        content.addView(warningBanner, warningParams);

        warningBanner.setVisibility(View.GONE);

        // --------------------------------------------------------------
        // Reclaimed
        // --------------------------------------------------------------

        content.addView(
                section(getString(R.string.dash_reclaimed_title))
        );

        reclaimedValue = big(
                "0 min",
                34,
                true,
                R.color.unhook_teal
        );

        content.addView(reclaimedValue, wrap());

        reclaimedWeek = small(
                "",
                13,
                R.color.gray
        );

        content.addView(reclaimedWeek, wrap());

        // --------------------------------------------------------------
        // Today
        // --------------------------------------------------------------

        content.addView(
                section(getString(R.string.dash_today_title))
        );

        todayTotal = row("dash_total");
        content.addView(todayTotal, todayTotal.getLayoutParams());

        todayPickups = row("dash_pickups");
        content.addView(todayPickups, todayPickups.getLayoutParams());

        todayLongest = row("dash_longest");
        content.addView(todayLongest, todayLongest.getLayoutParams());

        // --------------------------------------------------------------
        // Apps
        // --------------------------------------------------------------

        content.addView(
                section(getString(R.string.dash_apps_title))
        );

        chart = new BarChartView(this);

        LinearLayout.LayoutParams chartParams = match();

        chartParams.height = dp(6 * 30 + 4);

        content.addView(chart, chartParams);

        // --------------------------------------------------------------
        // Coach
        // --------------------------------------------------------------

        content.addView(
                section(getString(R.string.dash_coach_title))
        );

        stageValue = small(
                "",
                14,
                R.color.unhook_ink
        );

        content.addView(stageValue, wrap());

        reportText = small(
                "",
                14,
                R.color.gray
        );

        content.addView(reportText, wrap());

        // --------------------------------------------------------------
        // Pulse
        // --------------------------------------------------------------

        TextView pulseTitle = small(
                getString(R.string.pulse_title),
                14,
                R.color.unhook_ink
        );

        LinearLayout.LayoutParams pulseParams = wrap();
        pulseParams.topMargin = dp(12);

        pulseCard = new LinearLayout(this);
        pulseCard.setOrientation(LinearLayout.VERTICAL);

        pulseCard.addView(
                pulseTitle,
                wrap()
        );

        LinearLayout buttons = new LinearLayout(this);

        buttons.setOrientation(
                LinearLayout.HORIZONTAL
        );

        TextView yes = chipButton(
                getString(R.string.pulse_yes)
        );

        yes.setOnClickListener(
                v -> answerPulse(0)
        );

        TextView no = chipButton(
                getString(R.string.pulse_not_really)
        );

        no.setOnClickListener(
                v -> answerPulse(1)
        );

        buttons.addView(
                yes,
                yes.getLayoutParams()
        );

        buttons.addView(
                no,
                no.getLayoutParams()
        );

        pulseCard.addView(
                buttons,
                wrap()
        );

        content.addView(
                pulseCard,
                pulseParams
        );

        // --------------------------------------------------------------
        // Settings
        // --------------------------------------------------------------

        content.addView(
                section(getString(R.string.dash_settings_title))
        );

        monitorSwitch = new Switch(this);

        monitorSwitch.setText(
                getString(R.string.dash_monitor_toggle)
        );

        monitorSwitch.setOnCheckedChangeListener(
                (button, enabled) -> toggleMonitoring(enabled)
        );

        content.addView(
                monitorSwitch,
                match()
        );

        // --------------------------------------------------------------
        // Pick triggers
        // --------------------------------------------------------------

        TextView triggers = link(
                getString(R.string.dash_pick_triggers)
        );

        triggers.setOnClickListener(
                v -> showTriggerPicker()
        );

        content.addView(
                triggers,
                triggers.getLayoutParams()
        );

        // --------------------------------------------------------------
        // Replay onboarding
        // --------------------------------------------------------------

        TextView onboard = link(
                getString(R.string.dash_replay_onboarding)
        );

        onboard.setOnClickListener(
                v -> startActivity(
                        new Intent(
                                this,
                                OnboardingActivity.class
                        )
                )
        );

        content.addView(
                onboard,
                onboard.getLayoutParams()
        );

        // --------------------------------------------------------------
        // Erase
        // --------------------------------------------------------------

        TextView erase = link(
                getString(R.string.dash_erase)
        );

        erase.setTextColor(
                ContextCompat.getColor(
                        this,
                        R.color.unhook_amber
                )
        );

        erase.setOnClickListener(
                v -> confirmErase()
        );

        content.addView(
                erase,
                erase.getLayoutParams()
        );
    }

    /**
     * Creates a row but DOES NOT add it to content.
     *
     * This prevents the "child already has a parent" crash.
     */
    private TextView row(String key) {

        TextView text = small(
                "",
                15,
                R.color.unhook_ink
        );

        text.setTag(key);

        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(6);

        text.setLayoutParams(params);

        return text;
    }

    /**
     * Creates a section title but DOES NOT add it to content.
     *
     * The caller is responsible for adding it exactly once.
     */
    private TextView section(String textValue) {

        TextView text = small(
                textValue.toUpperCase(),
                12,
                R.color.gray
        );

        LinearLayout.LayoutParams params = wrap();

        params.topMargin = dp(28);

        text.setLayoutParams(params);

        return text;
    }

    private TextView chipButton(String textValue) {

        TextView button = small(
                textValue,
                13,
                R.color.unhook_teal
        );

        button.setBackgroundResource(
                R.drawable.chip_bg
        );

        button.setPadding(
                dp(14),
                dp(8),
                dp(14),
                dp(8)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(
                0,
                dp(8),
                dp(8),
                0
        );

        button.setLayoutParams(params);

        return button;
    }

    private TextView link(String textValue) {

        TextView text = small(
                textValue,
                14,
                R.color.unhook_teal
        );

        LinearLayout.LayoutParams params = wrap();

        params.topMargin = dp(10);

        text.setLayoutParams(params);

        return text;
    }

    private TextView big(
            String textValue,
            int sp,
            boolean bold,
            int color
    ) {

        TextView text = new TextView(this);

        text.setText(textValue);

        text.setTextSize(sp);

        text.setTypeface(
                text.getTypeface(),
                bold
                        ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL
        );

        text.setTextColor(
                ContextCompat.getColor(
                        this,
                        color
                )
        );

        return text;
    }

    private TextView small(
            String textValue,
            int sp,
            int color
    ) {

        TextView text = new TextView(this);

        text.setText(textValue);

        text.setTextSize(sp);

        text.setTextColor(
                ContextCompat.getColor(
                        this,
                        color
                )
        );

        return text;
    }

    private LinearLayout.LayoutParams wrap() {

        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams match() {

        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {

        return (int) (
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    private static final class Snapshot {

        SecurityChecks.SelfIntegrity integrity =
                SecurityChecks.SelfIntegrity.FIRST_RUN;

        boolean dataCorrupted;

        boolean modelTampered;

        long reclaimedTodayMs;

        long reclaimedWeekMs;

        long totalTodayMs;

        int pickups;

        long longestMs;

        List<BarChartView.Entry> bars =
                Collections.emptyList();

        List<String> triggerPkgs =
                Collections.emptyList();

        boolean monitorEnabled;

        CoachEngine.Stage stage =
                CoachEngine.Stage.BASELINE;

        boolean softened;

        CoachEngine.Report report =
                new CoachEngine.Report();

        SessionEntity pulseCandidate;
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private void loadAsync() {

        final UnhookApplication app =
                UnhookApplication.get();

        app.executors()
                .diskIo()
                .execute(() -> {

                    final Snapshot snapshot =
                            gather(app);

                    runOnUiThread(
                            () -> refresh(snapshot)
                    );
                });
    }

    private Snapshot gather(
            UnhookApplication app
    ) {

        Snapshot snapshot = new Snapshot();

        UnhookRepository repo =
                UnhookRepository.get();

        snapshot.integrity =
                SecurityChecks.verifySelfIntegrity(
                        this,
                        app.crypto()
                );

        snapshot.dataCorrupted =
                repo.corruptedAggregateCount(7) > 0;

        snapshot.modelTampered =
                LogisticRegressionModel
                        .load(app)
                        .isTampered();

        long dayStart =
                UnhookRepository.dayStart(
                        System.currentTimeMillis()
                );

        long weekStart =
                dayStart
                        - 6L * 24L * 3600L * 1000L;

        snapshot.reclaimedTodayMs =
                repo.timeReclaimedMs(dayStart);

        snapshot.reclaimedWeekMs =
                repo.timeReclaimedMs(weekStart);

        snapshot.pickups =
                repo.dao()
                        .countSessionsSince(dayStart);

        Long longest =
                repo.dao()
                        .maxSessionSince(dayStart);

        snapshot.longestMs =
                longest == null
                        ? 0
                        : longest;

        snapshot.totalTodayMs = 0;

        List<BarChartView.Entry> bars =
                new ArrayList<>();

        try {

            UsageStatsManager usageStatsManager =
                    (UsageStatsManager)
                            getSystemService(
                                    USAGE_STATS_SERVICE
                            );

            if (usageStatsManager != null) {

                List<UsageStats> stats =
                        usageStatsManager.queryUsageStats(
                                UsageStatsManager.INTERVAL_DAILY,
                                dayStart,
                                System.currentTimeMillis()
                        );

                if (stats != null) {

                    for (UsageStats stat : stats) {

                        long foreground =
                                stat.getTotalTimeInForeground();

                        if (foreground <= 0) {
                            continue;
                        }

                        if (stat.getPackageName()
                                .equals(getPackageName())) {
                            continue;
                        }

                        snapshot.totalTodayMs += foreground;

                        bars.add(
                                new BarChartView.Entry(
                                        labelFor(
                                                stat.getPackageName()
                                        ),
                                        foreground / 60000f
                                )
                        );
                    }
                }
            }

        } catch (Exception ignored) {
            // Usage access may not be granted.
        }

        Collections.sort(
                bars,
                (a, b) ->
                        Float.compare(
                                b.minutes,
                                a.minutes
                        )
        );

        if (bars.size() > 6) {

            bars = new ArrayList<>(
                    bars.subList(0, 6)
            );
        }

        snapshot.bars = bars;

        List<AppRuleEntity> rules =
                repo.triggerRules();

        List<String> triggerPackages =
                new ArrayList<>();

        for (AppRuleEntity rule : rules) {

            triggerPackages.add(
                    rule.pkg
            );
        }

        snapshot.triggerPkgs =
                triggerPackages;

        snapshot.monitorEnabled =
                !"0".equals(
                        repo.getSetting(
                                "monitor_enabled"
                        )
                )
                        && UsageStatsDetector
                        .isUsageAccessGranted(this);

        snapshot.stage =
                CoachEngine.stageOf(repo);

        snapshot.softened =
                CoachEngine.isSoftened(repo);

        snapshot.report =
                CoachEngine.buildWeeklyReport(
                        repo.dao(),
                        repo
                );

        List<SessionEntity> candidates =
                repo.dao()
                        .lastLongTriggerSession(
                                dayStart
                                        - 7L
                                        * 24L
                                        * 3600L
                                        * 1000L
                        );

        snapshot.pulseCandidate =
                candidates.isEmpty()
                        ? null
                        : candidates.get(0);

        return snapshot;
    }

    // ------------------------------------------------------------------
    // Refresh UI
    // ------------------------------------------------------------------

    private void refresh(Snapshot snapshot) {

        if (snapshot == null) {
            return;
        }

        boolean integrityOk =
                snapshot.integrity
                        == SecurityChecks.SelfIntegrity.OK
                        || snapshot.integrity
                        == SecurityChecks.SelfIntegrity.FIRST_RUN;

        chipSecurity.setText(
                integrityOk
                        ? getString(
                                R.string.security_chip_ok
                        )
                        : getString(
                                R.string.security_chip_bad
                        )
        );

        chipSecurity.setTextColor(
                ContextCompat.getColor(
                        this,
                        integrityOk
                                ? R.color.unhook_teal
                                : R.color.unhook_amber
                )
        );

        boolean showWarning =
                !integrityOk
                        || snapshot.dataCorrupted
                        || snapshot.modelTampered;

        warningBanner.setVisibility(
                showWarning
                        ? View.VISIBLE
                        : View.GONE
        );

        reclaimedValue.setText(
                minutes(
                        snapshot.reclaimedTodayMs
                )
        );

        reclaimedWeek.setText(
                getString(
                        R.string.dash_reclaimed_week,
                        minutes(
                                snapshot.reclaimedWeekMs
                        )
                )
        );

        todayTotal.setText(
                getString(
                        R.string.dash_total,
                        minutes(
                                snapshot.totalTodayMs
                        )
                )
        );

        todayPickups.setText(
                getString(
                        R.string.dash_pickups,
                        snapshot.pickups
                )
        );

        todayLongest.setText(
                getString(
                        R.string.dash_longest,
                        minutes(
                                snapshot.longestMs
                        )
                )
        );

        chart.setData(
                snapshot.bars
        );

        stageValue.setText(
                getString(
                        R.string.dash_stage,
                        stageName(
                                snapshot.stage
                        )
                )
        );

        reportText.setText(
                reportLine(snapshot)
        );

        pulseCard.setVisibility(
                snapshot.pulseCandidate == null
                        ? View.GONE
                        : View.VISIBLE
        );

        pulseSession =
                snapshot.pulseCandidate;

        // Prevent the listener from firing
        // while synchronizing the switch.
        monitorSwitch.setOnCheckedChangeListener(
                null
        );

        monitorSwitch.setChecked(
                snapshot.monitorEnabled
        );

        monitorSwitch.setOnCheckedChangeListener(
                (button, enabled) ->
                        toggleMonitoring(enabled)
        );
    }

    private String reportLine(
            Snapshot snapshot
    ) {

        CoachEngine.Report report =
                snapshot.report;

        int change =
                report.changePercent();

        String changeText =
                change > 0
                        ? "+" + change
                        : String.valueOf(change);

        return getString(
                R.string.dash_report_line,
                report.topAppOpens,
                minutes(
                        report.reclaimedThisWeekMs
                ),
                changeText
        )
                + (
                snapshot.softened
                        ? " "
                        + getString(
                        R.string.coach_softened
                )
                        : ""
        );
    }

    private String minutes(long milliseconds) {

        long minutes =
                milliseconds / 60000L;

        if (minutes >= 60) {

            return
                    (minutes / 60)
                            + " h "
                            + (minutes % 60)
                            + " min";
        }

        return minutes + " min";
    }

    private String stageName(
            CoachEngine.Stage stage
    ) {

        switch (stage) {

            case BASELINE:
                return getString(
                        R.string.stage_baseline
                );

            case GENTLE:
                return getString(
                        R.string.stage_gentle
                );

            case STEADY:
                return getString(
                        R.string.stage_steady
                );

            default:
                return getString(
                        R.string.stage_full
                );
        }
    }

    private String labelFor(
            String packageName
    ) {

        try {

            return String.valueOf(
                    getPackageManager()
                            .getApplicationLabel(
                                    getPackageManager()
                                            .getApplicationInfo(
                                                    packageName,
                                                    0
                                            )
                            )
            );

        } catch (Exception exception) {

            int dot =
                    packageName.lastIndexOf('.');

            if (dot >= 0
                    && dot + 1 < packageName.length()) {

                return packageName.substring(
                        dot + 1
                );
            }

            return packageName;
        }
    }

    // ------------------------------------------------------------------
    // Monitoring
    // ------------------------------------------------------------------

    private void toggleMonitoring(
            boolean enabled
    ) {

        UnhookRepository repo =
                UnhookRepository.get();

        repo.putSetting(
                "monitor_enabled",
                enabled ? "1" : "0"
        );

        if (enabled) {

            DetectionService.start(this);

            NightlyWorker.enqueue(this);

            WeeklyReportWorker.enqueue(this);

        } else {

            DetectionService.stop(this);
        }
    }

    // ------------------------------------------------------------------
    // Pulse
    // ------------------------------------------------------------------

    private void answerPulse(
            int label
    ) {

        if (pulseSession == null) {
            return;
        }

        final String packageName =
                pulseSession.pkg;

        final byte[] none =
                new byte[0];

        UnhookApplication
                .get()
                .executors()
                .diskIo()
                .execute(() ->
                        UnhookRepository
                                .get()
                                .recordFeedback(
                                        packageName,
                                        "PULSE",
                                        label,
                                        0.5,
                                        none,
                                        System.currentTimeMillis()
                                )
                );

        pulseCard.setVisibility(
                View.GONE
        );
    }

    // ------------------------------------------------------------------
    // Trigger picker
    // ------------------------------------------------------------------

    private void showTriggerPicker() {

        UnhookApplication
                .get()
                .executors()
                .diskIo()
                .execute(() -> {

                    List<android.content.pm.ResolveInfo> resolveInfos =
                            getPackageManager()
                                    .queryIntentActivities(
                                            new Intent(
                                                    Intent.ACTION_MAIN
                                            ).addCategory(
                                                    Intent.CATEGORY_LAUNCHER
                                            ),
                                            0
                                    );

                    List<String> packages =
                            new ArrayList<>();

                    List<String> labels =
                            new ArrayList<>();

                    List<Boolean> checked =
                            new ArrayList<>();

                    List<AppRuleEntity> rules =
                            UnhookRepository
                                    .get()
                                    .triggerRules();

                    for (
                            android.content.pm.ResolveInfo info
                            : resolveInfos
                    ) {

                        String packageName =
                                info.activityInfo.packageName;

                        if (packageName.equals(
                                getPackageName()
                        )) {
                            continue;
                        }

                        if (packages.contains(
                                packageName
                        )) {
                            continue;
                        }

                        packages.add(
                                packageName
                        );

                        labels.add(
                                String.valueOf(
                                        info.loadLabel(
                                                getPackageManager()
                                        )
                                )
                        );

                        boolean isTrigger =
                                false;

                        for (
                                AppRuleEntity rule
                                : rules
                        ) {

                            if (rule.pkg.equals(
                                    packageName
                            )
                                    && rule.isTrigger) {

                                isTrigger = true;
                                break;
                            }
                        }

                        checked.add(
                                isTrigger
                        );
                    }

                    String[] names =
                            labels.toArray(
                                    new String[0]
                            );

                    boolean[] selected =
                            new boolean[
                                    checked.size()
                            ];

                    for (
                            int i = 0;
                            i < checked.size();
                            i++
                    ) {

                        selected[i] =
                                checked.get(i);
                    }

                    List<String> finalPackages =
                            packages;

                    runOnUiThread(() ->
                            new AlertDialog.Builder(this)

                                    .setTitle(
                                            getString(
                                                    R.string.dash_pick_triggers
                                            )
                                    )

                                    .setMultiChoiceItems(
                                            names,
                                            selected,
                                            (dialog, which, isChecked) ->
                                                    selected[which] =
                                                            isChecked
                                    )

                                    .setPositiveButton(
                                            getString(
                                                    android.R.string.ok
                                            ),
                                            (dialog, which) -> {

                                                final boolean[] finalSelected =
                                                        selected;

                                                UnhookApplication
                                                        .get()
                                                        .executors()
                                                        .diskIo()
                                                        .execute(() -> {

                                                            UnhookRepository repo =
                                                                    UnhookRepository
                                                                            .get();

                                                            for (
                                                                    int i = 0;
                                                                    i < finalPackages.size();
                                                                    i++
                                                            ) {

                                                                repo.setTrigger(
                                                                        finalPackages.get(i),
                                                                        finalSelected[i]
                                                                );
                                                            }
                                                        });
                                            }
                                    )

                                    .setNegativeButton(
                                            getString(
                                                    android.R.string.cancel
                                            ),
                                            null
                                    )

                                    .show()
                    );
                });
    }

    // ------------------------------------------------------------------
    // Erase
    // ------------------------------------------------------------------

    private void confirmErase() {

        new AlertDialog.Builder(this)

                .setTitle(
                        getString(
                                R.string.erase_title
                        )
                )

                .setMessage(
                        getString(
                                R.string.erase_body
                        )
                )

                .setPositiveButton(
                        getString(
                                R.string.erase_next
                        ),
                        (dialog, which) ->

                                new AlertDialog.Builder(this)

                                        .setTitle(
                                                getString(
                                                        R.string.erase_title2
                                                )
                                        )

                                        .setMessage(
                                                getString(
                                                        R.string.erase_body2
                                                )
                                        )

                                        .setPositiveButton(
                                                getString(
                                                        R.string.erase_confirm
                                                ),
                                                (dialog2, which2) -> {

                                                    UnhookApplication
                                                            .get()
                                                            .executors()
                                                            .diskIo()
                                                            .execute(() -> {

                                                                Eraser.erase(
                                                                        getApplicationContext()
                                                                );

                                                                runOnUiThread(() -> {

                                                                    Intent intent =
                                                                            new Intent(
                                                                                    this,
                                                                                    OnboardingActivity.class
                                                                            );

                                                                    intent.setFlags(
                                                                            Intent.FLAG_ACTIVITY_NEW_TASK
                                                                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                                    );

                                                                    startActivity(
                                                                            intent
                                                                    );

                                                                    finishAffinity();
                                                                });
                                                            });
                                                }
                                        )

                                        .setNegativeButton(
                                                getString(
                                                        android.R.string.cancel
                                                ),
                                                null
                                        )

                                        .show()
                )

                .setNegativeButton(
                        getString(
                                android.R.string.cancel
                        ),
                        null
                )

                .show();
    }
}
