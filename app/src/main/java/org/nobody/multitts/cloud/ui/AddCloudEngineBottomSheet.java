package org.nobody.multitts.cloud.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import java.util.concurrent.Executors;
import org.nobody.multitts.R;
import org.nobody.multitts.cloud.CloudAiTtsEngine;
import org.nobody.multitts.cloud.CloudTtsConfig;
import org.nobody.multitts.cloud.CloudTtsProviderType;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 对齐 Legado MD3 截图的「添加/编辑云引擎」BottomSheet，包含引擎类型、名称、Base URL、API Key、模型与连接测试。
 */
public class AddCloudEngineBottomSheet extends BottomSheetDialogFragment {

    public interface OnSavedListener {
        void onSaved(Engine engine);
    }

    private Engine targetEngine;
    private CloudTtsConfig config;
    private OnSavedListener savedListener;

    public static AddCloudEngineBottomSheet newInstance(Engine engine) {
        AddCloudEngineBottomSheet sheet = new AddCloudEngineBottomSheet();
        sheet.targetEngine = engine;
        sheet.config = CloudTtsConfig.fromEngine(engine);
        return sheet;
    }

    public void setOnSavedListener(OnSavedListener listener) {
        this.savedListener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_cloud_engine_form, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        AutoCompleteTextView spinnerProvider = view.findViewById(R.id.dropdown_provider);
        TextInputEditText etName = view.findViewById(R.id.et_engine_name);
        TextInputEditText etBaseUrl = view.findViewById(R.id.et_base_url);
        TextInputEditText etApiKey = view.findViewById(R.id.et_api_key);
        TextInputEditText etModel = view.findViewById(R.id.et_model);
        MaterialButton btnTest = view.findViewById(R.id.btn_test_connection);
        MaterialButton btnSave = view.findViewById(R.id.btn_save_engine);
        TextView tvTestResult = view.findViewById(R.id.tv_test_result);

        CloudTtsProviderType[] providers = CloudTtsProviderType.values();
        String[] labels = new String[providers.length];
        for (int i = 0; i < providers.length; i++) {
            labels[i] = providers[i].displayName;
        }
        spinnerProvider.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, labels));
        spinnerProvider.setText(config.providerType.displayName, false);
        spinnerProvider.setOnItemClickListener((parent, v, pos, id) -> {
            config.providerType = providers[pos];
            etName.setText(config.providerType.displayName);
            etBaseUrl.setText(config.providerType.defaultBaseUrl);
            etModel.setText(config.providerType.defaultModel);
        });

        etName.setText(config.name);
        etBaseUrl.setText(config.baseUrl);
        etApiKey.setText(config.apiKey);
        etModel.setText(config.model);

        btnTest.setOnClickListener(v -> {
            syncFromInputs(etName, etBaseUrl, etApiKey, etModel);
            btnTest.setEnabled(false);
            tvTestResult.setText("正在测试连接...");
            Executors.newSingleThreadExecutor().execute(() -> {
                String msg = CloudAiTtsEngine.testConnection(config);
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        btnTest.setEnabled(true);
                        tvTestResult.setText(msg);
                    });
                }
            });
        });

        btnSave.setOnClickListener(v -> {
            syncFromInputs(etName, etBaseUrl, etApiKey, etModel);
            targetEngine.name = config.name;
            targetEngine.type = config.providerType.engineType;
            targetEngine.url = config.effectiveBaseUrl();
            targetEngine.param = config.toParamJson();
            // TODO: 写入 AppDatabase.engineDao()
            Toast.makeText(requireContext(), "已保存云引擎：" + targetEngine.name, Toast.LENGTH_SHORT).show();
            if (savedListener != null) {
                savedListener.onSaved(targetEngine);
            }
            dismiss();
        });
    }

    private void syncFromInputs(TextInputEditText etName, TextInputEditText etBaseUrl, TextInputEditText etApiKey, TextInputEditText etModel) {
        config.name = etName.getText() != null ? etName.getText().toString().trim() : "";
        config.baseUrl = etBaseUrl.getText() != null ? etBaseUrl.getText().toString().trim() : "";
        config.apiKey = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
        config.model = etModel.getText() != null ? etModel.getText().toString().trim() : config.providerType.defaultModel;
    }
}
