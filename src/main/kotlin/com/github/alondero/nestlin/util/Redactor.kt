package com.github.alondero.nestlin.util

/**
 * Credential redaction for everything Nestlin logs, surfaces in exceptions,
 * or shows in the UI (issue #268).
 *
 * The policy is intentionally narrow: passwords, API tokens, POST bodies,
 * and authenticated URLs must never appear in:
 *   - log lines (`System.err.println(...)`)
 *   - thrown exception messages
 *   - save-state bytes (the slot manager scrubs the RA bytes anyway)
 *   - screenshots / diagnostic bundles
 *   - URLs the user can see (e.g. an avatar URL is fine; a profile URL
 *     with a `t=` query param must have the param scrubbed)
 *
 * Anything that could carry a credential goes through the same funnel:
 *   - [redactUrl]      — for HTTP request URLs and any link the UI shows
 *   - [redactMessage]  — for free-form text in exceptions or server errors
 *
 * The methods are pure (no I/O, no state) so they're trivially testable
 * and the same logic runs in production, tests, and the diagnostic dump.
 */
object Redactor {

    /** Parameter names whose values are always treated as credentials. */
    private val SENSITIVE_PARAMS: Set<String> = setOf(
        "t", "token", "api_token", "password", "pass", "username", "u", "key",
    )

    /** Minimum token length to consider redacting inside a free-form message. */
    private const val MIN_TOKEN_LEN: Int = 16

    /** Regex for tokens in messages: contiguous alphanumeric runs of MIN_TOKEN_LEN or more. */
    private val TOKEN_RE = Regex("[A-Za-z0-9]{${MIN_TOKEN_LEN},}")

    /**
     * Scrub credential-bearing query parameters from [url]. The path is
     * returned untouched; only the value of sensitive parameters (see
     * [SENSITIVE_PARAMS]) is replaced with `***`. Unknown parameter names
     * are preserved verbatim.
     *
     * Returns the input unchanged when [url] is null/blank or has no query
     * string. Case-insensitive parameter matching; preserves the original
     * parameter order and casing of unknown names.
     */
    fun redactUrl(url: String?): String {
        if (url.isNullOrEmpty()) return url ?: ""
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val query = url.substring(q + 1)
        if (query.isEmpty()) return url
        val redacted = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                // Bare key (e.g. `?standalone`) — leave alone.
                pair
            } else {
                val name = pair.substring(0, eq)
                val redactedName = if (name.lowercase() in SENSITIVE_PARAMS) "${name}=***" else pair
                redactedName
            }
        }
        return "$base?$redacted"
    }

    /**
     * Replace credentials that may have leaked into a free-form message —
     * typically a server-side error message returned by rcheevos's HTTP
     * response. The rules are conservative:
     *
     *   - Any contiguous alphanumeric run of [MIN_TOKEN_LEN] or more is
     *     treated as a token and replaced with `***`. This catches API
     *     tokens (rcheevos issues 32-char hex strings), passwords (rcheevos
     *     only stores a hash on the server, so this rarely fires in practice
     *     — but defence-in-depth is the whole point), and case-corrected
     *     usernames that happen to be long enough.
     *   - The match must NOT be inside a URL — URLs are handled by
     *     [redactUrl] first.
     *   - We do NOT redacted short tokens (anything < [MIN_TOKEN_LEN]) because
     *     that's likely legitimate text.
     *
     * Returns the input unchanged when [message] is null/blank.
     */
    fun redactMessage(message: String?): String {
        if (message.isNullOrEmpty()) return message ?: ""
        return TOKEN_RE.replace(message, "***")
    }

    /**
     * Belt-and-suspenders redaction for log lines that combine a URL and a
     * message body. URL query parameters are scrubbed first; then the
     * remaining text has long alphanumeric runs replaced.
     */
    fun redactLogLine(line: String?): String {
        if (line.isNullOrEmpty()) return line ?: ""
        // Find URLs in the line and redact each, then redact tokens in the
        // remaining text. Simple regex for http(s) URLs is sufficient —
        // rcheevos only ever emits https URLs to retroachievements.org.
        val urlRe = Regex("https?://[^\\s]+")
        val scrubbed = urlRe.replace(line) { match ->
            redactUrl(match.value)
        }
        return redactMessage(scrubbed)
    }
}