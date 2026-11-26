package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.nobody.multitts.cloud.CloudAiTtsEngine;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;
import org.nobody.multitts.tts.speaker.Speaker;

public class CloudEngineActivity extends Activity {

    private LinearLayout listContainer;
    private TextView tabVoice;
    private TextView tabEngine;
    private TextView fab;
    private boolean showingVoices = false;
    private AudioTrack activeTrack;
    private boolean isNight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        Window win = getWindow();
        if (win != null) {
            win.setStatusBarColor(cSurface());
            win.setNavigationBarColor(cSurface());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = win.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (!isNight) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cSurface());
        root.setFitsSystemWindows(true);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        // Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(10), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(cTextPrimary());
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(cCard());
        back.setBackground(backBg);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        backLp.rightMargin = dp(12);
        topBar.addView(back, backLp);

        TextView title = new TextView(this);
        title.setText("朗读引擎与音色");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(cTextPrimary());
        topBar.addView(title);
        main.addView(topBar);

        // Segmented Pill Tab Bar
        LinearLayout tabWrap = new LinearLayout(this);
        tabWrap.setOrientation(LinearLayout.HORIZONTAL);
        tabWrap.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable tabTrackBg = new GradientDrawable();
        tabTrackBg.setColor(cCard());
        tabTrackBg.setCornerRadius(dp(16));
        tabWrap.setBackground(tabTrackBg);

        tabVoice = makeTab("音色", false);
        tabEngine = makeTab("引擎", true);
        tabVoice.setOnClickListener(v -> {
            if (!showingVoices) {
                showingVoices = true;
                updateTabStyles();
                reloadContent();
            }
        });
        tabEngine.setOnClickListener(v -> {
            if (showingVoices) {
                showingVoices = false;
                updateTabStyles();
                reloadContent();
            }
        });
        tabWrap.addView(tabVoice, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabWrap.addView(tabEngine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams tabWrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabWrapLp.setMargins(dp(16), dp(4), dp(16), dp(10));
        main.addView(tabWrap, tabWrapLp);

        View divider = new View(this);
        divider.setBackgroundColor(cDivider());
        main.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(16), dp(10), dp(16), dp(88));
        scroll.addView(listContainer);
        main.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main);

