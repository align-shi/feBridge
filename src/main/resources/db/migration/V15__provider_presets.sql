ALTER TABLE model_provider DROP CONSTRAINT model_provider_provider_type_check;
ALTER TABLE model_provider ADD CONSTRAINT model_provider_provider_type_check
    CHECK (provider_type IN ('DEEPSEEK','ZHIPU','QWEN','MOONSHOT','DOUBAO','SILICONFLOW','MINIMAX','OPENAI','GEMINI','OPENROUTER','GROQ','MISTRAL','XAI','CUSTOM'));
