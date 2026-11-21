# Architecture

Construction Calc is one app made of many **tools** (Foot-Inch, Conduit Bending, Voltage Drop…)
that share one engine, one project system, one settings screen and one look. This document is the
contract for building a tool.

## Module map

```
:app                 Shell: drawer, navigation, ToolHost, the one Settings screen, file import.
                     ToolRegistry lists every tool.
:core:measure        Pure Kotlin. Rational (exact fractions), Quantity (number/length/area/volume),
                     feet-inch parsing, the expression evaluator, formatting, the keypad
                     input state machine (CalcInput), and Decimals (how every number field parses
                     and prints plain decimals). The calculator brain, reusable by any tool.
:core:electrical     Pure Kotlin. NEC tables and electrical rules shared by every electrical tool and
                     the design engine: ampacity, corrections, ConductorSizing, ConductorProtection
                     (240.4), ConduitFill (Chapter 9, Note 7), FillDerating, Table 8/9, motors, AcPhases,
                     RacewayBends (Table 2 radii, the 360° sections), PullFormulas and PullRatings
                     (pulling tension, sidewall pressure, limits), CableWeight.
:core:model          Pure Kotlin + kotlinx.serialization. Project → Run → ProjectItem, panels/circuits,
                     run paths (RunPath, PathSegment) and pull settings (PullSetup), the design engine
                     (design/: ProjectAnalysis, PullAnalysis, MaterialTakeoff, Wire, PublishedValues), ResultValue
                     (the unit of exchange between tools), ToolTarget, the project and backup file
                     formats (plain JSON), text export (ProjectText, including the design).
:core:data           Android. AppServices: SettingsRepository (app-wide plus per-tool settings),
                     ProjectRepository (one JSON file per project), RecentResultsRepository, BackupService,
                     ProjectSharing (share sheet and file picker, no permissions).
:core:ui             Android/Compose. Theme, the ToolModule/ToolHost API, ToolIds,
                     ToolInputsViewModel + toolViewModel(), and the UI kit: ToolScaffold, MeasureField,
                     NumberField, ChoiceField, SegmentedChoice, ToggleRow, ResultCard, FormSection,
                     MeasureKeypad, FractionPad, settings rows, dialogs, value picker.
:feature:<tool>      One module per tool. Depends on all core modules (via the cc.android.feature
                     plugin).
build-logic/         Convention plugins: cc.jvm.library, cc.android.library, cc.android.feature.
```

Dependencies only point down: features → core:ui → core:data → core:model → core:electrical →
core:measure. Features never depend on each other. They share data through `ResultValue`s and the
project, and open each other by id (`ToolIds`) with a `ToolTarget`, never by import.

## How the pieces connect

- **Values flow between tools.** A tool calls `host.publish(results)` whenever its results change;
  the shell offers them to other tools once they have settled for 1.2 s (pass `immediate = true`
  for a deliberate result such as Foot-Inch's `=`). Every `MeasureField` (via the picker button in
  its calculator sheet) and `NumberField(pickKinds = …)` offers those values, results saved in the
  active project, and what the project's design works out (circuit currents and loads, circuit and
  run lengths, panel loads), filtered by `ValueKind`.
- **Tools open each other for a circuit or run.** `host.openTool(ToolIds.VOLTAGE_DROP, target =
  ToolTarget.OfCircuit(projectId, circuitId))` (or `OfRun`) opens a tool *for* that part of the
  project. The tool reads `host.target` and starts from it with `vm.prefill(target) { … }` (Voltage
  Drop for a circuit, Conduit Fill and Bending for a run, Panels on the run or circuit).
  `ToolScaffold` then names the target and shows a back arrow, and Save defaults to that run. A
  draft can carry a `conduit` (a bend does) that the save sheet offers as the run's conduit.
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
  results the value picker offers. Always read it with `AppSettings.activeTarget(projects)`
  (archived projects are never a target; a stale run falls back to the last run).
- **Electrical design lives in the project.** `Project.panels/groups/design` and `Run.raceway`
  link panels → circuits → conduit runs. `ProjectAnalysis.analyze(project)` (pure, tested) turns
  that into wire sizes, grounds, voltage drop, fill, bend totals, feeder loads and phase balance;
  any tool can call it. A run's 360° bend check uses its path (per pull section), and its fill
  check adds jam and clearance notes. Its notes are `DesignNote`s with a level (INFO explains, WARNING is a
  problem), so screens never guess severity from the text.
- **Runs have a route.** `Run.path` is a list of `PathSegment`s (straights with slope, bends with
  radius and plane, pull points) shared by Conduit Bending, Panels › Runs, the Wire Pull Planner and
  exports; `Run.pull` holds the run's pull settings. When a path exists, `Raceway.length` is its
  routed length. Never set one without the other: change them through `Project.withRunPath`,
  `withRunLength`, `withRaceway` or `addBendsToRunPath` (all in `RunPath.kt`), which keep them in
  step. `PullAnalysis.analyze(run, runDesign)` is the pull for a run (both directions), and
  `MaterialTakeoffs.of(project)` is the project's wire and conduit footage for reports.
- **Tools can add bends to a run's path** without being imported: a tool overrides
  `ToolModule.pathBends(item)` (Conduit Bending does), and a draft can carry `pathBends` (the save
  sheet offers "Add to run path", and updating the item updates its bends) or a whole `route` (the
  Pull Planner working without a project).
- **Company profile** (Settings › Company) heads exports: use `AppSettings.company.headerLines()`.
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
    // class VoltageDropViewModel : ToolInputsViewModel<VoltageDropInputs>(VoltageDropInputs(), VoltageDropInputs.serializer())
    // toolViewModel keys it by the opened item and target (rotation keeps it, each saved item has its
    // own state) and restores a saved item once.
    val vm: VoltageDropViewModel = toolViewModel(VoltageDropTool.ID, host)
    LaunchedEffect(host.target) {                                  // opened for a project circuit?
        (host.target as? ToolTarget.OfCircuit)?.let { t -> vm.prefill(t) { /* inputs from the circuit */ null } }
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
- **Plain numbers use `Decimals`** (`core:measure`) to parse and print, single/three phase is
  `AcPhases`, conduit is the core `ConduitType`/`TradeSize`, and switches are `ToggleRow`: one of
  each for the whole app.
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
  tests against published values. Shared tables go in `:core:electrical`. Manufacturer figures
  (a sidewall pressure preset, friction values) are labelled guidance in the UI, with their source,
  and a user value replaces them.
- **Validate on construction.** Model classes that come from files (`Circuit`, `PathSegment`,
  `PullSetup`, `StoredQuantity`) check their values in `init`, so a damaged file fails to load as
  "damaged" instead of crashing later.
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
