package com.febridge.model;

public enum ProviderType {
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-flash",
        "https://api-docs.deepseek.com/"
    ),
    ZHIPU("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3", "https://docs.bigmodel.cn/"),
    QWEN(
        "通义千问 / 阿里云百炼",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "",
        "https://help.aliyun.com/en/model-studio/base-url"
    ),
    MOONSHOT(
        "Kimi / 月之暗面",
        "https://api.moonshot.cn/v1",
        "",
        "https://platform.kimi.com/docs/api/chat"
    ),
    DOUBAO(
        "豆包 / 火山方舟",
        "https://ark.cn-beijing.volces.com/api/v3",
        "",
        "https://api.volcengine.com/api-docs/?serviceCode=ark&version=2024-01-01"
    ),
    SILICONFLOW(
        "硅基流动",
        "https://api.siliconflow.cn/v1",
        "",
        "https://docs.siliconflow.cn/docs/userguide/quickstart"
    ),
    MINIMAX(
        "MiniMax（国际站）",
        "https://api.minimax.io/v1",
        "",
        "https://platform.minimax.io/docs/api-reference/text-openai-api"
    ),
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1",
        "",
        "https://developers.openai.com/api/reference/overview"
    ),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "",
        "https://ai.google.dev/gemini-api/docs/openai"
    ),
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        "",
        "https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request"
    ),
    GROQ("Groq", "https://api.groq.com/openai/v1", "", "https://console.groq.com/docs/openai"),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1", "", "https://docs.mistral.ai/api"),
    XAI(
        "xAI / Grok",
        "https://api.x.ai/v1",
        "",
        "https://docs.x.ai/developers/rest-api-reference/inference"
    ),
    CUSTOM("自定义 OpenAI 兼容服务", "", "", "");

    private final String label;
    private final String baseUrl;
    private final String suggestedModel;
    private final String documentationUrl;

    ProviderType(String label, String baseUrl, String suggestedModel, String documentationUrl) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.suggestedModel = suggestedModel;
        this.documentationUrl = documentationUrl;
    }

    public String label() {
        return label;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String suggestedModel() {
        return suggestedModel;
    }

    public String documentationUrl() {
        return documentationUrl;
    }
}
