package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.nobody.multitts.cloud.CloudAiTtsEngine;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;
import org.nobody.multitts.tts.speaker.Speaker;

public class AddCloudEngineBottomSheet {

    public static final int REQ_PICK_MIMO_AUDIO = 9527;
    private static final int MAX_STORED_DATA_URL_LENGTH = 1_500_000;
    private static final String[] MIMO_MODELS = new String[] {
            "mimo-v2.5-tts",
            "mimo-v2.5-tts-voicedesign",
            "mimo-v2.5-tts-voiceclone"
    };

    private static WeakReference<CloudTtsConfig> activeConfigRef = new WeakReference<>(null);
    private static WeakReference<Button> activePickAudioBtnRef = new WeakReference<>(null);
    private static WeakReference<Button> activeClearAudioBtnRef = new WeakReference<>(null);
    private static WeakReference<TextView> activeStatusTvRef = new WeakReference<>(null);

    public interface OnSavedListener {
        void onSaved(Engine engine);
    }

    private AddCloudEngineBottomSheet() {
    }

    public static void handleActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_MIMO_AUDIO || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        CloudTtsConfig cfg = activeConfigRef.get();
        if (uri == null || cfg == null) {
            return;
        }
        try {
            String fileName = queryDisplayName(activity, uri);
            String mime = resolveAudioMime(activity, uri, fileName);
            String prefix = "data:" + mime + ";base64,";
            int maxRawBytes = ((MAX_STORED_DATA_URL_LENGTH - prefix.length()) / 4) * 3;

            byte[] bytes;
            try (InputStream is = activity.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (is == null) {
                    throw new IllegalStateException("无法读取所选音频文件");
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > maxRawBytes) {
                        throw new IllegalStateException("参考音频过大（本机最多保存约 1.5 MB），请选择 10～30 秒的短片段");
                    }
                }
                bytes = bos.toByteArray();
            }
            if (bytes.length == 0) {
                throw new IllegalStateException("所选音频文件为空");
            }
            String dataUrl = prefix + Base64.encodeToString(bytes, Base64.NO_WRAP);
            if (dataUrl.length() > MAX_STORED_DATA_URL_LENGTH) {
                throw new IllegalStateException("参考音频过大（本机最多保存约 1.5 MB），请选择更短的音频");
            }
            cfg.referenceAudioBase64 = dataUrl;
            cfg.referenceAudioName = fileName;

