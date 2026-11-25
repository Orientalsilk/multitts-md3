package org.nobody.multitts.cloud;

import R6.g;
import R6.h;
import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nobody.multitts.tts.engine.Engine;
import org.nobody.multitts.tts.speaker.Speaker;

/**
 * 统一云端 AI TTS 引擎适配器，实现 MultiTTS 原生 R6.h 接口。
 * 直接复用包内已集成的 OkHttp3，支持 OpenAI / Gemini / MiMo (SSE) / Volcengine / 阿里云等。
 */
public class CloudAiTtsEngine implements h {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final MediaType SSML_MEDIA = MediaType.get("application/ssml+xml; charset=utf-8");

    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final Context context;
    private final Engine engine;
    private final Speaker speaker;
    private final CloudTtsConfig config;
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private volatile boolean cancelled = false;

    public CloudAiTtsEngine(Context context, Engine engine, Speaker speaker) {
        this.context = context.getApplicationContext();
        this.engine = engine;
        this.speaker = speaker;
        this.config = CloudTtsConfig.fromEngine(engine);
        if (speaker != null && !TextUtils.isEmpty(speaker.param)) {
            this.config.voice = speaker.param;
        }
    }

    @Override
    public void c() {
        // 初始化：OkHttpClient 为全局复用，此处无需额外阻塞操作
        cancelled = false;
    }

    @Override
    public void cancel() {
        cancelled = true;
        Call call = activeCall.getAndSet(null);
        if (call != null) {
            call.cancel();
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
            // 通过 MultiTTS 的 R6.g (p020c2.a) 回调写入音频流
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
            case VOLCENGINE:
                return callVolcengine(speed, pitch, text);
            case AZURE:
                return callAzure(speed, pitch, text);
            case ALIBABA:
                return callAlibabaDashScope(speed, text);
            default:
                return callOpenAi(speed, text);
        }
    }

    private byte[] callOpenAi(float speed, String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/audio/speech";
        if (config.effectiveBaseUrl().endsWith("/audio/speech")) {
            url = config.effectiveBaseUrl();
        }
        float clampedSpeed = Math.max(0.25f, Math.min(4.0f, speed));
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("input", text);
        if (config.voice.startsWith("voice_")) {
            JSONObject voiceObj = new JSONObject();
            voiceObj.put("id", config.voice);
            body.put("voice", voiceObj);
        } else {
            body.put("voice", config.voice);
        }
        body.put("response_format", "wav");
        body.put("speed", clampedSpeed);
        if (!config.model.startsWith("tts-1") && !TextUtils.isEmpty(config.instructions)) {
            body.put("instructions", config.instructions);
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();
        return executeBytes(request);
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
        JSONObject speechConfig = new JSONObject().put("voiceConfig", voiceConfig);
        JSONObject genConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig);

        JSONObject content = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(content))
                .put("generationConfig", genConfig);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", config.apiKey)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        String jsonStr = new String(executeBytes(request), StandardCharsets.UTF_8);
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
        JSONObject audio = new JSONObject()
                .put("format", "wav")
                .put("voice", config.voice);
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", text));
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("audio", audio)
                .put("stream", true);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("api-key", config.apiKey)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        Call call = SHARED_CLIENT.newCall(request);
        activeCall.set(call);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
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
                    byte[] decoded = Base64.decode(audioObj.getString("data"), Base64.DEFAULT);
                    pcmOut.write(decoded);
                }
            }
        } finally {
            activeCall.compareAndSet(call, null);
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

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer;" + config.apiKey)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        String jsonStr = new String(executeBytes(request), StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        int code = resp.optInt("code", -1);
        if (code != 0 && code != 3000) {
            throw new IOException("Volcengine error " + code + ": " + resp.optString("message"));
        }
        return Base64.decode(resp.getString("data"), Base64.DEFAULT);
    }

    private byte[] callAzure(float speed, float pitch, String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/cognitiveservices/v1";
        String ratePct = Math.round((speed - 1.0f) * 100) + "%";
        String ssml = "<speak version='1.0' xml:lang='zh-CN'><voice name='" + config.voice + "'>"
                + "<prosody rate='" + ratePct + "'>" + escapeXml(text) + "</prosody></voice></speak>";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", config.apiKey)
                .addHeader("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
                .post(RequestBody.create(ssml, SSML_MEDIA))
                .build();
        return executeBytes(request);
    }

    private byte[] callAlibabaDashScope(float speed, String text) throws Exception {
        String url = config.effectiveBaseUrl() + "/services/aigc/multimodal-generation/generation";
        JSONObject input = new JSONObject()
                .put("text", text)
                .put("voice", config.voice);
        JSONObject params = new JSONObject()
                .put("speed", Math.max(0.5f, Math.min(2.0f, speed)));
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("input", input)
                .put("parameters", params);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();
        String jsonStr = new String(executeBytes(request), StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        String audioUrl = resp.getJSONObject("output").getJSONObject("audio").getString("url");
        return executeBytes(new Request.Builder().url(audioUrl).get().build());
    }

    private byte[] executeBytes(Request request) throws IOException {
        Call call = SHARED_CLIENT.newCall(request);
        activeCall.set(call);
        try (Response response = call.execute()) {
            ResponseBody respBody = response.body();
            if (!response.isSuccessful() || respBody == null) {
                String err = respBody != null ? respBody.string() : response.message();
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
            return respBody.bytes();
        } finally {
            activeCall.compareAndSet(call, null);
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

    /**
     * 桥接 MultiTTS 的 R6.g (p020c2.a) 回调：
     * 实际挂钩时，请对照反编译后的 p020c2.a 音频写入方法名替换或补全此桥接。
     */
    private static void deliverAudioOrError(g callback, byte[] wavBytes, String errorMsg) {
        if (callback == null) {
            return;
        }
        if (errorMsg != null && callback instanceof p020c2.a) {
            ((p020c2.a) callback).u(errorMsg);
            return;
        }
        // TODO: 将 wavBytes (或剥离 44 字节头后的 PCM) 传给 p020c2.a 的音频数据写入方法
    }
}
