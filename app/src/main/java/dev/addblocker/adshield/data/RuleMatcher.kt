package dev.addblocker.adshield.data

/**
 * Immutable snapshot of the active rules. A query for `a.b.example.com` is
 * matched against `a.b.example.com`, `b.example.com`, `example.com` and `com`.
 * An allow-list hit at any level wins over a block-list hit, which is how you
 * un-break a specific host that a big list over-blocked.
 *
 * Instances are swapped atomically (single volatile reference), so the DNS
 * workers never see a half-built rule set.
 */
class RuleMatcher(
    private val blocked: Set<String>,
    private val allowed: Set<String>
) {

    val blockCount: Int get() = blocked.size
    val allowCount: Int get() = allowed.size
    val isEmpty: Boolean get() = blocked.isEmpty()

    fun isBlocked(domain: String): Boolean {
        if (blocked.isEmpty()) return false

        val name = domain.lowercase().trimEnd('.')
        if (name.isEmpty()) return false

        var start = 0
        while (start < name.length) {
            if (start == 0) {
                if (allowed.contains(name)) return false
                if (blocked.contains(name)) return true
            } else {
                val suffix = name.substring(start)
                if (allowed.contains(suffix)) return false
                if (blocked.contains(suffix)) return true
            }

            val dot = name.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return false
    }

    companion object {
        val EMPTY = RuleMatcher(emptySet(), emptySet())
    }
}
