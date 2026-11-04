package com.xixin.vmq.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 一次连接测试的结果。
 *
 * 分层设计的目的：把「App 失败」拆成能在 UI 上直接看懂的五类原因，
 * 而不是笼统地显示「推送失败」，让用户在「浏览器能开、App 不行」之间反复猜。
 */
data class PingResult(
    val ok: Boolean,
    val title: String,
    val detail: String = "",
    val hint: String = ""
)

/**
 * 连接诊断。
 *
 * ## 为什么需要它
 *
 * 「浏览器打得开、App 请求失败」有确定的几种成因，逐一验证比猜快得多：
 *
 * 1. **地址填错层级**：只填 `https://域名`，请求落到 `/`，
 *    Cloudflare Pages 的 `_redirects` 会把所有路径重写到 `index.html` 并返回 **200**，
 *    于是 App 判定「成功」，实际什么都没发生。
 * 2. **密钥不一致**：服务端 `APP_SECRET` 与 App 里填的不同 → HMAC 不匹配 → 401。
 * 3. **服务端未配环境变量**：`APP_SECRET` 缺失时后端直接 500。
 * 4. **UA 被拦**：OkHttp 默认 UA 是 `okhttp/4.x`，个别 WAF / Bot Fight 会挑战非浏览器 UA。
 *    浏览器用真 UA，所以只有 App 受影响 —— 这是「浏览器正常、App 失败」的典型成因。
 * 5. **业务失败但 HTTP 200**：服务端若只回 `{code:404}` 而不改状态码，
 *    只看 HTTP 状态的客户端会误判成功。
 *
 * ## 测试策略
 *
 * 先 GET（探活，判断地址是否指向正确接口），再 POST 一个必然不匹配的签名，
 * 观察返回码来反推密钥配置是否正确。签名刻意用错，避免污染真实订单数据。
 */
