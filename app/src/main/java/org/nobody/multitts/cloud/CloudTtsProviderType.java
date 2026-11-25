package org.nobody.multitts.cloud;

/**
 * 云端 AI TTS 提供商枚举，1:1 对齐 legado-with-MD3 并保留 Fish Audio。
 */
public enum CloudTtsProviderType {
    OPENAI("cloud_openai", "OpenAI Speech", "https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy", true),
    GEMINI("cloud_gemini", "Google Gemini TTS", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash-preview-tts", "Kore", true),
    MIMO("cloud_mimo", "Xiaomi MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-tts", "mimo_default", false),
    FISHAUDIO("cloud_fishaudio", "Fish Audio", "https://api.fish.audio/v1", "s2.1-pro-free", "", false),
    AZURE("cloud_azure", "Microsoft Azure Speech", "https://eastasia.tts.speech.microsoft.com", "neural", "zh-CN-XiaoxiaoNeural", false),
    ALIBABA("cloud_dashscope", "Alibaba Cloud Model Studio", "https://dashscope.aliyuncs.com/api/v1", "qwen3-tts-flash", "Cherry", true),
    POLLY("cloud_polly", "Amazon Polly", "https://polly.us-east-1.amazonaws.com", "neural", "Zhiyu", false),
    VOLCENGINE("cloud_volcengine", "Volcengine Speech", "https://openspeech.bytedance.com/api/v1/tts", "volcano_tts", "BV001_streaming", false);

    public final String engineType;
    public final String displayName;
    public final String defaultBaseUrl;
    public final String defaultModel;
    public final String defaultVoice;
    public final boolean usesInstruction;

    CloudTtsProviderType(
            String engineType,
            String displayName,
            String defaultBaseUrl,
            String defaultModel,
            String defaultVoice,
            boolean usesInstruction) {
        this.engineType = engineType;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.defaultVoice = defaultVoice;
        this.usesInstruction = usesInstruction;
    }

    public static CloudTtsProviderType fromEngineType(String type) {
        if (type == null) {
            return null;
        }
        for (CloudTtsProviderType value : values()) {
            if (value.engineType.equalsIgnoreCase(type.trim())) {
                return value;
            }
        }
        return null;
    }

    public static boolean isCloudType(String type) {
        return fromEngineType(type) != null;
    }
}
