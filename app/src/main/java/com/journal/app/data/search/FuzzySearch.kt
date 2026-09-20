package com.journal.app.data.search

import com.journal.app.data.local.JournalEntry
import kotlin.math.abs
import kotlin.math.min

/**
 * Forgiving text search over journal entries.
 *
 * ### Why this is not a SQL `LIKE` (or FTS)
 * Georgian is heavily inflected and has no capitals, so the word the user remembers is rarely the
 * form they typed: `მუშაობა` in the entry, `მუშაობდა` or `მუშაობ` in the search box. A `LIKE
 * '%…%'` finds neither. SQLite's FTS5 would help with tokenisation but its stemmers know nothing
 * about Mkhedruli, and `unicode61` folding is a no-op on a script without case — so the extra
 * table and migration would buy almost nothing.
 *
 * Instead, matching happens in memory over the entry list the feed already holds. A personal
 * journal is thousands of rows, not millions, and scoring the lot costs well under a frame.
 *
 * ### The scoring ladder
 * Every whitespace-separated token in the query must match *somewhere* in the entry (AND, not OR
 * — an OR search over free text returns everything). A token matches, best first:
 *
 * | Match | Score |
 * |---|---|
 * | whole word is identical | 200 |
 * | a word starts with the token (`მუშაობ` → `მუშაობა`) | 120 |
 * | the token starts with a whole word (`მუშაობაში` → `მუშაობა`) | 110 |
 * | the token appears anywhere in the text | 100 |
 * | a word is within an edit-distance budget of the token | 40..60 |
 *
 * The whole query appearing verbatim adds a large bonus on top, so an exact phrase always
 * outranks a scattering of near-misses. The edit-distance budget scales with token length: typos
 * in a three-letter word are indistinguishable from a different word, so short tokens must match
 * exactly.
 */
object FuzzySearch {

    /** Below this length, an edit-distance match is noise rather than a typo. */
    private const val MIN_FUZZY_LENGTH = 4

    /** A word must be at least this long to be worth matching a longer query token against. */
    private const val MIN_STEM_LENGTH = 4

    private const val SCORE_PHRASE = 1_000
    private const val SCORE_WORD_EXACT = 200
    private const val SCORE_WORD_PREFIX = 120
    private const val SCORE_TOKEN_STEM = 110
    private const val SCORE_SUBSTRING = 100
    private const val SCORE_FUZZY_BASE = 70
    private const val SCORE_FUZZY_PENALTY = 15

    /**
     * Splits on everything that is not a letter, a digit, `#` or `_`.
     *
     * `#` survives so `#მუშაობა` searches as one token and finds the tag; `_` survives because
     * that is how [com.journal.app.data.remote.OpenRouterClient] joins two-word tags.
     */
    private val SPLITTER = Regex("[^\\p{L}\\p{N}#_]+")

    /** The haystack for one entry: its text, its prompt, and its tags. */
    fun searchableText(entry: JournalEntry): String = buildString {
        append(entry.content)
        entry.prompt?.let {
            append(' ')
            append(it)
        }
        if (entry.tags.isNotEmpty()) {
            append(' ')
            append(entry.tags.replace(JournalEntry.TAG_SEPARATOR, " "))
        }
    }

    fun words(text: String): List<String> =
        text.lowercase().split(SPLITTER).filter { it.isNotEmpty() }

    /**
     * Ranks [entries] against [query], best match first, dropping everything that does not match.
     *
     * A blank query returns the input untouched — the caller can always hand the feed straight
     * through without special-casing "not searching".
     */
    fun rank(entries: List<JournalEntry>, query: String): List<JournalEntry> {
        if (query.isBlank()) return entries
        return entries
            .mapNotNull { entry ->
                score(searchableText(entry), query)?.let { entry to it }
            }
            // Equal relevance falls back to newest-first, matching the unfiltered feed.
            .sortedWith(compareByDescending<Pair<JournalEntry, Int>> { it.second }
                .thenByDescending { it.first.createdAt })
            .map { it.first }
    }

    /**
     * Relevance of [haystack] for [query], or null when any query token is missing.
     *
     * Higher is better. The absolute value is meaningless on its own — it exists only to order
     * one result set.
     */
    fun score(haystack: String, query: String): Int? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return 0

        val lowerHaystack = haystack.lowercase()
        val lowerQuery = trimmed.lowercase()
        val haystackWords = words(haystack)
        val tokens = words(trimmed)

        if (tokens.isEmpty()) {
            // Query was pure punctuation; fall back to a literal containment check.
            return if (lowerHaystack.contains(lowerQuery)) SCORE_PHRASE else null
        }

        var total = 0
        for (token in tokens) {
            total += bestTokenScore(lowerHaystack, haystackWords, token) ?: return null
        }
        // Verbatim phrase bonus, only when it adds information over the tokens themselves.
        if (tokens.size > 1 && lowerHaystack.contains(lowerQuery)) total += SCORE_PHRASE
        return total
    }

    private fun bestTokenScore(
        lowerHaystack: String,
        haystackWords: List<String>,
        token: String
    ): Int? {
        var best: Int? = null

        fun offer(candidate: Int) {
            val current = best
            if (current == null || candidate > current) best = candidate
        }

        // Substring covers the cases word-splitting misses — the middle of a compound, or a
        // token spanning a character the splitter dropped.
        if (lowerHaystack.contains(token)) offer(SCORE_SUBSTRING)

        val budget = fuzzyBudget(token.length)
        for (word in haystackWords) {
            when {
                word == token -> offer(SCORE_WORD_EXACT)
                word.startsWith(token) -> offer(SCORE_WORD_PREFIX)
                // The inverse case: the user typed a longer inflected form than the entry holds.
                token.startsWith(word) && word.length >= MIN_STEM_LENGTH -> offer(SCORE_TOKEN_STEM)
                budget > 0 -> {
                    val distance = boundedDistance(word, token, budget)
                    if (distance != null) offer(SCORE_FUZZY_BASE - distance * SCORE_FUZZY_PENALTY)
                }
            }
        }
        return best
    }

    /** How many edits a token of this length may absorb before the match becomes meaningless. */
    private fun fuzzyBudget(length: Int): Int = when {
        length < MIN_FUZZY_LENGTH -> 0
        length <= 6 -> 1
        else -> 2
    }

    /**
     * Levenshtein distance, or null as soon as it is known to exceed [budget].
     *
     * Two rows rather than a full matrix, and an early bail once every cell in a row is already
     * over budget — both matter because this runs once per (word, token) pair across the journal.
     */
    fun boundedDistance(a: String, b: String, budget: Int): Int? {
        if (abs(a.length - b.length) > budget) return null
        if (a == b) return 0
        if (a.isEmpty()) return b.length.takeIf { it <= budget }
        if (b.isEmpty()) return a.length.takeIf { it <= budget }

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
                rowBest = min(rowBest, current[j])
            }
            if (rowBest > budget) return null
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length].takeIf { it <= budget }
    }
}
