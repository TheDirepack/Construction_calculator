package com.thedirepack.constructioncalc

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.thedirepack.constructioncalc.core.data.ProjectStore
import com.thedirepack.constructioncalc.core.model.Project
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class AppFlowTest : AppUiTest() {
    @Test
    fun keypadCommitsCalculationToTapeAndReportsDivisionByZero() {
        for (key in listOf("5", "Feet", "3", "Plus", "6", "Equals")) icon(key).activate()
        compose.onNodeWithText("5' 9\"").assertIsDisplayed()
        for (key in listOf("Divide", "0", "Equals")) icon(key).activate()
        compose.onNodeWithText("Can't divide by zero").assertIsDisplayed()
        icon("Calculation tape").activate()
        compose.onNodeWithText("Calculation Tape").assertIsDisplayed()
        // The inline tape under the display shows the same "= 5' 9"": match only the row inside the
        // sheet (a container holding the sheet title but not the toolbar's tape button).
        val tapeSheet = hasAnyDescendant(hasText("Calculation Tape")) and
            !hasAnyDescendant(hasContentDescription("Calculation tape"))
        compose.onNode(hasText("= 5' 9\"") and hasAnyAncestor(tapeSheet)).assertIsDisplayed()
    }

    @Test
    fun quickSetupRunsFromSettingsAndCanBeFinishedOrSkipped() {
        openTool("Settings")
        click("Run quick setup again", scroll = true)
        await(hasText("Quick setup · 1 of 3"))
        compose.onNodeWithText("Plumbing").assertIsDisplayed()
        click("Next")
        await(hasText("Where do you work?"))
        click("Next")
        await(hasText("Units and keypad"))
        click("Done")
        await(hasContentDescription("Open tools menu"))

        openTool("Settings")
        click("Run quick setup again", scroll = true)
        await(hasText("Quick setup · 1 of 3"))
        click("Skip")
        await(hasContentDescription("Open tools menu"))
    }

    @Test
    fun conduitFillCalculationCanBeSavedAndReopenedFromAProject() {
        createProject()
        icon("Back").activate()
        openTool("Conduit Fill")
        compose.onNode(hasSetTextAction() and hasContentDescription("Group 1 wire count"))
            .performScrollTo().performTextReplacement("5")
        icon("Save to project").activate()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Branch raceway")
        click("Save", scroll = true)

        val item = savedProject().runs.single().items.single()
        assertEquals("Branch raceway", item.title)
        assertTrue(item.results.isNotEmpty())

        relaunch()
        openProject()
        await(hasText("Branch raceway"))
        click("Branch raceway", scroll = true)
        click("Open in Conduit Fill", scroll = true)
        compose.onNode(hasSetTextAction() and hasContentDescription("Group 1 wire count") and hasText("5"))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun drawerOpensCalculatorFormsAndSettings() {
        openTool("Voltage Drop")
        compose.onNodeWithText("Load current").performScrollTo().assertIsDisplayed()
        icon("Save to project").assertDoesNotExist()
        openTool("Conduit Bending")
        compose.onNodeWithText("Bend type").performScrollTo().assertIsDisplayed()
        openTool("Settings")
        compose.onNodeWithText("General").performScrollTo().assertIsDisplayed()
    }
}

abstract class AppUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val projectName = "CI project ${UUID.randomUUID().toString().take(8)}"
    private val services get() = (context.applicationContext as ConstructionCalcApp).services
    private var scenario: ActivityScenario<MainActivity>? = null
    protected lateinit var fixtures: File

    @Before
    fun launch() {
        fixtures = Files.createTempDirectory(context.cacheDir.toPath(), "ci-fixtures-").toFile()
        // Tests start in the app, past the first-launch quick setup (its own test reruns it).
        instrumentation.runOnMainSync { services.settings.setSetupDone(true) }
        relaunch()
    }

    @After
    fun cleanUp() {
        scenario?.close()
        instrumentation.runOnMainSync {
            services.projects.projects.value.singleOrNull { it.name == projectName }?.let {
                check(services.projects.delete(it.id))
            }
        }
        runBlocking { services.projects.flush() }
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
    }

    protected fun relaunch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    protected fun back() {
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    protected fun icon(label: String, substring: Boolean = false) =
        compose.onNodeWithContentDescription(label, substring = substring)

    protected fun click(label: String, scroll: Boolean = false) {
        val node = compose.onNode(hasText(label) and hasClickAction())
        if (scroll) node.performScrollTo()
        node.activate()
    }

    protected fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 20_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun openTool(title: String) {
        icon("Open tools menu").activate()
        click(title, scroll = true)
    }

    protected fun createProject() {
        openTool("Projects")
        // The empty screen and its floating action button both offer this action.
        await(hasText("New project"))
        compose.onAllNodes(hasText("New project") and hasClickAction()).onFirst().activate()
        compose.onNode(hasSetTextAction()).performTextReplacement(projectName)
        click("Create")
        await(hasText("Drawings · 0"))
    }

    protected fun openProject() {
        openTool("Projects")
        val projectRow = hasText(projectName) and hasClickAction() and
            !hasContentDescription("Project: $projectName. Change project.")
        compose.onNode(projectRow).performScrollTo().activate()
    }

    protected fun savedProject(): Project {
        runBlocking { services.projects.flush() }
        return ProjectStore.files(context).loadAll().single { it.name == projectName }
    }

    /**
     * Substitute the system document picker's result. The app still launches its real
     * contract and reads/writes the selected URI through ContentResolver.
     */
    protected fun withDocumentResult(action: String, file: File, launchPicker: () -> Unit) {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == action) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            launchPicker()
            compose.waitForIdle()
            assertEquals("Expected one picker request for $action", 1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}


// Route buttons through their accessibility actions. Canvas gestures still inject touch input.
internal fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick)
