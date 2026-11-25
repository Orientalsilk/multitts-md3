package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F6F7F2"));
        root.setFitsSystemWindows(true);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(16), dp(14), dp(16), dp(80));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(Color.parseColor("#1B1C18"));
        back.setPadding(dp(4), dp(4), dp(16), dp(4));
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("朗读引擎与音色");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#1B1C18"));
        topBar.addView(title);
        main.addView(topBar);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(14), 0, dp(10));
        tabVoice = makeTab("音色", false);
        tabEngine = makeTab("引擎", true);
        tabVoice.setOnClickListener(v -> {
            showingVoices = true;
            updateTabStyles();
            reloadContent();
        });
        tabEngine.setOnClickListener(v -> {
            showingVoices = false;
            updateTabStyles();
            reloadContent();
        });
        tabs.addView(tabVoice, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabEngine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        main.addView(tabs);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E0E4D8"));
        main.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(12), 0, dp(16));
        scroll.addView(listContainer);
        main.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main);

        fab = new TextView(this);
        fab.setText("+");
        fab.setTextSize(26);
        fab.setTextColor(Color.parseColor("#2A3B17"));
        fab.setGravity(Gravity.CENTER);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setColor(Color.parseColor("#DCE6CF"));
        fabBg.setCornerRadius(dp(18));
        fab.setBackground(fabBg);
        fab.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, fab);
            CloudTtsProviderType[] values = CloudTtsProviderType.values();
            for (int i = 0; i < values.length; i++) {
                menu.getMenu().add(0, i, i, "添加 " + values[i].displayName);
            }
            menu.setOnMenuItemClickListener(item -> {
                CloudTtsProviderType chosen = values[item.getItemId()];
                AddCloudEngineBottomSheet.show(this, chosen, e -> reloadContent());
                return true;
            });
            menu.show();
        });

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp(20);
        fabLp.bottomMargin = dp(24);
        root.addView(fab, fabLp);

        setContentView(root);
        reloadContent();
    }

    private void updateTabStyles() {
        applyTabStyle(tabVoice, showingVoices);
        applyTabStyle(tabEngine, !showingVoices);
        if (fab != null) {
            fab.setVisibility(showingVoices ? View.GONE : View.VISIBLE);
        }
    }

    private void applyTabStyle(TextView tv, boolean active) {
        tv.setTextColor(Color.parseColor(active ? "#4B662C" : "#6B7066"));
        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E6ECD9"));
            bg.setCornerRadius(dp(14));
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

    @SuppressWarnings("unchecked")
    private void reloadVoices() {
        listContainer.removeAllViews();
        addHint("重启 MultiTTS 后，主界面顶部引擎标签栏最右侧也会显示以下云端音色分组。");
        List<Engine> all = loadEnginesViaReflection();
        int totalSpeakers = 0;
        for (Engine e : all) {
            if (!CloudTtsProviderType.isCloudType(e.type)) {
                continue;
            }
            List<Speaker> speakers = loadSpeakersForGroupViaReflection(e.code);
            CloudTtsProviderType pt = CloudTtsProviderType.fromEngineType(e.type);
            addHeader((!TextUtils.isEmpty(e.name) ? e.name : e.code)
                    + " · " + (pt != null ? pt.displayName : e.type));
            if (speakers.isEmpty()) {
                addHint("暂无音色，请切回「引擎」标签点 ✎ 重新保存一次即可生成");
            } else {
                for (Speaker sp : speakers) {
                    totalSpeakers++;
                    addSpeakerCard(e, sp);
                }
            }
        }
        if (totalSpeakers == 0) {
            addHint("暂无已生成的云端音色，请先在「引擎」标签添加并保存云引擎。");
        }
    }

    private void addSpeakerCard(Engine engine, Speaker sp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EAECE4"));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(!TextUtils.isEmpty(sp.name) ? sp.name : sp.id);
        nameTv.setTextSize(16);
        nameTv.setTextColor(Color.parseColor("#1B1C18"));
        TextView subTv = new TextView(this);
        CloudTtsConfig cfg = CloudTtsConfig.fromEngine(engine);
        String voiceInfo = !TextUtils.isEmpty(sp.param) ? sp.param
                : (!TextUtils.isEmpty(cfg.referenceAudioName) ? ("克隆：" + cfg.referenceAudioName) : cfg.model);
        subTv.setText(cfg.model + " · " + voiceInfo);
        subTv.setTextSize(12);
        subTv.setTextColor(Color.parseColor("#5C6057"));
        textCol.addView(nameTv);
        textCol.addView(subTv);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView playBtn = new TextView(this);
        playBtn.setText("试听");
        playBtn.setTextSize(13);
        playBtn.setTextColor(Color.parseColor("#2A3B17"));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(Color.parseColor("#DCE6CF"));
        playBg.setCornerRadius(dp(12));
        playBtn.setBackground(playBg);
        playBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        playBtn.setOnClickListener(v -> auditionSpeaker(engine, sp, playBtn));
        card.addView(playBtn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        listContainer.addView(card, lp);
    }

    private void auditionSpeaker(Engine engine, Speaker sp, TextView btn) {
        btn.setEnabled(false);
        btn.setText("合成中");
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

    private static void playWavBytes(byte[] wav) {
        if (wav == null || wav.length < 44) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = bb.getInt(24);
        if (sampleRate < 8000 || sampleRate > 96000) {
            sampleRate = 24000;
        }
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, pcm.length))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(pcm, 0, pcm.length);
        track.play();
    }

    private void reloadEngines() {
        listContainer.removeAllViews();
        List<Engine> all = loadEnginesViaReflection();
        List<Engine> cloudList = new ArrayList<>();
        List<Engine> httpList = new ArrayList<>();
        for (Engine e : all) {
            if (CloudTtsProviderType.isCloudType(e.type)) {
                cloudList.add(e);
            } else {
                httpList.add(e);
            }
        }

        addHeader("云 TTS");
        if (cloudList.isEmpty()) {
            addHint("暂无云端 AI 引擎，点击右下角 + 添加（OpenAI / Gemini / MiMo / Fish Audio 等）");
        } else {
            for (Engine e : cloudList) {
                CloudTtsProviderType pt = CloudTtsProviderType.fromEngineType(e.type);
                addEngineCard(e, pt != null ? pt.displayName : e.type);
            }
        }

        addHeader("HTTP 协议 TTS");
        if (httpList.isEmpty()) {
            addHint("暂无 HTTP TTS 引擎");
        } else {
            for (Engine e : httpList) {
                addEngineCard(e, "HTTP TTS");
            }
        }
    }

    private void addHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#5C6057"));
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        listContainer.addView(tv);
    }

    private void addHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#757970"));
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        listContainer.addView(tv);
    }

    private void addEngineCard(Engine e, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EAECE4"));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(!TextUtils.isEmpty(e.name) ? e.name : e.code);
        nameTv.setTextSize(16);
        nameTv.setTextColor(Color.parseColor("#1B1C18"));
        TextView subTv = new TextView(this);
        subTv.setText(subtitle);
        subTv.setTextSize(13);
        subTv.setTextColor(Color.parseColor("#5C6057"));
        textCol.addView(nameTv);
        textCol.addView(subTv);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView editBtn = new TextView(this);
        editBtn.setText("✎");
        editBtn.setTextSize(18);
        editBtn.setTextColor(Color.parseColor("#3E4239"));
        editBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        editBtn.setOnClickListener(v -> AddCloudEngineBottomSheet.show(this, e, saved -> reloadContent()));
        card.addView(editBtn);

        TextView delBtn = new TextView(this);
        delBtn.setText("🗑");
        delBtn.setTextSize(17);
        delBtn.setTextColor(Color.parseColor("#3E4239"));
        delBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        delBtn.setOnClickListener(v -> {
            deleteEngineViaReflection(e);
            reloadContent();
        });
        card.addView(delBtn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        listContainer.addView(card, lp);
    }

    private TextView makeTab(String text, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(8), 0, dp(8));
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
            Method m = helper.getMethod("c");
            Object res = m.invoke(null);
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
            Toast.makeText(this, "已删除：" + e.name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "删除失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
