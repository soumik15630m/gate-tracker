package org.studyspace.app

import java.text.Normalizer

data class TopicFilter(val importance: String = "all", val level: String = "all", val source: String = "self")
val topicLevels = listOf("all" to "All topic levels", "subject" to "Subjects only", "parent" to "Parent topics / have children", "leaf" to "Final subtopics / no children")
object SmartSearch {
    private val marks = Regex("\\p{M}+"); private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(punctuation, " ").trim()
    private fun near(a: String, b: String, budget: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > budget || a.length > 40 || b.length > 40) return false
        var before = IntArray(0); var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1); row[0] = i
            for (j in 1..b.length) {
                row[j] = minOf(row[j - 1] + 1, previous[j] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) row[j] = minOf(row[j], before[j - 2] + 1)
            }
            before = previous; previous = row
        }
        return previous[b.length] <= budget
    }
    fun tokenScore(text: String, token: String): Int {
        if (text == token) return 100
        val words = text.split(' ')
        if (words.any { it.startsWith(token) }) return 85
        if (text.contains(token)) return 65
        if (token.length >= 4 && words.any { near(it, token, if (token.length >= 8) 2 else 1) }) return 35
        return -1
    }
}
class TopicSearch(private val index: TopicIndex) {
    private val texts = index.preorder.associate { it.id to SmartSearch.normalize(it.title) }
    private val depths = HashMap<String, Int>().apply { index.preorder.forEach { put(it.id, (get(it.parentId) ?: -1) + 1) } }
    fun matches(topic: Topic, filter: TopicFilter): Boolean {
        val parent = !index.children[topic.id].isNullOrEmpty()
        if (filter.level == "subject" && topic.parentId != null || filter.level == "parent" && !parent || filter.level == "leaf" && parent) return false
        val rated = when (filter.source) { "subject" -> index.subjects[topic.id] ?: topic; "parent" -> index.byId[topic.parentId] ?: topic; else -> topic }
        return matchesImportance(rated, filter.importance)
    }
    fun results(query: String, filter: TopicFilter = TopicFilter(), limit: Int = 60): List<VisibleTopic> {
        val normalized = SmartSearch.normalize(query).take(120); val tokens = normalized.split(' ').filter { it.isNotBlank() }.take(8)
        val inherited = HashMap<String, IntArray>(); val found = ArrayList<Pair<VisibleTopic, Int>>()
        for (topic in index.preorder) {
            val text = texts.getValue(topic.id); val own = tokens.map { SmartSearch.tokenScore(text, it) }
            val parent = inherited[topic.parentId]
            val combined = IntArray(tokens.size) { i -> maxOf(own[i], parent?.get(i)?.takeIf { it >= 0 }?.let { (it - 1).coerceAtLeast(0) } ?: -1) }
            inherited[topic.id] = combined
            if (matches(topic, filter) && combined.all { it >= 0 }) {
                val row = VisibleTopic(topic, depths[topic.id] ?: 0, index.progress[topic.id] ?: Progress(), !index.children[topic.id].isNullOrEmpty())
                found.add(row to (combined.sum() + own.count { it >= 0 } * 15 + if (tokens.isNotEmpty() && text == normalized) 100 else 0))
            }
        }
        return (if (tokens.isEmpty()) found else found.sortedWith(compareByDescending<Pair<VisibleTopic, Int>> { it.second }.thenBy { it.first.depth })).take(limit).map { it.first }
    }
    fun breadcrumb(id: String): String {
        val names = ArrayDeque<String>(); val seen = HashSet<String>(); var node = index.byId[id]
        while (node != null && names.size < 9 && seen.add(node.id)) { names.addFirst(node.title); node = index.byId[node.parentId] }
        if (node != null) names.addFirst(index.subjects[id]?.title.orEmpty() + " / …")
        return names.joinToString(" / ")
    }
}
