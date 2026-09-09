# ai-wechat-scan-login

微信公众号「扫码关注 + 回复短码」登录方案（适用于**未认证的个人订阅号**），包含 Vue 3 单页前端与零依赖 Java 后端。

线上地址：https://wechatscan.menghun3.cc

## 实现原理

个人订阅号无法使用「带参二维码」和「网页授权」，本方案仅依赖**接收消息推送 + 被动回复**能力：

```
┌──────────┐   ① create        ┌──────────┐                        ┌──────────┐
│ 浏览器    │ ────────────────> │ Java API │  生成 loginId + 6位短码 │          │
│ 登录页    │ <──────────────── │ (39000)  │  （5 分钟有效）         │          │
│          │                   └──────────┘                        │          │
│          │   ② 用户扫码关注公众号，回复页面上的 6 位短码              │          │
│          │                                                        │  微信服务器│
│          │                   ┌──────────┐   ③ 消息推送(XML)       │          │
│          │                   │ Java API │ <──────────────────── │          │
│          │                   │ 验签+短码 │   ④ 被动回复"登录成功"  │          │
│          │   ⑤ 每2s轮询      │ 反查会话  │ ────────────────────> │          │
│          │ ────────────────> └──────────┘                        └──────────┘
│ 首页欢迎页│ <────────────────  status: confirmed + JWT Token（一次性）
└──────────┘
```

- 后端不主动调用任何微信 API，仅校验消息签名（SHA1）即可完成整套流程
- 登录成功签发 HS256 JWT（7 天有效），前端存 sessionStorage，**Token 只下发一次**

## 目录结构

```
├── frontend/               # Vue 3 + Vite 单页前端
│   ├── src/App.vue         #   登录页 + 首页欢迎页
│   ├── public/wechat-qrcode.jpg  # 公众号二维码（扫码关注用）
│   └── vite.config.js
├── wechat-scan-api/
│   ├── WechatScanApi.java  # 后端（单文件，零第三方依赖，JDK 21）
│   └── wechat-scan-api.jar # 构建产物（已忽略，见下方构建命令）
├── images/                 # 二维码素材
└── docs/                   # 微信域名验证文件留档
```

## 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/wxlogin/create` | 创建登录会话，返回 `loginId`、`code`（6位短码）、`expiresIn`（300s） |
| GET | `/api/auth/wxlogin/status?loginId=` | 轮询状态：`pending` / `confirmed`（含 token）/ `expired` |
| GET/POST | `/api/wechat/callback` | 微信服务器回调：GET 验证 echostr；POST 接收消息（SHA1 验签） |
| GET | `/api/health` | 健康检查 |

环境变量：

| 变量 | 说明 |
|---|---|
| `PORT` | 监听端口（默认 `39000`） |
| `WECHAT_TOKEN` | 公众号后台配置的消息推送 Token（**必填**） |
| `LOGIN_TOKEN_KEY` | JWT 签名密钥（不配置则自动生成并持久化到工作目录 `token.key`） |
| `CODE_TTL_SECONDS` | 短码有效期（默认 `300` 秒） |
| `LOGIN_TOKEN_TTL` | JWT 有效期秒数（默认 7 天） |
| `WECHAT_DEBUG` | `true` 时开放 `/api/wechat/simulate` 本地模拟接口（生产必须关闭） |

## 本地构建与运行

后端（需 JDK 21）：

```bash
cd wechat-scan-api
javac -encoding UTF-8 --release 21 -d build/classes WechatScanApi.java
jar --create --file wechat-scan-api.jar --manifest build/manifest.mf -C build/classes .
# manifest 内容：Main-Class: WechatScanApi
WECHAT_TOKEN=your-token java -jar wechat-scan-api.jar
```

前端（需 Node 18+）：

```bash
cd frontend
npm install
npm run dev      # 开发模式，/api 代理到 127.0.0.1:39000
npm run build    # 产物在 dist/
```

## 公众号后台配置

「消息推送」配置（配置后即可接收用户消息，完成短码登录闭环）：

| 配置项 | 值 |
|---|---|
| URL | `https://wechatscan.menghun3.cc/api/wechat/callback` |
| Token | 与服务器 `backend/wechat.token` 一致 |
| 加解密方式 | 明文模式 |

## 注意事项

- 短码 5 分钟有效，过期后页面点「刷新重试」重新获取；回复过期短码会收到公众号过期提示
- Token 一次性下发：`status` 返回 `confirmed` 后会话即销毁，不可重复获取
- `token.key` / `wechat.token` / `wechat.token.env` 等密钥文件一律不入 Git（见 `.gitignore`）
