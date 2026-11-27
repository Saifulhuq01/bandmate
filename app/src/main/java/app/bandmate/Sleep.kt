package app.bandmate

/** One stored minute of band data. [minute] = epoch minutes (UTC). */
data class Sample(val minute: Long, val kind: Int, val intensity: Int, val steps: Int, val heartRate: Int)

enum class Stage { DEEP, LIGHT, AWAKE }

data class Segment(val startMinute: Long, val endMinute: Long, val stage: Stage) {
    val length get() = (endMinute - startMinute).toInt()
}

data class Night(
    val startMinute: Long,
    val endMinute: Long,
    val deep: Int,
    val light: Int,
    val awake: Int,
    val segments: List<Segment>,
) {
    val asleep get() = deep + light
    val score get() = Sleep.score(this)
}

/**
 * Turns the band's per-minute activity "kind" into sleep sessions.
 * Kinds (low 4 bits): 9 = light sleep, 11 = deep sleep, 0 and 10 = "same as before".
 */
object Sleep {
    private const val LIGHT = 9
    private const val DEEP = 11
    /** Non-sleep stretches shorter than this stay inside one night (counted as awake). */
    private const val MAX_GAP = 45

    /** Resolves "no change" kinds by carrying the last real kind forward. */
    fun resolveKinds(samples: List<Sample>): IntArray {
        var last = -1
        return IntArray(samples.size) { i ->
            val k = samples[i].kind and 0x0f
            if (k == 0 || k == 10) last else { last = k; k }
        }
    }

    /** The longest sleep session that starts inside [fromMinute, toMinute). Samples must be sorted. */
    fun night(samples: List<Sample>, fromMinute: Long, toMinute: Long): Night? {
        if (samples.isEmpty()) return null
        val kinds = resolveKinds(samples)
        val sleepKind = HashMap<Long, Int>()
        samples.forEachIndexed { i, s ->
            if (s.minute in fromMinute until toMinute + 12 * 60 && (kinds[i] == LIGHT || kinds[i] == DEEP)) {
                sleepKind[s.minute] = kinds[i]
            }
        }
        if (sleepKind.isEmpty()) return null

        // Group sleep minutes into sessions, bridging short wake gaps.
        val minutes = sleepKind.keys.sorted()
        var best: Pair<Long, Long>? = null
        var bestAsleep = 0
        var start = minutes[0]
        var prev = minutes[0]
        var count = 1
        fun close() {
            if (start < toMinute && count > bestAsleep) { best = start to prev + 1; bestAsleep = count }
        }
        for (m in minutes.drop(1)) {
            if (m - prev > MAX_GAP) { close(); start = m; count = 0 }
            count++
            prev = m
        }
        close()
        val (s, e) = best ?: return null
        if (bestAsleep < 30) return null

        val segments = ArrayList<Segment>()
        var deep = 0; var light = 0; var awake = 0
        var m = s
        while (m < e) {
            val stage = when (sleepKind[m]) { DEEP -> Stage.DEEP; LIGHT -> Stage.LIGHT; else -> Stage.AWAKE }
            when (stage) { Stage.DEEP -> deep++; Stage.LIGHT -> light++; Stage.AWAKE -> awake++ }
            val lastSeg = segments.lastOrNull()
            if (lastSeg != null && lastSeg.stage == stage && lastSeg.endMinute == m) {
                segments[segments.size - 1] = lastSeg.copy(endMinute = m + 1)
            } else {
                segments += Segment(m, m + 1, stage)
            }
            m++
        }
        return Night(s, e, deep, light, awake, segments)
    }

    /** 0–100: mostly duration (8 h = full), plus deep-sleep share and few awakenings. */
    fun score(n: Night): Int {
        if (n.asleep == 0) return 0
        val duration = minOf(1.0, n.asleep / 480.0) * 60
        val deepShare = minOf(1.0, (n.deep.toDouble() / n.asleep) / 0.25) * 25
        val calm = (1 - minOf(1.0, n.awake / 60.0)) * 15
        return (duration + deepShare + calm).toInt().coerceIn(0, 100)
    }
}
