package com.flash.headway.data

/** Schematic network map layout (generated; not geographic). */
object MapLayout {
    const val WIDTH = 1140f
    const val HEIGHT = 1460f

    /** Station id to (x, y, label side): l = left, r = right, d = right and below, u = rotated up-right. */
    val stations: Map<String, Triple<Float, Float, Char>> = mapOf(
        "wimco-nagar-depot" to Triple(880f, 60f, 'r'),
        "wimco-nagar" to Triple(880f, 106f, 'r'),
        "tiruvottiyur" to Triple(880f, 152f, 'r'),
        "tiruvottiyur-theradi" to Triple(880f, 198f, 'r'),
        "kaladipet" to Triple(880f, 244f, 'r'),
        "tollgate" to Triple(880f, 290f, 'r'),
        "new-washermenpet" to Triple(880f, 336f, 'r'),
        "tondiarpet" to Triple(880f, 382f, 'r'),
        "theagaraya-college" to Triple(880f, 428f, 'r'),
        "washermenpet" to Triple(880f, 474f, 'r'),
        "mannadi" to Triple(852f, 532f, 'r'),
        "high-court" to Triple(818f, 578f, 'r'),
        "central" to Triple(770f, 620f, 'd'),
        "government-estate" to Triple(770f, 680f, 'r'),
        "lic" to Triple(770f, 730f, 'r'),
        "thousand-lights" to Triple(770f, 780f, 'r'),
        "agdms" to Triple(770f, 830f, 'r'),
        "teynampet" to Triple(770f, 880f, 'r'),
        "nandanam" to Triple(770f, 930f, 'r'),
        "saidapet" to Triple(770f, 980f, 'r'),
        "little-mount" to Triple(740f, 1028f, 'r'),
        "guindy" to Triple(705f, 1074f, 'r'),
        "alandur" to Triple(660f, 1120f, 'l'),
        "nanganallur-road" to Triple(660f, 1180f, 'r'),
        "meenambakkam" to Triple(660f, 1240f, 'r'),
        "airport" to Triple(660f, 1300f, 'r'),
        "egmore" to Triple(712f, 620f, 'u'),
        "nehru-park" to Triple(654f, 620f, 'u'),
        "kilpauk" to Triple(596f, 620f, 'u'),
        "pachaiyappas-college" to Triple(538f, 620f, 'u'),
        "shenoy-nagar" to Triple(480f, 620f, 'u'),
        "anna-nagar-east" to Triple(422f, 620f, 'u'),
        "anna-nagar-tower" to Triple(364f, 620f, 'u'),
        "thirumangalam" to Triple(312f, 668f, 'l'),
        "koyambedu" to Triple(308f, 735f, 'l'),
        "cmbt" to Triple(304f, 803f, 'l'),
        "arumbakkam" to Triple(300f, 870f, 'l'),
        "vadapalani" to Triple(300f, 940f, 'r'),
        "ashok-nagar" to Triple(420f, 1000f, 'r'),
        "ekkattuthangal" to Triple(540f, 1060f, 'l'),
        "st-thomas-mount" to Triple(770f, 1120f, 'r'),
        "porur-junction" to Triple(180f, 1000f, 'r'),
        "porur-bypass" to Triple(140f, 1050f, 'r'),
        "thelliyaragaram" to Triple(140f, 1095f, 'r'),
        "iyyappanthangal" to Triple(140f, 1140f, 'r'),
        "kattupakkam" to Triple(140f, 1185f, 'r'),
        "kumananchavadi" to Triple(140f, 1230f, 'r'),
        "karayanchavadi" to Triple(140f, 1275f, 'r'),
        "mullaithottam" to Triple(140f, 1320f, 'r'),
        "poonamallee" to Triple(140f, 1365f, 'r'),
        "poonamallee-bypass" to Triple(140f, 1410f, 'r'),
    )

    val lines: Map<Line, List<String>> = mapOf(
        Line.BLUE to listOf("wimco-nagar-depot", "wimco-nagar", "tiruvottiyur", "tiruvottiyur-theradi", "kaladipet", "tollgate", "new-washermenpet", "tondiarpet", "theagaraya-college", "washermenpet", "mannadi", "high-court", "central", "government-estate", "lic", "thousand-lights", "agdms", "teynampet", "nandanam", "saidapet", "little-mount", "guindy", "alandur", "nanganallur-road", "meenambakkam", "airport"),
        Line.GREEN to listOf("central", "egmore", "nehru-park", "kilpauk", "pachaiyappas-college", "shenoy-nagar", "anna-nagar-east", "anna-nagar-tower", "thirumangalam", "koyambedu", "cmbt", "arumbakkam", "vadapalani", "ashok-nagar", "ekkattuthangal", "alandur", "st-thomas-mount"),
        Line.YELLOW to listOf("vadapalani", "porur-junction", "porur-bypass", "thelliyaragaram", "iyyappanthangal", "kattupakkam", "kumananchavadi", "karayanchavadi", "mullaithottam", "poonamallee", "poonamallee-bypass"),
    )
}
