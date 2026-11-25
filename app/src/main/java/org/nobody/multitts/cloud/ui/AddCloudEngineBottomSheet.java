package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.Executors;
import org.nobody.multitts.cloud.CloudAiTtsEngine;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 纯原生 Dialog 实现的 Legado MD3 风格底部弹窗（零 XML / 零 Material R.attr 依赖）。
 */
public final class AddCloudEngineBottomSheet {

    public interface OnSavedListener {
        void onSaved(Engine engine);
    }

    private AddCloudEngineBottomSheet() {
    }

    public static void show(Activity activity, Engine targetEngine, OnSavedListener listener) {
        CloudTtsConfig config = CloudTtsConfig.fromEngine(targetEngine);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(activity);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.parseColor("#F6F7F2"));
        float r24 = dp(activity, 24);
        sheetBg.setCornerRadii(new float[]{r24, r24, r24, r24, 0, 0, 0, 0});
        scroll.setBackground(sheetBg);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        container.setPadding(pad, dp(activity, 14), pad, dp(activity, 24));
        scroll.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部拖拽条
        View handle = new View(activity);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#C2C7BA"));
        handleBg.setCornerRadius(dp(activity, 2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(activity, 14);
        container.addView(handle, hLp);

        TextView title = new TextView(activity);
        title.setText("云端 AI 朗读引擎");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 10));
        container.addView(title);

        // 云引擎类型选择
        TextView lblType = new TextView(activity);
        lblType.setText("云引擎类型");
        lblType.setTextSize(12f);
        lblType.setTextColor(Color.parseColor("#5C6057"));
        container.addView(lblType);

        Spinner spinner = new Spinner(activity);
        spinner.setBackground(roundedBox(activity, "#FFFFFF"));
        spinner.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        CloudTtsProviderType[] providers = CloudTtsProviderType.values();
        String[] labels = new String[providers.length];
        int selectedIdx = 0;
        for (int i = 0; i < providers.length; i++) {
            labels[i] = providers[i].displayName;
            if (providers[i] == config.providerType) {
                selectedIdx = i;
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selectedIdx);
        container.addView(spinner, lpMarginTop(activity, 4));

        EditText etName = addInput(activity, container, "引擎名称", config.name, false);
        EditText etBaseUrl = addInput(activity, container, "自定义 Base URL（可选）", config.baseUrl, false);
        EditText etApiKey = addInput(activity, container, "API Key", config.apiKey, true);
        EditText etModel = addInput(activity, container, "合成引擎 / 模型", config.model, false);

        final boolean[] firstSelect = {true};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (firstSelect[0]) {
                    firstSelect[0] = false;
                    return;
                }
                config.providerType = providers[pos];
                etName.setText(config.providerType.displayName);
                etBaseUrl.setText(config.providerType.defaultBaseUrl);
                etModel.setText(config.providerType.defaultModel);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button btnTest = makePillButton(activity, "连接测试", "#DCE6CF", "#2A3B17");
        container.addView(btnTest, lpMarginTop(activity, 14));

        TextView tvResult = new TextView(activity);
        tvResult.setTextSize(12.5f);
        tvResult.setTextColor(Color.parseColor("#5C6057"));
        tvResult.setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), dp(activity, 6));
        container.addView(tvResult);

        Button btnSave = makePillButton(activity, "保存引擎", "#4B662C", "#FFFFFF");
        container.addView(btnSave, lpMarginTop(activity, 6));

        btnTest.setOnClickListener(v -> {
            sync(config, etName, etBaseUrl, etApiKey, etModel);
            btnTest.setEnabled(false);
            tvResult.setText("正在测试连接...");
            Executors.newSingleThreadExecutor().execute(() -> {
                String msg = CloudAiTtsEngine.testConnection(config);
                activity.runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    tvResult.setText(msg);
                });
            });
        });

        btnSave.setOnClickListener(v -> {
            sync(config, etName, etBaseUrl, etApiKey, etModel);
            targetEngine.name = config.name;
            targetEngine.type = config.providerType.engineType;
            targetEngine.url = config.effectiveBaseUrl();
            targetEngine.param = config.toParamJson();
            saveEngineViaReflection(activity, targetEngine);
            if (listener != null) {
                listener.onSaved(targetEngine);
            }
            dialog.dismiss();
        });

        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    private static EditText addInput(Activity act, LinearLayout parent, String label, String val, boolean secret) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setTextColor(Color.parseColor("#5C6057"));
        parent.addView(tv, lpMarginTop(act, 10));

        EditText et = new EditText(act);
        et.setBackground(roundedBox(act, "#EAECE4"));
        et.setPadding(dp(act, 14), dp(act, 11), dp(act, 14), dp(act, 11));
        et.setTextSize(14.5f);
        et.setTextColor(Color.parseColor("#1B1C18"));
        if (secret) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            et.setSingleLine(true);
        }
        et.setText(val);
        parent.addView(et, lpMarginTop(act, 4));
        return et;
    }

    private static Button makePillButton(Activity act, String text, String bgHex, String fgHex) {
        Button btn = new Button(act);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(14.5f);
        btn.setTextColor(Color.parseColor(fgHex));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(act, 24));
        btn.setBackground(bg);
        btn.setPadding(0, dp(act, 12), 0, dp(act, 12));
        return btn;
    }

    private static GradientDrawable roundedBox(Activity act, String hex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(hex));
        gd.setCornerRadius(dp(act, 14));
        return gd;
    }

    private static void sync(CloudTtsConfig config, EditText etName, EditText etBaseUrl,
                             EditText etApiKey, EditText etModel) {
        config.name = etName.getText().toString().trim();
        config.baseUrl = etBaseUrl.getText().toString().trim();
        config.apiKey = etApiKey.getText().toString().trim();
        String m = etModel.getText().toString().trim();
        config.model = m.isEmpty() ? config.providerType.defaultModel : m;
    }

    private static void saveEngineViaReflection(Activity activity, Engine engine) {
        try {
            Class<?> q6c = Class.forName("Q6.c");
            Object db = q6c.getMethod("t").invoke(null);
            Object dao = db.getClass().getMethod("engineDao").invoke(db);
            try {
                dao.getClass().getMethod("insert", Engine.class).invoke(dao, engine);
            } catch (Throwable ignored) {
                dao.getClass().getMethod("update", Engine.class).invoke(dao, engine);
            }
            db.getClass().getMethod("close").invoke(db);
            Toast.makeText(activity, "已保存云引擎：" + engine.name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "保存失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static LinearLayout.LayoutParams lpMarginTop(Activity act, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, topDp);
        return lp;
    }

    private static int dp(Activity act, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics()));
    }
}
