package dev.ggoggam.vitre.core.frame

/**
 * What every pool agrees on: how many lanes there may be, and what they are called.
 *
 * Small enough to look like it should live on [FramePool], and deliberately not there. Both
 * platform pools need the ids *before* a pool exists — the lanes are built from them — so the
 * constants have to sit somewhere neither pool owns.
 */
object Lanes {
    /** Lane ids for a pool of [count] lanes: `a`, `b`, `c`, `d`. */
    fun laneIds(count: Int): List<String> {
        require(count in 1..MAX_LANES) { "lane count must be 1..$MAX_LANES, was $count" }
        return List(count) { ('a' + it).toString() }
    }

    /** The most lanes a pool may have. Four fits a phone screen and saturates a phone's radio. */
    const val MAX_LANES: Int = 4
}
