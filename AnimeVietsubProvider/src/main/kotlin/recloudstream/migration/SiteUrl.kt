package recloudstream.migration

import java.net.URL
import java.util.Locale

/** Chuẩn hoá URL cũ (domain khác) về domain hiện tại. Dựng lại từ SiteUrlKt trong dex. */
fun normalizeSiteUrl(url: String, currentBase: String): String {
    if (url.isBlank()) return url
    val base = currentBase.trimEnd('/')
    if (base.isBlank()) return url
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return base + url
    if (!url.startsWith("http://") && !url.startsWith("https://")) return url
    return try {
        val u = URL(url)
        val host = u.host.lowercase(Locale.ROOT).removePrefix("www.")
        val curHost = URL(base).host.lowercase(Locale.ROOT).removePrefix("www.")
        if (host == curHost) return url
        val q = u.query?.let { "?$it" } ?: ""
        base + (if (u.path.isNotBlank()) u.path else "/") + q
    } catch (_: Exception) {
        url
    }
}

fun siteOrigin(url: String): String? = try {
    val u = URL(url)
    if (u.host.isNullOrBlank()) null else "${u.protocol}://${u.host}"
} catch (_: Exception) {
    null
}

fun siteHost(url: String): String = try {
    URL(url).host.lowercase(Locale.ROOT).removePrefix("www.")
} catch (_: Exception) {
    ""
}
