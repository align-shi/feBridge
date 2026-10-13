# 调用轨迹

## 使用
每条助手消息下方默认折叠显示最新进度、耗时和总 Token。
展开摘要后点击“查看完整轨迹”，打开独立弹窗：
- 顶部：整次调用状态、总耗时、输入/输出/总 Token、模型和工具调用次数。
- 左侧：上下文、模型调用、工具调用的调用树和相对时间条，支持节点筛选。
- 右侧：选中节点的行为、耗时、用量、输入、输出、配置和原始事件。
- 生成期间每两秒更新，历史记录仍按 requestId 从 PostgreSQL 读取。

一次用户请求对应一条 Trace，requestId 是 Trace ID。请求/响应事件配对为一个节点。
工具关联到发起它的模型节点，表示因果关系；工具执行不计入父模型的耗时。
时间条按事件时间定位，耗时由后端单调时钟计量；两者有少量记录开销差异。

## 数据与统计口径
沿用 V9 agent_run_event 表和 GET /api/agents/{agentId}/runs/{requestId}/events，
没有新增外部观测服务，也不把业务轨迹上传到第三方。

MODEL_REQUEST 增加 step、模型、供应商、调用参数、消息快照和截断标记；
MODEL_RESPONSE 记录同一步骤、响应内容、工具指令、单次耗时及厂商 usage。
工具节点通过 callId 配对，step 关联发起模型。
RUN_COMPLETED / RUN_FAILED 记录总耗时。失败时保留此前已返回的模型用量。

Token 只对 MODEL_RESPONSE 汇总一次，不再次叠加 RUN_COMPLETED 中的累计 usage。
厂商未返回的字段展示“未返回”，部分缺失时标为“已知部分”，不按字符估算。
工具自身不产生平台侧模型 Token，因此用量显示“—”；工具结果进入下一次模型输入后，
其消耗体现在下一次模型返回的输入 Token 中。

输入快照每条消息均分总预算 6,000 字符，单条上限 2,000 字符，
参数快照上限 1,000 字符；截断快照用于查看，不用于执行或重放。
执行请求使用原始上下文，不受快照预算影响。
鉴权请求头、API Key 不采集；敏感字段和 Bearer 格式脱敏，
reasoning_content / thinking 字段不记录。任意业务文本不保证完整脱敏。
单条事件仍有限长和数组限制，单次查询最多 100 条事件。
既有事件可以重组成轨迹；旧链路未保存的模型输入等信息无法补齐。
会话已经结束而轨迹缺少结束事件时，显示“已中断”，不继续计时。

## 设计参考
参考开源项目的观测模型和交互思路，未复制其代码或引入 SDK：
- [Langfuse 开源项目](https://github.com/langfuse/langfuse)
- [Langfuse 节点类型](https://langfuse.com/docs/observability/features/observation-types)：区分 agent、generation、tool 等执行单元。
- [Langfuse 用量统计](https://langfuse.com/docs/observability/features/token-and-cost-tracking)：模型调用级别的用量与汇总。
- [Arize Phoenix 开源项目](https://github.com/Arize-ai/phoenix)
- [Phoenix Trace 和 Span](https://arize.com/docs/phoenix/learn/tracing)：一条调用由多个有时间、状态和父子关系的执行单元组成。

## 验证
后端测试使用模拟模型与 MCP，验证真实 PostgreSQL 事件持久化与请求快照脱敏。
前端节点转换测试：npm run test:trace --prefix web。
界面使用隔离模拟数据验证，不产生真实模型费用，不访问业务 MCP。
