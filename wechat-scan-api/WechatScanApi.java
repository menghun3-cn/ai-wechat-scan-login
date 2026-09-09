import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信扫码/短码登录 API（零依赖，JDK 21，编译为 jar 运行）
 *
 * 流程：前端 create 生成 loginId+6位短码 → 用户把短码发给公众号 → 微信推送 callback
 *       → 验签+短码反查会话+openid 校验 → 签发 Token → 前端按 loginId 轮询 status 拿 Token
 *
 * 环境变量：PORT(默认39000)、WECHAT_TOKEN(必填)、LOGIN_TOKEN_KEY、
 *           CODE_TTL_SECONDS(默认300)、LOGIN_TOKEN_TTL(默认7天)、WECHAT_DEBUG(开放模拟接口)
 */
public class WechatScanApi {

    // ---------- 配置 ----------
    static final int PORT = Integer.parseInt(env("PORT", "39000"));
    static final String WECHAT_TOKEN = System.getenv("WECHAT_TOKEN");
    static final boolean DEBUG_SIMULATE = "true".equalsIgnoreCase(env("WECHAT_DEBUG", "false"));
    static final long CODE_TTL_SECONDS = Long.parseLong(env("CODE_TTL_SECONDS", "300"));
    static final long LOGIN_TOKEN_TTL_SECONDS = Long.parseLong(env("LOGIN_TOKEN_TTL", String.valueOf(7 * 24 * 3600)));

    // ---------- 会话状态 ----------
    /** loginId -> 会话 */
    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** code -> loginId（短码反查索引） */
    static final Map<String, String> CODE_INDEX = new ConcurrentHashMap<>();

    static final SecureRandom RANDOM = new SecureRandom();
    static String loginTokenKey;

    public static void main(String[] args) throws Exception {
        if (WECHAT_TOKEN == null || WECHAT_TOKEN.isBlank()) {
            System.err.println("[FATAL] 缺少环境变量 WECHAT_TOKEN（公众号后台配置的消息推送 Token）");
            System.exit(1);
        }
        loginTokenKey = loadOrCreateKey();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/api/auth/wxlogin/create", WechatScanApi::handleCreate);
        server.createContext("/api/auth/wxlogin/status", WechatScanApi::handleStatus);
        server.createContext("/api/wechat/callback", WechatScanApi::handleCallback);
        server.createContext("/api/health", ex -> sendJson(ex, 200, "{\"status\":\"UP\"}"));
        if (DEBUG_SIMULATE) {
            server.createContext("/api/wechat/simulate", WechatScanApi::handleSimulate);
            System.out.println("[init] DEBUG simulate endpoint enabled");
        }
        server.start();
        System.out.println("WechatScanApi started on port " + PORT + ", code TTL=" + CODE_TTL_SECONDS + "s");
    }

    // ================= 微信消息回调 =================

