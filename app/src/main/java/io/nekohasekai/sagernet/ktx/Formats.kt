package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

data class SubscriptionInfo(
    val url: String,
    val name: String? = null
)

fun parseSubscriptionUrl(rawText: String): SubscriptionInfo? {
    val text = rawText.trim()
    if (text.isBlank()) return null
    val lower = text.lowercase()

    // 1. sub:// 格式 (3x-ui / Shadowrocket 常见)
    if (lower.startsWith("sub://")) {
        var payload = text.substring(6).trim()
        var title: String? = null
        if (payload.contains("?title=")) {
            val parts = payload.split("?title=", limit = 2)
            payload = parts[0]
            title = runCatching { Uri.decode(parts[1]) }.getOrNull()
        } else if (payload.contains("#")) {
            val parts = payload.split("#", limit = 2)
            payload = parts[0]
            title = runCatching { Uri.decode(parts[1]) }.getOrNull()
        }

        val decoded = runCatching {
            String(Util.b64Decode(payload)).trim()
        }.getOrNull()

        val actualUrl = if (decoded != null && (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true))) {
            decoded
        } else if (payload.startsWith("http://", ignoreCase = true) || payload.startsWith("https://", ignoreCase = true)) {
            payload
        } else {
            null
        }

        if (actualUrl != null) {
            return SubscriptionInfo(actualUrl, title)
        }
    }

    // 2. shadowrocket:// 格式
    if (lower.startsWith("shadowrocket://")) {
        val uri = runCatching { text.toUri() }.getOrNull()
        val title = uri?.getQueryParameter("title") ?: uri?.fragment

        val addIndex = text.indexOf("add/", ignoreCase = true)
        if (addIndex != -1) {
            val subPart = text.substring(addIndex + 4).substringBefore("?").substringBefore("#")
            if (subPart.startsWith("sub://", ignoreCase = true)) {
                return parseSubscriptionUrl(subPart)?.let {
                    if (!title.isNullOrBlank()) it.copy(name = title) else it
                }
            } else if (subPart.startsWith("http://", ignoreCase = true) || subPart.startsWith("https://", ignoreCase = true)) {
                return SubscriptionInfo(subPart, title)
            }
        }

        val queryUrl = uri?.getQueryParameter("url")
        if (!queryUrl.isNullOrBlank()) {
            if (queryUrl.startsWith("sub://", ignoreCase = true)) {
                return parseSubscriptionUrl(queryUrl)?.let {
                    if (!title.isNullOrBlank()) it.copy(name = title) else it
                }
            }
            return SubscriptionInfo(queryUrl, title)
        }
    }

    // 3. clash:// / clashmeta:// / mihomo:// / meta://
    if (lower.startsWith("clash://") || lower.startsWith("clashmeta://") ||
        lower.startsWith("mihomo://") || lower.startsWith("meta://")
    ) {
        val uri = runCatching { text.toUri() }.getOrNull()
        var name = uri?.getQueryParameter("name")

        var url: String? = uri?.getQueryParameter("url")
        val urlIndex = text.indexOf("url=")
        if (urlIndex != -1) {
            val afterUrl = text.substring(urlIndex + 4)
            val nameIndex = afterUrl.indexOf("&name=")
            val rawUrl = if (nameIndex != -1) {
                if (name.isNullOrBlank()) {
                    name = runCatching { Uri.decode(afterUrl.substring(nameIndex + 6)) }.getOrNull()
                }
                afterUrl.substring(0, nameIndex)
            } else {
                afterUrl
            }
            val decoded = runCatching { Uri.decode(rawUrl) }.getOrDefault(rawUrl)
            if (url.isNullOrBlank() || (decoded.length > url.length && (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)))) {
                url = decoded
            }
        } else if (url.isNullOrBlank() && uri != null) {
            val ssp = uri.schemeSpecificPart.removePrefix("//")
            if (ssp.startsWith("http://", ignoreCase = true) || ssp.startsWith("https://", ignoreCase = true)) {
                url = ssp
            }
        }

        if (!url.isNullOrBlank()) {
            return SubscriptionInfo(url, name)
        }
    }

    // 4. sing-box:// 格式
    if (lower.startsWith("sing-box://")) {
        val uri = runCatching { text.toUri() }.getOrNull()
        val url = uri?.getQueryParameter("url")
        val name = uri?.getQueryParameter("name") ?: uri?.fragment
        if (!url.isNullOrBlank()) {
            return SubscriptionInfo(url, name)
        }
    }

    // 5. 其他客户端常见 scheme: v2rayng:// / surge:// / surge3:// / stash:// / loon:// / quantumult-x:// / quanx://
    if (lower.startsWith("v2rayng://") || lower.startsWith("surge://") ||
        lower.startsWith("surge3://") || lower.startsWith("stash://") ||
        lower.startsWith("loon://") || lower.startsWith("quantumult-x://") ||
        lower.startsWith("quanx://")
    ) {
        val uri = runCatching { text.toUri() }.getOrNull()
        val url = uri?.getQueryParameter("url")
            ?: uri?.getQueryParameter("remote-resource")
            ?: uri?.getQueryParameter("sub")
        val name = uri?.getQueryParameter("name") ?: uri?.getQueryParameter("title") ?: uri?.fragment
        if (!url.isNullOrBlank()) {
            return SubscriptionInfo(url, name)
        }
    }

    // 6. sn://subscription 格式 (SagerNet 专用序列化)
    if (lower.startsWith("sn://subscription")) {
        return SubscriptionInfo(text, null)
    }

    // 7. 直接的 http:// 或 https:// 链接 (3x-ui / 机场订阅)
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        val uri = runCatching { text.toUri() }.getOrNull()
        val path = uri?.path?.lowercase() ?: ""
        val query = uri?.query?.lowercase() ?: ""

        val isSubPattern = path.contains("/sub/") ||
                path.endsWith("/sub") ||
                path.contains("/json/") ||
                path.endsWith("/json") ||
                path.contains("/subscribe") ||
                path.contains("/api/v1/client/subscribe") ||
                path.contains("/link/") ||
                query.contains("token=") ||
                query.contains("sub=") ||
                query.contains("key=") ||
                query.contains("auth=") ||
                query.contains("target=") ||
                query.contains("flag=")

        if (isSubPattern) {
            val name = uri?.fragment?.takeIf { it.isNotBlank() }
            return SubscriptionInfo(text, name)
        }

        val isHttpProxy = runCatching {
            parseHttp(text)
            true
        }.getOrDefault(false)

        if (!isHttpProxy) {
            val name = uri?.fragment?.takeIf { it.isNotBlank() }
            return SubscriptionInfo(text, name)
        }
    }

    return null
}

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        val trimmed = trim()
        val lower = trimmed.lowercase()

        val subInfo = parseSubscriptionUrl(trimmed)
        if (subInfo != null) {
            if (subInfo.url.startsWith("sn://subscription")) {
                throw SubscriptionFoundException(subInfo.url)
            }
            val clashUrl = HttpUrl.Builder()
                .scheme("https")
                .host("install-config")
                .addQueryParameter("url", subInfo.url)
                .apply {
                    if (!subInfo.name.isNullOrBlank()) {
                        addQueryParameter("name", subInfo.name)
                    }
                }
                .build()
                .toString()
                .replaceFirst("https://", "clash://")
            throw SubscriptionFoundException(clashUrl)
        }

        if (lower.startsWith("sn://")) {
            Logs.d("Try parse universal link: $this")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("socks://") || lower.startsWith("socks4://") || lower.startsWith("socks4a://") || lower.startsWith(
                "socks5://"
            )
        ) {
            Logs.d("Try parse socks link: $this")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            Logs.d("Try parse http link: $this")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w(it)
                val clashUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("install-config")
                    .addQueryParameter("url", this)
                    .build()
                    .toString()
                    .replaceFirst("https://", "clash://")
                throw (SubscriptionFoundException(clashUrl))
            }
        } else if (lower.startsWith("vmess://")) {
            Logs.d("Try parse v2ray link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("vless://")) {
            Logs.d("Try parse vless link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("trojan://")) {
            Logs.d("Try parse trojan link: $this")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("trojan-go://")) {
            Logs.d("Try parse trojan-go link: $this")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("ss://") || lower.startsWith("shadowsocks://")) {
            Logs.d("Try parse shadowsocks link: $this")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("naive+")) {
            Logs.d("Try parse naive link: $this")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("hysteria://")) {
            Logs.d("Try parse hysteria1 link: $this")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
            Logs.d("Try parse hysteria2 link: $this")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("tuic://")) {
            Logs.d("Try parse TUIC link: $this")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("anytls://")) {
            Logs.d("Try parse anytls link: $this")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (lower.startsWith("mieru://") || lower.startsWith("mierus://")) {
            Logs.d("Try parse mieru link: $this")
            runCatching {
                entities.add(parseMieru(this))
            }.onFailure {
                Logs.w(it)
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
//    var isBadLink = false
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                return@test
            }
        }
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}