package dev.addblocker.adshield.data

/**
 * Parsing and normalisation helpers for block lists. Accepts plain domain lists,
 * hosts files ("0.0.0.0 ads.example.com") and simple AdBlock-style domain rules
 * ("||ads.example.com^"). Everything is normalised to a bare lower-case domain.
 */
object Domains {

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val IGNORED = setOf(
        "localhost",
        "localhost.localdomain",
        "localhost6",
        "local",
        "broadcasthost",
        "wpad",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-allnodes",
        "ip6-allrouters",
        "0.0.0.0",
        "127.0.0.1",
        "255.255.255.255",
        "::1",
        "::"
    )

    private fun isIpToken(token: String): Boolean =
        token.contains(':') || IPV4.matches(token)

    /** Returns a bare domain, or null when the text is not a usable domain rule. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        s = s.removePrefix("||")
        s = s.removePrefix("*.")
        s = s.substringBefore('^')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        s = s.trim('.', '|', '^')
        s = s.lowercase()

        if (s.length < 4 || s.length > 253) return null
        if (!s.contains('.')) return null
        if (s.startsWith(".") || s.endsWith(".") || s.contains("..")) return null
        if (s.startsWith("-") || s.endsWith("-")) return null
        if (s in IGNORED) return null
        for (ch in s) {
            val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'
            if (!ok) return null
        }
        return s
    }

    /** Parses one line of a hosts file, domain list or AdBlock domain rule. */
    fun parseLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("!") || trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("[")) {
            return null
        }

        val withoutComment = trimmed.substringBefore('#').trim()
        if (withoutComment.isEmpty()) return null

        if (withoutComment.startsWith("||")) {
            return normalize(withoutComment)
        }

        if (withoutComment.any { it == ' ' || it == '\t' }) {
            val tokens = withoutComment.split(WHITESPACE)
            val candidates = if (tokens.size > 1 && isIpToken(tokens[0])) tokens.drop(1) else tokens
            for (candidate in candidates) {
                val domain = normalize(candidate)
                if (domain != null) return domain
            }
            return null
        }

        return normalize(withoutComment)
    }

    private val WHITESPACE = Regex("\\s+")
}
