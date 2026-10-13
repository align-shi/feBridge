# 用户与角色

## 第一版范围
固定 ADMIN（管理员）和 USER（普通用户）两个角色，每个账号一个角色。
管理员创建账号、编辑显示名称、设置角色、启用/禁用账号、重置其他用户密码。
所有登录用户可修改自己的密码、退出登录。

管理员管理模型供应商、模型定义、能力测试和 MCP 服务、工具策略。
普通用户可读取模型与工具目录，并创建、配置和使用智能体、Skills、会话与调用轨迹。
权限由后端 Spring Security 校验，前端导航仅控制入口显示。
当前智能体、Skills 和会话仍属于共享工作空间，没有用户数据隔离或资源授权表。
此阶段不实现开放注册、多租户、自定义角色、SSO 或账号删除。

## 初始管理员
项目本地生成了 config/bootstrap-admin.properties，已加入 .gitignore。
初始账号为 admin，随机密码查看该文件中的 platform.auth.bootstrap-password。
只在 platform_user 为空时创建初始管理员；已有账号时不会覆盖密码。
环境变量 PLATFORM_ADMIN_USERNAME / PLATFORM_ADMIN_PASSWORD 可覆盖初始配置。
PLATFORM_AUTH_BOOTSTRAP_ENABLED=false 可关闭自动初始化。
首次登录后可从左侧账号菜单修改密码。修改后请使用新密码重新登录。
本地初始化配置修改不等于重置现有管理员密码。

## 数据与会话
V10__users_and_roles.sql 新增 platform_user，已有业务表和数据不变。
密码使用 Spring Security PBKDF2-SHA256（随机盐、310,000 次迭代）保存，不保存明文。
账号统一转为小写，长度 3–32，支持字母、数字、点、下划线和短横线。
密码长度 10–128；显示名称最多 80 字符。
用户管理 API 只返回公开账号字段，不返回密码哈希、登录失败计数或会话版本。
连续 10 次错误密码后暂锁定该账号 5 分钟；管理员重置密码会解除锁定。
不能禁用自己或取消自己的管理员角色，且必须保留至少一个启用的管理员。
角色/启用状态改变、密码变更后递增 session_version，下一次请求使旧登录失效。
显示名称修改不会使会话失效。

登录使用服务端 HttpSession。Cookie 为 FEBRIDGE_SESSION，HttpOnly，SameSite=Lax，
12 小时无活动超时；服务重启后需要重新登录。当前不做跨实例会话共享。
CSRF 验证覆盖登录和其他写接口；前端先获取验证令牌，登录后刷新它。
密码修改后当前及其他设备的旧会话失效，退出登录使当前会话失效。
HTTPS 部署设置 PLATFORM_COOKIE_SECURE=true。

## API
- GET /api/auth/csrf：读取本会话 CSRF token 与 headerName。
- POST /api/auth/login：username、password；先携带 CSRF 令牌。
- GET /api/auth/me：当前用户。
- POST /api/auth/logout：退出当前会话。
- POST /api/auth/password：currentPassword、newPassword。
- GET /api/users：管理员查看最近 500 个账号。
- POST /api/users：username、displayName、password、role。
- PUT /api/users/{id}：displayName、role、enabled。
- POST /api/users/{id}/password：password；管理员重置他人密码。

未登录访问业务 GET API 返回 401，越权或缺少 CSRF 返回 403。
旧无认证调用脚本也需要登录、保留 Cookie 并在写请求携带 CSRF header。

## 验证
后端真实 PG 测试覆盖登录、会话 ID 轮换、CSRF、角色越权、禁用、密码修改/重置、
失败登录锁定、最后一个管理员保护和初始化幂等。测试账号在事务中回滚。
前端接口测试验证令牌刷新、会话过期和写请求不重放：npm run test:auth --prefix web。
界面验证使用独立本地模拟接口，不创建真实业务用户。
