package com.curveon.data.road

/**
 * A road's number as its sign shows it: the text ("E39", "Rv7", "Fv541", "B27") and the sign's
 * colours, which tell the kind of road (in Norway green for E- and riksveg, white for fylkesveg).
 * From Norway's road register (NVDB) where there is one, else from OpenStreetMap's ref tag, read
 * the way the country signs its roads.
 */
data class RoadRef(val text: String, val style: Style) {

    /** Background, text and border colours (ARGB). */
    enum class Style(val background: Long, val text: Long, val border: Long) {
        GREEN(0xFF00843D, 0xFFFFFFFF, 0xFFFFFFFF),
        WHITE(0xFFFFFFFF, 0xFF000000, 0xFF000000),
        /** Norway's kommunal veg: its number isn't signed, so a quieter sign. */
        GREY(0xFFDADDE1, 0xFF1A1A1A, 0xFF8A97A6),
        BLUE(0xFF1F5AA6, 0xFFFFFFFF, 0xFFFFFFFF),
        YELLOW(0xFFFFD100, 0xFF000000, 0xFF000000),
        RED(0xFFD52B1E, 0xFFFFFFFF, 0xFFFFFFFF)
    }

    companion object {
        /** From a road number in the speed limits file (see tools/speedlimits/build_speedlimits.py). */
        fun fromNvdb(code: Int): RoadRef? {
            val number = code % 1_000_000
            return when (code / 1_000_000) {
                1 -> RoadRef("E$number", Style.GREEN)
                2 -> RoadRef("Rv$number", Style.GREEN)
                3 -> RoadRef("Fv$number", Style.WHITE)
                4 -> RoadRef("Kv$number", Style.GREY)
                else -> null
            }
        }

        /**
         * From OpenStreetMap's ref ([ref], the first of several), the road's class in the map
         * ([kindDetail]: motorway, trunk, primary, …) and the country's ISO code ([country]).
         */
        fun fromOsm(ref: String?, kindDetail: String?, country: String?): RoadRef? {
            val raw = ref?.split(';')?.first()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val compact = raw.replace(Regex("""^([A-Za-z]+)\s+(\d)"""), "$1$2")
            if (Regex("""^E\d+$""").matches(compact)) return RoadRef(compact, Style.GREEN)
            val digits = compact.all { it.isDigit() }
            val major = kindDetail == "motorway" || kindDetail == "trunk" || kindDetail == "primary"
            return when (country) {
                // Norway's register is used where it reaches; this is the guess without it.
                "NO" -> if (digits) RoadRef((if (major) "Rv" else "Fv") + compact, if (major) Style.GREEN else Style.WHITE)
                    else RoadRef(compact, Style.WHITE)
                "SE" -> RoadRef(compact, Style.BLUE)
                "DK" -> RoadRef(compact, if (major) Style.YELLOW else Style.WHITE)
                "FI" -> {
                    val n = compact.toIntOrNull()
                    RoadRef(compact, when {
                        n == null -> Style.WHITE
                        n < 40 -> Style.RED
                        n < 100 -> Style.YELLOW
                        else -> Style.WHITE
                    })
                }
                "DE" -> when {
                    compact.startsWith("A") -> RoadRef(compact, Style.BLUE)
                    compact.startsWith("B") -> RoadRef(compact, Style.YELLOW)
                    else -> RoadRef(compact, Style.WHITE)
                }
                else -> RoadRef(compact, Style.WHITE)
            }
        }
    }
}

/** What is known about the road the rider is on: its number and whether it is a priority road. */
/** [name]: the road's name as the map has it, shown for a moment when the road card is tapped. */
data class CurrentRoad(val ref: RoadRef?, val priority: Boolean, val name: String? = null) {
    val isEmpty get() = ref == null && !priority
}

/**
 * A road as the map tiles have it: OpenStreetMap's ref, the road's class and its name. [name] may come from a
 * road next to it when this one has none; [ownName] is only this road's own.
 */
data class MapRoad(val ref: String?, val kindDetail: String?, val name: String?, val ownName: String? = name)

/** The road after a maneuver: its number sign and name, each when known. */
data class NextRoad(val ref: RoadRef?, val name: String?)
