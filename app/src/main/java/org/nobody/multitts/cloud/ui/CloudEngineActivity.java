package org.nobody.multitts.cloud.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 纯代码构建的 Legado MD3 风格「朗读引擎与音色」管理页（不依赖 XML 资源 ID，可直接打入 extra DEX）。
 */
public class CloudEngineActivity extends AppCompatActivity {

    private final List<EngineRow> rows = new ArrayList<>();
    private EngineAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F6F7F2"));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("朗读引擎与音色");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        column.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TabLayout tabLayout = new TabLayout(this);
        tabLayout.setBackgroundColor(Color.parseColor("#F6F7F2"));
        tabLayout.setSelectedTabIndicatorColor(Color.parseColor("#4B662C"));
        tabLayout.setTabTextColors(Color.parseColor("#6B7066"), Color.parseColor("#2A3B17"));
        tabLayout.addTab(tabLayout.newTab().setText("音色"));
        tabLayout.addTab(tabLayout.newTab().setText("引擎"), true);
        column.addView(tabLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setPadding(dp(16), dp(8), dp(16), dp(88));
        rv.setClipToPadding(false);
        column.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new EngineAdapter(this, rows, new EngineAdapter.Listener() {
            @Override
            public void onEdit(Engine engine) {
                AddCloudEngineBottomSheet.show(CloudEngineActivity.this, engine, saved -> reloadEngines());
            }

            @Override
            public void onDelete(Engine engine) {
                deleteEngineViaReflection(engine);
                reloadEngines();
            }
        });
        rv.setAdapter(adapter);

        FloatingActionButton fab = new FloatingActionButton(this);
        fab.setImageResource(android.R.drawable.ic_input_add);
        fab.setContentDescription("添加云引擎");
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AddCloudEngineBottomSheet.handleActivityResult(this, requestCode, resultCode, data);
    }

    private void reloadEngines() {
        rows.clear();
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
        rows.add(EngineRow.header("云 TTS"));
        if (cloudList.isEmpty()) {
            rows.add(EngineRow.hint("暂无云端 AI 引擎，点击右下角 + 添加（OpenAI / Gemini / MiMo / 火山等）"));
        } else {
            for (Engine e : cloudList) {
                CloudTtsConfig cfg = CloudTtsConfig.fromEngine(e);
                rows.add(EngineRow.item(e, cfg.providerType.displayName + " · " + cfg.model));
            }
        }
        rows.add(EngineRow.header("HTTP 协议 TTS"));
        for (Engine e : httpList) {
            rows.add(EngineRow.item(e, "HTTP TTS"));
        }
        adapter.notifyDataSetChanged();
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

    static class EngineRow {
        final int type; // 0=header, 1=item, 2=hint
        final String text;
        final Engine engine;
        final String subtitle;

        private EngineRow(int type, String text, Engine engine, String subtitle) {
            this.type = type;
            this.text = text;
            this.engine = engine;
            this.subtitle = subtitle;
        }

        static EngineRow header(String title) {
            return new EngineRow(0, title, null, null);
        }

        static EngineRow item(Engine engine, String subtitle) {
            return new EngineRow(1, engine.name, engine, subtitle);
        }

        static EngineRow hint(String msg) {
            return new EngineRow(2, msg, null, null);
        }
    }

    static class EngineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface Listener {
            void onEdit(Engine engine);
            void onDelete(Engine engine);
        }

        private final Context ctx;
        private final List<EngineRow> items;
        private final Listener listener;

        EngineAdapter(Context ctx, List<EngineRow> items, Listener listener) {
            this.ctx = ctx;
            this.items = items;
            this.listener = listener;
        }

        private int dp(int v) {
            return Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0 || viewType == 2) {
                TextView tv = new TextView(ctx);
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                tv.setPadding(dp(4), dp(14), dp(4), dp(6));
                tv.setTextSize(viewType == 0 ? 13f : 12f);
                tv.setTextColor(Color.parseColor(viewType == 0 ? "#4B662C" : "#757970"));
                return new TextVH(tv);
            }
            MaterialCardView card = new MaterialCardView(ctx);
            RecyclerView.LayoutParams cardLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(cardLp);
            card.setRadius(dp(16));
            card.setCardElevation(0);
            card.setStrokeWidth(0);
            card.setCardBackgroundColor(Color.parseColor("#EAECE4"));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(12), dp(14));

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(textCol, textLp);

            TextView tvName = new TextView(ctx);
            tvName.setTextSize(16f);
            tvName.setTextColor(Color.parseColor("#1B1C18"));
            textCol.addView(tvName);

            TextView tvSub = new TextView(ctx);
            tvSub.setTextSize(12.5f);
            tvSub.setTextColor(Color.parseColor("#5C6057"));
            textCol.addView(tvSub);

            ImageButton btnEdit = new ImageButton(ctx);
            btnEdit.setImageResource(android.R.drawable.ic_menu_edit);
            btnEdit.setBackground(new GradientDrawable());
            row.addView(btnEdit, new LinearLayout.LayoutParams(dp(40), dp(40)));

            ImageButton btnDel = new ImageButton(ctx);
            btnDel.setImageResource(android.R.drawable.ic_menu_delete);
            btnDel.setBackground(new GradientDrawable());
            row.addView(btnDel, new LinearLayout.LayoutParams(dp(40), dp(40)));

            card.addView(row);
            return new ItemVH(card, tvName, tvSub, btnEdit, btnDel);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            EngineRow row = items.get(position);
            if (holder instanceof TextVH) {
                ((TextVH) holder).tv.setText(row.text);
            } else if (holder instanceof ItemVH) {
                ItemVH vh = (ItemVH) holder;
                vh.tvName.setText(row.text);
                vh.tvSub.setText(row.subtitle);
                vh.btnEdit.setOnClickListener(v -> listener.onEdit(row.engine));
                vh.btnDel.setOnClickListener(v -> listener.onDelete(row.engine));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class TextVH extends RecyclerView.ViewHolder {
            final TextView tv;
            TextVH(TextView tv) { super(tv); this.tv = tv; }
        }

        static class ItemVH extends RecyclerView.ViewHolder {
            final TextView tvName, tvSub;
            final ImageButton btnEdit, btnDel;
            ItemVH(View root, TextView tvName, TextView tvSub, ImageButton btnEdit, ImageButton btnDel) {
                super(root);
                this.tvName = tvName;
                this.tvSub = tvSub;
                this.btnEdit = btnEdit;
                this.btnDel = btnDel;
            }
        }
    }
}