        fab = new TextView(this);
        fab.setText("+ 添加引擎");
        fab.setTextSize(14);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setTextColor(cOnPrimaryContainer());
        fab.setGravity(Gravity.CENTER);
        fab.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setColor(cPrimaryContainer());
        fabBg.setCornerRadius(dp(16));
        fab.setBackground(fabBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fab.setElevation(dp(4));
        }
        fab.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, fab);
            CloudTtsProviderType[] values = CloudTtsProviderType.values();
            for (int i = 0; i < values.length; i++) {
                menu.getMenu().add(0, i, i, "添加 " + values[i].displayName);
            }
            menu.getMenu().add(0, values.length, values.length, "添加 自定义 HTTP 协议 TTS");
            menu.setOnMenuItemClickListener(item -> {
                int idx = item.getItemId();
                if (idx < values.length) {
                    CloudTtsProviderType chosen = values[idx];
                    AddCloudEngineBottomSheet.show(this, chosen, e -> reloadContent());
                } else {
                    AddCloudEngineBottomSheet.showCustomHttpDialog(this, null, e -> reloadContent());
                }
                return true;
            });
            menu.show();
        });

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp(20);
        fabLp.bottomMargin = dp(24);
        root.addView(fab, fabLp);

        setContentView(root);
        reloadContent();
    }

    @Override
    protected void onDestroy() {
        stopActiveTrack();
        super.onDestroy();
    }

    private void stopActiveTrack() {
        if (activeTrack != null) {
            try {
                activeTrack.stop();
            } catch (Throwable ignored) {}
            try {
                activeTrack.release();
            } catch (Throwable ignored) {}
            activeTrack = null;
        }
    }

    private void updateTabStyles() {
        applyTabStyle(tabVoice, showingVoices);
        applyTabStyle(tabEngine, !showingVoices);
        if (fab != null) {
            fab.setVisibility(showingVoices ? View.GONE : View.VISIBLE);
        }
    }

    private void applyTabStyle(TextView tv, boolean active) {
        tv.setTextColor(active ? cOnPrimaryContainer() : cTextSecondary());
        tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(cPrimaryContainer());
            bg.setCornerRadius(dp(12));
            tv.setBackground(bg);
        } else {
            tv.setBackground(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AddCloudEngineBottomSheet.handleActivityResult(this, requestCode, resultCode, data);
    }

    private void reloadContent() {
        if (showingVoices) {
            reloadVoices();
        } else {
            reloadEngines();
        }
    }

    private void reloadVoices() {
        listContainer.removeAllViews();
        addHint("正在加载云端音色...");
        new Thread(() -> {
            final List<Engine> all = loadEnginesViaReflection();
            final List<Engine> cloudEngines = new ArrayList<>();
            final List<List<Speaker>> groups = new ArrayList<>();
            for (Engine e : all) {
                if (!CloudTtsProviderType.isCloudType(e.type)) {
                    continue;
                }
                cloudEngines.add(e);
                groups.add(loadSpeakersForGroupViaReflection(e.code));
            }
            runOnUiThread(() -> {
                if (!showingVoices || isFinishing()) return;
                listContainer.removeAllViews();
                addBannerHint("点引擎右侧「+ 添加音色」可挂载多个音色；回到主界面下拉列表或重启即可同步顶栏。");
                int totalSpeakers = 0;
                for (int i = 0; i < cloudEngines.size(); i++) {
                    Engine e = cloudEngines.get(i);
                    List<Speaker> speakers = groups.get(i);
                    CloudTtsProviderType pt = CloudTtsProviderType.fromEngineType(e.type);
                    String title = (!TextUtils.isEmpty(e.name) ? e.name : e.code)
                            + " · " + (pt != null ? pt.displayName : e.type)
                            + " (" + speakers.size() + ")";
                    addVoiceGroupHeader(e, title);
                    if (speakers.isEmpty()) {
                        addHint("暂无音色，点右上角「+ 添加音色」即可添加");
                    } else {
                        for (Speaker sp : speakers) {
                            totalSpeakers++;
                            addSpeakerCard(e, sp);
                        }
                    }
                }
                if (totalSpeakers == 0) {
                    addHint("暂无已生成的云端音色，请先切换到「引擎」标签添加并保存云引擎。");
                }
            });
        }).start();
    }

    private void addSpeakerCard(Engine engine, Speaker sp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard());
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(!TextUtils.isEmpty(sp.name) ? sp.name : sp.id);
        nameTv.setTextSize(15);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(cTextPrimary());
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);

        TextView subTv = new TextView(this);
        CloudTtsConfig cfg = CloudTtsConfig.fromEngine(engine);
        boolean isCloneCard = cfg.providerType == CloudTtsProviderType.MIMO
                && "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(cfg.model != null ? cfg.model.trim() : "");
        String voiceInfo;
        if (isCloneCard) {
            String cloneLabel = (!TextUtils.isEmpty(sp.code) && !sp.code.equals(engine.code))
                    ? sp.code
                    : (!TextUtils.isEmpty(cfg.referenceAudioName) ? cfg.referenceAudioName : "已配置参考音频");
            voiceInfo = "克隆 · " + cloneLabel;
        } else {
            String rawCode = !TextUtils.isEmpty(sp.code) && !sp.code.equals(engine.code) ? sp.code : sp.param;
            voiceInfo = !TextUtils.isEmpty(rawCode) ? rawCode : cfg.model;
        }
        subTv.setText(cfg.model + " · " + voiceInfo);
        subTv.setTextSize(12);
        subTv.setTextColor(cTextSecondary());
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subTv.setPadding(0, dp(2), 0, 0);

        textCol.addView(nameTv);
        textCol.addView(subTv);
        topRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView playBtn = makeChip("试听", cPrimaryContainer(), cOnPrimaryContainer());
        playBtn.setOnClickListener(v -> auditionSpeaker(engine, sp, playBtn));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.leftMargin = dp(8);
        topRow.addView(playBtn, playLp);

        card.addView(topRow);

        // Action row aligned cleanly at bottom right
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        actionRow.setPadding(0, dp(10), 0, 0);

        TextView editSpBtn = makeChip("编辑", cCardHigh(), cTextPrimary());
        editSpBtn.setOnClickListener(v -> AddCloudEngineBottomSheet.showSpeakerDialog(this, engine, sp, this::reloadVoices));
        actionRow.addView(editSpBtn);

        TextView delSpBtn = makeChip("删除", cErrorContainer(), cOnErrorContainer());
        delSpBtn.setOnClickListener(v -> confirmDelete(
                "删除音色",
                "确定要删除音色「" + (!TextUtils.isEmpty(sp.name) ? sp.name : sp.id) + "」吗？",
                () -> {
                    deleteSingleSpeakerViaReflection(engine, sp);
                    reloadVoices();
                }));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        delLp.leftMargin = dp(8);
        actionRow.addView(delSpBtn, delLp);

        card.addView(actionRow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        listContainer.addView(card, lp);
    }

    private void auditionSpeaker(Engine engine, Speaker sp, TextView btn) {
        btn.setEnabled(false);
        btn.setText("合成中...");
        new Thread(() -> {
            try {
                CloudAiTtsEngine tts = new CloudAiTtsEngine(this, engine, sp);
                Method m = CloudAiTtsEngine.class.getDeclaredMethod("synthesizeInternal", float.class, float.class, String.class);
                m.setAccessible(true);
                byte[] wav = (byte[]) m.invoke(tts, 1.0f, 1.0f, "你好，这是云端音色试听测试。");
                playWavBytes(wav);
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("试听");
                });
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("试听");
                    Toast.makeText(this, "试听失败：" + cause.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void playWavBytes(byte[] wav) {
        if (wav == null || wav.length < 44) {
            return;
        }
        stopActiveTrack();
        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int channels = bb.getShort(22) & 0xFFFF;
        int sampleRate = bb.getInt(24);
        if (sampleRate < 8000 || sampleRate > 96000) {
            sampleRate = 24000;
        }
        int channelMask = (channels == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, pcm.length))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        activeTrack = track;
        track.write(pcm, 0, pcm.length);
        track.play();
    }

    private static boolean isHttpEngine(Engine e) {
        if (e == null) return false;
        if ("http".equalsIgnoreCase(e.type)) return true;
        return e.code != null && e.code.startsWith("http_");
    }

    private void reloadEngines() {
        listContainer.removeAllViews();
        List<Engine> all = loadEnginesViaReflection();
        List<Engine> cloudList = new ArrayList<>();
        List<Engine> httpList = new ArrayList<>();
        List<Engine> innerList = new ArrayList<>();
        for (Engine e : all) {
            if (CloudTtsProviderType.isCloudType(e.type)) {
                cloudList.add(e);
            } else if (isHttpEngine(e)) {
                httpList.add(e);
            } else {
                innerList.add(e);
            }
        }

        addHeader("云端 AI TTS (" + cloudList.size() + ")");
        if (cloudList.isEmpty()) {
            addHint("暂无云端 AI 引擎，点击右下角「+ 添加引擎」开始配置");
        } else {
            for (Engine e : cloudList) {
                CloudTtsProviderType pt = CloudTtsProviderType.fromEngineType(e.type);
                CloudTtsConfig cfg = CloudTtsConfig.fromEngine(e);
                String sub = (pt != null ? pt.displayName : e.type) + " · " + cfg.model;
                addEngineCard(e, sub, true, true);
            }
        }

        java.util.LinkedHashMap<String, List<Engine>> httpGroups = new java.util.LinkedHashMap<>();
        for (Engine e : httpList) {
            String key = httpFamilyKey(e);
            List<Engine> grp = httpGroups.get(key);
            if (grp == null) {
                grp = new ArrayList<>();
                httpGroups.put(key, grp);
            }
            grp.add(e);
        }
        addHeader("HTTP 协议 TTS (" + httpGroups.size() + " 组 / " + httpList.size() + " 项)");
        if (httpGroups.isEmpty()) {
            addHint("暂无 HTTP TTS 引擎");
        } else {
            for (java.util.Map.Entry<String, List<Engine>> entry : httpGroups.entrySet()) {
                List<Engine> grp = entry.getValue();
                if (grp.size() == 1) {
                    Engine e = grp.get(0);
                    String urlHost = !TextUtils.isEmpty(e.url) ? e.url : ("HTTP 引擎 · " + e.code);
                    addEngineCard(e, urlHost, false, true);
                } else {
                    addHttpGroupCard(entry.getKey(), grp);
                }
            }
        }

        if (!innerList.isEmpty()) {
            addHeader("内置 / 离线引擎 (" + innerList.size() + ")");
            for (Engine e : innerList) {
                String sub = (!TextUtils.isEmpty(e.type) ? e.type : "inner") + " · " + e.code;
                addEngineCard(e, sub, false, false);
            }
        }
    }

    private static String httpFamilyKey(Engine e) {
        String raw = !TextUtils.isEmpty(e.name) ? e.name.trim() : (e.code != null ? e.code : "HTTP");
        String cleaned = raw
                .replaceAll("(?i)\\s*\\([^)]*\\)", "")
                .replaceAll("(?i)\\s+v?\\d+(?:\\.\\d+)*(?:\\s+(?:Flash-Lite|Flash|Pro|Lite|TTS|Preview|Turbo))*", "")
                .replaceAll("(?i)\\s+(?:Flash-Lite|Flash|Pro|Lite|TTS)$", "")
                .trim();
        return TextUtils.isEmpty(cleaned) ? raw : cleaned;
    }

    private void addHttpGroupCard(String familyTitle, final List<Engine> versions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard());
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(familyTitle);
        nameTv.setTextSize(15);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(cTextPrimary());
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);

        StringBuilder sb = new StringBuilder("已合并 " + versions.size() + " 个版本：");
        for (int i = 0; i < versions.size(); i++) {
            if (i > 0) sb.append(" · ");
            Engine ve = versions.get(i);
            sb.append(!TextUtils.isEmpty(ve.name) ? ve.name : ve.code);
        }
        TextView subTv = new TextView(this);
        subTv.setText(sb.toString());
        subTv.setTextSize(12);
        subTv.setTextColor(cTextSecondary());
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        subTv.setPadding(0, dp(2), 0, 0);

        textCol.addView(nameTv);
        textCol.addView(subTv);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView listBtn = makeChip("版本 (" + versions.size() + ")", cCardHigh(), cTextPrimary());
        listBtn.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, listBtn);
            for (int i = 0; i < versions.size(); i++) {
                Engine ve = versions.get(i);
                pm.getMenu().add(0, i, i, !TextUtils.isEmpty(ve.name) ? ve.name : ve.code);
            }
            pm.setOnMenuItemClickListener(item -> {
                Engine chosen = versions.get(item.getItemId());
                new AlertDialog.Builder(this)
                        .setTitle(!TextUtils.isEmpty(chosen.name) ? chosen.name : chosen.code)
                        .setMessage("类型：" + chosen.type + "\n代号：" + chosen.code + "\n接口：" + (!TextUtils.isEmpty(chosen.url) ? chosen.url : "(脚本)"))
                        .setPositiveButton("编辑此版本", (d, w) -> AddCloudEngineBottomSheet.showCustomHttpDialog(this, chosen, saved -> reloadContent()))
                        .setNeutralButton("关闭", null)
                        .setNegativeButton("删除此版本", (d, w) -> {
                            deleteEngineViaReflection(chosen);
                            reloadContent();
                        })
                        .show();
                return true;
            });
            pm.show();
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(8);
        card.addView(listBtn, btnLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        listContainer.addView(card, lp);
    }

    private void addVoiceGroupHeader(final Engine engine, String titleText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(14), dp(2), dp(8));

        TextView tv = new TextView(this);
        tv.setText(titleText);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(cTextSecondary());
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView addBtn = makeChip("+ 添加音色", cPrimaryContainer(), cOnPrimaryContainer());
        addBtn.setOnClickListener(v -> AddCloudEngineBottomSheet.showSpeakerDialog(this, engine, null, this::reloadVoices));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = dp(8);
        row.addView(addBtn, addLp);

        listContainer.addView(row);
    }

    private void deleteSingleSpeakerViaReflection(Engine engine, Speaker targetSp) {
        try {
            Class<?> qc = Class.forName("Q6.c");
            List<Speaker> list = new ArrayList<>();
            Object existingList = qc.getMethod("z", String.class).invoke(null, engine.code);
            if (existingList instanceof List<?>) {
                for (Object o : (List<?>) existingList) {
                    if (o instanceof Speaker) {
                        Speaker s = (Speaker) o;
                        if (!targetSp.id.equals(s.id)) {
                            list.add(s);
                        }
                    }
                }
            }
            qc.getMethod("a0", String.class, List.class).invoke(null, engine.code, list);
            try { qc.getMethod("Z").invoke(null); } catch (Throwable ignored) {}
            try { Class.forName("Z6.o").getMethod("d").invoke(null); } catch (Throwable ignored) {}
            try { Class.forName("R6.i").getMethod("e").invoke(null); } catch (Throwable ignored) {}
            Toast.makeText(this, "已删除音色：" + targetSp.name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "删除音色失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(cTextSecondary());
        tv.setPadding(dp(2), dp(14), dp(2), dp(8));
        listContainer.addView(tv);
    }

    private void addBannerHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(cOnPrimaryContainer());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cPrimaryContainer());
        bg.setAlpha(isNight ? 180 : 140);
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        listContainer.addView(tv, lp);
    }

    private void addHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(cTextSecondary());
        tv.setPadding(dp(4), dp(6), dp(4), dp(8));
        listContainer.addView(tv);
    }

    private void addEngineCard(Engine e, String subtitle, boolean isCloud, boolean allowDelete) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard());
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(!TextUtils.isEmpty(e.name) ? e.name : e.code);
        nameTv.setTextSize(15);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(cTextPrimary());
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);

        TextView subTv = new TextView(this);
        subTv.setText(subtitle);
        subTv.setTextSize(12);
        subTv.setTextColor(cTextSecondary());
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        subTv.setPadding(0, dp(2), 0, 0);

        textCol.addView(nameTv);
        textCol.addView(subTv);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean isHttp = isHttpEngine(e);
        TextView editBtn = makeChip((isCloud || isHttp) ? "编辑" : "详情", cCardHigh(), cTextPrimary());
        editBtn.setOnClickListener(v -> {
            if (isCloud) {
                AddCloudEngineBottomSheet.show(this, e, saved -> reloadContent());
            } else if (isHttp) {
                AddCloudEngineBottomSheet.showCustomHttpDialog(this, e, saved -> reloadContent());
            } else {
                showHttpEngineInfo(e);
            }
        });
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.leftMargin = dp(8);
        card.addView(editBtn, editLp);

        if (allowDelete) {
            TextView delBtn = makeChip("删除", cErrorContainer(), cOnErrorContainer());
            delBtn.setOnClickListener(v -> confirmDelete(
                    "删除引擎",
                    "确定要删除「" + (!TextUtils.isEmpty(e.name) ? e.name : e.code) + "」及其关联音色吗？",
                    () -> {
                        deleteEngineViaReflection(e);
                        reloadContent();
                    }));
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            card.addView(delBtn, delLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        listContainer.addView(card, lp);
    }

    private void showHttpEngineInfo(Engine e) {
        boolean isHttp = isHttpEngine(e);
        String msg = "名称：" + (!TextUtils.isEmpty(e.name) ? e.name : e.code)
                + "\n类型：" + (!TextUtils.isEmpty(e.type) ? e.type : "inner")
                + "\n代号：" + e.code
                + (isHttp ? "\n接口：" + (!TextUtils.isEmpty(e.url) ? e.url : "(HTTP 脚本)") : "");
        new AlertDialog.Builder(this)
                .setTitle(isHttp ? "HTTP 协议 TTS 详情" : "内置 / 离线引擎详情")
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmDelete(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> onConfirm.run())
                .show();
    }

    private TextView makeChip(String text, int bgColor, int fgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(fgColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(11));
        tv.setBackground(bg);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        return tv;
    }

    private TextView makeTab(String text, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(9), 0, dp(9));
        applyTabStyle(tv, active);
        return tv;
    }

    @SuppressWarnings("unchecked")
    private List<Speaker> loadSpeakersForGroupViaReflection(String group) {
        try {
            Class<?> qc = Class.forName("Q6.c");
            Object res = qc.getMethod("z", String.class).invoke(null, group);
            if (res instanceof List<?>) {
                return (List<Speaker>) res;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Engine> loadEnginesViaReflection() {
        try {
            Class<?> helper = Class.forName("K6.d");
            Object db = helper.getMethod("a").invoke(null);
            try {
                Object dao = db.getClass().getMethod("engineDao").invoke(db);
                Object res = dao.getClass().getMethod("d").invoke(dao);
                if (res instanceof List<?>) {
                    return (List<Engine>) res;
                }
            } finally {
                try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> helper = Class.forName("K6.d");
            Object res = helper.getMethod("c").invoke(null);
            if (res instanceof List<?>) {
                return (List<Engine>) res;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    private void deleteEngineViaReflection(Engine e) {
        try {
            Class<?> helper = Class.forName("K6.d");
            Object db = helper.getMethod("a").invoke(null);
            Object dao = db.getClass().getMethod("engineDao").invoke(db);
            Class<?> bfClass = Class.forName("B6.f");
            Object fn = bfClass.getConstructor(dao.getClass(), Engine.class, int.class)
                    .newInstance(dao, e, 0);
            Class<?> rqClass = Class.forName("r2.q");
            rqClass.getMethod("C",
                    Class.forName("androidx.room.C"),
                    boolean.class,
                    boolean.class,
                    Class.forName("A5.l"))
                    .invoke(null, db, false, true, fn);
            try {
                db.getClass().getMethod("close").invoke(db);
            } catch (Throwable ignored) {
            }
            try {
                Class.forName("Q6.c").getMethod("a0", String.class, List.class).invoke(null, e.code, Collections.emptyList());
                Class.forName("Q6.c").getMethod("Z").invoke(null);
            } catch (Throwable ignored) {}
            try {
                Class.forName("Z6.o").getMethod("d").invoke(null);
            } catch (Throwable ignored) {}
            try {
                Class.forName("R6.i").getMethod("e").invoke(null);
            } catch (Throwable ignored) {}
            Toast.makeText(this, "已删除：" + e.name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "删除失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int cSurface() { return Color.parseColor(isNight ? "#131511" : "#F6F7F2"); }
    private int cCard() { return Color.parseColor(isNight ? "#1E211B" : "#EAECE4"); }
    private int cCardHigh() { return Color.parseColor(isNight ? "#2A2E25" : "#DDE1D5"); }
    private int cPrimaryContainer() { return Color.parseColor(isNight ? "#344B1E" : "#DCE6CF"); }
    private int cOnPrimaryContainer() { return Color.parseColor(isNight ? "#DCE6CF" : "#2A3B17"); }
    private int cErrorContainer() { return Color.parseColor(isNight ? "#4D2422" : "#F4DFDD"); }
    private int cOnErrorContainer() { return Color.parseColor(isNight ? "#F5B8B3" : "#8C1D18"); }
    private int cTextPrimary() { return Color.parseColor(isNight ? "#E4E6DE" : "#1B1C18"); }
    private int cTextSecondary() { return Color.parseColor(isNight ? "#A3A89C" : "#5C6057"); }
    private int cDivider() { return Color.parseColor(isNight ? "#2C3027" : "#E0E4D8"); }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
