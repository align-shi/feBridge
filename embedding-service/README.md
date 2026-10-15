# feBridge Embeddings 服务

独立 Python 服务，默认模型 BAAI/bge-m3，提供文本向量，不生成聊天回答。
默认归一化输出 1024 维稠密向量，CPU/GPU 由配置控制。
接口使用 OpenAI Embeddings 的常用请求和响应格式；支持字符串或字符串数组，
float/Base64 输出、usage 和模型列表。不支持 Token ID 输入、任意向量维度截断或在线切换模型。

## 安装与启动（Windows）

在本目录执行，使用 Python 3.11–3.13（建议 3.12）：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
# CPU 环境先安装 CPU 版 PyTorch：
.\.venv\Scripts\python.exe -m pip install "torch>=2.6,<3" --index-url https://download.pytorch.org/whl/cpu
.\.venv\Scripts\python.exe -m pip install -e ".[model]"
Copy-Item .env.example .env
.\.venv\Scripts\python.exe -m febridge_embeddings
```

默认地址 http://127.0.0.1:8091，接口文档 http://127.0.0.1:8091/docs。
首次启动会下载模型权重至 .cache/models，下载及加载完成后才开始提供服务。
这可能较慢，并需要足够磁盘和内存；有 GPU 时按 PyTorch 官方安装方式选择对应 CUDA 版本。
仅启动一个 worker，避免重复加载模型。

离线部署：先准备完整模型目录，将 EMBEDDING_MODEL_SOURCE 指向该目录。
生产使用可填写 EMBEDDING_MODEL_REVISION 固定 Hugging Face 提交版本。
.env 和模型缓存不进入 Git。可配置 EMBEDDING_API_KEY，客户端使用 Authorization: Bearer。
默认只绑定本机；跨机器使用时配置监听地址和访问密钥。

## 调用

```powershell
$body = @{ model='bge-m3'; input=@('模具转移查询规范','模具台账') } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://127.0.0.1:8091/v1/embeddings' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

响应结构：

```json
{
  "object": "list",
  "data": [{"object": "embedding", "index": 0, "embedding": [0.01, 0.02]}],
  "model": "bge-m3",
  "usage": {"prompt_tokens": 10, "total_tokens": 10}
}
```

上面向量只是格式示例，真实 BGE-M3 返回 1024 个数。
input 支持字符串和字符串数组。dimensions 如果提供，必须等于当前模型维度。
encoding_format 支持 float（默认）和 base64（小端 float32）。
usage 按当前模型分词器计算，包含特殊 Token，不是聊天模型计费量。
GET /v1/models 返回服务模型名称及维度；GET /health 检查进程，
GET /ready 返回模型就绪状态、实际设备和有效 Token 上限。

## 边界与配置

默认每次最多 32 条文本，推理 batch size 为 8，单条最多 512 Token。
超过上限返回 400，要求调用方先分段，不静默截断。
单条最多 32,768 字符、批量总计 131,072 字符、请求体 1 MB。
同一进程同时只推理一批，并发冲突返回 429 与 Retry-After，调用方稍后重试。
参数可通过 .env.example 中的环境变量修改。
BGE-M3 本身支持更长输入，但调大上限会增加推理成本。

切换模型需要核对其推理方法、查询/文档提示词、输出维度及许可证。
本实现先面向 BGE-M3；其他模型不能只改名称就保证检索效果。
数据库入库文本与查询文本必须使用同一版本、同一处理规则。
本服务不存储文本、不写入 PG，也不做知识库检索；Java 平台的 Embeddings 接入作为后续工作。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m pytest -q
```

测试注入模拟推理器，不下载模型，覆盖请求格式、批量顺序、鉴权、
编码、维度、输入限制、繁忙状态和异常后恢复。
实际模型推理需要安装 model 依赖并下载权重，再用真实业务文本验证检索效果。
没有生产环境的假向量回退。

## Docker（CPU）

```powershell
docker build -t febridge-embeddings .
docker run --rm -p 127.0.0.1:8091:8091 -v febridge_embedding_models:/app/.cache/models febridge-embeddings
```

服务实现参考 [Sentence Transformers](https://sbert.net/docs/sentence_transformer/usage/usage.html)；
模型参考 [BGE-M3 官方模型卡](https://huggingface.co/BAAI/bge-m3)。
