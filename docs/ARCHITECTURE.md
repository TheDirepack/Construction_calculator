# Architecture

Construction Calc is one app made of many **tools** (Foot-Inch, Conduit Bending, Voltage Drop…)
that share one engine, one project system, one settings screen and one look. This document is the
contract for building a tool.

## Module map

```
:app                 Shell: drawer, navigation, ToolHost, the one Settings screen, file import.
                     ToolRegistry lists every tool.
:core:measure        Pure Kotlin. Rational (exact fractions), Quantity (number/length/area/volume),
                     feet-inch parsing, the expression evaluator, formatting, and the keypad
                     input state machine (CalcInput). The calculator brain, reusable by any tool.
:core:electrical     Pure Kotlin. NEC tables and electrical formulas shared by the electrical tools.
:core:model          Pure Kotlin + kotlinx.serialization. Project → Run → ProjectItem, ResultValue
                     (the unit of exchange between tools), the project and backup file formats (plain JSON), text export.
:core:data           Android. AppServices: SettingsRepository (app-wide plus per-tool settings),
                     ProjectRepository (one JSON file per project), RecentResultsRepository, BackupService,
                     ProjectSharing (share sheet and file picker, no permissions).
:core:ui             Android/Compose. Theme, the ToolModule/ToolHost API, and the UI kit:
                     ToolScaffold, MeasureField, NumberField, ChoiceField, SegmentedChoice,
                     ResultCard, FormSection, MeasureKeypad, FractionPad, settings rows, dialogs.
:feature:<tool>      One module per tool. Depends on all core modules (via the cc.android.feature
                     plugin).
build-logic/         Convention plugins: cc.jvm.library, cc.android.library, cc.android.feature.
```

Dependencies only point down: features → core:ui → core:data → core:model → core:measure.
Features never depend on each other. They share data through `ResultValue`s, not imports.

## How the pieces connect

- **Values flow between tools.** When a tool finishes a calculation it calls
  `host.publish(results)`. Every `MeasureField` and `NumberField(pickKinds = …)` in every
  other tool offers those values, plus results saved in the active project, filtered by
  `ValueKind`. So a length from Foot-Inch can feed Voltage Drop without retyping.
- **Every length input is the calculator.** `MeasureField` opens `MeasureInputSheet`, the
  feet-inch keypad with fraction keys, math and recent values. It returns an exact `Quantity`.
- **Projects.** `host.saveToProject(draft)` opens the save sheet: pick a project and run, or
  create them. A `ProjectItem` stores the tool's own `inputs` (JSON, shaped however the tool
  likes), `results` (`ResultValue`s anyone can read) and a human `summary`. Opening an item from
  Projects calls `tool.Content(host)` with `host.openedItem` set, and the tool restores its inputs.
- **One file.** A project is one pretty-printed JSON file (`Name.project.json`), the same format on disk and
  when shared. Share = attachment + readable text body. Import = file picker or "open with".
- **One active project, everywhere.** `ToolScaffold` puts the `ProjectSwitcher` under every tool's
  title: "No project" (the default) or the active project and run, with a dropdown to switch,
  pick the run saves go to, export any project, create one, or manage projects. The active
  project is app-wide (`AppSettings.activeProjectId/activeRunId`): it's where Save goes and whose
  results the value picker offers.
- **Save/load everything.** Two file types, both plain pretty-printed JSON, told apart by
  their `format` field (`AppFileCodec`):
  - `Name.project.json`: one project (`ProjectCodec`). Importing one that already exists asks
    **Replace mine** or **Keep both**.
  - `… .backup.json`: any mix of all projects, app settings, every tool's settings page values,
    tools' own data and recent values (`BackupDocument` / `BackupService`). Restore lets the user
    pick sections and **Merge** (add new, update older projects) or **Replace all**.
  - Settings › Data: share or save a full backup, share all projects, and import or restore. The
    same opener handles both file types, as does "open with" from email, Files or Drive.
- **One settings screen.** App-wide: theme, animations, length display (Auto / ft-in / in),
  fraction precision. A tool with options sets `hasSettings = true` and implements
  `SettingsContent()`. That becomes its page in Settings, and `host.openSettings()` from inside
  the tool opens it directly. Tools never build their own settings screens or menus.

## Adding a tool

1. Create `feature/<name>/build.gradle.kts`:
   ```kotlin
   plugins { id("cc.android.feature") }
   android { namespace = "com.thedirepack.constructioncalc.feature.<name>" }
   ```
   Add `include(":feature:<name>")` to `settings.gradle.kts`, the dependency in
   `app/build.gradle.kts`, and the object to `ToolRegistry` in `:app`.
