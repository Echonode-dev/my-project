package com.unhook.app.interstitial;

import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.unhook.app.R;
import com.unhook.app.UnhookApplication;
import com.unhook.app.coach.CoachEngine;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.detection.EventHandler;
import com.unhook.app.ml.LogisticRegressionModel;
import com.unhook.app.overlay.InterceptionController;

/**
 * The habit-loop interrupt, full screen. Three variants (EXTRA_MODE):
 *   INTERSTITIAL — 5.5 s breathing, then one contextual question + [Yes]/[Not now]
 *   GRAYSCALE    — same + the whole screen is desaturated (honest preview)
 *   WALL         — limit/schedule reached; message + escape, no breathing
 *
 * "Not now" is always one tap, never punished, and doubles as the strongest
 * ML training label (aborted open = compulsive). No guilt copy, ever.
 */
public class InterstitialActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_REASON = "reason";
    public static final String EXTRA_PROB = "prob";
    public static final String EXTRA_OPENS_TODAY = "opensToday";
    public static final String EXTRA_TYPICAL_HOUR = "typicalHour";
    public static final String EXTRA_FEATURES = "features";

    private String pkg;
    private String mode;
    private String reason;
    private double prob;
    private double[] features = new double[0];
    private boolean answered;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        reason = getIntent().getStringExtra(EXTRA_REASON);
        prob = getIntent().getDoubleExtra(EXTRA_PROB, 0.5);
        double[] f = getIntent().getDoubleArrayExtra(EXTRA_FEATURES);
        if (f != null) {
            features = f;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        hideSystemBars();

        boolean wall = "WALL".equals(mode);
        if ("GRAYSCALE".equals(mode)) {
            getWindow().getDecorView().setColorFilter(grayMatrix());
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(28);
        root.setPadding(pad, dp(48), pad, pad);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.unhook_teal_dark));

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(sp(20), Typeface.BOLD, Color.WHITE);
        title.setText(wall ? wallTitle() : questionText());
        content.addView(title, matchWrap());

        final TextView question = text(sp(15), Typeface.NORMAL, 0xCCFFFFFF);
        question.setText(wall ? wallBody() : getString(R.string.interstitial_breathe_first));
        question.setPadding(0, dp(16), 0, 0);
        content.addView(question, matchWrap());

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(24), 0, 0);

        final TextView yes = button(getString(R.string.interstitial_yes));
        final TextView no = button(getString(wall
                ? R.string.interstitial_need_it : R.string.interstitial_not_now));

        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNotNow();
            }
        });
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onYes();
            }
        });
        buttons.addView(no, wrapWeight());
        buttons.addView(yes, wrapWeight());
        setButtonsEnabled(buttons, false);
        content.addView(buttons, matchWrap());

        if (wall) {
            enableButtons(buttons);
        } else {
            final BreathingView breathe = new BreathingView(this);
            content.addView(breathe, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
            breathe.start(new BreathingView.OnFinished() {
                @Override
                public void onBreathingFinished() {
                    question.setText("");
                    enableButtons(buttons);
                }
            });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        InterceptionController.removeCover(this);
    }

    // ---------------- outcomes ----------------

    private void onYes() {
        answered = true;
        recordLabel(0, "INTERSTITIAL");
        finish(); // returns to the app underneath
    }

    private void onNotNow() {
        answered = true;
        recordLabel(1, "INTERSTITIAL");
        showSuggestion();
    }

    /** WALL escape: honest, allowed, and used as a relapse signal (no guilt). */
    private void onRelapse() {
        answered = true;
        recordLabel(1, "WALL");
        CoachEngine.onRelapse(UnhookApplication.get().executors().diskIo(),
                UnhookRepository.get());
        finish();
    }

    private void showSuggestion() {
        // Replace content with ONE concrete "what to do instead".
        setContentView(buildSuggestionView());
    }

    private View buildSuggestionView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(ContextCompat.getColor(this, R.color.unhook_teal_dark));

        CoachEngine.Suggestion s = CoachEngine.suggest(prob, features);

        TextView nice = text(sp(18), Typeface.BOLD, Color.WHITE);
        nice.setText(getString(R.string.suggestion_nice));
        box.addView(nice, matchWrap());

        TextView what = text(sp(22), Typeface.BOLD, 0xFFFFD8A8);
        what.setText(s.title(this));
        what.setPadding(0, dp(20), 0, dp(8));
        box.addView(what, matchWrap());

        TextView why = text(sp(15), Typeface.NORMAL, 0xCCFFFFFF);
        why.setText(s.body(this));
        box.addView(why, matchWrap());

        TextView home = button(getString(R.string.suggestion_home));
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        box.addView(home, lp);
        return box;
    }

    // ---------------- helpers ----------------

    private void recordLabel(int label, String source) {
        final UnhookApplication app = UnhookApplication.get();
        final String p = pkg;
        final double pr = prob;
        final byte[] blob = UnhookRepository.doublesToBytes(features);
        final int l = label;
        final String src = source;
        app.executors().mlTrainer().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long ts = System.currentTimeMillis();
                    UnhookRepository.get().recordFeedback(p, src, l, pr, blob, ts);
                    LogisticRegressionModel.load(app)
                            .update(new com.unhook.app.ml.FeatureVector(
                                    UnhookRepository.bytesToDoubles(blob)), l);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception ignored) {
        }
        finish();
    }

    private String questionText() {
        int opens = getIntent().getIntExtra(EXTRA_OPENS_TODAY, 0);
        int hour = getIntent().getIntExtra(EXTRA_TYPICAL_HOUR, -1);
        String app = labelFor(pkg);
        if (opens > 0 && hour >= 0) {
            return getString(R.string.interstitial_question_full, app, opens, hourLabel(hour));
        }
        if (opens > 0) {
            return getString(R.string.interstitial_question_opens, app, opens);
        }
        return getString(R.string.interstitial_question_plain, app);
    }

    private String wallTitle() {
        if ("SCHEDULE".equals(reason)) {
            return getString(R.string.wall_title_schedule, labelFor(pkg));
        }
        return getString(R.string.wall_title_limit, labelFor(pkg));
    }

    private String wallBody() {
        if ("SCHEDULE".equals(reason)) {
            return getString(R.string.wall_body_schedule);
        }
        return getString(R.string.wall_body_limit);
    }

    private String labelFor(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(packageName, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return packageName == null ? "this app" : packageName;
        }
    }

    private String hourLabel(int hour) {
        String period = hour < 12 ? "am" : "pm";
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        return h12 + period;
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void setButtonsEnabled(LinearLayout bar, boolean enabled) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            bar.getChildAt(i).setAlpha(enabled ? 1f : 0.4f);
            bar.getChildAt(i).setEnabled(enabled);
        }
    }

    private void enableButtons(LinearLayout bar) {
        setButtonsEnabled(bar, true);
    }

    private TextView text(float sizeSp, int style, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sizeSp);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView button(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(16f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(ContextCompat.getColor(this, R.color.unhook_teal_dark));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.btn_round);
        int pad = dp(14);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private static ColorMatrixColorFilter grayMatrix() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        return new ColorMatrixColorFilter(m);
    }

    @Override
    protected void onDestroy() {
        if (!answered && !"WALL".equals(mode)) {
            // User dismissed via Home/back: no training update — only real
            // signals (answers, bounces) feed the model.
        }
        super.onDestroy();
    }
}
