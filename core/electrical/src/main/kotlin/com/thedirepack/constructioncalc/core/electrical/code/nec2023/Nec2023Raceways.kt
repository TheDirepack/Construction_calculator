package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.BendRadiusColumn
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.TradeSize.*
import com.thedirepack.constructioncalc.core.electrical.code.BendRadiusRow
import com.thedirepack.constructioncalc.core.electrical.code.RacewayArticle

/** NEC 2023 raceways: Chapter 9 Tables 2 and 4, and each raceway article. Data only. */
internal object Nec2023Raceways {

    /**
     * The article for each raceway type, checked against the local PDF of the 2023 edition.
     *
     * Bend radius column, from each article's "Bends — How Made" (xxx.24(A)):
     * - EMT 358.24(A): "Table 2, Chapter 9 for one-shot and full shoe benders".
     * - IMC 342.24(A), RMC 344.24(A), PVC 352.24(A): "Table 2, Chapter 9" without naming a column.
     *   These are usually bent with one-shot or full-shoe benders (or are factory elbows), so the
     *   one-shot column is used. It is the smaller radius, so it is also the conservative choice for
     *   sidewall pressure.
     * - FMC 348.24(A), LFMC 350.24(A), LFNC 356.24(A), ENT 362.24(A): "the column 'Other Bends'".
     *
     * Degrees between pull points, "Bends — (B) Number in One Run", printed pages: IMC 342.24(B)
     * p. 70-232, RMC 344.24(B) p. 70-233, FMC 348.24(B) p. 70-235, LFMC 350.24(B) p. 70-236, PVC
     * 352.24(B) p. 70-238, HDPE 353.24(B) p. 70-240, LFNC 356.24(B) p. 70-244, EMT 358.24(B) p. 70-245,
     * ENT 362.24(B) p. 70-248. (Earlier editions numbered these xxx.26.) PVC Schedule 40 rows also
     * cover HDPE (Article 353), which has the same rule.
     */
    val raceways: Map<ConduitType, RacewayArticle> = mapOf(
        ConduitType.EMT to RacewayArticle("Article 358", BendRadiusColumn.ONE_SHOT, "358.24(B)"),
        ConduitType.RMC to RacewayArticle("Article 344", BendRadiusColumn.ONE_SHOT, "344.24(B)"),
        ConduitType.IMC to RacewayArticle("Article 342", BendRadiusColumn.ONE_SHOT, "342.24(B)"),
        ConduitType.PVC_40 to RacewayArticle("Articles 352 and 353", BendRadiusColumn.ONE_SHOT, "352.24(B)"),
        ConduitType.PVC_80 to RacewayArticle("Article 352", BendRadiusColumn.ONE_SHOT, "352.24(B)"),
        ConduitType.FMC to RacewayArticle("Article 348", BendRadiusColumn.OTHER, "348.24(B)"),
        ConduitType.LFMC to RacewayArticle("Article 350", BendRadiusColumn.OTHER, "350.24(B)"),
        ConduitType.LFNC_B to RacewayArticle("Article 356", BendRadiusColumn.OTHER, "356.24(B)"),
        ConduitType.ENT to RacewayArticle("Article 362", BendRadiusColumn.OTHER, "362.24(B)"),
        ConduitType.PVC_A to RacewayArticle("Article 352", BendRadiusColumn.ONE_SHOT, "352.24(B)"),
        ConduitType.PVC_EB to RacewayArticle("Article 352", BendRadiusColumn.ONE_SHOT, "352.24(B)"),
    )

