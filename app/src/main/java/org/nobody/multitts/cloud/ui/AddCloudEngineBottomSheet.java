package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
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

    private static String providerGuideFor(CloudTtsProviderType pt) {
        if (pt == null) return "";
        switch (pt) {
            case OPENAI:
                return "官方接口 /v1/audio/speech；gpt-4o-mini-tts 支持「音色指令」，Base URL 留空即用官方地址。";
            case GEMINI:
                return "填 Google AI Studio API Key；内置 30 个官方预设音色，支持「导演指令」控制语气。";
            case MIMO:
                return "填小米 MiMo API Key；可在下方切换标准合成、音色设计或声音克隆（支持单音色独立音频）。";
            case FISHAUDIO:
                return "填 Fish Audio Bearer Token；默认 s2.1-pro-free，音色填 32 位 reference_id。";
            case AZURE:
                return "填 Azure 语音订阅密钥与区域 Region（如 eastasia），Base URL 留空自动拼接。";
            case ALIBABA:
                return "填阿里云百炼 DashScope API Key；HTTP 接口支持 Qwen-TTS 系列，instruct 模型支持指令。";
            case POLLY:
                return "填 AWS Access Key、Secret Key 与 Region（如 us-east-1），支持 neural / generative。";
            case VOLCENGINE:
                return "填火山引擎 Access Token 与 App ID；默认集群 volcano_tts，支持情感 style 字段。";
            default:
                return "";
        }
    }

    private static String voiceMenuLabel(CloudTtsProviderType pt, String voice) {
        if (pt != CloudTtsProviderType.GEMINI || voice == null) return voice;
        switch (voice) {
            case "Zephyr": return "Zephyr · 明亮";
            case "Puck": return "Puck · 轻快";
            case "Charon": return "Charon · 稳重播报";
            case "Kore": return "Kore · 坚定";
            case "Fenrir": return "Fenrir · 活力";
            case "Leda": return "Leda · 年轻";
            case "Orus": return "Orus · 沉稳";
            case "Aoede": return "Aoede · 自然舒展";
            case "Callirrhoe": return "Callirrhoe · 随和";
            case "Autonoe": return "Autonoe · 明朗";
            case "Enceladus": return "Enceladus · 气声";
            case "Iapetus": return "Iapetus · 清晰";
            case "Umbriel": return "Umbriel · 轻松";
            case "Algieba": return "Algieba · 顺滑";
            case "Despina": return "Despina · 柔和顺滑";
            case "Erinome": return "Erinome · 清澈";
            case "Algenib": return "Algenib · 沙哑质感";
            case "Rasalgethi": return "Rasalgethi · 资讯解说";
            case "Laomedeia": return "Laomedeia · 昂扬";
            case "Achernar": return "Achernar · 轻柔";
            case "Alnilam": return "Alnilam · 笃定";
            case "Schedar": return "Schedar · 均衡";
            case "Gacrux": return "Gacrux · 成熟";
            case "Pulcherrima": return "Pulcherrima · 前倾饱满";
            case "Achird": return "Achird · 亲切";
            case "Zubenelgenubi": return "Zubenelgenubi · 休闲";
            case "Vindemiatrix": return "Vindemiatrix · 温和";
            case "Sadachbia": return "Sadachbia · 活泼";
            case "Sadaltager": return "Sadaltager · 博学";
            case "Sulafat": return "Sulafat · 温暖";
            default: return voice;
        }
    }

    private static String[] presetModelsFor(CloudTtsProviderType pt) {
        if (pt == null) {
            return new String[0];
        }
        switch (pt) {
            case OPENAI:
                return new String[] { "gpt-4o-mini-tts", "gpt-4o-mini-tts-2025-12-15", "tts-1", "tts-1-hd" };
            case GEMINI:
                return new String[] {
                        "gemini-3.8-flash-tts",
                        "gemini-3.8-flash-lite-tts",
                        "gemini-3.1-flash-tts-preview",
                        "gemini-2.5-flash-preview-tts",
                        "gemini-2.5-pro-preview-tts"
                };
            case MIMO:
                return MIMO_MODELS;
            case FISHAUDIO:
                return new String[] { "s2.1-pro-free", "s2.1", "s1", "speech-1.6", "speech-1.5" };
            case AZURE:
                return new String[] { "neural", "hd", "multilingual" };
            case ALIBABA:
                return new String[] { "qwen3-tts-flash", "qwen3-tts-instruct-flash", "qwen-audio-3.1-tts-flash", "qwen-audio-3.1-tts-plus" };
            case POLLY:
                return new String[] { "neural", "generative", "long-form", "standard" };
            case VOLCENGINE:
                return new String[] { "volcano_tts", "volcano_mega" };
            default:
                return new String[0];
        }
    }

    private static String[] presetVoicesFor(CloudTtsProviderType type) {
        switch (type) {
            case OPENAI:
                return new String[] { "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar" };
            case GEMINI:
                return new String[] {
                        "Zephyr", "Puck", "Charon", "Kore", "Fenrir",
                        "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
                        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina",
                        "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
                        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
                        "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
                };
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
        boolean night = isNight(activity);
        root.setBackgroundColor(Color.parseColor(night ? "#131511" : "#F6F7F2"));
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
        title.setTextColor(Color.parseColor(isNight(activity) ? "#E4E6DE" : "#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 14));
        root.addView(title);

        TextView typeLabel = new TextView(activity);
        typeLabel.setText("云引擎类型");
        typeLabel.setTextSize(12);
        typeLabel.setTextColor(Color.parseColor("#5C6057"));
        root.addView(typeLabel);

        Button typeBtn = makePillButton(activity, config.providerType.displayName,
                night ? "#1E211B" : "#FFFFFF", night ? "#E4E6DE" : "#1B1C18");
        root.addView(typeBtn, lpMarginTop(activity, 6));

        TextView guideTv = new TextView(activity);
        guideTv.setTextSize(12);
        guideTv.setTextColor(Color.parseColor(night ? "#DCE6CF" : "#2A3B17"));
        guideTv.setBackground(roundedBox(activity, night ? "#2A381D" : "#E6ECD9"));
        guideTv.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        root.addView(guideTv, lpMarginTop(activity, 10));

        EditText nameEdit = addInput(activity, root, "引擎名称",
                TextUtils.isEmpty(config.name) ? config.providerType.displayName : config.name, false);
        EditText urlEdit = addInput(activity, root, "自定义 Base URL（留空使用官方默认）", config.baseUrl, false);
        urlEdit.setHint(config.providerType.defaultBaseUrl);
        EditText keyEdit = addInput(activity, root, "API Key / Access Key", config.apiKey, true);
        TextView toggleKeyBtn = new TextView(activity);
        toggleKeyBtn.setText("显示密钥");
        toggleKeyBtn.setTextSize(12);
        toggleKeyBtn.setTextColor(Color.parseColor(night ? "#B4D08F" : "#4B662C"));
        toggleKeyBtn.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), 0);
        final boolean[] keyVisible = new boolean[] { false };
        toggleKeyBtn.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            keyEdit.setInputType(keyVisible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            keyEdit.setSelection(keyEdit.getText().length());
            toggleKeyBtn.setText(keyVisible[0] ? "隐藏密钥" : "显示密钥");
        });
        ((LinearLayout) keyEdit.getParent()).addView(toggleKeyBtn);

        EditText secretKeyEdit = addInput(activity, root, "Secret Access Key（Amazon Polly 必填）", config.secretKey, true);
        View secretKeyWrap = (View) secretKeyEdit.getParent();

        EditText appIdEdit = addInput(activity, root, "App ID（火山引擎必填）", config.appId, false);
        View appIdWrap = (View) appIdEdit.getParent();

        EditText regionEdit = addInput(activity, root, "区域 Region（如 eastasia / us-east-1）", config.region, false);
        View regionWrap = (View) regionEdit.getParent();

        Button modelSelectBtn = makePillButton(activity, "合成引擎 / 模型：" + config.model,
                night ? "#1E211B" : "#FFFFFF", night ? "#E4E6DE" : "#1B1C18");
        root.addView(modelSelectBtn, lpMarginTop(activity, 12));

        EditText modelEdit = addInput(activity, root, "自定义模型 ID", config.model, false);
        View modelContainer = (View) modelEdit.getParent();
        final boolean[] customModelMode = new boolean[] { false };

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

        TextView statusTv = new TextView(activity);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), 0);
        statusTv.setVisibility(View.GONE);
        root.addView(statusTv);

        LinearLayout actionBar = new LinearLayout(activity);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        Button testBtn = makePillButton(activity, "连接测试",
                night ? "#344B1E" : "#DCE6CF", night ? "#DCE6CF" : "#2A3B17");
        Button saveBtn = makePillButton(activity, "保存引擎",
                night ? "#B4D08F" : "#4B662C", night ? "#1F310B" : "#FFFFFF");
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.rightMargin = dp(activity, 10);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        actionBar.addView(testBtn, b1Lp);
        actionBar.addView(saveBtn, b2Lp);
        root.addView(actionBar, lpMarginTop(activity, 16));

        activeConfigRef = new WeakReference<>(config);
        activePickAudioBtnRef = new WeakReference<>(pickAudioBtn);
        activeClearAudioBtnRef = new WeakReference<>(clearAudioBtn);
        activeStatusTvRef = new WeakReference<>(statusTv);

        Runnable refreshDynamicViews = () -> {
            CloudTtsProviderType pt = config.providerType;
            guideTv.setText(providerGuideFor(pt));
            urlEdit.setHint(pt.defaultBaseUrl);
            boolean isMimo = pt == CloudTtsProviderType.MIMO;
            boolean isPolly = pt == CloudTtsProviderType.POLLY;
            boolean isAzure = pt == CloudTtsProviderType.AZURE;
            boolean isVolc = pt == CloudTtsProviderType.VOLCENGINE;

            secretKeyWrap.setVisibility(isPolly ? View.VISIBLE : View.GONE);
            appIdWrap.setVisibility(isVolc ? View.VISIBLE : View.GONE);
            regionWrap.setVisibility((isPolly || isAzure) ? View.VISIBLE : View.GONE);

            String[] modelChoices = presetModelsFor(pt);
            boolean hasPresets = modelChoices.length > 0;
            modelSelectBtn.setVisibility(hasPresets ? View.VISIBLE : View.GONE);
            modelContainer.setVisibility((!hasPresets || customModelMode[0]) ? View.VISIBLE : View.GONE);

            String curModel = TextUtils.isEmpty(config.model) ? pt.defaultModel : config.model.trim();
            config.model = curModel;
            if (!customModelMode[0]) {
                modelEdit.setText(curModel);
            }
            modelSelectBtn.setText("合成引擎 / 模型：" + curModel);

            boolean isClone = isMimo && "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(curModel);
            boolean isDesign = isMimo && "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(curModel);

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

        modelSelectBtn.setOnClickListener(v -> {
            String[] choices = presetModelsFor(config.providerType);
            PopupMenu menu = new PopupMenu(activity, modelSelectBtn);
            for (int i = 0; i < choices.length; i++) {
                menu.getMenu().add(0, i, i, choices[i]);
            }
            menu.getMenu().add(0, choices.length, choices.length, "自定义模型 ID...");
            menu.setOnMenuItemClickListener(item -> {
                int idx = item.getItemId();
                if (idx < choices.length) {
                    customModelMode[0] = false;
                    config.model = choices[idx];
                } else {
                    customModelMode[0] = true;
                }
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
                menu.getMenu().add(0, i, i, voiceMenuLabel(config.providerType, presets[i]));
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
            if (target.note == null) target.note = "";

            if (!saveEngineViaReflection(activity, target)) {
                return;
            }
            int speakerCount = ensureDefaultSpeakerViaReflection(activity, target, config);
            try {
                Class.forName("Q6.c").getMethod("Z").invoke(null);
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
            win.setBackgroundDrawable(sheetBg(activity));
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
        String m = modelEdit.getText().toString().trim();
        if (!TextUtils.isEmpty(m)) {
            config.model = m;
        } else if (TextUtils.isEmpty(config.model)) {
            config.model = config.providerType.defaultModel;
        }
        String v = voiceEdit.getText().toString().trim();
        config.voice = TextUtils.isEmpty(v) ? config.providerType.defaultVoice : v;
    }

    private static boolean saveEngineViaReflection(Activity activity, Engine target) {
        Throwable firstErr = null;
        try {
            Class<?> helper = Class.forName("K6.d");
            Method saveMethod = helper.getMethod("l", Engine.class);
            saveMethod.invoke(null, target);
            if (engineExistsInDb(target.code)) {
                return true;
            }
        } catch (Throwable t) {
            firstErr = t.getCause() != null ? t.getCause() : t;
        }

        // Fallback: write directly via AppDatabase.engineDao() + B6.f (mode 1 / 2)
        try {
            Class<?> helper = Class.forName("K6.d");
            Class<?> bfClass = Class.forName("B6.f");
            Class<?> rqClass = Class.forName("r2.q");
            Class<?> roomC = Class.forName("androidx.room.C");
            Class<?> fnIf = Class.forName("A5.l");

            for (int mode = 1; mode <= 2; mode++) {
                Object db = helper.getMethod("a").invoke(null);
                try {
                    Object dao = db.getClass().getMethod("engineDao").invoke(db);
                    Object fn = bfClass.getConstructor(dao.getClass(), Engine.class, int.class)
                            .newInstance(dao, target, mode);
                    rqClass.getMethod("C", roomC, boolean.class, boolean.class, fnIf)
                            .invoke(null, db, false, true, fn);
                } finally {
                    try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
                }
                if (engineExistsInDb(target.code)) {
                    return true;
                }
            }
        } catch (Throwable t2) {
            if (firstErr == null) {
                firstErr = t2.getCause() != null ? t2.getCause() : t2;
            }
        }

        String errMsg = firstErr != null ? firstErr.getClass().getSimpleName() + ": " + firstErr.getMessage() : "未写入数据库";
        Toast.makeText(activity, "引擎保存失败：" + errMsg, Toast.LENGTH_LONG).show();
        return false;
    }

    private static boolean engineExistsInDb(String code) {
        if (TextUtils.isEmpty(code)) return false;
        try {
            Class<?> helper = Class.forName("K6.d");
            Object db = helper.getMethod("a").invoke(null);
            try {
                Object dao = db.getClass().getMethod("engineDao").invoke(db);
                Object found = dao.getClass().getMethod("e", String.class).invoke(dao, code);
                if (found != null) return true;
            } finally {
                try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private static int ensureDefaultSpeakerViaReflection(Activity activity, Engine target, CloudTtsConfig config) {
        try {
            boolean isCloneOrDesign = config.providerType == CloudTtsProviderType.MIMO
                    && ("mimo-v2.5-tts-voiceclone".equalsIgnoreCase(config.model)
                        || "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(config.model));
            Speaker sp = new Speaker();
            if (isCloneOrDesign && !TextUtils.isEmpty(config.referenceAudioName)) {
                sp.code = config.referenceAudioName;
            } else if (!TextUtils.isEmpty(config.voice)) {
                sp.code = config.voice;
            } else {
                sp.code = target.code + "_default";
            }
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
            if ("mimo-v2.5-tts-voiceclone".equalsIgnoreCase(config.model)
                    && config.providerType == CloudTtsProviderType.MIMO) {
                sp.param = config.referenceAudioBase64 != null ? config.referenceAudioBase64 : "";
            } else if ("mimo-v2.5-tts-voicedesign".equalsIgnoreCase(config.model)
                    && config.providerType == CloudTtsProviderType.MIMO) {
                sp.param = config.instructions != null ? config.instructions : "";
            } else {
                sp.param = config.voice != null ? config.voice : "";
            }

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
                            list.add((Speaker) o);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!list.isEmpty()) {
                return list.size();
            }
            list.add(sp);

            // Q6.c.a0 会先清理该 group 旧数据再执行 Room @Insert 插入整组音色并触发 Q6.c.Z()
            qc.getMethod("a0", String.class, List.class).invoke(null, target.code, list);

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


    public static void showCustomHttpDialog(Activity activity, Engine existing, OnSavedListener listener) {
        boolean isEdit = existing != null && !TextUtils.isEmpty(existing.code);
        boolean night = isNight(activity);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(night ? "#131511" : "#F6F7F2"));
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
        title.setText(isEdit ? "编辑 HTTP 协议 TTS" : "添加自定义 HTTP 协议 TTS");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor(night ? "#E4E6DE" : "#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 10));
        root.addView(title);

        TextView guideTv = new TextView(activity);
        guideTv.setText("支持任意自定义 HTTP 接口或 MultiTTS / 开源阅读 JS 规则，保存后自动走原生 HTTP 引擎解析。");
        guideTv.setTextSize(12);
        guideTv.setTextColor(Color.parseColor(night ? "#DCE6CF" : "#2A3B17"));
        guideTv.setBackground(roundedBox(activity, night ? "#2A381D" : "#E6ECD9"));
        guideTv.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        root.addView(guideTv);

        EditText nameEdit = addInput(activity, root, "引擎名称（如：自定义 TTS）",
                isEdit && existing.name != null ? existing.name : "", false);
        EditText urlEdit = addInput(activity, root, "请求 URL 或 <js> 脚本规则（必填）",
                isEdit && existing.url != null ? existing.url : "", false);
        urlEdit.setHint("https://example.com/tts?text={{speakText}}");
        urlEdit.setSingleLine(false);
        urlEdit.setMaxLines(6);
        EditText headerEdit = addInput(activity, root, "请求头 Headers（JSON 格式，可选）",
                isEdit && existing.header != null ? existing.header : "", false);
        headerEdit.setSingleLine(false);
        headerEdit.setMaxLines(4);
        EditText paramEdit = addInput(activity, root, "默认参数 / 音色模板 Param（可选）",
                isEdit && existing.param != null ? existing.param : "", false);
        paramEdit.setSingleLine(false);
        paramEdit.setMaxLines(4);
        EditText noteEdit = addInput(activity, root, "备注（可选）",
                isEdit && existing.note != null ? existing.note : "", false);

        Button saveBtn = makePillButton(activity, "保存 HTTP 引擎",
                night ? "#B4D08F" : "#4B662C", night ? "#1F310B" : "#FFFFFF");
        root.addView(saveBtn, lpMarginTop(activity, 16));

        saveBtn.setOnClickListener(v -> {
            String name = nameEdit.getText().toString().trim();
            String url = urlEdit.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                Toast.makeText(activity, "请填写请求 URL 或 JS 规则", Toast.LENGTH_SHORT).show();
                return;
            }
            Engine target = isEdit ? existing : new Engine();
            if (TextUtils.isEmpty(target.code)) {
                target.code = "http_" + UUID.randomUUID().toString().substring(0, 8);
            }
            target.name = TextUtils.isEmpty(name) ? "自定义 HTTP TTS" : name;
            target.type = "http";
            target.url = url;
            target.header = headerEdit.getText().toString().trim();
            target.param = paramEdit.getText().toString().trim();
            target.note = noteEdit.getText().toString().trim();

            if (!saveEngineViaReflection(activity, target)) {
                return;
            }
            try {
                Class<?> qc = Class.forName("Q6.c");
                Object existingSpList = qc.getMethod("z", String.class).invoke(null, target.code);
                boolean hasSp = (existingSpList instanceof List<?>) && !((List<?>) existingSpList).isEmpty();
                if (!hasSp) {
                    Speaker sp = new Speaker();
                    sp.group = target.code;
                    sp.code = target.code + "_default";
                    sp.name = target.name;
                    sp.param = "";
                    sp.locale = "zh-CN";
                    sp.desc = "";
                    sp.note = "";
                    sp.extendUI = "";
                    sp.avatar = "";
                    sp.sampleRate = 24000;
                    sp.type = 1;
                    sp.speed = 1.0f;
                    sp.pitch = 1.0f;
                    sp.volume = 1.0f;
                    try {
                        Object cid = qc.getMethod("B", Speaker.class).invoke(null, sp);
                        if (cid instanceof String && !TextUtils.isEmpty((String) cid)) sp.id = (String) cid;
                    } catch (Throwable ignored) {}
                    if (TextUtils.isEmpty(sp.id)) sp.id = sp.code;
                    List<Speaker> spList = new ArrayList<>();
                    spList.add(sp);
                    qc.getMethod("a0", String.class, List.class).invoke(null, target.code, spList);
                }
                qc.getMethod("Z").invoke(null);
            } catch (Throwable ignored) {}
            try { Class.forName("Z6.o").getMethod("d").invoke(null); } catch (Throwable ignored) {}
            try { Class.forName("R6.i").getMethod("e").invoke(null); } catch (Throwable ignored) {}
            Toast.makeText(activity, "已保存 HTTP 引擎：" + target.name, Toast.LENGTH_SHORT).show();
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
            win.setBackgroundDrawable(sheetBg(activity));
        }
        dialog.show();
    }

    public static void showSpeakerDialog(Activity activity, Engine engine, Speaker existingSp, Runnable onSaved) {
        if (engine == null) {
            return;
        }
        CloudTtsConfig cfg = CloudTtsConfig.fromEngine(engine);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(isNight(activity) ? "#131511" : "#F6F7F2"));
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
        title.setText((existingSp == null ? "添加音色 · " : "编辑音色 · ") + engine.name);
        title.setTextSize(19);
        title.setTextColor(Color.parseColor(isNight(activity) ? "#E4E6DE" : "#1B1C18"));
        title.setPadding(0, 0, 0, dp(activity, 12));
        root.addView(title);

        boolean isClone = cfg.providerType == CloudTtsProviderType.MIMO
                && "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(cfg.model);
        boolean isDesign = cfg.providerType == CloudTtsProviderType.MIMO
                && "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(cfg.model);

        String initName = existingSp != null && !TextUtils.isEmpty(existingSp.name) ? existingSp.name : "";
        String initVoice = "";
        if (isDesign) {
            initVoice = existingSp != null && !TextUtils.isEmpty(existingSp.param) ? existingSp.param : cfg.instructions;
        } else if (!isClone) {
            initVoice = existingSp != null && !TextUtils.isEmpty(existingSp.param) ? existingSp.param : cfg.voice;
        }

        EditText spNameEdit = addInput(activity, root, "音色显示名称（如：旁白男声 / 御姐音）", initName, false);
        EditText spVoiceEdit = addInput(activity, root,
                isDesign ? "音色设计描述词（如：温柔知性的年轻女声）" : "Voice ID / Fish Audio reference_id",
                initVoice, false);
        View spVoiceWrap = (View) spVoiceEdit.getParent();
        spVoiceWrap.setVisibility(isClone ? View.GONE : View.VISIBLE);

        final CloudTtsConfig cloneHolder = new CloudTtsConfig();
        if (isClone && existingSp != null && !TextUtils.isEmpty(existingSp.param)
                && (existingSp.param.startsWith("data:audio/") || existingSp.param.length() > 128)) {
            cloneHolder.referenceAudioBase64 = existingSp.param;
            cloneHolder.referenceAudioName = !TextUtils.isEmpty(existingSp.code) ? existingSp.code : cfg.referenceAudioName;
        }

        Button pickAudioBtn = makePillButton(activity,
                TextUtils.isEmpty(cloneHolder.referenceAudioName)
                        ? "选择 MP3 / WAV 参考音频"
                        : "重新选择参考音频（" + cloneHolder.referenceAudioName + "）",
                "#EAECE4", "#2A3B17");
        Button clearAudioBtn = makePillButton(activity, "清除参考音频", "#F2E4E4", "#8C1D18");
        TextView cloneStatusTv = new TextView(activity);
        cloneStatusTv.setTextSize(12);
        cloneStatusTv.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), 0);

        if (isClone) {
            root.addView(pickAudioBtn, lpMarginTop(activity, 10));
            root.addView(clearAudioBtn, lpMarginTop(activity, 8));
            clearAudioBtn.setVisibility(TextUtils.isEmpty(cloneHolder.referenceAudioBase64) ? View.GONE : View.VISIBLE);
            cloneStatusTv.setVisibility(View.VISIBLE);
            cloneStatusTv.setTextColor(Color.parseColor("#5C6057"));
            cloneStatusTv.setText(TextUtils.isEmpty(cloneHolder.referenceAudioBase64)
                    ? "请为该克隆音色选择 10～30 秒清晰人声（MP3 / WAV，≤ 1.5 MB）"
                    : "已绑定参考音频：" + cloneHolder.referenceAudioName);
            root.addView(cloneStatusTv);

            activeConfigRef = new WeakReference<>(cloneHolder);
            activePickAudioBtnRef = new WeakReference<>(pickAudioBtn);
            activeClearAudioBtnRef = new WeakReference<>(clearAudioBtn);
            activeStatusTvRef = new WeakReference<>(cloneStatusTv);

            pickAudioBtn.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("audio/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp3"});
                activity.startActivityForResult(intent, REQ_PICK_MIMO_AUDIO);
            });
            clearAudioBtn.setOnClickListener(v -> {
                cloneHolder.referenceAudioBase64 = "";
                cloneHolder.referenceAudioName = "";
                pickAudioBtn.setText("选择 MP3 / WAV 参考音频");
                clearAudioBtn.setVisibility(View.GONE);
                cloneStatusTv.setText("已清除参考音频，请重新选择");
            });
        } else {
            String[] presets = isDesign ? new String[0] : presetVoicesFor(cfg.providerType);
            if (presets.length > 0) {
                Button quickBtn = makePillButton(activity, "快捷选择预设音色", "#EAECE4", "#2A3B17");
                root.addView(quickBtn, lpMarginTop(activity, 8));
                quickBtn.setOnClickListener(v -> {
                    PopupMenu pm = new PopupMenu(activity, quickBtn);
                    for (int i = 0; i < presets.length; i++) {
                        pm.getMenu().add(0, i, i, voiceMenuLabel(cfg.providerType, presets[i]));
                    }
                    pm.setOnMenuItemClickListener(item -> {
                        String chosen = presets[item.getItemId()];
                        spVoiceEdit.setText(chosen);
                        if (TextUtils.isEmpty(spNameEdit.getText().toString().trim())) {
                            spNameEdit.setText(chosen);
                        }
                        return true;
                    });
                    pm.show();
                });
            }
        }

        Button saveBtn = makePillButton(activity, "保存音色", "#4B662C", "#FFFFFF");
        root.addView(saveBtn, lpMarginTop(activity, 16));

        saveBtn.setOnClickListener(v -> {
            String vName = spNameEdit.getText().toString().trim();
            String vId = isClone ? cloneHolder.referenceAudioBase64 : spVoiceEdit.getText().toString().trim();
            if (isClone && TextUtils.isEmpty(vId)) {
                Toast.makeText(activity, "请先选择 MP3 / WAV 参考音频", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(vName)) {
                if (isClone && !TextUtils.isEmpty(cloneHolder.referenceAudioName)) {
                    int dot = cloneHolder.referenceAudioName.lastIndexOf('.');
                    vName = dot > 0 ? cloneHolder.referenceAudioName.substring(0, dot) : cloneHolder.referenceAudioName;
                } else if (!isClone && !TextUtils.isEmpty(vId)) {
                    vName = vId.length() > 24 ? vId.substring(0, 24) : vId;
                } else {
                    vName = engine.name;
                }
            }
            try {
                Class<?> qc = Class.forName("Q6.c");
                List<Speaker> list = new ArrayList<>();
                Object existingList = qc.getMethod("z", String.class).invoke(null, engine.code);
                if (existingList instanceof List<?>) {
                    for (Object o : (List<?>) existingList) {
                        if (o instanceof Speaker) {
                            list.add((Speaker) o);
                        }
                    }
                }
                Speaker sp = existingSp != null ? existingSp : new Speaker();
                sp.group = engine.code;
                if (isClone) {
                    sp.code = !TextUtils.isEmpty(cloneHolder.referenceAudioName)
                            ? cloneHolder.referenceAudioName
                            : (engine.code + "_" + UUID.randomUUID().toString().substring(0, 6));
                } else if (isDesign) {
                    sp.code = vName;
                } else if (!TextUtils.isEmpty(vId)) {
                    sp.code = vId;
                } else if (TextUtils.isEmpty(sp.code)) {
                    sp.code = engine.code + "_" + UUID.randomUUID().toString().substring(0, 6);
                }
                sp.name = vName;
                sp.param = vId;
                sp.locale = "zh-CN";
                if (sp.desc == null) sp.desc = "";
                if (sp.note == null) sp.note = "";
                if (sp.extendUI == null) sp.extendUI = "";
                if (sp.avatar == null) sp.avatar = "";
                sp.sampleRate = 24000;
                sp.type = 1;
                sp.speed = 1.0f;
                sp.pitch = 1.0f;
                sp.volume = 1.0f;
                if (TextUtils.isEmpty(sp.id)) {
                    try {
                        Object computedId = qc.getMethod("B", Speaker.class).invoke(null, sp);
                        if (computedId instanceof String && !TextUtils.isEmpty((String) computedId)) {
                            sp.id = (String) computedId;
                        }
                    } catch (Throwable ignored) {}
                    if (TextUtils.isEmpty(sp.id)) {
                        sp.id = sp.code;
                    }
                }

                if (existingSp == null) {
                    boolean collision = true;
                    while (collision) {
                        collision = false;
                        for (Speaker sItem : list) {
                            if (sp.id.equals(sItem.id)) {
                                sp.id = sp.id + "_" + UUID.randomUUID().toString().substring(0, 4);
                                collision = true;
                                break;
                            }
                        }
                    }
                    list.add(sp);
                } else {
                    boolean replaced = false;
                    for (int i = 0; i < list.size(); i++) {
                        if (sp.id.equals(list.get(i).id)) {
                            list.set(i, sp);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        list.add(sp);
                    }
                }

                qc.getMethod("a0", String.class, List.class).invoke(null, engine.code, list);
                try {
                    qc.getMethod("Z").invoke(null);
                } catch (Throwable ignored) {}
                try {
                    Class.forName("Z6.o").getMethod("d").invoke(null);
                } catch (Throwable ignored) {}
                try {
                    Class.forName("R6.i").getMethod("e").invoke(null);
                } catch (Throwable ignored) {}
                Object verified = qc.getMethod("z", String.class).invoke(null, engine.code);
                int realCount = (verified instanceof List<?>) ? ((List<?>) verified).size() : list.size();
                Toast.makeText(activity, "已保存音色：" + vName + "（数据库共 " + realCount + " 个）", Toast.LENGTH_SHORT).show();
                if (onSaved != null) {
                    onSaved.run();
                }
                dialog.dismiss();
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                Toast.makeText(activity, "保存音色失败：" + cause.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        dialog.setContentView(scroll);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.setBackgroundDrawable(sheetBg(activity));
        }
        dialog.show();
    }

    private static EditText addInput(Activity activity, LinearLayout parent, String label, String val, boolean password) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextSize(12);
        boolean night = isNight(activity);
        tv.setTextColor(Color.parseColor(night ? "#A3A89C" : "#5C6057"));
        wrap.addView(tv);

        EditText et = new EditText(activity);
        et.setText(val != null ? val : "");
        et.setTextSize(15);
        et.setTextColor(Color.parseColor(night ? "#E4E6DE" : "#1B1C18"));
        et.setBackground(inputBox(activity));
        et.setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11));
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

    private static boolean isNight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static GradientDrawable sheetBg(Activity activity) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(isNight(activity) ? "#131511" : "#F6F7F2"));
        float r = dp(activity, 24);
        d.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        return d;
    }

    private static GradientDrawable inputBox(Activity activity) {
        boolean night = isNight(activity);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(night ? "#1E211B" : "#EAECE4"));
        d.setCornerRadius(dp(activity, 12));
        d.setStroke(dp(activity, 1), Color.parseColor(night ? "#44493E" : "#C4C9BC"));
        return d;
    }

    private static GradientDrawable roundedBox(Activity activity, String hex) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(hex));
        d.setCornerRadius(dp(activity, 14));
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
