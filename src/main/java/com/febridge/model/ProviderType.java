package com.febridge.model;

public enum ProviderType {
    DEEPSEEK("https://api.deepseek.com", "deepseek-flash"),
    ZHIPU("https://open.bigmodel.cn/api/paas/v4", "glm-5.3");

    private final String baseUrl;
    private final String suggestedModel;

    ProviderType(String baseUrl, String suggestedModel) {
        this.baseUrl = baseUrl;
        this.suggestedModel = suggestedModel;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String suggestedModel() {
        return suggestedModel;
    }
}
