<p align="center">
  <img src="web/public/brand/fengqiao-mark.svg" width="110" alt="枫桥 Logo" />
</p>

<h1 align="center">枫桥 · feBridge</h1>

<p align="center">通过智能体，连接用户与业务系统。</p>

轻量级企业智能体编排平台。配置提示词、Skills、模型和业务 MCP，
通过 ReAct 执行循环完成任务，并查看会话、调用轨迹与 Token 用量。

## 当前功能

- 智能体创建与编辑、系统提示词、模型参数与执行规则。
- DeepSeek、智谱模型接入，模型供应商管理和工具调用能力测试。
- Markdown Skills 管理、按顺序绑定到智能体。
- MCP Streamable HTTP 接入、工具发现、自定义鉴权请求头。
- 按 MCP 服务绑定智能体，默认使用服务下全部已启用工具。
- ReAct 模型与工具执行循环、次数限制、超时、工具失败策略。
- 持久化多轮会话和调用轨迹，展示节点输入输出、耗时与 Token。
- 用户登录、管理员/普通用户角色、账号管理及密码维护。
- 江南桥影品牌标志、深色登录页与前端工作台。

当前为单组织共享工作空间，尚未按用户隔离智能体或会话。
尚未实现长期记忆、RAG、流式输出、计费和可视化流程画布。

## 技术栈

| 后端 | 前端 | 数据 |
|---|---|---|
| Java 21、Spring Boot 4.1.1、MyBatis 4.0.0 | React、TypeScript、Vite | PostgreSQL 18.6、Flyway |
| Spring Security、MCP Java SDK | 独立前端工程 `web/` | JSONB、UUID、数据库迁移 |

模型通过兼容 OpenAI 的 HTTP 接口调用；Spring AI 2.0.1 用于 BOM 管理。

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

## 构建与测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
npm run test:auth --prefix web
npm run test:trace --prefix web
npm run build --prefix web
```

数据库集成测试需配置 `PLATFORM_TEST_DB_URL`、
`PLATFORM_TEST_DB_USERNAME`、`PLATFORM_TEST_DB_PASSWORD`。
未设置测试 URL 时自动跳过数据库测试。使用独立测试库；
测试数据事务回滚，但 Flyway 会创建和迁移表。

## 使用说明

- [Python Embeddings 服务](embedding-service/README.md)
- [模型供应商与官方地址](docs/model-providers.md)
- [用户与角色](docs/users-and-roles.md)
- [当前会话记忆与归属](docs/conversation-memory.md)
- [提示词、Skills 与执行规则](docs/agent-configuration.md)
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
demo/                       本地 MCP 协议演示
docs/                       功能说明与品牌资产
```

## License

[MIT](LICENSE)

跨会话长期记忆的使用范围、自动提取句式及接口说明见 [长期记忆](docs/long-term-memory.md)。

知识库的文档上传、向量检索和智能体绑定使用说明见 [知识库](docs/knowledge-base.md)。
