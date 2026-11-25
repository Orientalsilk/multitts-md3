package org.nobody.multitts.cloud;

import android.text.TextUtils;
import java.util.Locale;
import org.json.JSONObject;
import org.nobody.multitts.tts.engine.Engine;

public class CloudTtsConfig {

    public CloudTtsProviderType providerType = CloudTtsProviderType.OPENAI;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public String secretKey = "";
    public String region = "";
    public String appId = "";
    public String model = CloudTtsProviderType.OPENAI.defaultModel;
    public String voice = CloudTtsProviderType.OPENAI.defaultVoice;
    public String style = "";
    public String instructions = "";
    public String referenceAudioBase64 = "";
    public String referenceAudioName = "";
    public int maxConcurrency = 2;
    public int timeoutSeconds = 60;

    public String effectiveBaseUrl() {
        String raw = !TextUtils.isEmpty(baseUrl) ? baseUrl.trim() : providerType.defaultBaseUrl;
        int commaIdx = raw.indexOf(",={");
        if (commaIdx > 0) {
            raw = raw.substring(0, commaIdx).trim();
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private static CloudTtsProviderType inferFromUrlOrName(String url, String name) {
        String combined = ((url != null ? url : "") + " " + (name != null ? name : "")).toLowerCase(Locale.US);
        if (combined.contains("fish.audio") || combined.contains("fish audio") || combined.contains("fishaudio")) {
            return CloudTtsProviderType.FISHAUDIO;
        }
        if (combined.contains("xiaomimimo") || combined.contains("mimo")) {
            return CloudTtsProviderType.MIMO;
        }
        if (combined.contains("generativelanguage") || combined.contains("gemini")) {
            return CloudTtsProviderType.GEMINI;
        }
        if (combined.contains("speech.microsoft.com") || combined.contains("azure")) {
            return CloudTtsProviderType.AZURE;
        }
        if (combined.contains("dashscope") || combined.contains("aliyuncs") || combined.contains("qwen") || combined.contains("alibaba")) {
            return CloudTtsProviderType.ALIBABA;
        }
        if (combined.contains("polly") || combined.contains("amazonaws")) {
            return CloudTtsProviderType.POLLY;
        }
        if (combined.contains("openspeech.bytedance") || combined.contains("volcengine") || combined.contains("volcano")) {
            return CloudTtsProviderType.VOLCENGINE;
        }
        return null;
    }

    public static CloudTtsConfig fromEngine(Engine engine) {
        CloudTtsConfig cfg = new CloudTtsConfig();
        if (engine == null) {
            return cfg;
        }
        CloudTtsProviderType matched = CloudTtsProviderType.fromEngineType(engine.type);
        if (matched == null) {
            matched = inferFromUrlOrName(engine.url, engine.name);
        }
        if (matched != null) {
            cfg.providerType = matched;
            cfg.model = matched.defaultModel;
            cfg.voice = matched.defaultVoice;
        }
        if (!TextUtils.isEmpty(engine.name)) {
            cfg.name = engine.name;
        }
        if (!TextUtils.isEmpty(engine.url)) {
            String clean = engine.url.trim();
            int commaIdx = clean.indexOf(",={");
            if (commaIdx > 0) {
                clean = clean.substring(0, commaIdx).trim();
            }
            cfg.baseUrl = clean;
        }
        if (!TextUtils.isEmpty(engine.param)) {
            try {
                JSONObject json = new JSONObject(engine.param);
                String pType = json.optString("providerType", "");
                CloudTtsProviderType pMatched = CloudTtsProviderType.fromEngineType(pType);
                if (pMatched != null) {
                    cfg.providerType = pMatched;
                }
                if (json.has("baseUrl") && TextUtils.isEmpty(cfg.baseUrl)) {
                    cfg.baseUrl = json.optString("baseUrl", "");
                }
                cfg.apiKey = json.optString("apiKey", "");
                cfg.secretKey = json.optString("secretKey", "");
                cfg.region = json.optString("region", "");
                cfg.appId = json.optString("appId", "");
                String m = json.optString("model", "");
                cfg.model = !TextUtils.isEmpty(m) ? m : cfg.providerType.defaultModel;
                String v = json.optString("voice", "");
                cfg.voice = !TextUtils.isEmpty(v) ? v : cfg.providerType.defaultVoice;
                cfg.style = json.optString("style", "");
                cfg.instructions = json.optString("instructions", "");
                cfg.referenceAudioBase64 = json.optString("referenceAudioBase64", "");
                cfg.referenceAudioName = json.optString("referenceAudioName", "");
                cfg.maxConcurrency = json.optInt("maxConcurrency", 2);
                cfg.timeoutSeconds = json.optInt("timeoutSeconds", 60);
            } catch (Throwable ignored) {
            }
        }
        return cfg;
    }

    public String toParamJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("providerType", providerType.engineType);
            json.put("baseUrl", baseUrl != null ? baseUrl : "");
            json.put("apiKey", apiKey != null ? apiKey : "");
            json.put("secretKey", secretKey != null ? secretKey : "");
            json.put("region", region != null ? region : "");
            json.put("appId", appId != null ? appId : "");
            json.put("model", model != null ? model : "");
            json.put("voice", voice != null ? voice : "");
            json.put("style", style != null ? style : "");
            json.put("instructions", instructions != null ? instructions : "");
            json.put("referenceAudioBase64", referenceAudioBase64 != null ? referenceAudioBase64 : "");
            json.put("referenceAudioName", referenceAudioName != null ? referenceAudioName : "");
            json.put("maxConcurrency", maxConcurrency);
            json.put("timeoutSeconds", timeoutSeconds);
            return json.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }
}