2. Put the math in a `domain` package as **pure Kotlin with no Android imports**, and unit-test it in
   `src/test`. Domain functions take plain inputs and return a result data class.
3. Implement the tool:

```kotlin
object VoltageDropTool : ToolModule {
    const val ID = "voltage-drop"          // stable forever: it's saved in project files
    override val id = ID
    override val title = "Voltage Drop"
    override val summary = "Drop, % drop and minimum wire size for a run"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_bolt  // shared icons live in :core:ui res/drawable

    @Composable override fun Content(host: ToolHost) = VoltageDropScreen(host)
}

@Serializable                                // what goes in ProjectItem.inputs
data class VoltageDropInputs(
    val length: StoredQuantity? = null,      // defaults everywhere so old/new files still decode
    val amps: String = "",
    val wire: String = "12",
)

@Composable
fun VoltageDropScreen(host: ToolHost) {
    // Inputs live in a ViewModel keyed by the opened item, so rotation keeps them and each saved
    // item gets its own state. Restore once when opened from a project:
    val vm: VoltageDropViewModel = viewModel(key = "voltage-drop:${host.openedItem?.id}")
    LaunchedEffect(host.openedItem) {
        host.openedItem?.takeIf { it.toolId == VoltageDropTool.ID }
            ?.let { ToolInputs.decode<VoltageDropInputs>(it.inputs) }?.let(vm::restore)
    }
    val inputs = vm.inputs
    val result = remember(inputs) { VoltageDrop.calculate(…) }   // domain call
    val results: List<ResultValue> = …                           // label/kind/exact/text
    LaunchedEffect(results) { if (results.isNotEmpty()) host.publish(results, context = "…") }

    ToolScaffold(
        title = "Voltage Drop",
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(ItemDraft(VoltageDropTool.ID, "Voltage drop", ToolInputs.encode(inputs), results, summary))
        }),
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            FormSection("Circuit") {
                MeasureField("One-way length", inputs.length?.toQuantity(), { vm.update { i -> i.copy(length = it?.stored()) } })
                NumberField("Load", inputs.amps, { vm.update { i -> i.copy(amps = it) } }, unit = "A",
                    pickKinds = setOf(ValueKind.CURRENT))
                ChoiceField("Wire size", sizes, …)
            }
            ResultCard(results, notices = listOf(Notice("Exceeds 3% branch-circuit recommendation", NoticeLevel.WARNING)))
        }
    }
}
```

### Rules for tools

- **No permissions.** The app requests none today. If a feature truly needs one, add it in
  `:app`'s manifest with a comment saying why, and request it at the moment of use.
- **Minimal animation.** Don't add custom animations. Everything runs under the app's
  `MotionDurationScale`, so the Animations setting can turn motion off. Standard Material
  components are fine.
- **One settings screen.** Options go in `SettingsContent()`. Store them with
  `LocalAppServices.current.settings.module(ID)`, and read Booleans with
  `rememberModuleBoolean`. Never add a separate settings sheet or menu.
- **Lengths use `MeasureField`**, displayed with the app settings (`settings.format(q)`).
  Store lengths as `StoredQuantity` (exact) and publish them as `ValueKind.LENGTH` in inches.
- **Publish results with the right `ValueKind`** and base unit (see `ValueKind`), and stable `key`s.
- **Back up tool data.** Settings-page values are backed up automatically. If the tool keeps any
  other data outside projects (history, custom tables), override `ToolModule.backup(context)` with a
  `ToolBackup` (see Foot-Inch's history). Never store user data where backups can't reach it.
- **Inputs are a `@Serializable` data class** with sensible defaults. The app is pre-release:
  there are no users and no saved files to stay compatible with, so rename and restructure freely.
  (Before the first release, add file versioning and migrations.)
- **Show assumptions and limits** with `Notice`s (code tables used, derating not applied,
  above recommended limits). These calculators guide fieldwork; say what they don't cover.
- **Reference data cites its source** (e.g. "NEC 2023 Chapter 9 Table 8") and has spot-check
  tests against published values. Shared tables go in `:core:electrical`.
- **Original work only.** The apps in `docs/research/` are a feature inventory. Don't copy their
  code, text, art or layouts.
- **Accessibility**: content descriptions on icon buttons, no meaning carried by colour alone,
  48dp touch targets.

## Building

Use the locked wrapper. It picks the JDK and makes concurrent builds wait their turn:

```sh
scripts/gw :core:measure:test :core:model:test     # fast, pure-JVM tests
scripts/gw :feature:voltagedrop:testDebugUnitTest  # one tool's tests
scripts/gw :app:compileDebugKotlin                 # everything compiles
scripts/gw :app:assembleDebug                      # APK
```
