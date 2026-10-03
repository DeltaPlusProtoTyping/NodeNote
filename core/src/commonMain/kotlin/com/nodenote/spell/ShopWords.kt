package com.nodenote.spell

/**
 * Machine-shop, lean and estimating words a general English list doesn't know. The
 * apps accept them on every board, on top of the board's @people and each person's
 * own "add to dictionary" words.
 */
val ShopWords: List<String> = listOf(
    // Machining
    "deburr", "deburred", "deburring", "toolpath", "toolpaths", "chamfer", "chamfered", "chamfering", "chamfers",
    "collet", "collets", "endmill", "endmills", "workholding", "tolerancing", "fixturing", "probing", "bandsaw",
    "subassembly", "subassemblies", "anodize", "anodized", "anodizing", "powdercoat", "powdercoated", "passivation",
    "sandblast", "sandblasting", "kitting", "routings", "pre", "pallet", "pallets", "presetter", "toolchanger",
    // Software and machines
    "Mastercam", "SolidWorks", "ProShop", "Mazak", "Okuma", "Haas", "Fanuc", "Hurco", "Doosan", "McMaster",
    // Lean / process improvement
    "kanban", "kaizen", "gemba", "poka", "yoke", "takt", "andon", "heijunka", "jidoka", "muda", "mura", "muri",
    "uptime", "downtime", "throughput", "callout", "callouts", "timesheet", "timesheets", "upcharge", "upcharges",
    "pushback", "pushbacks", "roadblock", "roadblocks", "workflow", "workflows",
)