    /**
     * **Chapter 9 Table 2** "Radius of Conduit and Tubing Bends", printed page 70-716 (PDF page 719 of
     * the local copy); every value was read from the book. Radius to the centerline of the conduit,
     * inches: one shot and full shoe benders, other bends. Table 2 has no row for trade size 3/8.
     */
    val bendRadius: Map<TradeSize, BendRadiusRow> = mapOf(
        S_1_2 to BendRadiusRow(4.0, 4.0),
        S_3_4 to BendRadiusRow(4.5, 5.0),
        S_1 to BendRadiusRow(5.75, 6.0),
        S_1_1_4 to BendRadiusRow(7.25, 8.0),
        S_1_1_2 to BendRadiusRow(8.25, 10.0),
        S_2 to BendRadiusRow(9.5, 12.0),
        S_2_1_2 to BendRadiusRow(10.5, 15.0),
        S_3 to BendRadiusRow(13.0, 18.0),
        S_3_1_2 to BendRadiusRow(15.0, 21.0),
        S_4 to BendRadiusRow(16.0, 24.0),
        S_5 to BendRadiusRow(24.0, 30.0),
        S_6 to BendRadiusRow(30.0, 36.0),
    )

    private fun rows(type: ConduitType, vararg data: Triple<TradeSize, Double, Double>): List<ConduitSize> =
        data.map { (trade, id, area) -> ConduitSize(type, trade, id, area) }

    private fun row(trade: TradeSize, id: Double, area: Double) = Triple(trade, id, area)

