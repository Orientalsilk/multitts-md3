package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.concurrent.Executors;
import org.nobody.multitts.cloud.CloudAiTtsEngine;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 纯代码构建的 Legado MD3 风格「添加/编辑云引擎」BottomSheetDialog（无 XML 依赖）。
 */
public final class AddCloudEngineBottomSheet {

    public interface OnSavedListener {
        void onSaved(Engine engine);
    }

    private AddCloudEngineBottomSheet() {
    }

    public static void show(Activity activity, Engine targetEngine, OnSavedListener listener) {
        CloudTtsConfig config = CloudTtsConfig.fromEngine(targetEngine);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#F6F7F2"));
        int pad = dp(activity, 20);
        container.setPadding(pad, dp(activity, 16), pad, dp(activity, 24));
        scroll.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText("云端 AI 朗读引擎");
        title.setTextSize(18f);
        title.setTextColor(Color.parseColor("#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 12));
        container.addView(title);

        // 引擎类型下拉
        TextInputLayout tilProvider = new TextInputLayout(activity, null,
                com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
        tilProvider.setHint("云引擎类型");
        AutoCompleteTextView dropdown = new AutoCompleteTextView(tilProvider.getContext());
        dropdown.setInputType(InputType.TYPE_NULL);
        CloudTtsProviderType[] providers = CloudTtsProviderType.values();
        String[] labels = new String[providers.length];
        for (int i = 0; i < providers.length; i++) {
            labels[i] = providers[i].displayName;
        }
        dropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_dropdown_item_1line, labels));
        dropdown.setText(config.providerType.displayName, false);
        tilProvider.addView(dropdown);
        container.addView(tilProvider, lpMarginTop(activity, 4));

        TextInputEditText etName = addInput(activity, container, "引擎名称", config.name, false);
        TextInputEditText etBaseUrl = addInput(activity, container, "自定义 Base URL（可选）", config.baseUrl, false);
        TextInputEditText etApiKey = addInput(activity, container, "API Key", config.apiKey, true);
        TextInputEditText etModel = addInput(activity, container, "合成引擎 / 模型", config.model, false);

        dropdown.setOnItemClickListener((p, v, pos, id) -> {
            config.providerType = providers[pos];
            etName.setText(config.providerType.displayName);
            etBaseUrl.setText(config.providerType.defaultBaseUrl);
            etModel.setText(config.providerType.defaultModel);
        });

MaterialButton btnTest = new MaterialButton(activity);
btnTest.setBackgroundColor(Color.parseColor("#DCE6CF"));
btnTest.setTextColor(Color.parseColor("#2A3B17"));
        btnTest.setText("连接测试");
        container.addView(btnTest, lpMarginTop(activity, 14));

        TextView tvResult = new TextView(activity);
        tvResult.setTextSize(12.5f);
        tvResult.setTextColor(Color.parseColor("#5C6057"));
        tvResult.setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), dp(activity, 6));
        container.addView(tvResult);

        MaterialButton btnSave = new MaterialButton(activity);
        btnSave.setText("保存引擎");
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
        dialog.show();
    }

    private static TextInputEditText addInput(Activity act, LinearLayout parent, String hint, String val, boolean secret) {
        TextInputLayout til = new TextInputLayout(act, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(til.getContext());
        if (secret) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        et.setText(val);
        til.addView(et);
        parent.addView(til, lpMarginTop(act, 10));
        return et;
    }

    private static void sync(CloudTtsConfig config, TextInputEditText etName, TextInputEditText etBaseUrl,
                             TextInputEditText etApiKey, TextInputEditText etModel) {
        config.name = etName.getText() != null ? etName.getText().toString().trim() : "";
        config.baseUrl = etBaseUrl.getText() != null ? etBaseUrl.getText().toString().trim() : "";
        config.apiKey = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
        config.model = etModel.getText() != null ? etModel.getText().toString().trim() : config.providerType.defaultModel;
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
