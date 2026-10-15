<p align="center">
  <img src="web/public/brand/fengqiao-mark.svg" width="110" alt="枫桥 Logo" />
</p>

<h1 align="center">枫桥 · feBridge</h1>

<p align="center">通过智能体，连接用户与业务系统。</p>

轻量级企业智能体编排平台。配置提示词、Skills、模型和业务 MCP，
结合知识库和会话记忆，通过 ReAct 执行循环完成任务，并查看调用轨迹与 Token 用量。

## 当前功能

- 智能体创建与编辑、系统提示词、模型参数与执行规则。
- 草稿与版本管理：发布配置快照、历史差异对比、恢复草稿和切换当前发布版本；工作台继续用于草稿调试。
- DeepSeek、智谱、通义千问、Kimi、豆包等供应商预设及自定义兼容接口，支持模型管理和工具调用能力测试。
- 渐进式 Skills：先提供名称与描述，模型按需加载 SKILL.md 和 references、assets、scripts 资源；scripts 暂不执行。
- MCP Streamable HTTP 接入、工具发现、自定义鉴权请求头。
- 按 MCP 服务绑定智能体，默认使用服务下全部已启用工具。
- ReAct 模型与工具执行循环、次数限制、超时、工具失败策略。
- 持久化多轮会话和调用轨迹，展示节点输入输出、耗时与 Token。
- 智能体级记忆模式：无记忆、上下文记忆、跨会话记忆；支持会话摘要及长期记忆管理。
- 长期记忆通过本地明确句式自动提取，支持编辑、禁用、有效期和删除，不增加提取模型请求。
- 知识库：私有/共享、TXT/Markdown 上传、后台切分入库、失败重试、文档删除及检索调试。
- 智能体绑定知识库，通过 search_knowledge 工具检索，展示原文来源、相似度与调用轨迹。
- 独立 Python Embeddings 服务，默认 BGE-M3，提供兼容 OpenAI 的向量生成接口。
- 用户登录、管理员/普通用户角色、账号管理及密码维护。
- 江南桥影品牌标志、深色登录页与前端工作台。

当前为单组织共享工作空间，智能体配置共享；会话、长期记忆和调用轨迹按用户隔离。
私有知识库仅创建者可检索，共享知识库允许登录用户检索，文档仅由创建者维护。
第一版尚不支持 PDF/DOCX 解析、混合检索、重排序、流式输出、计费和可视化流程画布。

## 技术栈

| 后端 | 前端 | 数据 |
|---|---|---|
| Java 21、Spring Boot 4.1.1、MyBatis 4.0.0 | React、TypeScript、Vite | PostgreSQL 18.6、Flyway |
| Spring Security、MCP Java SDK | 独立前端工程 `web/` | JSONB、UUID、数据库迁移 |

模型通过兼容 OpenAI 的 HTTP 接口调用；Spring AI 2.0.1 用于 BOM 管理。
知识检索使用 Qdrant 保存向量，PG 保存原文、片段与权限；Python 3.11–3.13、FastAPI、
Sentence Transformers 和 PyTorch 提供 Embedding 推理，当前验证环境为 Python 3.12。

## 快速开始

准备 JDK 21、Docker（使用本地数据库时）、Node.js 22.12+。
当前前端验证环境为 Node.js 24.4.1。

### 1. 启动数据库和后端

在项目根目录的 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:PLATFORM_DB_PASSWORD = '请设置数据库密码'
$env:PLATFORM_ADMIN_PASSWORD = '请设置至少10位的初始管理员密码'

$keyBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($keyBytes)
$env:FEBRIDGE_MASTER_KEY = [Convert]::ToBase64String($keyBytes)

docker compose up -d
.\mvnw.cmd spring-boot:run
```

本地 PG 监听 `127.0.0.1:5433`，数据库和用户名均为 `febridge`。
后端默认监听 `127.0.0.1:8081`；启动时 Flyway 自动创建表。

初始管理员账号默认为 `admin`，密码使用 `PLATFORM_ADMIN_PASSWORD`。
**只在用户表为空时初始化**，修改环境变量不会重置已有账号。

加密主密钥必须独立保存并在后续启动中复用。
不要每次启动重新生成；否则已有模型和 MCP 凭证无法解密。

使用现有 PostgreSQL 时，先创建 `febridge` 数据库，再设置：

```powershell
$env:PLATFORM_DB_URL = 'jdbc:postgresql://localhost:5432/febridge'
$env:PLATFORM_DB_USERNAME = '你的数据库用户'
$env:PLATFORM_DB_PASSWORD = '你的数据库密码'
```

也可在被 Git 忽略的 `config/local.properties` 中配置数据源，
在 `config/secrets.properties` 中配置 `febridge.security.master-key`，
在 `config/bootstrap-admin.properties` 中配置初始管理员。
这些本地文件及真实凭证均不包含在仓库中。IDEA 工作目录应为项目根目录。

### 2. 启动前端

另开终端，在项目根目录执行：

```powershell
cd web
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>，使用初始管理员账号登录。
Vite 将 `/api` 请求代理到后端。后端地址可通过
`web/.env.local` 中的 `BACKEND_URL` 修改，参见 `web/.env.example`。

### 3. 创建第一个智能体

