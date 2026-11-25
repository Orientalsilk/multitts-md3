package org.nobody.multitts.cloud;

import android.text.TextUtils;
import org.json.JSONObject;
import org.nobody.multitts.tts.engine.Engine;

/**
 * 存储在 Engine.param 中的云端 AI TTS 配置模型。
 */
public class CloudTtsConfig {
    public CloudTtsProviderType providerType = CloudTtsProviderType.OPENAI;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public String appId = ""; // Volcengine 等需要 AppId
    public String model = "";
    public String voice = "";
    public String style = "";
    public String instructions = "";
    public int maxConcurrency = 3;
    public int timeoutSeconds = 60;

    public static CloudTtsConfig fromEngine(Engine engine) {
        CloudTtsConfig config = new CloudTtsConfig();
        if (engine == null) {
            return config;
        }
        CloudTtsProviderType matched = CloudTtsProviderType.fromEngineType(engine.type);
        if (matched != null) {
            config.providerType = matched;
        }
        config.name = engine.name != null ? engine.name : config.providerType.displayName;
        config.baseUrl = !TextUtils.isEmpty(engine.url) ? engine.url.trim() : config.providerType.defaultBaseUrl;
        config.model = config.providerType.defaultModel;
        config.voice = config.providerType.defaultVoice;

        if (!TextUtils.isEmpty(engine.param)) {
            try {
                JSONObject json = new JSONObject(engine.param);
                String pType = json.optString("providerType", "");
                if (!TextUtils.isEmpty(pType)) {
                    CloudTtsProviderType parsed = CloudTtsProviderType.fromEngineType(pType);
                    if (parsed != null) {
                        config.providerType = parsed;
                    }
                }
                if (json.has("baseUrl") && !TextUtils.isEmpty(json.optString("baseUrl"))) {
                    config.baseUrl = json.optString("baseUrl").trim();
                }
                config.apiKey = json.optString("apiKey", "");
                config.appId = json.optString("appId", "");
                config.model = json.optString("model", config.providerType.defaultModel);
                config.voice = json.optString("voice", config.providerType.defaultVoice);
                config.style = json.optString("style", "");
                config.instructions = json.optString("instructions", "");
                config.maxConcurrency = Math.max(1, Math.min(8, json.optInt("maxConcurrency", 3)));
                config.timeoutSeconds = Math.max(10, Math.min(180, json.optInt("timeoutSeconds", 60)));
            } catch (Exception ignored) {
            }
        }
        return config;
    }

    public String toParamJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("providerType", providerType.engineType);
            json.put("baseUrl", baseUrl);
            json.put("apiKey", apiKey);
            json.put("appId", appId);
            json.put("model", model);
            json.put("voice", voice);
            json.put("style", style);
            json.put("instructions", instructions);
            json.put("maxConcurrency", maxConcurrency);
            json.put("timeoutSeconds", timeoutSeconds);
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String effectiveBaseUrl() {
        String url = TextUtils.isEmpty(baseUrl) ? providerType.defaultBaseUrl : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