    static void handleCallback(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            // 1. 验签（GET/POST 都要求）
            if (!checkSignature(q.get("signature"), q.get("timestamp"), q.get("nonce"))) {
                sendText(ex, 403, "invalid signature");
                return;
            }
            if ("GET".equals(ex.getRequestMethod())) {
                // 2. 接入验证：原样返回 echostr
                sendText(ex, 200, q.getOrDefault("echostr", ""));
                return;
            }
            // 3. POST 消息推送（明文 XML）
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String fromUser = extractTag(body, "FromUserName");
            String toUser = extractTag(body, "ToUserName");
            String msgType = extractTag(body, "MsgType");
            String reply;

            if ("text".equals(msgType)) {
                String content = extractTag(body, "Content");
                reply = handleTextMessage(fromUser, content == null ? "" : content.trim());
            } else if ("event".equals(msgType) && "subscribe".equals(extractTag(body, "Event"))) {
                reply = "欢迎关注！\n请回到登录页面获取 6 位数字短码，直接回复该短码即可完成登录。";
            } else {
                reply = null; // 其他消息不回复（返回 success 即可）
            }
            if (reply == null) {
                sendText(ex, 200, "success");
            } else {
                sendText(ex, 200, passiveTextReply(toUser, fromUser, reply));
            }
        } catch (Exception e) {
            System.err.println("[callback] error: " + e);
            sendText(ex, 200, "success"); // 异常时也返回 success，避免微信无限重推
        }
    }

    /** 文本消息：提取 6 位数字短码 → 反查会话 → openid+短码 校验 → 签发 Token */
    static String handleTextMessage(String openid, String content) {
        if (openid == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6}").matcher(content);
        if (!m.find()) {
            return "请回复登录页面显示的 6 位数字短码。";
        }
        String code = m.group();
        cleanupExpired();
        String loginId = CODE_INDEX.get(code);
        Session s = loginId == null ? null : SESSIONS.get(loginId);
        if (s == null) {
            return "短码无效或已过期，请刷新登录页面重新获取。";
        }
        if (System.currentTimeMillis() > s.expiresAt) {
            removeSession(s);
            return "短码已过期，请刷新登录页面重新获取。";
        }
        if (s.confirmed) {
            return "该短码已完成登录，如需重新登录请刷新页面。";
        }
        // 校验通过：确认会话，签发登录 Token
        s.openid = openid;
        s.token = issueLoginToken(openid);
        s.confirmed = true;
        System.out.println("[login] ok code=" + code + " openid=" + openid);
        return "登录成功，请返回浏览器页面继续操作。";
    }

    // ================= 登录会话接口 =================

    /** POST /api/auth/wxlogin/create → {"loginId":"...","code":"123456","expiresIn":300,"expiresAt":...} */
    static void handleCreate(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            cleanupExpired();
            String loginId = UUID.randomUUID().toString().replace("-", "");
            String code;
            do {
                code = String.valueOf(100000 + RANDOM.nextInt(900000));
            } while (CODE_INDEX.containsKey(code));
            long expiresAt = System.currentTimeMillis() + CODE_TTL_SECONDS * 1000;
            SESSIONS.put(loginId, new Session(loginId, code, expiresAt));
            CODE_INDEX.put(code, loginId);
            System.out.println("[create] loginId=" + loginId + " code=" + code);
            sendJson(ex, 200, "{\"loginId\":\"" + loginId + "\",\"code\":\"" + code
                    + "\",\"expiresIn\":" + CODE_TTL_SECONDS + ",\"expiresAt\":" + expiresAt + "}");
        } catch (Exception e) {
            System.err.println("[create] error: " + e);
            sendJson(ex, 500, "{\"error\":\"internal error\"}");
        }
    }

    /** GET /api/auth/wxlogin/status?loginId=xxx → {"status":"pending"|"confirmed"|"expired","token":...} */
    static void handleStatus(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String loginId = q.get("loginId");
            if (loginId == null || loginId.isBlank() || loginId.length() > 64) {
                sendJson(ex, 400, "{\"error\":\"loginId required\"}");
                return;
            }
            Session s = SESSIONS.get(loginId);
            if (s == null) {
                sendJson(ex, 200, "{\"status\":\"expired\"}");
                return;
            }
            if (System.currentTimeMillis() > s.expiresAt) {
                removeSession(s);
                sendJson(ex, 200, "{\"status\":\"expired\"}");
                return;
            }
            if (s.confirmed) {
                sendJson(ex, 200, "{\"status\":\"confirmed\",\"token\":\"" + jsonEscape(s.token)
                        + "\",\"openid\":\"" + jsonEscape(s.openid) + "\"}");
                // Token 只下发一次，随后进入终态清理
                removeSession(s);
            } else {
                sendJson(ex, 200, "{\"status\":\"pending\"}");
            }
        } catch (Exception e) {
            System.err.println("[status] error: " + e);
            sendJson(ex, 500, "{\"error\":\"internal error\"}");
        }
    }

    // ================= 本地调试模拟接口（仅 WECHAT_DEBUG=true 时注册） =================

    /** POST /api/wechat/simulate {"openid":"...","code":"123456"} → 模拟微信推送，返回被动回复 */
    static void handleSimulate(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String openid = extractJsonString(body, "openid");
            String code = extractJsonString(body, "code");
            if (openid == null || code == null) {
                sendJson(ex, 400, "{\"error\":\"openid and code required\"}");
                return;
            }
            String reply = handleTextMessage(openid, code);
            if (reply == null) {
                sendJson(ex, 200, "{\"reply\":null,\"shouldReply\":false}");
            } else {
                sendJson(ex, 200, "{\"reply\":\"" + jsonEscape(reply) + "\",\"shouldReply\":true}");
            }
        } catch (Exception e) {
            System.err.println("[simulate] error: " + e);
            sendJson(ex, 500, "{\"error\":\"internal error\"}");
        }
    }

    // ================= Token（HS256 JWT 风格，零依赖） =================

    static String issueLoginToken(String openid) {
        long now = System.currentTimeMillis() / 1000;
        String header = b64urlJson("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64urlJson("{\"sub\":\"" + jsonEscape(openid) + "\",\"iat\":" + now
                + ",\"exp\":" + (now + LOGIN_TOKEN_TTL_SECONDS) + ",\"jti\":\"" + UUID.randomUUID() + "\"}");
        String data = header + "." + payload;
        return data + "." + hmacSha256B64Url(loginTokenKey, data);
    }

    /** 校验登录 Token（供后续业务接口复用） */
    public static boolean verifyLoginToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;
            String expected = hmacSha256B64Url(loginTokenKey, parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) return false;
            String payload = new String(Base64.getUrlDecoder().decode(padB64(parts[1])), StandardCharsets.UTF_8);
            String expStr = extractJsonNumber(payload, "exp");
            return expStr != null && Long.parseLong(expStr) > System.currentTimeMillis() / 1000;
        } catch (Exception e) {
            return false;
        }
    }

    // ================= 微信验签与被动回复 =================

    /** signature = sha1(字典序排序(token, timestamp, nonce) 后拼接) */
    static boolean checkSignature(String signature, String timestamp, String nonce) {
        if (signature == null || timestamp == null || nonce == null) return false;
        try {
            List<String> list = new ArrayList<>(List.of(WECHAT_TOKEN, timestamp, nonce));
            list.sort(null);
            StringBuilder sb = new StringBuilder();
            for (String s : list) sb.append(s);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 被动回复文本消息（FromUserName 是公众号微信号，ToUserName 是用户 openid） */
    static String passiveTextReply(String toUser, String fromUser, String content) {
        long ts = System.currentTimeMillis() / 1000;
        return "<xml><ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + fromUser + "]]></FromUserName>"
                + "<CreateTime>" + ts + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content></xml>";
    }

    /** 提取 XML 标签值（兼容 CDATA 与纯文本） */
    static String extractTag(String xml, String tag) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + tag + ">", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    // ================= 会话与密钥管理 =================

    static void removeSession(Session s) {
        if (s.code != null) CODE_INDEX.remove(s.code, s.loginId);
        SESSIONS.remove(s.loginId, s);
    }

    static void cleanupExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.values().removeIf(s -> {
            if (now > s.expiresAt) {
                CODE_INDEX.remove(s.code, s.loginId);
                return true;
            }
            return false;
        });
    }

    /** 登录 Token 签名密钥：优先环境变量，否则自动生成并持久化到工作目录 token.key（重启不失效） */
    static String loadOrCreateKey() throws IOException {
        String fromEnv = System.getenv("LOGIN_TOKEN_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path p = Paths.get(System.getProperty("user.dir"), "token.key");
        if (Files.exists(p)) {
            return Files.readString(p).trim();
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        String keyStr = Base64.getEncoder().encodeToString(key);
        Files.writeString(p, keyStr);
        System.out.println("[init] generated login token key -> " + p);
        return keyStr;
    }

    // ================= 编解码与 HTTP 工具 =================

    static String hmacSha256B64Url(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String b64urlJson(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String padB64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return s + "=".repeat(pad);
    }

    static String extractJsonNumber(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 提取 JSON 字符串字段值（简单实现，仅用于调试模拟接口） */
    static String extractJsonString(String json, String field) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new ConcurrentHashMap<>();
        if (rawQuery == null) return map;
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                try {
                    map.putIfAbsent(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static void sendText(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        try (OutputStream os = ex.getResponseBody()) {
            ex.sendResponseHeaders(status, bytes.length);
            os.write(bytes);
        }
    }

    static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        try (OutputStream os = ex.getResponseBody()) {
            ex.sendResponseHeaders(status, bytes.length);
            os.write(bytes);
        }
    }

    // ---------- 领域对象 ----------
    static final class Session {
        final String loginId;
        final String code;
        final long expiresAt;
        volatile boolean confirmed;
        volatile String openid;
        volatile String token;

        Session(String loginId, String code, long expiresAt) {
            this.loginId = loginId;
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }
}
