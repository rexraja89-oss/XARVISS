package com.xarvis.ai.memory

import kotlin.math.ln

/**
 * Finds earlier conversations that relate to what Rex is talking about now, so XARVIS can bring them in like a
 * person who remembers ("last week you asked about the ESP32 camera…"). Rex, 14 Nov 2026: "I want it to catch up
 * properly like you and GPT… remember past conversations too if related". Pure, unit-tested: words shared with
 * the current topic, rare words counting more, a little preference for recent chats; weak matches are left out.
 */
object Related {
    private val STOP = (
        "the a an and or but if then so of to in on at for with from by as is are was were be been being it its this that these " +
            "those i me my you your he she they them we us our his her their what which who whom how why when where can could " +
            "would should will shall do does did done have has had not no yes yeah ok okay please thanks thank sir xarvis jarvis " +
            "zarvis about just also very really more most some any all there here than too now one get got like want need make " +
            "know tell let see look give take go going say said hai hain ka ki ke ko se me mein kya aur ye yeh wo woh nahi bhi " +
            "to hi ho tha thi kar karo kuch"
        ).split(" ").toSet()

    fun words(text: String): Set<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 && it !in STOP }.toSet()

    /**
     * Up to [limit] exchanges from other chats than [chatId], best match first, for the topic [topic] (Rex's
     * message plus the last turns, so "yes" still has a subject). [now] makes older chats count a little less.
     */
    fun find(all: List<Exchange>, chatId: String, topic: String, now: Long, limit: Int = 3): List<Exchange> {
        val want = words(topic)
        if (want.isEmpty()) return emptyList()
        val others = all.filter { it.chat != chatId }
        if (others.isEmpty()) return emptyList()
        val docs = others.map { words(it.user + " " + it.reply) }
        val df = HashMap<String, Int>()
        for (d in docs) for (w in d) if (w in want) df[w] = (df[w] ?: 0) + 1
        val n = docs.size.toDouble()
        val scored = others.indices.mapNotNull { i ->
            val shared = docs[i].filter { it in want }
            if (shared.size < MIN_SHARED) return@mapNotNull null
            var score = shared.sumOf { ln(1 + n / (df[it] ?: 1)) }
            val days = (now - others[i].time).coerceAtLeast(0) / 86_400_000.0
            score /= 1 + days / 60
            if (score < MIN_SCORE) null else others[i] to score
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinctBy { it.user + it.reply.take(80) }.take(limit)
    }

    /** At least this many meaningful words in common. */
    private const val MIN_SHARED = 2
    private const val MIN_SCORE = 2.0
}
