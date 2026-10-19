/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.urlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun MieruBean.buildMieruConfig(port: Int): String {
    val portBinding = JSONObject().apply {
        if (!portRange.isNullOrBlank()) {
            put("portRange", portRange)
        } else {
            put("port", finalPort)
        }
        put("protocol", protocol)
    }

    val serverInfo = JSONArray().apply {
        put(JSONObject().apply {
            put("ipAddress", finalAddress)
            if (!domainName.isNullOrBlank()) {
                put("domainName", domainName)
            }
            put("portBindings", JSONArray().apply {
                put(portBinding)
            })
        })
    }

    return JSONObject().apply {
        put("activeProfile", "default")
        put("socks5Port", port)
        put("loggingLevel", "INFO")
        put("profiles", JSONArray().apply {
            put(JSONObject().apply {
                put("profileName", "default")
                put("user", JSONObject().apply {
                    put("name", username)
                    put("password", password)
                })
                put("servers", serverInfo)
                put("mtu", mtu ?: 1400)
                if (!multiplexing.isNullOrBlank()) {
                    put("multiplexing", JSONObject().apply {
                        put("level", multiplexing)
                    })
                }
                if (!handshakeMode.isNullOrBlank()) {
                    put("handshakeMode", handshakeMode)
                }
            })
        })
    }.toStringPretty()
}

fun parseMieru(url: String): MieruBean {
    if (url.startsWith("mierus://")) {
        val link = url.replace("mierus://", "https://").toHttpUrlOrNull()
            ?: error("invalid mierus link: $url")
        return MieruBean().apply {
            initializeDefaultValues()
            username = link.username
            password = link.password
            serverAddress = link.host
            name = link.fragment ?: link.queryParameter("profile") ?: ""

            link.queryParameter("port")?.let { p ->
                if (p.contains("-")) {
                    portRange = p
                } else {
                    serverPort = p.toIntOrNull() ?: 0
                }
            }
            link.queryParameter("protocol")?.let {
                protocol = it.uppercase()
            }
            link.queryParameter("multiplexing")?.let { m ->
                multiplexing = when (m.uppercase()) {
                    "OFF", "MULTIPLEXING_OFF" -> "MULTIPLEXING_OFF"
                    "LOW", "MULTIPLEXING_LOW" -> "MULTIPLEXING_LOW"
                    "MIDDLE", "MULTIPLEXING_MIDDLE" -> "MULTIPLEXING_MIDDLE"
                    "HIGH", "MULTIPLEXING_HIGH" -> "MULTIPLEXING_HIGH"
                    else -> m
                }
            }
            link.queryParameter("handshake-mode")?.let { h ->
                handshakeMode = when (h.uppercase()) {
                    "NO_WAIT", "HANDSHAKE_NO_WAIT" -> "HANDSHAKE_NO_WAIT"
                    else -> "HANDSHAKE_STANDARD"
                }
            }
            link.queryParameter("mtu")?.toIntOrNull()?.let {
                mtu = it
            }
            link.queryParameter("traffic-pattern")?.let {
                trafficPattern = it
            }
            link.queryParameter("domain-name")?.let {
                domainName = it
            } ?: link.queryParameter("domain_name")?.let {
                domainName = it
            }
        }
    } else if (url.startsWith("mieru://")) {
        val b64 = url.substringAfter("mieru://").trim()
        val jsonStr = b64.decodeBase64UrlSafe()
        val root = JSONObject(jsonStr)
        val profiles = root.optJSONArray("profiles") ?: error("invalid mieru json: missing profiles")
        val p = profiles.getJSONObject(0)
        val user = p.optJSONObject("user")
        val servers = p.optJSONArray("servers")
        val server = servers?.optJSONObject(0)
        val portBindings = server?.optJSONArray("portBindings")
        val pb = portBindings?.optJSONObject(0)

        return MieruBean().apply {
            initializeDefaultValues()
            name = p.optString("profileName", "")
            username = user?.optString("name", "") ?: ""
            password = user?.optString("password", "") ?: ""
            serverAddress = server?.optString("ipAddress", "") ?: ""
            domainName = server?.optString("domainName", "") ?: ""
            pb?.optString("portRange")?.takeIf { it.isNotBlank() }?.let {
                portRange = it
            } ?: run {
                serverPort = pb?.optInt("port", 0) ?: 0
            }
            pb?.optString("protocol")?.takeIf { it.isNotBlank() }?.let {
                protocol = it.uppercase()
            }
            p.optInt("mtu", 1400).let { mtu = it }
            p.optJSONObject("multiplexing")?.optString("level")?.takeIf { it.isNotBlank() }?.let {
                multiplexing = it
            }
            p.optString("handshakeMode")?.takeIf { it.isNotBlank() }?.let {
                handshakeMode = it
            }
        }
    } else {
        error("unsupported mieru link format: $url")
    }
}

fun MieruBean.toUri(): String {
    val builder = linkBuilder().username(username).password(password).host(serverAddress)
    if (!portRange.isNullOrBlank()) {
        builder.addQueryParameter("port", portRange)
    } else {
        builder.port(serverPort)
    }
    builder.addQueryParameter("protocol", protocol)
    if (!multiplexing.isNullOrBlank()) {
        builder.addQueryParameter("multiplexing", multiplexing)
    }
    if (!handshakeMode.isNullOrBlank()) {
        builder.addQueryParameter("handshake-mode", handshakeMode)
    }
    if (!trafficPattern.isNullOrBlank()) {
        builder.addQueryParameter("traffic-pattern", trafficPattern)
    }
    if (mtu != null && mtu != 1400) {
        builder.addQueryParameter("mtu", mtu.toString())
    }
    if (!domainName.isNullOrBlank()) {
        builder.addQueryParameter("domain-name", domainName)
    }
    if (displayName().isNotBlank()) {
        builder.encodedFragment(displayName().urlSafe())
    }
    return builder.toLink("mierus")
}
