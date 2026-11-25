package org.nobody.multitts.cloud.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.nobody.multitts.R;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 对齐 Legado MD3「朗读引擎与音色」的 Material 3 引擎管理页面。
 * 支持按「云 TTS」「HTTP 协议 TTS」分组展示，并通过 BottomSheet 添加/编辑云引擎。
 */
public class CloudEngineActivity extends AppCompatActivity {

    private final List<EngineRow> rows = new ArrayList<>();
    private EngineAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_engine_md3);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_md3);
        toolbar.setNavigationOnClickListener(v -> finish());

        TabLayout tabLayout = findViewById(R.id.tab_voice_engine);
        tabLayout.addTab(tabLayout.newTab().setText("音色"));
        tabLayout.addTab(tabLayout.newTab().setText("引擎"), true);

        RecyclerView rv = findViewById(R.id.rv_cloud_engines);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EngineAdapter(rows, new EngineAdapter.Listener() {
            @Override
            public void onEdit(Engine engine) {
                AddCloudEngineBottomSheet sheet = AddCloudEngineBottomSheet.newInstance(engine);
                sheet.setOnSavedListener(saved -> reloadEngines());
                sheet.show(getSupportFragmentManager(), "edit_cloud_engine");
            }

            @Override
            public void onDelete(Engine engine) {
                // TODO: 调用 AppDatabase.engineDao().delete(engine) 后刷新
                reloadEngines();
            }
        });
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add_engine);
        fab.setOnClickListener(v -> {
            Engine empty = new Engine(UUID.randomUUID().toString(), "");
            empty.type = CloudTtsProviderType.OPENAI.engineType;
            AddCloudEngineBottomSheet sheet = AddCloudEngineBottomSheet.newInstance(empty);
            sheet.setOnSavedListener(saved -> reloadEngines());
            sheet.show(getSupportFragmentManager(), "add_cloud_engine");
        });

        reloadEngines();
    }

    private void reloadEngines() {
        rows.clear();
        // TODO: 从 K6.d.c() / AppDatabase.engineDao() 加载全部 Engine 并按 CloudTtsProviderType 分组
        List<Engine> cloudList = new ArrayList<>();
        List<Engine> httpList = new ArrayList<>();

        if (!cloudList.isEmpty()) {
            rows.add(EngineRow.header("云 TTS"));
            for (Engine e : cloudList) {
                CloudTtsConfig cfg = CloudTtsConfig.fromEngine(e);
                rows.add(EngineRow.item(e, cfg.providerType.displayName));
            }
        }
        if (!httpList.isEmpty()) {
            rows.add(EngineRow.header("HTTP 协议 TTS"));
            for (Engine e : httpList) {
                rows.add(EngineRow.item(e, "HTTP TTS"));
            }
        }
        adapter.notifyDataSetChanged();
    }

    static class EngineRow {
        final boolean isHeader;
        final String headerTitle;
        final Engine engine;
        final String subtitle;

        private EngineRow(boolean isHeader, String headerTitle, Engine engine, String subtitle) {
            this.isHeader = isHeader;
            this.headerTitle = headerTitle;
            this.engine = engine;
            this.subtitle = subtitle;
        }

        static EngineRow header(String title) {
            return new EngineRow(true, title, null, null);
        }

        static EngineRow item(Engine engine, String subtitle) {
            return new EngineRow(false, null, engine, subtitle);
        }
    }

    static class EngineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface Listener {
            void onEdit(Engine engine);
            void onDelete(Engine engine);
        }

        private final List<EngineRow> items;
        private final Listener listener;

        EngineAdapter(List<EngineRow> items, Listener listener) {
            this.items = items;
            this.listener = listener;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isHeader ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                TextView tv = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                tv.setPadding(48, 32, 48, 12);
                tv.setTextSize(13f);
                return new HeaderVH(tv);
            }
            View card = inflater.inflate(R.layout.item_cloud_engine_card, parent, false);
            return new ItemVH(card);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            EngineRow row = items.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).title.setText(row.headerTitle);
            } else if (holder instanceof ItemVH) {
                ItemVH vh = (ItemVH) holder;
                vh.title.setText(row.engine.name);
                vh.subtitle.setText(row.subtitle);
                vh.btnEdit.setOnClickListener(v -> listener.onEdit(row.engine));
                vh.btnDelete.setOnClickListener(v -> listener.onDelete(row.engine));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            final TextView title;
            HeaderVH(TextView tv) {
                super(tv);
                this.title = tv;
            }
        }

        static class ItemVH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;
            final ImageButton btnEdit;
            final ImageButton btnDelete;

            ItemVH(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.tv_engine_name);
                subtitle = itemView.findViewById(R.id.tv_engine_subtitle);
                btnEdit = itemView.findViewById(R.id.btn_edit_engine);
                btnDelete = itemView.findViewById(R.id.btn_delete_engine);
            }
        }
    }
}