            Button pickBtn = activePickAudioBtnRef.get();
            if (pickBtn != null) {
                int kb = Math.max(1, bytes.length / 1024);
                pickBtn.setText("重新选择参考音频（" + fileName + " · " + kb + " KB）");
            }
            Button clearBtn = activeClearAudioBtnRef.get();
            if (clearBtn != null) {
                clearBtn.setVisibility(View.VISIBLE);
            }
            TextView statusTv = activeStatusTvRef.get();
            if (statusTv != null) {
                statusTv.setVisibility(View.VISIBLE);
                statusTv.setTextColor(Color.parseColor("#4B662C"));
                statusTv.setText("参考音频已载入，可点击「连接测试」或「保存引擎」。");
            }
        } catch (Throwable t) {
            Toast.makeText(activity, "参考音频导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String resolveAudioMime(Activity activity, Uri uri, String displayName) {
        String type = activity.getContentResolver().getType(uri);
        if (type != null) {
            String lower = type.toLowerCase();
            if ("audio/mpeg".equals(lower) || "audio/mp3".equals(lower)) {
                return "audio/mpeg";
            }
            if ("audio/wav".equals(lower) || "audio/x-wav".equals(lower) || "audio/wave".equals(lower)) {
                return "audio/wav";
            }
        }
        String lowerName = displayName != null ? displayName.toLowerCase() : "";
        if (lowerName.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lowerName.endsWith(".wav")) {
            return "audio/wav";
        }
        throw new IllegalStateException("只支持 MP3 或 WAV 参考音频");
    }

    private static String queryDisplayName(Activity activity, Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return !TextUtils.isEmpty(last) ? last : "reference.wav";
    }

    private static String[] presetVoicesFor(CloudTtsProviderType type) {
        switch (type) {
            case OPENAI:
                return new String[] { "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar" };
            case GEMINI:
                return new String[] { "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr", "Leda", "Orus" };
            case MIMO:
                return new String[] { "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean" };
            case ALIBABA:
                return new String[] { "Cherry", "Ethan", "Chelsie", "Serena", "Dylan", "Jada", "Sunny" };
            case VOLCENGINE:
                return new String[] { "BV001_streaming", "BV002_streaming", "BV700_streaming", "BV701_streaming" };
            case AZURE:
                return new String[] { "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-XiaoyiNeural" };
            case POLLY:
                return new String[] { "Zhiyu", "Joanna", "Matthew", "Ivy" };
            default:
                return new String[0];
        }
    }

    public static void show(Activity activity, CloudTtsProviderType initialType, OnSavedListener listener) {
        Engine preset = null;
        if (initialType != null) {
            preset = new Engine();
            preset.type = initialType.engineType;
            preset.name = initialType.displayName;
        }
        show(activity, preset, listener);
    }

    public static void show(Activity activity, Engine existing, OnSavedListener listener) {
        CloudTtsConfig config = CloudTtsConfig.fromEngine(existing);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F7F2"));
        root.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 24));

        View handle = new View(activity);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#C2C7BA"));
        handleBg.setCornerRadius(dp(activity, 2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(activity, 14);
        root.addView(handle, handleLp);

        TextView title = new TextView(activity);
        title.setText("云端 AI 朗读引擎");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 14));
        root.addView(title);

        TextView typeLabel = new TextView(activity);
        typeLabel.setText("云引擎类型");
        typeLabel.setTextSize(12);
        typeLabel.setTextColor(Color.parseColor("#5C6057"));
        root.addView(typeLabel);

        Button typeBtn = makePillButton(activity, config.providerType.displayName, "#FFFFFF", "#1B1C18");
        root.addView(typeBtn, lpMarginTop(activity, 6));

        EditText nameEdit = addInput(activity, root, "引擎名称",
                TextUtils.isEmpty(config.name) ? config.providerType.displayName : config.name, false);
        EditText urlEdit = addInput(activity, root, "自定义 Base URL（可选）", config.baseUrl, false);
        EditText keyEdit = addInput(activity, root, "API Key / Access Key", config.apiKey, true);

        EditText secretKeyEdit = addInput(activity, root, "Secret Access Key（Amazon Polly 必填）", config.secretKey, true);
        View secretKeyWrap = (View) secretKeyEdit.getParent();

        EditText appIdEdit = addInput(activity, root, "App ID（火山引擎必填）", config.appId, false);
        View appIdWrap = (View) appIdEdit.getParent();

        EditText regionEdit = addInput(activity, root, "区域 Region（如 eastasia / us-east-1）", config.region, false);
        View regionWrap = (View) regionEdit.getParent();

        EditText modelEdit = addInput(activity, root, "合成引擎 / 模型", config.model, false);
        View modelContainer = (View) modelEdit.getParent();

        Button mimoModelBtn = makePillButton(activity, "合成引擎：" + config.model, "#FFFFFF", "#1B1C18");
        root.addView(mimoModelBtn, lpMarginTop(activity, 12));

        EditText voiceEdit = addInput(activity, root, "音色 / Voice ID（可选，Fish Audio 填 reference_id）", config.voice, false);
        View voiceWrap = (View) voiceEdit.getParent();

        Button quickVoiceBtn = makePillButton(activity, "快捷选择预设音色", "#EAECE4", "#2A3B17");
        root.addView(quickVoiceBtn, lpMarginTop(activity, 8));

        EditText instructionsEdit = addInput(activity, root, "音色描述 / 情感指令（可选，MiMo 音色设计必填）", config.instructions, false);
        View instructionsWrap = (View) instructionsEdit.getParent();

        Button pickAudioBtn = makePillButton(activity,
                !TextUtils.isEmpty(config.referenceAudioBase64)
                        ? ("重新选择参考音频（" + (TextUtils.isEmpty(config.referenceAudioName) ? "已载入" : config.referenceAudioName) + "）")
                        : "选择 MP3 / WAV 参考音频",
                "#FFFFFF", "#1B1C18");
        root.addView(pickAudioBtn, lpMarginTop(activity, 10));

        Button clearAudioBtn = makePillButton(activity, "清除参考音频", "#EAECE4", "#3E4239");
        root.addView(clearAudioBtn, lpMarginTop(activity, 8));

        TextView mimoCloneHint = new TextView(activity);
        mimoCloneHint.setText("从手机文件中选择 MP3 或 WAV（约 10～30 秒，不超过 1.5 MB）。应用会在本机编码为 Data URL 并保存在引擎配置中；克隆模型使用非流式请求。");
        mimoCloneHint.setTextSize(12);
        mimoCloneHint.setTextColor(Color.parseColor("#5C6057"));
        mimoCloneHint.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 4));
        root.addView(mimoCloneHint);

        Button testBtn = makePillButton(activity, "连接测试", "#DCE6CF", "#2A3B17");
        root.addView(testBtn, lpMarginTop(activity, 16));

        TextView statusTv = new TextView(activity);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), 0);
        statusTv.setVisibility(View.GONE);
        root.addView(statusTv);

        Button saveBtn = makePillButton(activity, "保存引擎并生成音色", "#4B662C", "#FFFFFF");
        root.addView(saveBtn, lpMarginTop(activity, 14));

        activeConfigRef = new WeakReference<>(config);
        activePickAudioBtnRef = new WeakReference<>(pickAudioBtn);
        activeClearAudioBtnRef = new WeakReference<>(clearAudioBtn);
        activeStatusTvRef = new WeakReference<>(statusTv);

        Runnable refreshDynamicViews = () -> {
            CloudTtsProviderType pt = config.providerType;
            boolean isMimo = pt == CloudTtsProviderType.MIMO;
            boolean isPolly = pt == CloudTtsProviderType.POLLY;
            boolean isAzure = pt == CloudTtsProviderType.AZURE;
            boolean isVolc = pt == CloudTtsProviderType.VOLCENGINE;

            secretKeyWrap.setVisibility(isPolly ? View.VISIBLE : View.GONE);
            appIdWrap.setVisibility(isVolc ? View.VISIBLE : View.GONE);
            regionWrap.setVisibility((isPolly || isAzure) ? View.VISIBLE : View.GONE);

            modelContainer.setVisibility(isMimo ? View.GONE : View.VISIBLE);
            mimoModelBtn.setVisibility(isMimo ? View.VISIBLE : View.GONE);

            boolean isClone = false;
            boolean isDesign = false;
            if (isMimo) {
                String m = TextUtils.isEmpty(config.model) ? CloudTtsProviderType.MIMO.defaultModel : config.model.trim();
                config.model = m;
                modelEdit.setText(m);
                mimoModelBtn.setText("合成引擎：" + m);
                isClone = "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(m);
                isDesign = "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(m);
            }

            voiceWrap.setVisibility((isClone || isDesign) ? View.GONE : View.VISIBLE);
            quickVoiceBtn.setVisibility((!isClone && !isDesign && presetVoicesFor(pt).length > 0) ? View.VISIBLE : View.GONE);
            instructionsWrap.setVisibility((pt.usesInstruction || isMimo) ? View.VISIBLE : View.GONE);
            pickAudioBtn.setVisibility(isClone ? View.VISIBLE : View.GONE);
            clearAudioBtn.setVisibility(isClone && !TextUtils.isEmpty(config.referenceAudioBase64) ? View.VISIBLE : View.GONE);
            mimoCloneHint.setVisibility(isClone ? View.VISIBLE : View.GONE);
        };
        refreshDynamicViews.run();

        typeBtn.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(activity, typeBtn);
            CloudTtsProviderType[] values = CloudTtsProviderType.values();
            for (int i = 0; i < values.length; i++) {
                menu.getMenu().add(0, i, i, values[i].displayName);
            }
            menu.setOnMenuItemClickListener(item -> {
                CloudTtsProviderType selected = values[item.getItemId()];
                config.providerType = selected;
                config.voice = selected.defaultVoice;
                config.model = selected.defaultModel;
                typeBtn.setText(selected.displayName);
                nameEdit.setText(selected.displayName);
                urlEdit.setText("");
                modelEdit.setText(selected.defaultModel);
                voiceEdit.setText(selected.defaultVoice);
                refreshDynamicViews.run();
                return true;
            });
            menu.show();
        });

        mimoModelBtn.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(activity, mimoModelBtn);
            for (int i = 0; i < MIMO_MODELS.length; i++) {
                menu.getMenu().add(0, i, i, MIMO_MODELS[i]);
            }
            menu.setOnMenuItemClickListener(item -> {
                config.model = MIMO_MODELS[item.getItemId()];
                refreshDynamicViews.run();
                return true;
            });
            menu.show();
        });

        quickVoiceBtn.setOnClickListener(v -> {
            String[] presets = presetVoicesFor(config.providerType);
            if (presets.length == 0) {
                return;
            }
            PopupMenu menu = new PopupMenu(activity, quickVoiceBtn);
            for (int i = 0; i < presets.length; i++) {
                menu.getMenu().add(0, i, i, presets[i]);
            }
            menu.setOnMenuItemClickListener(item -> {
                String chosen = presets[item.getItemId()];
                config.voice = chosen;
                voiceEdit.setText(chosen);
                return true;
            });
            menu.show();
        });

        pickAudioBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave"
            });
            activity.startActivityForResult(intent, REQ_PICK_MIMO_AUDIO);
        });

        clearAudioBtn.setOnClickListener(v -> {
            config.referenceAudioBase64 = "";
            config.referenceAudioName = "";
            pickAudioBtn.setText("选择 MP3 / WAV 参考音频");
            clearAudioBtn.setVisibility(View.GONE);
            Toast.makeText(activity, "已清除参考音频", Toast.LENGTH_SHORT).show();
        });

        testBtn.setOnClickListener(v -> {
            sync(config, nameEdit, urlEdit, keyEdit, secretKeyEdit, appIdEdit, regionEdit, modelEdit, voiceEdit, instructionsEdit);
            statusTv.setVisibility(View.VISIBLE);
            statusTv.setTextColor(Color.parseColor("#5C6057"));
            statusTv.setText("正在测试连接...");
            testBtn.setEnabled(false);
            new Thread(() -> {
                String res = CloudAiTtsEngine.testConnection(config);
                activity.runOnUiThread(() -> {
                    testBtn.setEnabled(true);
                    statusTv.setTextColor(res.startsWith("连接成功")
                            ? Color.parseColor("#4B662C")
                            : Color.parseColor("#BA1A1A"));
                    statusTv.setText(res);
                });
            }).start();
        });

        saveBtn.setOnClickListener(v -> {
            sync(config, nameEdit, urlEdit, keyEdit, secretKeyEdit, appIdEdit, regionEdit, modelEdit, voiceEdit, instructionsEdit);
            boolean isEdit = existing != null && !TextUtils.isEmpty(existing.code);
            Engine target = isEdit ? existing : new Engine();
            if (TextUtils.isEmpty(target.code)) {
                target.code = "cloud_" + UUID.randomUUID().toString().substring(0, 8);
            }
            target.name = TextUtils.isEmpty(config.name) ? config.providerType.displayName : config.name;
            target.type = config.providerType.engineType;
            target.url = config.effectiveBaseUrl();
            target.param = config.toParamJson();
            target.header = "";

            saveEngineViaReflection(activity, target);
            int speakerCount = ensureDefaultSpeakerViaReflection(activity, target, config);
            try {
                Class.forName("K6.d").getMethod("i", android.content.Context.class).invoke(null, activity.getApplicationContext());
            } catch (Throwable ignored) {}
            try {
                Class.forName("Z6.o").getMethod("d").invoke(null);
            } catch (Throwable t) {
                Toast.makeText(activity, "顶栏刷新异常：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
            try {
                Class.forName("R6.i").getMethod("e").invoke(null);
            } catch (Throwable ignored) {}
            if (speakerCount > 0) {
                Toast.makeText(activity, "已保存云引擎（" + target.name + "，共 " + speakerCount + " 个）", Toast.LENGTH_SHORT).show();
            }
            if (listener != null) {
                listener.onSaved(target);
            }
            dialog.dismiss();
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        dialog.setContentView(scroll);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.setBackgroundDrawable(roundedBox(activity, "#F6F7F2"));
        }
        dialog.show();
    }

    private static void sync(
            CloudTtsConfig config,
            EditText nameEdit,
            EditText urlEdit,
            EditText keyEdit,
            EditText secretKeyEdit,
            EditText appIdEdit,
            EditText regionEdit,
            EditText modelEdit,
            EditText voiceEdit,
            EditText instructionsEdit) {
        config.name = nameEdit.getText().toString().trim();
        config.baseUrl = urlEdit.getText().toString().trim();
        config.apiKey = keyEdit.getText().toString().trim();
        config.secretKey = secretKeyEdit.getText().toString().trim();
        config.appId = appIdEdit.getText().toString().trim();
        config.region = regionEdit.getText().toString().trim();
        config.instructions = instructionsEdit.getText().toString().trim();
        if (config.providerType != CloudTtsProviderType.MIMO) {
            String m = modelEdit.getText().toString().trim();
            config.model = TextUtils.isEmpty(m) ? config.providerType.defaultModel : m;
        }
        String v = voiceEdit.getText().toString().trim();
        config.voice = TextUtils.isEmpty(v) ? config.providerType.defaultVoice : v;
    }

    private static void saveEngineViaReflection(Activity activity, Engine target) {
        try {
            Class<?> helper = Class.forName("K6.d");
            Method saveMethod = helper.getMethod("l", Engine.class);
            saveMethod.invoke(null, target);
        } catch (Throwable t) {
            Toast.makeText(activity, "保存失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("unchecked")
    private static int ensureDefaultSpeakerViaReflection(Activity activity, Engine target, CloudTtsConfig config) {
        try {
            Speaker sp = new Speaker();
            sp.code = target.code;
            sp.group = target.code;
            sp.name = target.name;
            sp.locale = "zh-CN";
            sp.desc = "";
            sp.note = "";
            sp.extendUI = "";
            sp.avatar = "";
            sp.sampleRate = 24000;
            sp.type = 1;
            sp.gender = 0;
            sp.speed = 1.0f;
            sp.pitch = 1.0f;
            sp.volume = 1.0f;
            boolean isCloneOrDesign = config.providerType == CloudTtsProviderType.MIMO
                    && ("mimo-v2.5-tts-voiceclone".equalsIgnoreCase(config.model)
                        || "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(config.model));
            sp.param = isCloneOrDesign ? "" : (config.voice != null ? config.voice : "");

            Class<?> qc = Class.forName("Q6.c");
            try {
                Object computedId = qc.getMethod("B", Speaker.class).invoke(null, sp);
                if (computedId instanceof String && !TextUtils.isEmpty((String) computedId)) {
                    sp.id = (String) computedId;
                }
            } catch (Throwable ignored) {
            }
            if (TextUtils.isEmpty(sp.id)) {
                sp.id = "sp_" + target.code;
            }

            List<Speaker> list = new ArrayList<>();
            try {
                Object existingList = qc.getMethod("z", String.class).invoke(null, target.code);
                if (existingList instanceof List<?>) {
                    for (Object o : (List<?>) existingList) {
                        if (o instanceof Speaker) {
                            Speaker oldSp = (Speaker) o;
                            if (!sp.id.equals(oldSp.id)) {
                                list.add(oldSp);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            list.add(sp);

            // Q6.c.a0 会先清理该 group 旧数据再执行 Room @Insert 插入整组音色并触发 Q6.c.Z()
            qc.getMethod("a0", String.class, List.class).invoke(null, target.code, list);

            try {
                Class.forName("K6.d").getMethod("i", android.content.Context.class)
                        .invoke(null, activity.getApplicationContext());
            } catch (Throwable ignored) {
            }
            try {
                qc.getMethod("K", android.content.Context.class)
                        .invoke(null, activity.getApplicationContext());
            } catch (Throwable ignored) {
            }

            Object verified = qc.getMethod("z", String.class).invoke(null, target.code);
            int count = (verified instanceof List<?>) ? ((List<?>) verified).size() : list.size();
            if (count <= 0) {
                Toast.makeText(activity, "音色未写入数据库，请检查日志", Toast.LENGTH_LONG).show();
            }
            return count;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Toast.makeText(activity, "音色生成失败：" + cause.getMessage(), Toast.LENGTH_LONG).show();
            return 0;
        }
    }

    private static EditText addInput(Activity activity, LinearLayout parent, String label, String val, boolean password) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(Color.parseColor("#5C6057"));
        wrap.addView(tv);

        EditText et = new EditText(activity);
        et.setText(val != null ? val : "");
        et.setTextSize(15);
        et.setTextColor(Color.parseColor("#1B1C18"));
        et.setBackground(roundedBox(activity, "#EAECE4"));
        et.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        wrap.addView(et, lpMarginTop(activity, 6));
        parent.addView(wrap, lpMarginTop(activity, 12));
        return et;
    }

    private static Button makePillButton(Activity activity, String text, String bgHex, String fgHex) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.parseColor(fgHex));
        btn.setBackground(roundedBox(activity, bgHex));
        btn.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        return btn;
    }

    private static GradientDrawable roundedBox(Activity activity, String hex) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(hex));
        d.setCornerRadius(dp(activity, 18));
        return d;
    }

    private static LinearLayout.LayoutParams lpMarginTop(Activity activity, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, topDp);
        return lp;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
