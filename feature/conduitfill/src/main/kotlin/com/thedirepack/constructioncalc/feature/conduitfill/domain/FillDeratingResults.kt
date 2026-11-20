package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.DeratingResult
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.measure.Decimals

/** Derating as values other tools and projects can use. */
fun DeratingResult.toResults(): List<ResultValue> = buildList {
    add(
        ResultValue.number(
            "derate_ccc", "Current-carrying conductors",
            ValueKind.NUMBER,
            currentCarrying.toDouble(),
            "$currentCarrying (×${Decimals.trim(adjustmentFactor)})",
        ),
    )
    rows.forEachIndexed { i, row ->
        val usable = row.usable ?: return@forEachIndexed
        val g = row.group
        add(
            ResultValue.number(
                "derate_$i",
                "${g.count} × ${g.size.label} ${g.insulation?.code.orEmpty()} derated",
                ValueKind.CURRENT,
                kotlin.math.round(usable * 10) / 10.0,
                "${Decimals.trim(usable)} A" + (row.maxOcpd?.let { " · max $it A OCPD" } ?: ""),
            ),
        )
    }
}

