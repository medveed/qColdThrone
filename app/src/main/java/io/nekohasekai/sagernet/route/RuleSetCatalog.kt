package app.qcoldthrone.mobile.route

/** The name → .srs URL map of the routeprofiles `srslist.h` (the desktop's compiled-in ruleSetList). */
class RuleSetCatalog(entries: List<Pair<String, String>>) {
    private val map = LinkedHashMap<String, String>()

    init {
        for ((name, url) in entries) if (!map.containsKey(name)) map[name] = url
    }

    private val nameList: List<String> = ArrayList(map.keys)

    val size: Int get() = map.size

    fun urlOf(name: String): String? = map[name]

    fun names(): List<String> = nameList

    /** Case-insensitive: exact match, then prefix matches, then substring matches, each in list order. */
    fun search(query: String, limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return map.entries.take(limit).map { it.key to it.value }
        val exact = ArrayList<Pair<String, String>>()
        val prefix = ArrayList<Pair<String, String>>()
        val contains = ArrayList<Pair<String, String>>()
        for ((name, url) in map) {
            when {
                name.equals(q, ignoreCase = true) -> exact.add(name to url)
                name.startsWith(q, ignoreCase = true) -> prefix.add(name to url)
                name.contains(q, ignoreCase = true) -> contains.add(name to url)
            }
        }
        return (exact + prefix + contains).take(limit)
    }

    companion object {
        private val ENTRY = Regex("\\{\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\}")

        fun parseSrsList(text: String): RuleSetCatalog =
            RuleSetCatalog(ENTRY.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList())
    }
}
