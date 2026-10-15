# 模型供应商预设

新增供应商支持 14 个选项，选择后自动填写 API Base URL，可按账号地域修改。
默认走 OpenAI 兼容 Chat Completions，API Key 加密保存，编辑时留空保留原密钥。
新地址实际生效后，后端会在该地址后拼接 /chat/completions。

| 供应商 | 默认 Base URL | 官方文档 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | [官方文档](https://api-docs.deepseek.com/) |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | [官方文档](https://docs.bigmodel.cn/) |
| 通义千问 / 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | [官方文档](https://help.aliyun.com/en/model-studio/base-url) |
| Kimi / 月之暗面 | `https://api.moonshot.cn/v1` | [官方文档](https://platform.kimi.com/docs/api/chat) |
| 豆包 / 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | [官方文档](https://api.volcengine.com/api-docs/?serviceCode=ark&version=2024-01-01) |
| 硅基流动 | `https://api.siliconflow.cn/v1` | [官方文档](https://docs.siliconflow.cn/docs/userguide/quickstart) |
| MiniMax（国际站） | `https://api.minimax.io/v1` | [官方文档](https://platform.minimax.io/docs/api-reference/text-openai-api) |
| OpenAI | `https://api.openai.com/v1` | [官方文档](https://developers.openai.com/api/reference/overview) |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | [官方文档](https://ai.google.dev/gemini-api/docs/openai) |
| OpenRouter | `https://openrouter.ai/api/v1` | [官方文档](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request) |
| Groq | `https://api.groq.com/openai/v1` | [官方文档](https://console.groq.com/docs/openai) |
| Mistral AI | `https://api.mistral.ai/v1` | [官方文档](https://docs.mistral.ai/api) |
| xAI / Grok | `https://api.x.ai/v1` | [官方文档](https://docs.x.ai/developers/rest-api-reference/inference) |
| 自定义 OpenAI 兼容服务 | 手动填写 | 服务自己的接口文档 |

地址核对日期：2026-10-14。百炼默认北京，Kimi 默认国内站，MiniMax 默认国际站；
其他地域或账户体系可修改地址，API Key 需与所选地址匹配。
自定义选项支持使用相同请求格式的服务；不会根据 URL 自动切换为其他 API 协议。
地址不能包含用户凭证、查询参数或片段；输入完整 /chat/completions 地址时会自动提取基础地址。

模型定义仍需填写账号实际可用的模型标识。新增选项只提供接入预设，
不代表已经用真实账号验证所有模型的参数、工具调用或 JSON 输出能力。
通用参数校验继续生效，选择供应商不会自动增加 top_k 等扩展参数。
可使用已有“测试工具调用”功能检查具体模型；测试会消耗供应商 Token。

V15 数据库迁移扩展供应商类型，不修改已有供应商地址、密钥或模型绑定。
未提交 baseUrl 的旧创建接口继续使用预设地址；旧更新接口保留原地址。

验证：真实 PG 事务回滚测试覆盖全部供应商保存、预设、自定义地址更新和密钥保持；
无效 URL 校验不发起网络请求。模型调用通过已有客户端与模拟测试验证，
不使用真实 API Key、不请求付费模型。
