package com.flash.headway.data

/**
 * Platform numbers by station, line and the terminal the train heads to.
 * Source: India Metro (metro.reclaimchennai.city), which cites CMRL. No Yellow Line data yet.
 */
object Platforms {
    private val table: Map<String, Map<Line, Map<String, String>>> = mapOf(
        "agdms" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "airport" to mapOf(Line.BLUE to mapOf("wimco-nagar-depot" to "1/2")),
        "alandur" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2"), Line.GREEN to mapOf("st-thomas-mount" to "3", "central" to "4")),
        "anna-nagar-east" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "anna-nagar-tower" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "arumbakkam" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "ashok-nagar" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "central" to mapOf(Line.BLUE to mapOf("airport" to "5", "wimco-nagar-depot" to "6"), Line.GREEN to mapOf("st-thomas-mount" to "1/2")),
        "cmbt" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "egmore" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "ekkattuthangal" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "government-estate" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "guindy" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "high-court" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "kaladipet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "kilpauk" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "koyambedu" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "lic" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "little-mount" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "mannadi" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "meenambakkam" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "nandanam" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "nanganallur-road" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "nehru-park" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "new-washermenpet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "pachaiyappas-college" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "saidapet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "shenoy-nagar" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "teynampet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "theagaraya-college" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "thirumangalam" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "thousand-lights" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "tiruvottiyur" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "tiruvottiyur-theradi" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "tollgate" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "tondiarpet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "vadapalani" to mapOf(Line.GREEN to mapOf("st-thomas-mount" to "1", "central" to "2")),
        "washermenpet" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "wimco-nagar" to mapOf(Line.BLUE to mapOf("airport" to "1", "wimco-nagar-depot" to "2")),
        "wimco-nagar-depot" to mapOf(Line.BLUE to mapOf("airport" to "1")),
    )

    fun at(station: String, line: Line, terminal: String): String? = table[station]?.get(line)?.get(terminal)
}
