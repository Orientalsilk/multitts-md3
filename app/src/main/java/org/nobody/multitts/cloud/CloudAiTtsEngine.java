package org.nobody.multitts.cloud;

import R6.g;
import R6.h;
import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nobody.multitts.tts.engine.Engine;
import org.nobody.multitts.tts.speaker.Speaker;

/**
 * 统一云端 AI TTS 引擎适配器（纯系统 HttpURLConnection 实现，彻底摆脱第三方 OkHttp 混淆类名依赖）。
 */
public class CloudAiTtsEngine implements h {

    private final Context context;
    private final Engine engine;
    private final Speaker speaker;
    private final CloudTtsConfig config;
    private final AtomicReference<HttpURLConnection> activeConn = new AtomicReference<>();
    private volatile boolean cancelled = false;

    public CloudAiTtsEngine(Context context, Engine engine, Speaker speaker) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.engine = engine;
        this.speaker = speaker;
        this.config = CloudTtsConfig.fromEngine(engine);
        if (speaker != null && !TextUtils.isEmpty(speaker.param)) {
            this.config.voice = speaker.param;
        }
    }

    @Override
    public void c() {
        cancelled = false;
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection conn = activeConn.getAndSet(null);
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void release() {
        cancel();
    }

    @Override
    public void d(float speed, float pitch, String text, g callback) {
        cancelled = false;
        String engineKey = engine != null && !TextUtils.isEmpty(engine.code) ? engine.code : config.providerType.engineType;
        try {
            SynthesisLimiter.acquire(engineKey, config.maxConcurrency);
            if (cancelled) {
                return;
            }
            byte[] audioBytes = synthesizeInternal(speed, pitch, text);
            if (cancelled || audioBytes == null || audioBytes.length == 0) {
                return;
            }
            deliverAudioOrError(callback, audioBytes, null);
        } catch (Exception e) {
            if (!cancelled) {
                deliverAudioOrError(callback, null, e.getMessage() != null ? e.getMessage() : "Cloud TTS Error");
            }
        } finally {
            SynthesisLimiter.release(engineKey);
        }
    }

    /**
     * 供 UI「连接测试」按钮调用，返回耗时或错误信息。
     */
    public static String testConnection(CloudTtsConfig testConfig) {
        long start = System.currentTimeMillis();
        try {
            Engine dummy = new Engine();
            dummy.type = testConfig.providerType.engineType;
            dummy.url = testConfig.baseUrl;
            dummy.param = testConfig.toParamJson();
            CloudAiTtsEngine engine = new CloudAiTtsEngine(null, dummy, null);
            byte[] result = engine.synthesizeInternal(1.0f, 1.0f, "连接测试成功");
            long cost = System.currentTimeMillis() - start;
            if (result != null && result.length > 0) {
                return "连接成功（耗时 " + cost + " ms，音频 " + result.length + " 字节）";
            }
            return "连接失败：返回音频为空";
        } catch (Exception e) {
            return "连接失败：" + e.getMessage();
        }
    }

    private byte[] synthesizeInternal(float speed, float pitch, String text) throws Exception {
        switch (config.providerType) {
            case OPENAI:
                return callOpenAi(speed, text);
            case GEMINI:
                return callGemini(text);
            case MIMO:
                return callMimoSse(text);
            case FISHAUDIO:
                return callFishAudio(text);
            case VOLCENGINE:
                return callVolcengine(speed, pitch, text);
            case AZURE:
                return callAzure(speed, text);
            case ALIBABA:
                return callAlibabaDashScope(speed, text);
            default:
                return callOpenAi(speed, text);
        }
    }

    private byte[] callOpenAi(float speed, String text) throws Exception {
        String base = config.effectiveBaseUrl();
        String url = base.endsWith("/audio/speech") ? base : base + "/audio/speech";
        float clampedSpeed = Math.max(0.25f, Math.min(4.0f, speed));
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("input", text);
        if (config.voice.startsWith("voice_")) {
            body.put("voice", new JSONObject().put("id", config.voice));
        } else {
            body.put("voice", config.voice);
        }
        body.put("response_format", "wav");
        body.put("speed", clampedSpeed);
        if (!config.model.startsWith("tts-1") && !TextUtils.isEmpty(config.instructions)) {
            body.put("instructions", config.instructions);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        return executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] callFishAudio(String text) throws Exception {
        String base = config.effectiveBaseUrl();
        String url = base.endsWith("/tts") ? base : base + "/tts";
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("format", "wav");
        if (!TextUtils.isEmpty(config.voice)) {
            body.put("reference_id", config.voice);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        if (!TextUtils.isEmpty(config.model)) {
            headers.put("model", config.model);
        }
        return executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] callGemini(String text) throws Exception {
        String base = config.effectiveBaseUrl().replaceFirst("/openai$", "");
        String model = config.model.startsWith("models/") ? config.model.substring(7) : config.model;
        String url = base + "/models/" + model + ":generateContent";

        String prompt = TextUtils.isEmpty(config.instructions)
                ? text
                : "### DIRECTOR'S NOTES\n" + config.instructions + "\n\n### TRANSCRIPT\n" + text;

        JSONObject voiceConfig = new JSONObject()
                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", config.voice));
        JSONObject genConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("voiceConfig", voiceConfig));

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(
                        new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", genConfig);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");

        String jsonStr = new String(executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        JSONObject inlineData = resp.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("inlineData");
        String mimeType = inlineData.optString("mimeType", "audio/pcm");
        byte[] raw = Base64.decode(inlineData.getString("data"), Base64.DEFAULT);
        if (mimeType.contains("wav") || mimeType.contains("mpeg") || mimeType.contains("mp3")) {
            return raw;
        }
        return pcm16MonoToWav(raw, 24000);
    }

    private byte[] callMimoSse(String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/chat/completions";
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("messages", new JSONArray().put(
                        new JSONObject().put("role", "assistant").put("content", text)))
                .put("audio", new JSONObject().put("format", "wav").put("voice", config.voice))
                .put("stream", true);

        HttpURLConnection conn = openConnection("POST", url);
        conn.setRequestProperty("api-key", config.apiKey);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        activeConn.set(conn);

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        try {
            byte[] payloadBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payloadBytes);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readError(conn));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && !cancelled) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    JSONObject audioObj = delta.optJSONObject("audio");
                    if (audioObj != null && audioObj.has("data")) {
                        pcmOut.write(Base64.decode(audioObj.getString("data"), Base64.DEFAULT));
                    }
                }
            }
        } finally {
            activeConn.compareAndSet(conn, null);
            conn.disconnect();
        }
        return pcm16MonoToWav(pcmOut.toByteArray(), 24000);
    }

    private byte[] callVolcengine(float speed, float pitch, String text) throws Exception {
        String url = config.effectiveBaseUrl();
        float speedRatio = Math.max(0.2f, Math.min(3.0f, speed));
        float pitchRatio = Math.max(0.1f, Math.min(3.0f, pitch));

        JSONObject audio = new JSONObject()
                .put("voice_type", config.voice)
                .put("encoding", "wav")
                .put("speed_ratio", speedRatio)
                .put("pitch_ratio", pitchRatio)
                .put("volume_ratio", 1.0);
        if (!TextUtils.isEmpty(config.style)) {
            audio.put("emotion", config.style);
        }

        JSONObject body = new JSONObject()
                .put("app", new JSONObject().put("appid", config.appId).put("token", config.apiKey).put("cluster", config.model))
                .put("user", new JSONObject().put("uid", "multitts"))
                .put("audio", audio)
                .put("request", new JSONObject()
                        .put("reqid", String.valueOf(System.nanoTime()))
                        .put("text", text)
                        .put("operation", "query"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer;" + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");

        String jsonStr = new String(executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        int code = resp.optInt("code", -1);
        if (code != 0 && code != 3000) {
            throw new IOException("Volcengine error " + code + ": " + resp.optString("message"));
        }
        return Base64.decode(resp.getString("data"), Base64.DEFAULT);
    }

    private byte[] callAzure(float speed, String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/cognitiveservices/v1";
        String ratePct = Math.round((speed - 1.0f) * 100) + "%";
        String ssml = "<speak version='1.0' xml:lang='zh-CN'><voice name='" + config.voice + "'>"
                + "<prosody rate='" + ratePct + "'>" + escapeXml(text) + "</prosody></voice></speak>";

        Map<String, String> headers = new HashMap<>();
        headers.put("Ocp-Apim-Subscription-Key", config.apiKey);
        headers.put("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm");
        headers.put("Content-Type", "application/ssml+xml; charset=utf-8");
        return executeBytes("POST", url, headers, ssml.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] callAlibabaDashScope(float speed, String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/services/aigc/multimodal-generation/generation";
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("input", new JSONObject().put("text", text).put("voice", config.voice))
                .put("parameters", new JSONObject().put("speed", Math.max(0.5f, Math.min(2.0f, speed))));

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");

        String jsonStr = new String(executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        String audioUrl = resp.getJSONObject("output").getJSONObject("audio").getString("url");
        return executeBytes("GET", audioUrl, new HashMap<>(), null);
    }

    private HttpURLConnection openConnection(String method, String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
        }
        return conn;
    }

    private byte[] executeBytes(String method, String urlStr, Map<String, String> headers, byte[] body) throws IOException {
        HttpURLConnection conn = openConnection(method, urlStr);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        activeConn.set(conn);
        try {
            if (body != null) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readError(conn));
            }
            try (InputStream is = conn.getInputStream()) {
                return readAllBytes(is);
            }
        } finally {
            activeConn.compareAndSet(conn, null);
            conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream err = conn.getErrorStream();
            if (err == null) {
                return conn.getResponseMessage();
            }
            return new String(readAllBytes(err), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] pcm16MonoToWav(byte[] pcm, int sampleRate) {
        int totalDataLen = pcm.length + 36;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(totalDataLen);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);

        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void deliverAudioOrError(g callback, byte[] wavBytes, String errorMsg) {
        if (callback == null) {
            return;
        }
        if (errorMsg != null && callback instanceof p020c2.a) {
            ((p020c2.a) callback).u(errorMsg);
            return;
        }
        // TODO: 将 wavBytes 传给 p020c2.a 音频写入方法
    }
}
