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
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nobody.multitts.tts.engine.Engine;
import org.nobody.multitts.tts.speaker.Speaker;

/**
 * 1:1 移植自 legado-with-MD3 的成熟云端 TTS 方案（纯系统 HttpURLConnection 实现，反射对接 MultiTTS 原生 c2.a 音频管线）。
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
        if (speaker != null) {
            boolean isVoiceClone = this.config.providerType == CloudTtsProviderType.MIMO
                    && "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(this.config.model);
            boolean isVoiceDesign = this.config.providerType == CloudTtsProviderType.MIMO
                    && "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(this.config.model);
            if (isVoiceClone) {
                if (!TextUtils.isEmpty(speaker.param)
                        && (speaker.param.startsWith("cloud_file:")
                            || speaker.param.startsWith("data:audio/")
                            || speaker.param.length() > 128)) {
                    this.config.referenceAudioBase64 = speaker.param;
                }
            } else if (isVoiceDesign) {
                if (!TextUtils.isEmpty(speaker.param)) {
                    this.config.instructions = speaker.param;
                }
            } else if (!TextUtils.isEmpty(speaker.code)
                    && !speaker.code.equals(engine.code)
                    && !speaker.code.startsWith(engine.code + "_")) {
                this.config.voice = speaker.code;
            } else if (!TextUtils.isEmpty(speaker.param)) {
                this.config.voice = speaker.param;
            }
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
        String engineKey = engine != null && !TextUtils.isEmpty(engine.code)
                ? engine.code
                : config.providerType.engineType;
        boolean acquired = false;
        try {
            SynthesisLimiter.acquire(engineKey, config.maxConcurrency);
            acquired = true;
            if (cancelled) {
                return;
            }
            invokeCallbackStart(callback);
            byte[] audioBytes = synthesizeInternal(speed, pitch, text);
            if (cancelled) {
                return;
            }
            if (audioBytes == null || audioBytes.length == 0) {
                invokeCallbackError(callback, "Cloud TTS returned empty audio");
                return;
            }
            deliverAudio(callback, audioBytes);
        } catch (Exception e) {
            if (!cancelled) {
                invokeCallbackError(callback, e.getMessage() != null ? e.getMessage() : "Cloud TTS Error");
            }
        } finally {
            if (acquired) {
                SynthesisLimiter.release(engineKey);
            }
        }
    }

    public static String testConnection(CloudTtsConfig testConfig) {
        return testConnection(null, testConfig);
    }

    public static String testConnection(Context ctx, CloudTtsConfig testConfig) {
        long start = System.currentTimeMillis();
        try {
            Engine dummy = new Engine();
            dummy.type = testConfig.providerType.engineType;
            dummy.url = testConfig.baseUrl;
            dummy.param = testConfig.toParamJson();
            CloudAiTtsEngine engine = new CloudAiTtsEngine(ctx, dummy, null);
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
                return callMimo(text);
            case FISHAUDIO:
                return callFishAudio(speed, text);
            case VOLCENGINE:
                return callVolcengine(speed, pitch, text);
            case AZURE:
                return callAzure(speed, pitch, text);
            case ALIBABA:
                return callAlibabaDashScope(text);
            case POLLY:
                return callAwsPolly(text);
            default:
                return callOpenAi(speed, text);
        }
    }

    private static String cleanBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int commaIdx = s.indexOf(",={");
        if (commaIdx > 0) {
            s = s.substring(0, commaIdx).trim();
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String resolveVoice(CloudTtsProviderType type) {
        String v = config.voice != null ? config.voice.trim() : "";
        if (type != CloudTtsProviderType.OPENAI && "alloy".equalsIgnoreCase(v)) {
            return type.defaultVoice;
        }
        if (TextUtils.isEmpty(v)) {
            return type.defaultVoice;
        }
        return v;
    }

    // 1. OpenAiCloudTtsProvider
    private byte[] callOpenAi(float speed, String text) throws Exception {
        String base = cleanBaseUrl(config.effectiveBaseUrl());
        String url = base.endsWith("/audio/speech") ? base : base + "/audio/speech";
        float clampedSpeed = Math.max(0.25f, Math.min(4.0f, speed));
        String voice = resolveVoice(CloudTtsProviderType.OPENAI);

        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("input", text);
        if (voice.startsWith("voice_")) {
            body.put("voice", new JSONObject().put("id", voice));
        } else {
            body.put("voice", voice);
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

    // 2. GeminiCloudTtsProvider
    private byte[] callGemini(String text) throws Exception {
        String base = cleanBaseUrl(config.effectiveBaseUrl()).replaceFirst("/openai$", "");
        String model = config.model.startsWith("models/") ? config.model.substring(7) : config.model;
        String url = base + "/models/" + model + ":generateContent";
        String voice = resolveVoice(CloudTtsProviderType.GEMINI);

        StringBuilder prompt = new StringBuilder("Synthesize the following transcript as speech.\n");
        if (!TextUtils.isEmpty(config.instructions)) {
            prompt.append("### DIRECTOR'S NOTES\n").append(config.instructions.trim()).append("\n");
        }
        prompt.append("### TRANSCRIPT\n").append(text);

        JSONObject voiceConfig = new JSONObject()
                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice));
        JSONObject genConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("voiceConfig", voiceConfig));

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(
                        new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt.toString())))))
                .put("generationConfig", genConfig);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");

        String jsonStr = new String(
                executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        JSONArray candidates = resp.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("Gemini 未返回候选结果：" + jsonStr);
        }
        JSONObject firstCand = candidates.getJSONObject(0);
        JSONObject content = firstCand.optJSONObject("content");
        JSONArray parts = content != null ? content.optJSONArray("parts") : null;
        JSONObject inlineData = null;
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null && p.has("inlineData")) {
                    inlineData = p.optJSONObject("inlineData");
                    break;
                }
            }
        }
        if (inlineData == null || TextUtils.isEmpty(inlineData.optString("data"))) {
            String reason = firstCand.optString("finishReason", "UNKNOWN");
            throw new IOException("Gemini 未返回音频数据 (finishReason=" + reason + ")");
        }
        String mimeType = inlineData.optString("mimeType", "audio/pcm").toLowerCase(Locale.US);
        byte[] raw = Base64.decode(inlineData.getString("data"), Base64.DEFAULT);
        if (mimeType.contains("wav") || mimeType.contains("mpeg") || mimeType.contains("mp3")) {
            return raw;
        }
        int sampleRate = 24000;
        int rateIdx = mimeType.indexOf("rate=");
        if (rateIdx >= 0) {
            try {
                String tail = mimeType.substring(rateIdx + 5).split("[;\\s]")[0];
                int parsed = Integer.parseInt(tail);
                if (parsed >= 8000 && parsed <= 96000) sampleRate = parsed;
            } catch (Throwable ignored) {}
        }
        return pcm16MonoToWav(raw, sampleRate);
    }

    // 3. MimoCloudTtsProvider
    private byte[] callMimo(String text) throws Exception {
        String base = cleanBaseUrl(config.effectiveBaseUrl());
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        String model = TextUtils.isEmpty(config.model) ? CloudTtsProviderType.MIMO.defaultModel : config.model.trim();
        boolean isVoiceClone = "mimo-v2.5-tts-voiceclone".equalsIgnoreCase(model);
        boolean isVoiceDesign = "mimo-v2.5-tts-voicedesign".equalsIgnoreCase(model);

        JSONArray messages = new JSONArray();
        if (isVoiceDesign) {
            String designPrompt = !TextUtils.isEmpty(config.instructions)
                    ? config.instructions.trim()
                    : "清晰自然、富有感染力的人声";
            messages.put(new JSONObject().put("role", "user").put("content", designPrompt));
        } else if (!TextUtils.isEmpty(config.instructions)) {
            messages.put(new JSONObject().put("role", "user").put("content", config.instructions.trim()));
        }
        String expression = !TextUtils.isEmpty(config.style) ? "(" + config.style.trim() + ")" : "";
        messages.put(new JSONObject().put("role", "assistant").put("content", expression + text));

        if (isVoiceClone || isVoiceDesign) {
            JSONObject audioObj = new JSONObject().put("format", "wav");
            if (isVoiceClone) {
                String refAudio = !TextUtils.isEmpty(config.referenceAudioBase64)
                        ? config.referenceAudioBase64.trim()
                        : resolveVoice(CloudTtsProviderType.MIMO);
                if (!TextUtils.isEmpty(refAudio) && refAudio.startsWith("cloud_file:")) {
                    refAudio = readCloneAudioFile(context, refAudio.substring("cloud_file:".length()));
                }
                if (TextUtils.isEmpty(refAudio)
                        || "mimo_default".equalsIgnoreCase(refAudio)
                        || "alloy".equalsIgnoreCase(refAudio)) {
                    throw new IOException("MiMo 声音克隆请先选择 MP3 / WAV 参考音频");
                }
                if (!refAudio.startsWith("data:audio/mpeg;base64,")
                        && !refAudio.startsWith("data:audio/wav;base64,")) {
                    refAudio = "data:audio/wav;base64," + refAudio;
                }
                audioObj.put("voice", refAudio);
            }
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("audio", audioObj)
                    .put("stream", false);

            Map<String, String> headers = new HashMap<>();
            headers.put("api-key", config.apiKey);
            headers.put("Content-Type", "application/json; charset=utf-8");

            String jsonStr = new String(
                    executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            JSONObject resp = new JSONObject(jsonStr);
            String b64 = resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getJSONObject("audio")
                    .getString("data");
            return Base64.decode(b64, Base64.DEFAULT);
        }

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("audio", new JSONObject().put("format", "pcm16").put("voice", resolveVoice(CloudTtsProviderType.MIMO)))
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && !cancelled) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        if ("[DONE]".equals(payload)) {
                            break;
                        }
                        continue;
                    }
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject first = choices.getJSONObject(0);
                    JSONObject delta = first.optJSONObject("delta");
                    if (delta == null) {
                        delta = first.optJSONObject("message");
                    }
                    if (delta == null) {
                        continue;
                    }
                    JSONObject audioObj = delta.optJSONObject("audio");
                    if (audioObj != null && audioObj.has("data") && !audioObj.isNull("data")) {
                        String b64 = audioObj.optString("data", "");
                        if (!b64.isEmpty()) {
                            pcmOut.write(Base64.decode(b64, Base64.DEFAULT));
                        }
                    }
                }
            }
        } finally {
            activeConn.compareAndSet(conn, null);
            conn.disconnect();
        }
        if (pcmOut.size() == 0) {
            throw new IOException("MiMo TTS 没有返回音频数据");
        }
        return pcm16MonoToWav(pcmOut.toByteArray(), 24000);
    }

    private static String readCloneAudioFile(Context ctx, String fileName) throws IOException {
        if (ctx == null || TextUtils.isEmpty(fileName)) {
            throw new IOException("无法读取本地参考音频文件（缺少上下文）");
        }
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "cloud_clone_audio");
        java.io.File f = new java.io.File(dir, fileName);
        if (!f.isFile()) {
            throw new IOException("参考音频文件不存在，请重新选择：" + fileName);
        }
        try (java.io.InputStream is = new java.io.FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // 4. Fish Audio (https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)
    private byte[] callFishAudio(float speed, String text) throws Exception {
        String base = cleanBaseUrl(config.effectiveBaseUrl());
        String url = base.endsWith("/tts") ? base : base + "/tts";
        String voice = resolveVoice(CloudTtsProviderType.FISHAUDIO);
        float clampedSpeed = Math.max(0.5f, Math.min(2.0f, speed));

        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("format", "wav");
        body.put("prosody", new JSONObject().put("speed", clampedSpeed).put("volume", 0));
        if (!TextUtils.isEmpty(voice)) {
            body.put("reference_id", voice);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        String fishModel = !TextUtils.isEmpty(config.model) ? config.model.trim() : CloudTtsProviderType.FISHAUDIO.defaultModel;
        headers.put("model", fishModel);
        return executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    // 5. AzureSpeechCloudTtsProvider
    private byte[] callAzure(float speed, float pitch, String text) throws Exception {
        String base = cleanBaseUrl(config.baseUrl);
        if (TextUtils.isEmpty(base)) {
            String region = !TextUtils.isEmpty(config.region) ? config.region.trim() : "eastasia";
            base = "https://" + region + ".tts.speech.microsoft.com";
        }
        String url = base + "/cognitiveservices/v1";
        String voice = resolveVoice(CloudTtsProviderType.AZURE);
        String[] parts = voice.split("-");
        String locale = parts.length >= 2 ? (parts[0] + "-" + parts[1]) : "zh-CN";
        int pitchPct = Math.max(-100, Math.min(100, Math.round((pitch - 1.0f) * 100)));
        String pitchStr = (pitchPct >= 0 ? "+" : "") + pitchPct + "%";
        String prosody = "<prosody rate=\"" + speed + "\" pitch=\"" + pitchStr + "\" volume=\"0%\">"
                + escapeXml(text) + "</prosody>";
        String inner = !TextUtils.isEmpty(config.style)
                ? "<mstts:express-as style=\"" + escapeXml(config.style) + "\">" + prosody + "</mstts:express-as>"
                : prosody;
        String ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" "
                + "xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"" + escapeXml(locale) + "\">"
                + "<voice name=\"" + escapeXml(voice) + "\">" + inner + "</voice></speak>";

        Map<String, String> headers = new HashMap<>();
        headers.put("Ocp-Apim-Subscription-Key", config.apiKey);
        headers.put("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm");
        headers.put("Content-Type", "application/ssml+xml; charset=utf-8");
        headers.put("User-Agent", "MultiTTS");
        return executeBytes("POST", url, headers, ssml.getBytes(StandardCharsets.UTF_8));
    }

    // 6. AlibabaCloudTtsProvider (SSE streaming + URL fallback)
    private byte[] callAlibabaDashScope(String text) throws Exception {
        String base = cleanBaseUrl(config.effectiveBaseUrl());
        String url = base.endsWith("/generation") ? base : base + "/services/aigc/multimodal-generation/generation";
        String model = TextUtils.isEmpty(config.model) ? CloudTtsProviderType.ALIBABA.defaultModel : config.model.trim();
        String voice = resolveVoice(CloudTtsProviderType.ALIBABA);

        JSONObject input = new JSONObject()
                .put("text", text)
                .put("voice", voice)
                .put("language_type", "Auto");
        if (!TextUtils.isEmpty(config.instructions) && model.toLowerCase(Locale.US).contains("instruct")) {
            input.put("instructions", config.instructions.trim());
        }
        JSONObject body = new JSONObject().put("model", model).put("input", input);

        HttpURLConnection conn = openConnection("POST", url);
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        conn.setRequestProperty("X-DashScope-SSE", "enable");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        activeConn.set(conn);

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        String fallbackUrl = "";
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readError(conn));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && !cancelled) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        continue;
                    }
                    try {
                        JSONObject json = new JSONObject(payload);
                        JSONObject output = json.optJSONObject("output");
                        JSONObject audio = output != null ? output.optJSONObject("audio") : null;
                        if (audio != null) {
                            String b64 = audio.optString("data", "");
                            if (!b64.isEmpty()) {
                                pcmOut.write(Base64.decode(b64, Base64.DEFAULT));
                            }
                            String u = audio.optString("url", "");
                            if (!u.isEmpty()) {
                                fallbackUrl = u;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            activeConn.compareAndSet(conn, null);
            conn.disconnect();
        }
        if (pcmOut.size() > 0) {
            return pcm16MonoToWav(pcmOut.toByteArray(), 24000);
        }
        if (!TextUtils.isEmpty(fallbackUrl)) {
            return executeBytes("GET", fallbackUrl, new HashMap<>(), null);
        }
        throw new IOException("阿里云百炼 TTS 没有返回音频数据");
    }

    // 7. AwsPollyCloudTtsProvider (AWS SigV4)
    private byte[] callAwsPolly(String text) throws Exception {
        String region = !TextUtils.isEmpty(config.region) ? config.region.trim() : "us-east-1";
        String base = cleanBaseUrl(config.baseUrl);
        if (TextUtils.isEmpty(base)) {
            base = "https://polly." + region + ".amazonaws.com";
        }
        String url = base + "/v1/speech";
        String engineName = TextUtils.isEmpty(config.model) ? "neural" : config.model.trim();
        String voice = resolveVoice(CloudTtsProviderType.POLLY);

        byte[] bodyBytes = new JSONObject()
                .put("Engine", engineName)
                .put("OutputFormat", "pcm")
                .put("Text", text)
                .put("TextType", "text")
                .put("VoiceId", voice)
                .toString()
                .getBytes(StandardCharsets.UTF_8);

        SimpleDateFormat dfTime = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        dfTime.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat dfDate = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dfDate.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date now = new Date();
        String amzDate = dfTime.format(now);
        String date = dfDate.format(now);

        URI uri = new URI(url);
        String payloadHash = sha256Hex(bodyBytes);
        String canonicalHeaders = "host:" + uri.getHost() + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "POST\n"
                + (TextUtils.isEmpty(uri.getRawPath()) ? "/" : uri.getRawPath()) + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String scope = date + "/" + region + "/polly/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] kDate = hmacSha256(("AWS4" + config.secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, "polly");
        byte[] kSigning = hmacSha256(kService, "aws4_request");
        String signature = bytesToHex(hmacSha256(kSigning, stringToSign));

        Map<String, String> headers = new HashMap<>();
        headers.put("Host", uri.getHost());
        headers.put("X-Amz-Date", amzDate);
        headers.put("X-Amz-Content-Sha256", payloadHash);
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + config.apiKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);

        byte[] pcm = executeBytes("POST", url, headers, bodyBytes);
        return pcm16MonoToWav(pcm, 16000);
    }

    // 8. VolcengineCloudTtsProvider
    private byte[] callVolcengine(float speed, float pitch, String text) throws Exception {
        String url = cleanBaseUrl(config.effectiveBaseUrl());
        float speedRatio = Math.max(0.2f, Math.min(3.0f, speed));
        float pitchRatio = Math.max(0.1f, Math.min(3.0f, pitch));
        String voice = resolveVoice(CloudTtsProviderType.VOLCENGINE);
        String cluster = TextUtils.isEmpty(config.model) ? "volcano_tts" : config.model.trim();

        JSONObject audio = new JSONObject()
                .put("voice_type", voice)
                .put("encoding", "wav")
                .put("speed_ratio", speedRatio)
                .put("pitch_ratio", pitchRatio)
                .put("volume_ratio", 1.0);
        if (!TextUtils.isEmpty(config.style)) {
            audio.put("emotion", config.style);
        }

        JSONObject body = new JSONObject()
                .put("app", new JSONObject().put("appid", config.appId).put("token", config.apiKey).put("cluster", cluster))
                .put("user", new JSONObject().put("uid", "multitts"))
                .put("audio", audio)
                .put("request", new JSONObject()
                        .put("reqid", UUID.randomUUID().toString())
                        .put("text", text)
                        .put("text_type", "plain")
                        .put("operation", "query"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer;" + config.apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");

        String jsonStr = new String(
                executeBytes("POST", url, headers, body.toString().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        JSONObject resp = new JSONObject(jsonStr);
        int code = resp.optInt("code", -1);
        if (code != 0 && code != 3000) {
            throw new IOException("Volcengine error " + code + ": " + resp.optString("message"));
        }
        return Base64.decode(resp.getString("data"), Base64.DEFAULT);
    }

    private static byte[] hmacSha256(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
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
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void invokeCallbackStart(g callback) {
        if (callback == null) {
            return;
        }
        try {
            Method m = callback.getClass().getMethod("v");
            m.invoke(callback);
        } catch (Throwable ignored) {
        }
    }

    private void invokeCallbackError(g callback, String errorMsg) {
        if (callback == null || errorMsg == null) {
            return;
        }
        try {
            Method m = callback.getClass().getMethod("u", String.class);
            m.invoke(callback, errorMsg);
        } catch (Throwable ignored) {
        }
    }

    private void deliverAudio(g callback, byte[] wavOrPcm) {
        if (callback == null || wavOrPcm == null || wavOrPcm.length == 0) {
            return;
        }
        byte[] pcm = wavOrPcm;
        int sampleRate = 24000;

        if (wavOrPcm.length >= 44
                && wavOrPcm[0] == 'R' && wavOrPcm[1] == 'I'
                && wavOrPcm[2] == 'F' && wavOrPcm[3] == 'F'
                && wavOrPcm[8] == 'W' && wavOrPcm[9] == 'A'
                && wavOrPcm[10] == 'V' && wavOrPcm[11] == 'E') {
            ByteBuffer bb = ByteBuffer.wrap(wavOrPcm).order(ByteOrder.LITTLE_ENDIAN);
            int parsedRate = bb.getInt(24);
            if (parsedRate >= 8000 && parsedRate <= 96000) {
                sampleRate = parsedRate;
            }
            int pos = 12;
            while (pos + 8 <= wavOrPcm.length) {
                int chunkSize = bb.getInt(pos + 4);
                if (wavOrPcm[pos] == 'd' && wavOrPcm[pos + 1] == 'a'
                        && wavOrPcm[pos + 2] == 't' && wavOrPcm[pos + 3] == 'a') {
                    int dataStart = pos + 8;
                    int dataEnd = (chunkSize > 0 && dataStart + chunkSize <= wavOrPcm.length)
                            ? dataStart + chunkSize
                            : wavOrPcm.length;
                    pcm = Arrays.copyOfRange(wavOrPcm, dataStart, dataEnd);
                    break;
                }
                if (chunkSize < 0) {
                    break;
                }
                pos += 8 + chunkSize + (chunkSize & 1);
            }
        }

        boolean isWav = wavOrPcm.length >= 44
                && wavOrPcm[0] == 'R' && wavOrPcm[1] == 'I'
                && wavOrPcm[2] == 'F' && wavOrPcm[3] == 'F'
                && wavOrPcm[8] == 'W' && wavOrPcm[9] == 'A'
                && wavOrPcm[10] == 'V' && wavOrPcm[11] == 'E';
        if (!isWav && context != null) {
            int[] decodedRate = new int[] { sampleRate };
            byte[] decodedPcm = decodeCompressedAudioToPcm(context, wavOrPcm, decodedRate);
            if (decodedPcm != null && decodedPcm.length > 0) {
                pcm = decodedPcm;
                sampleRate = decodedRate[0];
            }
        }

        if (speaker != null && speaker.sampleRate != sampleRate) {
            speaker.sampleRate = sampleRate;
        }

        try {
            Method writeMethod = callback.getClass().getMethod("w", int.class, byte[].class);
            writeMethod.invoke(callback, pcm.length, pcm);
            Method finishMethod = callback.getClass().getMethod("t");
            finishMethod.invoke(callback);
        } catch (Throwable t) {
            invokeCallbackError(callback, "Audio delivery failed: " + t.getMessage());
        }
    }

    private static byte[] decodeCompressedAudioToPcm(Context ctx, byte[] encoded, int[] outSampleRate) {
        File tmp = null;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            tmp = File.createTempFile("cloud_tts_", ".audio", ctx.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(encoded);
            }
            extractor.setDataSource(tmp.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                outSampleRate[0] = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int sampleSize = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        outBuf.position(info.offset);
                        outBuf.get(chunk);
                        if (channels == 2) {
                            for (int i = 0; i + 3 < chunk.length; i += 4) {
                                pcmOut.write(chunk[i]);
                                pcmOut.write(chunk[i + 1]);
                            }
                        } else {
                            pcmOut.write(chunk);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outSampleRate[0] = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            return pcmOut.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }
}
