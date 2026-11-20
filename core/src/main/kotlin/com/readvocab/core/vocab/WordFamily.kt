package com.readvocab.core.vocab

/**
 * The frequency rank that best predicts whether a learner knows a word.
 *
 * A dictionary ranks every spelling and derived form on its own, which makes some easy words look
 * rare: British "defence" ranks 21,674 although "defense" ranks 657, and "jointly" ranks 8,902
 * although "joint" is common. Research on reading coverage counts word families (a word plus its
 * spellings and transparent derivations), so a word here takes the best rank among:
 * - its American spelling (colour, centre, organise, analyse, defence, catalogue, travelled, programme);
 * - its base, when the ending or prefix is transparent (-ly, -ness, -ful, -less, -ment, un-),
 *   or when it is an -ing or -ed form listed as a word of its own (neighboring).
 * Only a lower rank is ever taken, and only from a word that is in the dictionary.
 */
object WordFamily {
    /** @param rank the dictionary's rank of a headword (0 = no frequency data), or null if not a headword */
    fun effectiveRank(word: String, rank: (String) -> Int?): Int? {
        val own = rank(word) ?: return null
        var best = own.takeIf { it > 0 } ?: Int.MAX_VALUE
        val seen = HashSet<String>()
        fun consider(w: String, depth: Int) {
            if (!seen.add(w)) return
            val r = rank(w)
            if (r != null && r > 0 && r < best) best = r
            if (depth < 2) for (c in candidates(w)) consider(c, depth + 1)
        }
        for (c in candidates(word)) consider(c, 1)
        return if (best == Int.MAX_VALUE) own else best
    }

    internal fun candidates(w: String): List<String> {
        val out = ArrayList<String>(6)
        // American spellings.
        if (w.length >= 6) w.lastIndexOf("our").takeIf { it >= 2 }?.let { out += w.substring(0, it) + "or" + w.substring(it + 3) }
        if (w.length >= 5 && w.endsWith("re") && w[w.length - 3] !in "aeiou") out += w.dropLast(2) + "er"
        ISE.find(w)?.let { out += w.substring(0, it.range.first) + "iz" + it.groupValues[1] }
        YSE.find(w)?.let { out += w.substring(0, it.range.first) + "yz" + it.groupValues[1] }
        if (w.length >= 6 && w.endsWith("ence")) out += w.dropLast(4) + "ense"
        if (w.endsWith("ogue")) out += w.dropLast(2)
        if (w.endsWith("mme")) out += w.dropLast(2)
        LL.find(w)?.let { out += w.substring(0, it.range.first) + "l" + it.groupValues[1] }
        if (w.length >= 6 && "ae" in w) out += w.replace("ae", "e")
        // Transparent derivations.
        fun base(s: String) { if (s.length >= 3) out += s }
        when {
            w.endsWith("ically") -> base(w.dropLast(4))
            w.endsWith("ably") || w.endsWith("ibly") -> base(w.dropLast(1) + "e")
            w.endsWith("ily") && w.length > 5 -> base(w.dropLast(3) + "y")
            w.endsWith("ly") && w.length > 5 -> base(w.dropLast(2))
        }
        when {
            w.endsWith("iness") -> base(w.dropLast(5) + "y")
            w.endsWith("ness") -> base(w.dropLast(4))
            w.endsWith("iful") -> base(w.dropLast(4) + "y")
            w.endsWith("ful") -> base(w.dropLast(3))
            w.endsWith("less") && w.length > 6 -> base(w.dropLast(4))
            w.endsWith("ment") && w.length > 7 -> base(w.dropLast(4))
        }
        if (w.startsWith("un") && w.length >= 6) out += w.substring(2)
        // Inflected forms the dictionary lists as words of their own (neighboring, wounded).
        if (w.endsWith("ing") && w.length >= 7) { val s = w.dropLast(3); base(s); base(s + "e"); if (s.length > 3 && s.last() == s[s.length - 2]) base(s.dropLast(1)) }
        if (w.endsWith("ed") && w.length >= 6) { val s = w.dropLast(2); base(s); base(s + "e"); if (s.length > 3 && s.last() == s[s.length - 2]) base(s.dropLast(1)) }
        return out
    }

    private val ISE = Regex("is(e|ed|es|ing|ation|ations|er|ers)$")
    private val YSE = Regex("ys(e|ed|es|ing)$")
    private val LL = Regex("ll(ed|ing|er|ers)$")
}
