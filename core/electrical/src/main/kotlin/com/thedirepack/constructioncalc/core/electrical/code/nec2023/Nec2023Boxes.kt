package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import com.thedirepack.constructioncalc.core.electrical.code.StandardMetalBox

/** NEC 2023 Article 314 box fill tables (Tables 314.16(A) and 314.16(B)). Data only. */
internal object Nec2023Boxes {

    /**
     * **Table 314.16(B)** "Volume Allowance Required per Conductor" (NEC 2023), cubic inches.
     */
    val volumeAllowances: Map<ConductorSize, Double> = linkedMapOf(
        AWG_18 to 1.50,
        AWG_16 to 1.75,
        AWG_14 to 2.00,
        AWG_12 to 2.25,
        AWG_10 to 2.50,
        AWG_8 to 3.00,
        AWG_6 to 5.00,
    )

    /**
     * **Table 314.16(A)** "Metal Boxes" (NEC 2023): standard metal box trade sizes and minimum
     * internal volume in cubic inches.
     */
    val standardMetalBoxes: List<StandardMetalBox> = listOf(
        StandardMetalBox("4 × 1-1/4 round/octagonal", "4 × 1-1/4", 12.5),
        StandardMetalBox("4 × 1-1/2 round/octagonal", "4 × 1-1/2", 15.5),
        StandardMetalBox("4 × 2-1/8 round/octagonal", "4 × 2-1/8", 21.5),
        StandardMetalBox("4 × 1-1/4 square", "4 × 1-1/4", 18.0),
        StandardMetalBox("4 × 1-1/2 square", "4 × 1-1/2", 21.0),
        StandardMetalBox("4 × 2-1/8 square", "4 × 2-1/8", 30.3),
        StandardMetalBox("4-11/16 × 1-1/4 square", "4-11/16 × 1-1/4", 25.5),
        StandardMetalBox("4-11/16 × 1-1/2 square", "4-11/16 × 1-1/2", 29.5),
        StandardMetalBox("4-11/16 × 2-1/8 square", "4-11/16 × 2-1/8", 42.0),
        StandardMetalBox("3 × 2 × 1-1/2 device", "3 × 2 × 1-1/2", 7.5),
        StandardMetalBox("3 × 2 × 2 device", "3 × 2 × 2", 10.0),
        StandardMetalBox("3 × 2 × 2-1/4 device", "3 × 2 × 2-1/4", 10.5),
        StandardMetalBox("3 × 2 × 2-1/2 device", "3 × 2 × 2-1/2", 12.5),
        StandardMetalBox("3 × 2 × 2-3/4 device", "3 × 2 × 2-3/4", 14.0),
        StandardMetalBox("3 × 2 × 3-1/2 device", "3 × 2 × 3-1/2", 18.0),
        StandardMetalBox("4 × 2-1/8 × 1-1/2 handy", "4 × 2-1/8 × 1-1/2", 10.3),
        StandardMetalBox("4 × 2-1/8 × 1-7/8 handy", "4 × 2-1/8 × 1-7/8", 13.0),
        StandardMetalBox("4 × 2-1/8 × 2-1/8 handy", "4 × 2-1/8 × 2-1/8", 14.5),
        StandardMetalBox("3-3/4 × 2 × 2-1/2 masonry", "3-3/4 × 2 × 2-1/2", 14.0),
        StandardMetalBox("3-3/4 × 2 × 3-1/2 masonry", "3-3/4 × 2 × 3-1/2", 21.0),
        StandardMetalBox("FS (4 × 2-1/8 × 1-3/4 min)", "FS", 13.5),
        StandardMetalBox("FD (4 × 2-1/8 × 2-3/8 min)", "FD", 18.0),
    )
}
