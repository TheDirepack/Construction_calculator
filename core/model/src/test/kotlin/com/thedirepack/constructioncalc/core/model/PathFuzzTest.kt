package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.MaterialTakeoffs
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Imported files with damaged paths or pull settings never crash the app: they load (and analyze) or are rejected as damaged. */
class PathFuzzTest {
    private fun sample(): Project {
        val ft = { n: Long -> Quantity.feet(Rational.of(n)).stored() }
        val run = Run(
            name = "Feeder", raceway = Raceway(ConduitType.PVC_40),
            pull = PullSetup(
                incomingTension = listOf(25.0, 50.0), slackPerEnd = ft(3), pressureLimit = 800.0,
                cables = listOf(PullCable(3, ConductorSize.AWG_4_0, weightLbPerFt = 0.7, diameterIn = 0.6)),
                equipment = listOf(EquipmentRating("Rope", 4000.0), EquipmentRating("Puller")),
            ),
        )
        val path = listOf(
            PathSegment.Straight(ft(120), 5.0), PathSegment.Bend(90.0, Quantity.inches(Rational.of(36L)).stored(), BendPlane.VERTICAL_UP),
            PathSegment.PullPoint("PB-1"), PathSegment.Bend(45.0), PathSegment.Straight(ft(40), -10.0),
        )
        return Project(name = "Fuzz", createdAt = 0, runs = listOf(run)).withRunPath(run.id, path, 1)
    }

    @Test fun randomCorruptionNeverCrashes() {
        val text = ProjectCodec.encode(sample())
        val random = Random(7)
        val digits = "0123456789-.eE"
        var loaded = 0
        repeat(3000) {
            val chars = text.toCharArray()
            repeat(1 + random.nextInt(4)) {
                val i = random.nextInt(chars.size)
                // Bias toward numbers and quotes, where damage is most likely to slip past the parser.
                chars[i] = if (random.nextBoolean()) digits[random.nextInt(digits.length)] else (32 + random.nextInt(95)).toChar()
            }
            val project = try {
                ProjectCodec.decode(String(chars))
            } catch (e: ProjectFormatException) {
                return@repeat
            }
            loaded++
            // Whatever loads must be usable everywhere the path and pull are read.
            val design = ProjectAnalysis.analyze(project)
            project.runs.forEach { PullAnalysis.analyze(it, design.runs[it.id]) }
            MaterialTakeoffs.of(project, design)
            ProjectText.render(project)
        }
        assertTrue("some mutations should still load", loaded > 0)
    }

    @Test fun badPullSettingsAreDamagedFiles() {
        val good = ProjectCodec.encode(sample())
        listOf(
            good.replace("\"pressureLimit\": 800.0", "\"pressureLimit\": -1.0"),
            good.replace("\"count\": 3", "\"count\": 0"),
            good.replace("\"count\": 3", "\"count\": 100000"),
            good.replace("\"weightLbPerFt\": 0.7", "\"weightLbPerFt\": 0.0"),
            good.replace("\"pounds\": 4000.0", "\"pounds\": -5.0"),
            good.replace("25.0,", "-25.0,"),
        ).forEach { text ->
            assertTrue(text != good)
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
        }
        // The same damage inside a backup is a damaged backup, not a crash.
        val backup = AppFileCodec.encodeBackup(BackupDocument(projects = listOf(sample())))
        assertThrows(ProjectFormatException::class.java) {
            AppFileCodec.decode(backup.replace("\"angleDegrees\": 45.0", "\"angleDegrees\": 999.0"))
        }
    }
}
