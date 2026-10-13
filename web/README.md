# feBridge 前端

React + TypeScript + Vite，独立位于 `web/`，与根目录 Java 后端分开运行。

## 为什么选择 React

当前列表和表单用 React 或 Vue 都可以。枫桥后续会增加智能体调试、工具配置和可视化编排，
因此选择 React，并预留后续使用 React Flow 的空间。Vue 也可通过 Vue Flow 实现节点编辑，
如果团队已有 Vue 经验，团队熟悉度同样应作为选型依据。
当前没有安装流程画布或重型状态管理库，先保持工程轻量。

## 启动

需要 Node.js 22.12+；当前验证环境为 Node.js 24.4.1。
先在 IDEA 启动 Java 后端（8081），再在本目录运行：

```powershell
npm ci
npm run dev
```

访问 http://127.0.0.1:5173 。开发服务仅监听本机。
Vite 将 `/api` 转发到 `http://127.0.0.1:8081`，不需要修改后端 CORS 配置。
修改后端地址时，复制 `.env.example` 为 `.env.local`，修改 BACKEND_URL 后重启前端。
前端不保存数据库密码或模型 API 密钥。

## 当前功能

- 智能体列表：最近 100 条，支持在已加载数据中搜索与刷新。
- 创建：名称、描述、系统提示词、模型名称及 JSON 参数。
- 详情：提示词、模型配置、创建时间。
- 加载、空列表、请求失败提示；JSON 对象校验及防重复提交。

已支持登录与用户角色管理、模型供应商和模型定义、智能体配置、Skills、MCP 服务绑定、持久化多轮会话和调用轨迹。尚未实现智能体删除和可视化流程编排。

## 构建与目录

```powershell
npm run build
npm run preview
```

build 包含 TypeScript 检查，输出在 `dist/`。`preview` 仅预览静态产物，
没有开发环境的 API 代理；生产环境需将 `/api` 反向代理到 Java 后端。

- src/api.ts：后端接口及数据类型。
- src/App.tsx：智能体列表、详情、创建表单。
- src/styles.css：页面布局、配色和响应式样式。
- vite.config.ts：开发端口和代理。
- package-lock.json：固定依赖，后续使用 npm ci 安装。

官方资料：https://reactflow.dev/ 、https://vueflow.dev/ 、https://vite.dev/guide/
会话工作台已上线：详情页显示会话列表和聊天消息，支持刷新恢复、切换和新建会话。使用数据库保存记录，模型请求携带当前会话的近期历史。