1. 在模型管理中创建供应商，填写自己的 API Key，并新增模型定义。
2. 创建智能体，配置系统提示词、绑定模型。
3. 按需创建 Skills、注册并发现 MCP 服务，然后绑定到智能体。
4. 新建会话发送问题，展开调用轨迹查看模型与工具节点。

可以先运行 `node demo/mcp-server.mjs`，
注册演示地址 `http://127.0.0.1:8090/mcp`，使用虚拟客户数据体验工具调用。

### 4. 启用知识库（可选）

准备独立 Qdrant 服务，并在项目根目录的本地配置中填写连接。
以下为示例，不包含实际服务器地址或凭证：

```properties
# config/secrets.properties（已被 Git 忽略）
platform.knowledge.qdrant-url=http://127.0.0.1:6333
platform.knowledge.qdrant-key=替换为你的Qdrant密钥
platform.knowledge.embedding-url=http://127.0.0.1:8091
```

另开终端，首次安装并启动 Python 服务：

```powershell
cd embedding-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install "torch>=2.6,<3" --index-url https://download.pytorch.org/whl/cpu
.\.venv\Scripts\python.exe -m pip install -e ".[model]"
Copy-Item .env.example .env
.\.venv\Scripts\python.exe -m febridge_embeddings
```

首次运行会下载模型；再次启动只需执行最后一条命令。
已有虚拟环境或 .env 时直接复用，GPU 安装方式见 [服务说明](embedding-service/README.md)。

默认 Embedding 地址为 `http://127.0.0.1:8091`，状态接口为 `/ready`，
接口调试页面为 `/docs`。默认 BGE-M3 输出 1024 维向量。
Java 首次入库时自动创建独立 Qdrant 集合；启动后端不要求 Embedding 服务已就绪。

1. 重启 Java 后端，进入“知识库”，创建私有或共享知识库。
2. 上传 UTF-8 TXT/Markdown，单文件不超过 512KB 和 20 万字符。
3. 等待“已就绪”，在“检索调试”中核对片段和来源。
4. 在智能体详情中绑定知识库，确保模型支持工具调用且执行规则允许工具。
5. 对话时模型按需检索；调用轨迹记录检索参数、结果和耗时。

可直接上传 [模具调拨与维修测试文档](demo/knowledge/mould-transfer-guide.md)，
内容全部虚构。更换 Embedding 模型或版本时，应使用新的向量空间与集合并重新入库，
不能仅因维度相同就混用旧向量。

### 5. 配置记忆模式

在智能体详情中，与 Skills、MCP 同级的“记忆模式”配置里选择：

| 模式 | 行为 |
|---|---|
| 无记忆 | 仅发送本次问题，不带历史或长期记忆；聊天记录仍保存 |
| 上下文记忆 | 使用当前会话历史与摘要，不跨会话 |
| 跨会话记忆 | 使用当前会话上下文，并自动记录与召回长期记忆 |

新配置默认使用上下文记忆。模式应用于该智能体所有用户，但记忆内容按用户隔离。
知识库与记忆独立，无记忆模式仍可以检索知识库。
长期记忆第一版只识别明确句式，例如“以后默认查询苏州工厂”，自由表达可手动新增。

## 构建与测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
npm run test:auth --prefix web
npm run test:trace --prefix web
npm run test:skills --prefix web
npm run build --prefix web
```

数据库集成测试需配置 `PLATFORM_TEST_DB_URL`、
`PLATFORM_TEST_DB_USERNAME`、`PLATFORM_TEST_DB_PASSWORD`。
未设置测试 URL 时自动跳过数据库测试。使用独立测试库；
测试数据事务回滚，但 Flyway 会创建和迁移表。

真实 Embedding/Qdrant 验收测试需另外显式设置 PLATFORM_LIVE_KB_TEST=true。
该测试使用合成资料和随机临时集合，结束后清理集合，不调用付费对话模型；
运行前应配置可访问的测试服务，参见 [知识库说明](docs/knowledge-base.md)。

## 使用说明

- [Python Embeddings 服务](embedding-service/README.md)
- [模型供应商与官方地址](docs/model-providers.md)
- [用户与角色](docs/users-and-roles.md)
- [当前会话记忆与归属](docs/conversation-memory.md)
- [长期记忆与三种模式](docs/long-term-memory.md)
- [知识库、文档入库与检索](docs/knowledge-base.md)
- [提示词、Skills 与执行规则](docs/agent-configuration.md)
- [智能体草稿与版本管理](docs/agent-versions.md)
- [渐进式 Skills 与资源文件](docs/progressive-skills.md)
- [MCP 与 ReAct](docs/mcp-tools.md)
- [调用轨迹与用量统计](docs/observability.md)
- [前端工作台](web/README.md)
- [江南桥影品牌设计](docs/brand/logo-jiangnan-design.md)

## 目录

```text
src/main/java/com/febridge/   后端模块
src/main/resources/          配置、Flyway 迁移和 MyBatis 映射
src/test/                   后端测试
web/                        前端工程
embedding-service/          Python Embedding 推理服务
demo/                       MCP 演示、Skills 示例及知识库测试资料
docs/                       功能说明与品牌资产
```

## License

[MIT](LICENSE)
