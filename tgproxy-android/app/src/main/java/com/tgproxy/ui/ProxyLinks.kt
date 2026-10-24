package com.tgproxy.ui

import android.content.Intent
import android.net.Uri
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.ProxyType

/**
 * 将代理链接转换为 Telegram 可识别的 tg:// scheme（纯函数，JVM 可测）。
 *
 * - `tg://` 开头的链接原样返回；
 * - `http(s)://(www.)t.me/...` 转换为 `tg://...`（去掉主机部分，仅保留路径与查询）；
 * - 其余链接（socks5://、非 t.me 的 http(s)://）原样返回。
 *
 * 例：`https://t.me/proxy?server=1.2.3.4&port=443&secret=x` → `tg://proxy?server=1.2.3.4&port=443&secret=x`
 */
fun toTelegramScheme(link: String): String {
    if (link.startsWith("tg://")) return link
    val lower = link.lowercase()
    val isTme = lower.startsWith("https://t.me/") ||
        lower.startsWith("http://t.me/") ||
        lower.startsWith("https://www.t.me/") ||
        lower.startsWith("http://www.t.me/")
    if (!isTme) return link
    val rest = link.substringAfter("://")
    val pathAndQuery = rest.substringAfter("/", "")
    return "tg://$pathAndQuery"
}

/**
 * 构建 MTProto 跳转 Intent：ACTION_VIEW + [toTelegramScheme] 转换后的 tg:// 链接。
 */
fun buildTelegramViewIntent(link: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(toTelegramScheme(link)))

/**
 * 把任意可用代理转换为 Telegram 可识别的添加链接：
 * MTProto 用自带 secret 的 tg://proxy 分享链接；SOCKS5 必须用 tg://socks
 * （tg://proxy 缺 secret 会被 Telegram 忽略）。Telegram 收到后直接弹出
 * 「添加代理」窗口，全程不经过网页。
 */
fun toTelegramProxyUrl(entry: ProxyEntry): String =
    if (entry.type == ProxyType.MTPROTO) {
        toTelegramScheme(entry.link)
    } else {
        "tg://socks?server=" + entry.host + "&port=" + entry.port
    }

/**
 * 构建分享 Intent：ACTION_SEND text/plain + 完整链接，经 createChooser 弹出系统分享面板。
 */
fun buildShareIntent(link: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    return Intent.createChooser(send, "分享代理链接")
}
