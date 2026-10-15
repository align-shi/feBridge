# 知识库第一版

## 使用

1. 保持 Python Embedding 服务运行（默认 http://127.0.0.1:8091）。
2. 在 IDEA 重启 Java 后端，刷新前端，打开左侧“知识库”。
3. 创建知识库：私有仅创建者可读；共享允许所有登录用户检索，但仅创建者管理文档。
4. 上传 UTF-8 TXT 或 Markdown。单文件最大512KB且最多20万字符，每库最多200份文档。
5. 等待状态变为“已就绪”，先用“检索调试”核对原文片段和分数。
6. 智能体详情中，在与 Skills、MCP 同级的“绑定知识库”里选择并保存，最多8个。
7. 模型需要支持工具调用，执行规则需要启用工具且允许至少一次工具调用、两次模型请求。
8. 对话时模型按需调用 search_knowledge；工具结果返回文档名称、文档/片段ID、片段编号和原文。
   系统提示模型引用文档及片段；可在调用轨迹中查看真实检索结果，模型回答是否完整引用仍需核对。

检索调试不调用对话模型，只调用 Embedding 与 Qdrant。
知识库与记忆独立：“无记忆”模式仍可根据本次问题检索知识库。
共享知识库是明确面向全部登录用户的资料，私有知识库绑定智能体后也不会向其他用户开放。

## 架构与数据

- Java：访问权限、切分、文档任务、检索编排和智能体工具。
- PG：knowledge_base、knowledge_document、knowledge_chunk、agent_knowledge_base（V19）。
- Python：BGE-M3 Embeddings。
- Qdrant：1024维Cosine向量和 kb_id/document_id/embedding_space 三个元数据字段，不写原文。
- 文本切分：最多300个Unicode码点，优先换行/句号边界，重叠40个码点。
- Embedding每批8条，服务仍会严格检查实际Token长度；少见的高Token密度文本可能处理失败，需要缩短整理后重传。
- 后台每3秒领取任务。PG租约15分钟、claim校验、确定性片段ID和幂等upsert支持进程中断恢复。
- 回答请求不会等待文档处理，只有完整入库并标为ready的文档参与检索。
- 正常处理失败不会无限重试，可在页面点“重试”。
- 删除立即退出检索，再异步清理Qdrant与PG片段；删除失败单独标记delete_failed，重试继续删除。
- 原文不可原地编辑，替换文档请删除旧文档后上传新文档。
- 第一版尚未提供知识库改名、删除整个知识库、PDF/DOCX解析、全文/混合检索、重排序和语义阈值配置。

权限检查在检索前按可访问且已绑定的知识库过滤Qdrant，并在返回后回PG再次检查访问权、
文档ready状态与向量空间，防止旧向量或错误返回的数据进入模型。
召回先取最多limit*5候选，再取最多limit条有效片段。相似度不是答案置信度。

## 后端连接配置

现有 config/secrets.properties 已配置用户提供的Qdrant地址与密钥，该文件被Git忽略。
请勿把密钥复制到源码、README或前端。

配置项（可放本地properties或通过Spring配置注入）：

| 配置 | 默认值 |
|---|---|
| platform.knowledge.qdrant-url | http://127.0.0.1:6333 |
| platform.knowledge.qdrant-key | 空 |
| platform.knowledge.embedding-url | http://127.0.0.1:8091 |
| platform.knowledge.embedding-key | 空，填写后用Bearer发送 |
| platform.knowledge.embedding-model | bge-m3 |
| platform.knowledge.dimensions | 1024 |
| platform.knowledge.embedding-space | bge-m3-v1 |
| platform.knowledge.collection | febridge_kb_bge_m3_v1 |
| platform.knowledge.worker-enabled | true |

首次入库自动创建独立collection和payload索引；已有collection必须匹配维度和Cosine。
变更模型权重、版本或处理方式时，应使用新的embedding-space与collection，并重新上传文档。
同维度不代表同向量空间，不能只改模型名称后继续使用原collection。

## 接口

- GET/POST /api/knowledge-bases
- GET/POST /api/knowledge-bases/{id}/documents
- POST /api/knowledge-bases/{id}/documents/{document}/retry
- DELETE /api/knowledge-bases/{id}/documents/{document}
- POST /api/knowledge-bases/{id}/search，JSON：{"query":"调拨如何审批","limit":5}
- GET/PUT /api/agents/{id}/knowledge-bases，绑定JSON：{"knowledgeBaseIds":["UUID"]}
- POST文档采用JSON文本内容：{"name":"规范.md","content":"..."}，前端完成文件读取和UTF-8校验。
- 沿用Session登录和CSRF鉴权。文档修改仅限创建者，管理员不直接读取他人的私有资料。

## 验证

- KnowledgeClientsTest：HTTP契约、过滤字段、向量维度及模型校验。
- KnowledgePersistenceTest：文档任务、引用、权限、失败重试、删除和ReAct工具链路。
- TextChunksTest：Unicode、长度和边界。
- KnowledgeLiveTest：需显式设置PLATFORM_LIVE_KB_TEST=true，同时提供测试PG连接环境变量；
  使用真实Embedding/Qdrant，创建独立 febridge_kb_acceptance_ 随机集合，结束清理该集合，
  PG数据事务回滚，不调用付费对话模型。
- 已在本次开发中通过真实服务入库、检索、删除验收。

官方API参考：
- https://api.qdrant.tech/api-reference/collections/create-collection
- https://api.qdrant.tech/api-reference/points/upsert-points
- https://api.qdrant.tech/api-reference/search/query-points
