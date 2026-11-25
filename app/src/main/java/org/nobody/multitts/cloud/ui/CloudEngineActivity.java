package org.nobody.multitts.cloud.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 纯原生 Android 控件构建的 Legado MD3 风格「朗读引擎与音色」管理页：
 * 继承 android.app.Activity，零 XML 资源 ID / 零 AppCompat R.attr 依赖，彻底避免跨包资源 ID 冲突闪退。
 */
public class CloudEngineActivity extends Activity {

    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F6F7F2"));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(12), dp(16), dp(8));

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(android.R.drawable.ic_menu_revert);
        btnBack.setColorFilter(Color.parseColor("#1B1C18"));
        btnBack.setBackground(new GradientDrawable());
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("朗读引擎与音色");
        tvTitle.setTextSize(22f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.parseColor("#1B1C18"));
        tvTitle.setPadding(dp(8), 0, 0, 0);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部双 Tab（音色 | 引擎）
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(16), dp(4), dp(16), dp(8));

        TextView tabVoice = makeTab("音色", false);
        tabVoice.setOnClickListener(v -> finish());
        TextView tabEngine = makeTab("引擎", true);
        tabBar.addView(tabVoice, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabBar.addView(tabEngine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E0E4D8"));
        column.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 列表滚动区
        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(16), dp(8), dp(16), dp(96));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 右下角 MD3 圆角悬浮按钮（+ 添加云引擎）
        TextView fab = new TextView(this);
        fab.setText("+");
        fab.setTextSize(26f);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(Color.parseColor("#2A3B17"));
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setColor(Color.parseColor("#DCE6CF"));
        fabBg.setCornerRadius(dp(16));
        fab.setBackground(fabBg);
        fab.setElevation(dp(4));

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.setMargins(dp(24), dp(24), dp(24), dp(24));
        root.addView(fab, fabLp);

        fab.setOnClickListener(v -> {
            Engine empty = new Engine(UUID.randomUUID().toString(), "");
            empty.type = CloudTtsProviderType.OPENAI.engineType;
            AddCloudEngineBottomSheet.show(CloudEngineActivity.this, empty, saved -> reloadEngines());
        });

        setContentView(root);
        reloadEngines();
    }

    private TextView makeTab(String label, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(15f);
        tv.setPadding(0, dp(10), 0, dp(10));
        if (active) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(Color.parseColor("#4B662C"));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E6ECD9"));
            bg.setCornerRadius(dp(12));
            tv.setBackground(bg);
        } else {
            tv.setTextColor(Color.parseColor("#6B7066"));
        }
        return tv;
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
            addHint("暂无云端 AI 引擎，点击右下角 + 添加（OpenAI / Gemini / MiMo / 火山等）");
        } else {
            for (Engine e : cloudList) {
                CloudTtsConfig cfg = CloudTtsConfig.fromEngine(e);
                addEngineCard(e, cfg.providerType.displayName + " · " + cfg.model);
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

    private void addHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#4B662C"));
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        listContainer.addView(tv);
    }

    private void addHint(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.parseColor("#757970"));
        tv.setPadding(dp(4), dp(4), dp(4), dp(8));
        listContainer.addView(tv);
    }

    private void addEngineCard(final Engine engine, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(10), dp(14));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#EAECE4"));
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(4), 0, dp(4));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(engine.name);
        tvName.setTextSize(16f);
        tvName.setTextColor(Color.parseColor("#1B1C18"));
        textCol.addView(tvName);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextSize(12.5f);
        tvSub.setTextColor(Color.parseColor("#5C6057"));
        textCol.addView(tvSub);

        ImageButton btnEdit = new ImageButton(this);
        btnEdit.setImageResource(android.R.drawable.ic_menu_edit);
        btnEdit.setColorFilter(Color.parseColor("#3E4239"));
        btnEdit.setBackground(new GradientDrawable());
        btnEdit.setOnClickListener(v ->
                AddCloudEngineBottomSheet.show(CloudEngineActivity.this, engine, saved -> reloadEngines()));
        card.addView(btnEdit, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton btnDel = new ImageButton(this);
        btnDel.setImageResource(android.R.drawable.ic_menu_delete);
        btnDel.setColorFilter(Color.parseColor("#3E4239"));
        btnDel.setBackground(new GradientDrawable());
        btnDel.setOnClickListener(v -> {
            deleteEngineViaReflection(engine);
            reloadEngines();
        });
        card.addView(btnDel, new LinearLayout.LayoutParams(dp(40), dp(40)));

        listContainer.addView(card, cardLp);
    }

    @SuppressWarnings("unchecked")
    private List<Engine> loadEnginesViaReflection() {
        try {
            Class<?> k6d = Class.forName("K6.d");
            Method m = k6d.getMethod("c");
            Object res = m.invoke(null);
            if (res instanceof List) {
                return (List<Engine>) res;
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    private void deleteEngineViaReflection(Engine engine) {
        try {
            Class<?> q6c = Class.forName("Q6.c");
            Object db = q6c.getMethod("t").invoke(null);
            Object dao = db.getClass().getMethod("engineDao").invoke(db);
            dao.getClass().getMethod("delete", Engine.class).invoke(dao, engine);
            db.getClass().getMethod("close").invoke(db);
            Toast.makeText(this, "已删除：" + engine.name, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "删除失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