object ConnDiagnostics {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(15, TimeUnit.SECONDS)
            }
        }
    }

    suspend fun check(endpoint: String, secret: String): PingResult = withContext(Dispatchers.IO) {
        val url = endpoint.trim()

        if (!url.startsWith("https://")) {
            return@withContext PingResult(
                ok = false,
                title = "地址格式不对",
                detail = url,
                hint = "必须以 https:// 开头"
            )
        }
        if (!url.contains("/api/push")) {
            return@withContext PingResult(
                ok = false,
                title = "地址缺少 /api/push",
                detail = "当前: $url",
                hint = "只填域名会打到网站首页并返回 200，App 会误判为成功。请填成 https://你的域名/api/push"
            )
        }
        if (secret.isBlank()) {
            return@withContext PingResult(
                ok = false,
                title = "通讯密钥为空",
                hint = "填的服务端环境变量 APP_SECRET 的值"
            )
        }

        // ---- 第一步：GET 探活 ----
        val ping = try {
            val res = client.get(url) {
                header(HttpHeaders.UserAgent, UA)
            }
            val body = res.bodyAsText()
            Triple(res.status.value, body, null)
        } catch (e: Exception) {
            Triple(-1, "", e)
        }

        if (ping.third != null) {
            val e = ping.third!!
            return@withContext PingResult(
                ok = false,
                title = "网络层失败（还没到服务端）",
                detail = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                hint = when {
                    e.message?.contains("SSL", true) == true ||
                        e.message?.contains("Certificate", true) == true ->
                        "证书校验失败。若你用了自签名证书或代理，需要在系统里信任它"
                    e.message?.contains("UnknownHost", true) == true ->
                        "域名解析失败，检查域名拼写与 DNS"
                    e.message?.contains("timeout", true) == true ->
                        "连接超时，检查网络或服务端是否在线"
                    else -> "检查手机能否访问该域名（用手机浏览器试一下）"
                }
            )
        }

        val (status, body, _) = ping

        // 地址被重写成了首页 HTML：说明路径不对，打到了静态资源
        if (body.trimStart().startsWith("<")) {
            return@withContext PingResult(
                ok = false,
                title = "地址指向了网页而不是接口",
                detail = "HTTP $status · 返回 HTML",
                hint = "「/api/push」路径不在这个地址上，或该 Pages 项目未部署 Functions"
            )
        }

        when (status) {
            200 -> {
                val isOurService = runCatching {
                    JSONObject(body).optString("service") == "vmq-push"
                }.getOrDefault(false)
                if (!isOurService) {
                    return@withContext PingResult(
                        ok = false,
                        title = "接口返回了非预期内容",
                        detail = "HTTP 200 · ${body.take(100)}",
                        hint = "该地址不是本项目的 /api/push，或部署的是旧版本（缺少 GET 探活）"
                    )
                }
            }
            404 -> return@withContext PingResult(
                ok = false,
                title = "接口不存在（404）",
                detail = body.take(120),
                hint = "Pages 项目可能没有部署 functions/api，或后端还是旧版本"
            )
            500 -> return@withContext PingResult(
                ok = false,
                title = "服务端配置不完整（500）",
                detail = body.take(120),
                hint = "多半是 Cloudflare 没有配置 APP_SECRET 环境变量"
            )
            403 -> return@withContext PingResult(
                ok = false,
                title = "被安全策略拦截（403）",
                detail = body.take(120),
                hint = "Cloudflare 的 WAF / Bot Fight 拦住了非浏览器 UA。可在 Cloudflare 后台放行该路径"
            )
            else -> return@withContext PingResult(
                ok = false,
                title = "探活返回 HTTP $status",
                detail = body.take(120),
                hint = "对照服务端日志确认"
            )
        }

        // ---- 第二步：POST 一个签名必然错误的请求，验证密钥链路 ----
        // dryRun=true 告诉服务端「这是连通性测试，不要告警、不要记安全日志」，
        // 否则每次点「测试连接」都会被当成伪造推送而触发告警邮件。
        val wrongSign = "0".repeat(64)
        val postResult = try {
            val res = client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, UA)
                setBody(
                    JSONObject()
                        .put("type", "alipay")
                        .put("price", "0.00")
                        .put("sign", wrongSign)
                        .put("dryRun", true)
                        .toString()
                )
            }
            res.status.value to res.bodyAsText()
        } catch (e: Exception) {
            return@withContext PingResult(
                ok = false,
                title = "POST 请求失败",
                detail = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                hint = "GET 能通但 POST 不通，检查是否有只针对 POST 的拦截规则"
            )
        }

        val (pStatus, pBody) = postResult
        return@withContext when {
            // 签名错误被拒 → 说明服务端确实在校验签名，链路是通的
            pStatus == 401 -> PingResult(
                ok = true,
                title = "服务端连通且签名校验正常",
                detail = "GET 探活 200 · POST 无效签名被正确拒绝（401）",
                hint = "链路没问题。若仍收到 401，说明 App 里的密钥与服务端 APP_SECRET 不一致"
            )
            // 无效签名却返回 404 → 服务端没校验签名就往下走了，顺序不对
            pStatus == 404 -> PingResult(
                ok = false,
                title = "服务端未校验签名（异常）",
                detail = "无效签名竟返回 404 而非 401",
                hint = "后端 /api/push 的验签逻辑顺序可能被改动了"
            )
            pStatus == 403 -> PingResult(
                ok = false,
                title = "POST 被安全策略拦截（403）",
                detail = pBody.take(120),
                hint = "Cloudflare WAF 对 POST 有额外规则，检查 Bot Fight / 速率限制"
            )
            pStatus >= 500 -> PingResult(
                ok = false,
                title = "服务端错误（$pStatus）",
                detail = pBody.take(120),
                hint = "看 Cloudflare Pages 的 Functions 实时日志"
            )
            else -> PingResult(
                ok = false,
                title = "POST 返回了非预期状态 $pStatus",
                detail = pBody.take(120),
                hint = "无效签名本应返回 401"
            )
        }
    }

    private const val UA = "VMQ-Monitor/2.0 (Android)"
}
