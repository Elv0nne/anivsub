package recloudstream.migration

import android.content.SharedPreferences
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.DataStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Khi domain của site đổi, ID phim (hash của URL) đổi theo và CloudStream mất lịch sử/yêu thích/tiến độ xem.
 * Lớp này viết lại các khoá trong SharedPreferences của DataStore từ ID cũ sang ID mới.
 * Dựng lại từ ProviderDomainMigration trong dex (jadx 1.5.6).
 */
object ProviderDomainMigration {
    private const val DUB_STEP = 1_000_000L
    private const val KEY_HEALED = "healed_v44"
    private const val KEY_HOST = "migrated_domain_cache"
    private const val KEY_HOSTS = "known_hosts"
    private const val KEY_PAIRS = "id_pairs"
    private const val MAX_EPISODES = 2000
    private const val MAX_RELATIVE_EPISODE_ID = 1_073_741_824L
    private const val MAX_SEASONS = 19
    private const val SEASON_STEP = 10_000L

    private val repairedShows: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "s.id", "t.co", "cutt.ly", "is.gd", "shorturl.at",
        "rebrand.ly", "goo.gl", "ow.ly", "buff.ly", "t.ly", "linktr.ee", "short.gy"
    )
    private val SHOW_FOLDERS = setOf(
        "result_watch_state", "result_watch_state_data", "result_subscribed_state_data",
        "result_favorites_state_data", "result_episode", "result_season", "result_dub",
        "result_resume_watching_2", "result_resume_watching", "download_header_cache",
        "BACKUP_download_header_cache"
    )
    private val EPISODE_FOLDERS = setOf("video_pos_dur", "video_watch_state")

    /** Gọi mỗi lần mở trang chủ: nếu domain đã đổi so với lần trước thì chuyển dữ liệu cũ sang domain mới. */
    fun migrate(api: MainAPI, baseUrl: String? = null) {
        try {
            val ctx = CloudStreamApp.context ?: return
            val origin = siteOrigin(baseUrl ?: api.mainUrl) ?: return
            val host = hostOf(origin)
            if (host.isEmpty() || host in SHORTENERS) return
            val migPrefs = ctx.getSharedPreferences(api.name + "_migrated_prefs", 0)

            if (migPrefs.getString(KEY_HOST, null) != host) {
                val knownHosts = readHosts(migPrefs).toMutableSet()
                migPrefs.getString(KEY_HOST, null)?.let { knownHosts.add(it) }
                if (knownHosts.isEmpty()) {
                    // Lần đầu: dò host cũ từ chính dữ liệu đã lưu của provider này
                    for ((_, v) in DataStore.getSharedPrefs(ctx).all) {
                        val raw = v as? String ?: continue
                        if (!raw.startsWith("{")) continue
                        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                        if (obj.optString("apiName") == api.name) {
                            val h = hostOf(obj.optString("url", ""))
                            if (h.isNotEmpty() && h != host && h !in SHORTENERS) knownHosts.add(h)
                        }
                    }
                }
                if (knownHosts.isNotEmpty()) {
                    val prefs = DataStore.getSharedPrefs(ctx)
                    val all = prefs.all
                    val editor = prefs.edit()
                    migrateSnapshot(all, editor, api, origin, host, knownHosts, migPrefs)?.invoke()
                }
                knownHosts.add(host)
                migPrefs.edit()
                    .putString(KEY_HOSTS, JSONArray(knownHosts.toList()).toString())
                    .putString(KEY_HOST, host)
                    .apply()
            }
            // HIỆU NĂNG: `prefs.all` sao chép TOÀN BỘ dữ liệu CloudStream (lịch sử, đang xem dở, thư viện:
            // hàng nghìn mục) và được tính TRƯỚC khi healOrphanedShows kịp thấy cờ "đã chạy rồi". Trước đây
            // mỗi mục của trang chủ (tải song song, nhiều mục) đều làm một bản sao như vậy dù không còn gì
            // để sửa. Kiểm tra cờ trước, chỉ lấy snapshot khi thật sự cần.
            if (!migPrefs.getBoolean(KEY_HEALED, false)) runCatching {
                val prefs = DataStore.getSharedPrefs(ctx)
                healOrphanedShows(api, origin, prefs.all, prefs, migPrefs)
            }
        } catch (_: Throwable) {
        }
    }

    /** Gọi khi mở một phim: sửa ID cũ -> ID mới cho đúng phim này (nếu có dữ liệu mồ côi). */
    fun repairShow(api: MainAPI, incomingUrl: String, realUrl: String, episodeCount: Int = 0) {
        try {
            val ctx = CloudStreamApp.context ?: return
            val origin = siteOrigin(realUrl) ?: return
            val newId = cloudStreamId(realUrl, api.mainUrl)
            val guardKey = api.name + "|" + newId
            if (guardKey in repairedShows) return
            val prefs = DataStore.getSharedPrefs(ctx)
            val all = prefs.all
            val migPrefs = ctx.getSharedPreferences(api.name + "_migrated_prefs", 0)
            val candidates = LinkedHashSet<Int>()
            val incomingId = cloudStreamId(incomingUrl, api.mainUrl)
            if (incomingId != newId) candidates.add(incomingId)
            val paired = readPairs(migPrefs).optInt(newId.toString(), Int.MIN_VALUE)
            if (paired != Int.MIN_VALUE) candidates.add(paired)
            candidates.addAll(staleParents(all, newId))
            candidates.remove(newId)
            if (candidates.isNotEmpty()) {
                repairIds(api, origin, newId, candidates, episodeCount, all, prefs.edit(), migPrefs)
            }
            repairedShows.add(guardKey)
        } catch (_: Throwable) {
        }
    }

    private fun migrateSnapshot(
        snapshot: Map<String, *>, editor: SharedPreferences.Editor, api: MainAPI,
        origin: String, host: String, knownHosts: Set<String>, migPrefs: SharedPreferences
    ): (() -> Unit)? {
        val idMap = LinkedHashMap<Int, Int>()
        val fixedValues = HashMap<String, String>()
        var changed = false

        for ((key, value) in snapshot) {
            val raw = value as? String ?: continue
            if (!raw.startsWith("{")) continue
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (obj.optString("apiName") != api.name) continue
            val url = obj.optString("url", "")
            if (!url.startsWith("http")) continue
            val oldId = prefKeyId(key) ?: continue
            val urlHost = hostOf(url)
            if (urlHost != host && urlHost in knownHosts) {
                val newUrl = rebaseUrl(url, origin) ?: continue
                val newId = cloudStreamId(newUrl, api.mainUrl)
                obj.put("url", newUrl)
                if (oldId != newId) {
                    if (obj.optInt("id", Int.MIN_VALUE) == oldId) obj.put("id", newId)
                    idMap[oldId] = newId
                }
                fixedValues[key] = obj.toString()
                changed = true
            }
        }

        if (idMap.isNotEmpty()) {
            for ((key2, value2) in snapshot) {
                if (!isShowKey(key2)) continue
                val oldId = prefKeyId(key2) ?: continue
                val newId = idMap[oldId] ?: continue
                val raw2 = value2 as? String ?: continue
                if (!belongsTo(raw2, api.name)) continue
                val newKey = prefFolder(key2) + "/" + newId
                if (newKey == key2) continue
                if (hasKey(snapshot, newKey)) {
                    editor.remove(key2)
                    changed = true
                } else {
                    editor.putString(newKey, migrateStateJson(fixedValues[key2] ?: raw2, oldId, newId, origin))
                    editor.remove(key2)
                    changed = true
                }
            }
            val epFolders = episodeFolders(snapshot)
            if (epFolders.isNotEmpty()) {
                for ((oldId, newId) in idMap) {
                    if (moveEpisodeKeys(snapshot, editor, epFolders, oldId, newId, MAX_EPISODES)) changed = true
                }
            }
            val pairs = readPairs(migPrefs)
            for ((oldId, newId) in idMap) pairs.put(newId.toString(), oldId)
            migPrefs.edit().putString(KEY_PAIRS, pairs.toString()).apply()
        }
        return if (changed) ({ editor.apply() }) else null
    }

    /** Chạy một lần (cờ healed_v44): gom dữ liệu "đang xem dở" mồ côi về đúng ID phim hiện tại. */
    private fun healOrphanedShows(
        api: MainAPI, origin: String, snapshot: Map<String, *>,
        prefs: SharedPreferences, migPrefs: SharedPreferences
    ) {
        if (migPrefs.getBoolean(KEY_HEALED, false)) return
        val editor = prefs.edit()
        var changed = false
        for ((key, _) in snapshot) {
            if (prefFolderName(key) != "result_resume_watching_2") continue
            val showId = prefKeyId(key) ?: continue
            val stale = staleParents(snapshot, showId)
            if (stale.isEmpty()) continue
            val epFolders = episodeFolders(snapshot)
            val maxEpisode = maxEpisodeHint(snapshot, showId)
            for (oldId in stale) {
                for ((k, v) in snapshot) {
                    if (isShowKey(k) && prefKeyId(k) == oldId) {
                        val raw = v as? String ?: continue
                        if (belongsTo(raw, api.name)) {
                            val newKey = prefFolder(k) + "/" + showId
                            if (newKey != k && !hasKey(snapshot, newKey)) {
                                editor.putString(newKey, migrateStateJson(raw, oldId, showId, origin))
                                editor.remove(k)
                                changed = true
                            }
                        }
                    }
                }
                var changed3 = changed
                for ((k2, v2) in snapshot) {
                    if (isShowKey(k2) && prefKeyId(k2) == showId) {
                        val raw2 = v2 as? String ?: continue
                        val fixed = migrateStateJson(raw2, oldId, showId, origin)
                        if (fixed != raw2) {
                            editor.putString(k2, fixed)
                            changed3 = true
                        }
                    }
                }
                changed = if (moveEpisodeKeys(snapshot, editor, epFolders, oldId, showId, maxEpisode)) true else changed3
            }
        }
        if (changed) editor.apply()
        migPrefs.edit().putBoolean(KEY_HEALED, true).apply()
    }

    private fun maxEpisodeHint(snapshot: Map<String, *>, showId: Int): Int {
        var max = 0
        for ((key, value) in snapshot) {
            if (prefKeyId(key) != showId) continue
            val folder = prefFolderName(key)
            val raw = value as? String ?: continue
            when (folder) {
                "result_episode" -> {
                    raw.trim().toIntOrNull()?.let { if (it > max) max = it }
                    continue
                }
                "result_resume_watching", "result_resume_watching_2" -> {}
                else -> continue
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null) {
                val ep = obj.optInt("episode", 0)
                if (ep > max) max = ep
            }
        }
        return max.coerceIn(1, MAX_EPISODES)
    }

    private fun staleParents(snapshot: Map<String, *>, newId: Int): List<Int> {
        val out = ArrayList<Int>()
        for ((key, value) in snapshot) {
            if (prefFolderName(key) == "result_resume_watching_2" && prefKeyId(key) == newId) {
                val obj = runCatching { JSONObject((value as? String) ?: "") }.getOrNull()
                if (obj != null) {
                    val parentId = obj.optInt("parentId", Int.MIN_VALUE)
                    if (parentId != Int.MIN_VALUE && parentId != newId) out.add(parentId)
                }
            }
        }
        return out
    }

    private fun repairIds(
        api: MainAPI, origin: String, newId: Int, candidates: Set<Int>, episodeCount: Int,
        snapshot: Map<String, *>, editor: SharedPreferences.Editor, migPrefs: SharedPreferences
    ) {
        var changed = false
        val epFolders = episodeFolders(snapshot)
        val maxEpisode = if (episodeCount > 0) episodeCount else MAX_EPISODES
        for (oldId in candidates) {
            if (oldId == newId) continue
            for ((key, value) in snapshot) {
                if (isShowKey(key) && prefKeyId(key) == oldId) {
                    val raw = value as? String ?: continue
                    if (belongsTo(raw, api.name)) {
                        val newKey = prefFolder(key) + "/" + newId
                        if (newKey != key && !hasKey(snapshot, newKey)) {
                            editor.putString(newKey, migrateStateJson(raw, oldId, newId, origin))
                            editor.remove(key)
                            changed = true
                        }
                    }
                }
            }
            var changed3 = changed
            for ((key2, value2) in snapshot) {
                if (isShowKey(key2) && prefKeyId(key2) == newId) {
                    val raw2 = value2 as? String ?: continue
                    val fixed = migrateStateJson(raw2, oldId, newId, origin)
                    if (fixed != raw2) {
                        editor.putString(key2, fixed)
                        changed3 = true
                    }
                }
            }
            if (moveEpisodeKeys(snapshot, editor, epFolders, oldId, newId, maxEpisode)) changed3 = true
            changed = changed3
        }
        if (changed) editor.apply()
        if (migPrefs.getString(KEY_PAIRS, null) != null) {
            val pairs = readPairs(migPrefs)
            for (c in candidates) pairs.remove(c.toString())
            migPrefs.edit().putString(KEY_PAIRS, pairs.toString()).apply()
        }
    }

    /** Dời khoá tiến độ từng tập: ID tập = ID phim + delta (delta = dub*1e6 + season*1e4 + tập). */
    private fun moveEpisodeKeys(
        snapshot: Map<String, *>, editor: SharedPreferences.Editor, folders: Set<String>,
        oldId: Int, newId: Int, maxEpisode: Int
    ): Boolean {
        if (folders.isEmpty()) return false
        var changed = false
        val limit = maxEpisode.coerceIn(1, MAX_EPISODES)
        val episodeKeys = ArrayList<Pair<String, Int>>()
        for (k in snapshot.keys) {
            if (prefFolderName(k) in EPISODE_FOLDERS) {
                prefKeyId(k)?.let { episodeKeys.add(k to it) }
            }
        }
        if (episodeKeys.isEmpty()) return false
        for ((key, id) in episodeKeys) {
            val delta = 4294967295L and (id.toLong() - oldId.toLong())
            if (isEpisodeDelta(delta, limit)) {
                val to = prefFolder(key) + "/" + (newId.toLong() + delta).toInt()
                if (to != key && !hasKey(snapshot, to)) {
                    val raw = snapshot[key] as? String
                    if (raw != null) {
                        editor.putString(to, raw)
                        editor.remove(key)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun isEpisodeDelta(delta: Long, maxEpisode: Int): Boolean {
        if (delta < 0 || delta > 4294967295L) return false
        for (dub in 0 until 2) {
            val rest = delta - dub * DUB_STEP
            if (rest >= 0) {
                val season = rest / SEASON_STEP
                if (season <= MAX_SEASONS) {
                    val ep = rest % SEASON_STEP
                    if (ep in 0..maxEpisode.toLong()) return true
                }
            }
        }
        return false
    }

    private fun hostOf(url: String) = siteHost(url)

    private fun rebaseUrl(url: String, origin: String): String? =
        if (url.isBlank() || origin.isBlank()) null else normalizeSiteUrl(url, origin)

    /** Cách CloudStream tính ID phim: hash của URL bỏ mainUrl và dấu "/". */
    private fun cloudStreamId(url: String, mainUrl: String): Int =
        url.replace(mainUrl, "").replace("/", "").hashCode()

    @Suppress("unused")
    private fun episodeKeyId(showId: Int, offset: Int): Int = (showId.toLong() + offset.toLong()).toInt()

    private fun shiftEpisodeId(episodeId: Int, oldShowId: Int, newShowId: Int): Int {
        val delta = (episodeId.toLong() - oldShowId.toLong()) and 4294967295L
        return if (delta >= MAX_RELATIVE_EPISODE_ID) episodeId else (newShowId.toLong() + delta).toInt()
    }

    private fun prefFolder(key: String) = key.substringBeforeLast('/', "")
    private fun prefFolderName(key: String) = prefFolder(key).substringAfterLast('/')
    private fun prefKeyId(key: String): Int? = key.substringAfterLast('/').toIntOrNull()

    private fun isShowKey(key: String): Boolean {
        val folder = prefFolderName(key)
        return folder in SHOW_FOLDERS || folder.endsWith("_sync")
    }

    private fun belongsTo(raw: String, apiName: String): Boolean {
        if (!raw.trimStart().startsWith("{")) return true
        val name = runCatching { JSONObject(raw).optString("apiName", "") }.getOrNull() ?: ""
        return name.isEmpty() || name == apiName
    }

    private fun hasKey(snapshot: Map<String, *>, key: String) = snapshot.containsKey(key)

    private fun episodeFolders(snapshot: Map<String, *>): Set<String> {
        val folders = LinkedHashSet<String>()
        for (k in snapshot.keys) if (prefFolderName(k) in EPISODE_FOLDERS) folders.add(prefFolder(k))
        return folders
    }

    private fun migrateStateJson(raw: String, oldId: Int, newId: Int, origin: String?): String {
        if (!raw.trimStart().startsWith("{")) return raw
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        var touched = false
        if (oldId != newId) {
            if (obj.optInt("id", Int.MIN_VALUE) == oldId) {
                obj.put("id", newId)
                touched = true
            }
            if (obj.optInt("parentId", Int.MIN_VALUE) == oldId) {
                obj.put("parentId", newId)
                touched = true
                val episodeId = obj.optInt("episodeId", Int.MIN_VALUE)
                if (episodeId != Int.MIN_VALUE) {
                    val shifted = shiftEpisodeId(episodeId, oldId, newId)
                    if (shifted != episodeId) {
                        obj.put("episodeId", shifted)
                        touched = true
                    }
                }
            }
        }
        if (origin != null) {
            val url = obj.optString("url", "")
            if (url.startsWith("http")) {
                val rebased = rebaseUrl(url, origin)
                if (rebased != null && rebased != url) {
                    obj.put("url", rebased)
                    touched = true
                }
            }
        }
        return if (touched) obj.toString() else raw
    }

    private fun readPairs(migPrefs: SharedPreferences): JSONObject {
        val raw = migPrefs.getString(KEY_PAIRS, null)
        if (raw.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
    }

    private fun readHosts(migPrefs: SharedPreferences): Set<String> {
        val raw = migPrefs.getString(KEY_HOSTS, null) ?: return emptySet()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