    /**
     * **Chapter 9 Table 4** "Dimensions and Percent Area of Conduit and Tubing" (printed pages 70-716 to
     * 70-720), columns Nominal Internal Diameter (in.) and Total Area 100% (in.²). Every row was
     * checked against the printed 2023 table.
     *
     * Each type lists only the trade sizes the NEC table gives for it (PVC Type EB from 2" only, and so
     * on). Not included: LFNC-A and LFNC-C, and EMT 5" and 6" (see the note on the EMT rows).
     */
    val conduitDimensions: Map<ConduitType, List<ConduitSize>> = mapOf(
        // Article 358. The 2023 Table 4 also prints 5" and 6" rows, but they are copies of the RMC rows and
        // disagree with Annex C.1 (whose 5" and 6" columns need roughly 22.2 and 31.3 sq in), so they
        // are left out until the true EMT dimensions are confirmed.
        ConduitType.EMT to rows(
            ConduitType.EMT,
            row(S_1_2, 0.622, 0.304), row(S_3_4, 0.824, 0.533), row(S_1, 1.049, 0.864),
            row(S_1_1_4, 1.380, 1.496), row(S_1_1_2, 1.610, 2.036), row(S_2, 2.067, 3.356),
            row(S_2_1_2, 2.731, 5.858), row(S_3, 3.356, 8.846), row(S_3_1_2, 3.834, 11.545),
            row(S_4, 4.334, 14.753),
        ),
        // Article 344
        ConduitType.RMC to rows(
            ConduitType.RMC,
            row(S_1_2, 0.632, 0.314), row(S_3_4, 0.836, 0.549), row(S_1, 1.063, 0.887),
            row(S_1_1_4, 1.394, 1.526), row(S_1_1_2, 1.624, 2.071), row(S_2, 2.083, 3.408),
            row(S_2_1_2, 2.489, 4.866), row(S_3, 3.090, 7.499), row(S_3_1_2, 3.570, 10.010),
            row(S_4, 4.050, 12.882), row(S_5, 5.073, 20.212), row(S_6, 6.093, 29.158),
        ),
        // Article 342
        ConduitType.IMC to rows(
            ConduitType.IMC,
            row(S_1_2, 0.660, 0.342), row(S_3_4, 0.864, 0.586), row(S_1, 1.105, 0.959),
            row(S_1_1_4, 1.448, 1.647), row(S_1_1_2, 1.683, 2.225), row(S_2, 2.150, 3.630),
            row(S_2_1_2, 2.557, 5.135), row(S_3, 3.176, 7.922), row(S_3_1_2, 3.671, 10.584),
            row(S_4, 4.166, 13.631), row(S_5, 5.210, 21.32), row(S_6, 6.258, 30.76),
        ),
        // Articles 352 and 353 (the NEC prints HDPE with Schedule 40)
        ConduitType.PVC_40 to rows(
            ConduitType.PVC_40,
            row(S_1_2, 0.602, 0.285), row(S_3_4, 0.804, 0.508), row(S_1, 1.029, 0.832),
            row(S_1_1_4, 1.360, 1.453), row(S_1_1_2, 1.590, 1.986), row(S_2, 2.047, 3.291),
            row(S_2_1_2, 2.445, 4.695), row(S_3, 3.042, 7.268), row(S_3_1_2, 3.521, 9.737),
            row(S_4, 3.998, 12.554), row(S_5, 5.016, 19.761), row(S_6, 6.031, 28.567),
        ),
        // Article 352
        ConduitType.PVC_80 to rows(
            ConduitType.PVC_80,
            row(S_1_2, 0.526, 0.217), row(S_3_4, 0.722, 0.409), row(S_1, 0.936, 0.688),
            row(S_1_1_4, 1.255, 1.237), row(S_1_1_2, 1.476, 1.711), row(S_2, 1.913, 2.874),
            row(S_2_1_2, 2.290, 4.119), row(S_3, 2.864, 6.442), row(S_3_1_2, 3.326, 8.688),
            row(S_4, 3.786, 11.258), row(S_5, 4.768, 17.855), row(S_6, 5.709, 25.598),
        ),
        // Article 348
        ConduitType.FMC to rows(
            ConduitType.FMC,
            row(S_3_8, 0.384, 0.116), row(S_1_2, 0.635, 0.317), row(S_3_4, 0.824, 0.533),
            row(S_1, 1.020, 0.817), row(S_1_1_4, 1.275, 1.277), row(S_1_1_2, 1.538, 1.858),
            row(S_2, 2.040, 3.269), row(S_2_1_2, 2.500, 4.909), row(S_3, 3.000, 7.069),
            row(S_3_1_2, 3.500, 9.621), row(S_4, 4.000, 12.566),
        ),
        // Article 350
        ConduitType.LFMC to rows(
            ConduitType.LFMC,
            row(S_3_8, 0.494, 0.192), row(S_1_2, 0.632, 0.314), row(S_3_4, 0.830, 0.541),
            row(S_1, 1.054, 0.873), row(S_1_1_4, 1.395, 1.528), row(S_1_1_2, 1.588, 1.981),
            row(S_2, 2.033, 3.246), row(S_2_1_2, 2.493, 4.881), row(S_3, 3.085, 7.475),
            row(S_3_1_2, 3.520, 9.731), row(S_4, 4.020, 12.692),
        ),
        // Article 356 (type B; the NEC lists the same dimensions as LFMC through 2")
        ConduitType.LFNC_B to rows(
            ConduitType.LFNC_B,
            row(S_3_8, 0.494, 0.192), row(S_1_2, 0.632, 0.314), row(S_3_4, 0.830, 0.541),
            row(S_1, 1.054, 0.873), row(S_1_1_4, 1.395, 1.528), row(S_1_1_2, 1.588, 1.981),
            row(S_2, 2.033, 3.246),
        ),
        // Article 362
        ConduitType.ENT to rows(
            ConduitType.ENT,
            row(S_1_2, 0.602, 0.285), row(S_3_4, 0.804, 0.508), row(S_1, 1.029, 0.832),
            row(S_1_1_4, 1.360, 1.453), row(S_1_1_2, 1.590, 1.986), row(S_2, 2.047, 3.291),
        ),
        // Article 352, Type A
        ConduitType.PVC_A to rows(
            ConduitType.PVC_A,
            row(S_1_2, 0.700, 0.385), row(S_3_4, 0.910, 0.650), row(S_1, 1.175, 1.084),
            row(S_1_1_4, 1.500, 1.767), row(S_1_1_2, 1.720, 2.324), row(S_2, 2.155, 3.647),
            row(S_2_1_2, 2.635, 5.453), row(S_3, 3.230, 8.194), row(S_3_1_2, 3.690, 10.694),
            row(S_4, 4.180, 13.723),
        ),
        // Article 352, Type EB (the NEC has no 1/2" to 1-1/2" or 2-1/2" rows)
        ConduitType.PVC_EB to rows(
            ConduitType.PVC_EB,
            row(S_2, 2.221, 3.874), row(S_3, 3.330, 8.709), row(S_3_1_2, 3.804, 11.365),
            row(S_4, 4.289, 14.448), row(S_5, 5.316, 22.195), row(S_6, 6.336, 31.530),
        ),
    )
}
