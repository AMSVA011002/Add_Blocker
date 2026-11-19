package dev.addblocker.adshield.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the DNS engine answers with when a domain is blocked. */
enum class BlockResponse(val storageKey: String, val title: String, val detail: String) {
    NXDOMAIN(
        "nxdomain",
        "NXDOMAIN",
        "The lookup fails immediately. Ad code gives up straight away."
    ),
    NULL_IP(
        "nullip",
        "0.0.0.0 / ::",
        "The lookup succeeds but points to the null address. Ad slots usually render empty."
    );

    companion object {
        fun fromKey(key: String?): BlockResponse = values().firstOrNull { it.storageKey == key } ?: NXDOMAIN
    }
}

/** Upstream resolver used for names that are not blocked. */
enum class Upstream(val storageKey: String, val title: String, val primary: String, val secondary: String) {
    CLOUDFLARE("cloudflare", "Cloudflare", "1.1.1.1", "1.0.0.1"),
    GOOGLE("google", "Google", "8.8.8.8", "8.8.4.4"),
    QUAD9("quad9", "Quad9", "9.9.9.9", "149.112.112.112"),
    ADGUARD("adguard", "AdGuard", "94.140.14.14", "94.140.15.15");

    companion object {
        fun fromKey(key: String?): Upstream = values().firstOrNull { it.storageKey == key } ?: CLOUDFLARE
    }
}

/**
 * Small typed wrapper around SharedPreferences. All reads/writes happen on the
 * caller's thread; the DNS hot path only reads volatiles cached elsewhere.
 */
object Prefs {

    private const val FILE = "adshield_prefs"

    private const val KEY_RESPONSE = "block_response"
    private const val KEY_UPSTREAM = "upstream_dns"
    private const val KEY_INTERCEPT_ALL = "intercept_hardcoded_dns"
    private const val KEY_LOG = "log_queries"
    private const val KEY_CUSTOM_BLOCKED = "custom_blocked"
    private const val KEY_CUSTOM_ALLOWED = "custom_allowed"
    private const val KEY_DAILY_DATE = "daily_date"
    private const val KEY_DAILY_QUERIES = "daily_queries"
    private const val KEY_DAILY_BLOCKED = "daily_blocked"
    private const val KEY_ONBOARDED = "onboarded"

    private lateinit var sp: SharedPreferences
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        initialized = true
    }

    var blockResponse: BlockResponse
        get() = BlockResponse.fromKey(sp.getString(KEY_RESPONSE, null))
        set(value) {
            sp.edit().putString(KEY_RESPONSE, value.storageKey).apply()
        }

    var upstream: Upstream
        get() = Upstream.fromKey(sp.getString(KEY_UPSTREAM, null))
        set(value) {
            sp.edit().putString(KEY_UPSTREAM, value.storageKey).apply()
        }

    /** Also hijack well known public DNS addresses so apps that hard-code 8.8.8.8 are filtered too. */
    var interceptHardcodedDns: Boolean
        get() = sp.getBoolean(KEY_INTERCEPT_ALL, true)
        set(value) {
            sp.edit().putBoolean(KEY_INTERCEPT_ALL, value).apply()
        }

    var logQueries: Boolean
        get() = sp.getBoolean(KEY_LOG, true)
        set(value) {
            sp.edit().putBoolean(KEY_LOG, value).apply()
        }

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) {
            sp.edit().putBoolean(KEY_ONBOARDED, value).apply()
        }

    var customBlocked: Set<String>
        get() = sp.getStringSet(KEY_CUSTOM_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet(KEY_CUSTOM_BLOCKED, value.toSet()).apply()
        }

    var customAllowed: Set<String>
        get() = sp.getStringSet(KEY_CUSTOM_ALLOWED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet(KEY_CUSTOM_ALLOWED, value.toSet()).apply()
        }

    /** yyyyMMdd in local time. Only called from synchronized blocks. */
    fun todayKey(): String = dayFormat.format(Date())

    fun dailyStats(): Pair<Long, Long> {
        val today = todayKey()
        val storedDate = sp.getString(KEY_DAILY_DATE, null)
        if (storedDate != today) return 0L to 0L
        return sp.getLong(KEY_DAILY_QUERIES, 0L) to sp.getLong(KEY_DAILY_BLOCKED, 0L)
    }

    fun saveDailyStats(queries: Long, blocked: Long) {
        sp.edit()
            .putString(KEY_DAILY_DATE, todayKey())
            .putLong(KEY_DAILY_QUERIES, queries)
            .putLong(KEY_DAILY_BLOCKED, blocked)
            .apply()
    }

    fun resetDailyStats() {
        sp.edit()
            .putString(KEY_DAILY_DATE, todayKey())
            .putLong(KEY_DAILY_QUERIES, 0L)
            .putLong(KEY_DAILY_BLOCKED, 0L)
            .apply()
    }
}
