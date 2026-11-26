# Android Electrical Toolkit
## Consolidated Requirements, Goals, References, and Development Instructions

**Source:** Development-session instructions supplied by project owner\
**Document type:** Requirements extraction\
**Status:** Original requirements with an implementation checklist in §14

---

# 1. Primary Goals

## 1.1 Application Purpose

Develop a modular Android application for electrical calculations, planning, and design.

The application should:

- Combine multiple electrical tools in one application.
- Provide a clean, intuitive, consistent user experience.
- Make individual tools useful independently.
- Allow tools to exchange inputs, outputs, and project information.
- Support complete electrical projects rather than isolated calculations.
- Allow calculations, conduit runs, panels, circuits, and other information to be linked.
- Support persistent projects, importing, exporting, archiving, and sharing.
- Support adding new tools incrementally without major architectural changes.
- Minimize permissions, unnecessary complexity, and animations.
- Make commonly needed operations convenient without adding excessive features or configuration.

## 1.2 Architectural Goals

The application should function as one integrated system with multiple modules, not a collection of disconnected calculators.

Important architectural characteristics:

1. Reusable calculation engines.
2. Modular feature implementations.
3. Shared project data.
4. Shared settings infrastructure.
5. Consistent navigation and visual design.
6. Standardized import/export.
7. Cross-module references.
8. Extensibility for additional electrical tools.
9. Minimal dependencies and permissions.
10. Maintainable separation between calculation logic and interface logic.

## 1.3 Design Philosophy

- Start with simpler functionality and expand gradually.
- Build reusable foundations rather than creating independent implementations for each tool.
- Avoid unnecessary complexity.
- Avoid excessive business-oriented features.
- Keep features discoverable and easy to use.
- Integrate functionality where doing so provides practical value.
- Minimize configuration requirements for ordinary operations.
- Do not preserve unused legacy behavior merely for compatibility.

---

# 2. Application Modules

## 2.1 Foot-Inch Calculator

**Initial implementation priority.**

The first functional module requested.

### Required capabilities

- Perform calculations involving feet and inches.
- Support fractional measurements properly.
- Provide an interface suited to feet, inches, and fractional inputs.
- Allow results to be displayed using feet and inches.
- Allow results to be displayed using inches only.
- Make output presentation configurable or selectable.
- Use a clean, straightforward interface.
- Allow calculation results to be saved within projects.
- Allow results to be reused by other modules.
- Support sharing calculations.
- Keep the calculation engine independent enough to be reused elsewhere.

### Architectural requirements

- Calculation logic must not be tied exclusively to the calculator interface.
- Other modules must be able to call the measurement/calculation functionality.
- Results should be represented in a form suitable for storage, retrieval, and reuse.
- The design must accommodate future calculation types.
- Calculation history or saved calculations should integrate with the common project system.

### Reference application

**Architectural Calculator**

https://play.google.com/store/apps/details?id=evanwinograd.archcalc

Purpose: Reference for interface design, feet/inch operations, and fractional measurement handling.

The developer was asked to investigate whether the referenced APK could be downloaded and inspected to better understand the intended functionality.

---

## 2.2 Conduit Calculations and Bending

**Planned feature.**

Develop tools for conduit calculations, including:

- Conduit bending.
- Offsets.
- Other conduit-bending calculations.
- Planning conduit runs.
- Linking individual bends and conduit calculations into complete runs.
- Saving conduit runs within projects.
- Sharing conduit-run information.
- Reusing conduit-run calculations in other modules.

### Cross-module requirements

A conduit run should eventually be able to:

- Exist as a reusable project object.
- Reference related calculations.
- Be assigned to electrical circuits.
- Serve multiple circuits.
- Be associated with circuits across multiple panels.
- Supply relevant information to wire-sizing and wire-pull planning.

### Reference

https://play.google.com/store/apps/details?id=com.conduit

---

## 2.3 Panel Planning and Panel Schedules

**Explicit later development priority.**

Create a system for planning and designing electrical panels.

### Panel configuration

- Specify panel dimensions or size.
- Create circuits within a panel.
- Provide labels for circuits.
- Specify breaker sizes for each circuit.
- Group related circuits.
- Support creation of usable panel schedules.
- Support multiple panels within a project.

### Circuit configuration

Each circuit should support at least:

- Circuit identification.
- Circuit label.
- Breaker rating.
- Association with a panel.
- Membership in a circuit group where applicable.
- Association with relevant conduit runs.
- Relevant wire-sizing requirements.
- Relevant thermal-correction inputs.

### Circuit grouping

- Multiple circuits must be groupable.
- Grouped circuits should remain identifiable individually.
- Circuit groups should integrate with conduit-run planning.

### Cross-module integration

Panels and circuits should interact with:

- Conduit tools.
- Wire-sizing calculations.
- Thermal-correction calculations.
- Project planning.
- Future wire-pull planning.

### Example workflow

1. Create a panel.
2. Define circuit labels and breaker sizes.
3. Group circuits as needed.
4. Create a conduit run.
5. Assign the conduit run to circuits from one or more panels.
6. Determine required conductor sizes.
7. Apply applicable thermal-correction requirements.
8. Save all related information in the project.

The goal is to avoid repeatedly entering information that is already present elsewhere in the project.

---

## 2.4 Conductor and Wire Sizing

**Required integration with circuit and project planning.**

### Required capabilities

- Determine minimum required conductor sizes for a specified ampacity.
- Support conductor material selection.
- Account for specified thermal requirements.
- Support thermal correction in planning calculations.
- Connect conductor sizing to circuits and conduit runs.
- Reuse electrical information already stored elsewhere in a project.

### Required conductor materials

1. Copper.
2. Aluminum.
3. Copper-coated or copper-clad aluminum.

### Electrical data

- Include relevant conductor resistance information.
- Support material-specific electrical properties.
- Obtain reference data from authoritative electrical tables.
- Allow data to support calculations throughout the application.

### Data sources

The user explicitly authorized:

- Looking up existing electrical tables online.
- Importing suitable reference data.
- Consulting the locally available electrical codebook.

The transcript does not specify every required conductor type, insulation type, correction factor, or sizing rule.

These details require subsequent technical specification.

---

## 2.5 Wire-Pull Planner

**Later implementation priority; planning authorized after initial panel/circuit work.**

Requested functionality:

- Develop a wire-pull planning module.
- Integrate the planner with existing project data.
- Connect it to conduit runs.
- Connect it to circuits and panels.
- Reuse relevant conductor and electrical calculations.
- Avoid entering the same information independently in multiple tools.

### Development sequencing

The wire planner was initially excluded from immediate development.

The final instruction changed its status:

- Begin planning the wire-pull planner.
- Integrate it conceptually with the rest of the system.
- Continue resolving identified issues.

This authorizes planning, but does not clearly establish that full wire-pull implementation should begin immediately.

---

## 2.6 Voltage-Drop Calculations

**Future module.**

Reference:

https://play.google.com/store/apps/details?id=com.southwire.voltageDrop

Intended general purpose:

- Voltage-drop calculations.
- Integration with project circuits and conductor data.
- Reuse of conductor material properties.
- Potential integration with conductor sizing and conduit-run planning.

The specific formulas, input fields, and supported scenarios were not explicitly defined in the session.

---

## 2.7 Partial-Reel Calculator

**Future module.**

Reference:

https://play.google.com/store/apps/details?id=com.southwire.partialReelCalculator

Intended purpose: Calculate or estimate remaining conductor/cable quantities on partially used reels.

Detailed functional requirements remain undefined.

---

## 2.8 Unit Conversion

**Future module.**

Reference:

https://play.google.com/store/apps/details?id=com.southwire.conversion

The application should eventually support electrical and measurement conversions.

Conversion logic should be reusable by other modules where appropriate.

Specific conversion categories were not defined.

---

## 2.9 SIMpull-Related Functionality

**Future module/reference.**

Reference:

https://play.google.com/store/apps/details?id=com.simpull

Potential relevance to wire-pull planning and related conductor operations should be investigated.

No exact feature list was supplied.

---

## 2.10 VFD Sizing

**Future module.**

Reference:

https://play.google.com/store/apps/details?id=com.southwire.VFDSizingCalculator

Intended purpose: Variable-frequency-drive sizing calculations.

Specific calculations and compatibility requirements remain undefined.

---

## 2.11 Well and Pump Calculations

**Lowest implementation priority.**

Reference:

https://play.google.com/store/apps/details?id=com.welldrilling

The session explicitly states that well and pump functionality should be the last major area developed.

Do not prioritize implementation while working on the earlier modules.

---

# 3. Project Management System

## 3.1 Shared Project Context

Projects are global application objects.

A selected project must apply across the entire application, not just within an individual module.

### Requirements

- All modules should use the same currently selected project.
- A project can contain information from different modules.
- Information should remain associated with its originating project.
- Modules must be able to reference related objects from the same project.
- Switching projects should change the active working context consistently.
- Project data should remain available after closing and reopening the application.

## 3.2 Project Selection Interface

The main interface should have a project selector at the top.

### Required capabilities

- Display the current project.
- Select another project.
- Create a new project.
- Open project management.
- Provide a default **No Project** option.
- Provide convenient access to project exporting.

### Project selector

The requested design uses a dropdown at the top of the application.

Project management should not be buried inside unrelated modules.

## 3.3 Project Management

Users must be able to:

- Create projects.
- Select projects.
- Manage existing projects.
- Export individual projects.
- Archive projects.
- View archived projects.
- Unarchive projects.

Each project should have an export action or button associated with it.

## 3.4 No Project Mode

A default **No Project** context is required.

This allows access to the tools without requiring the user to first create or select a project.

The transcript does not explicitly settle whether unsaved calculations performed in this mode should persist automatically.

## 3.5 Project Archives

Projects must support archiving.

### Archiving behavior

- Move the project into an archived-project list.
- Preserve all project information.
- Prevent modifications while archived.
- Allow archived projects to be viewed or referenced.
- Allow archived projects to be unarchived.
- Restore editing access after unarchiving.

Archiving is not deletion.

## 3.6 Project Hierarchy

The requested organization is:

**Project → individual saved items/calculations/designs**

Examples of potential project contents:

- Saved calculator operations.
- Conduit bends.
- Conduit runs.
- Panels.
- Circuits.
- Circuit groups.
- Wire-sizing results.
- Thermal-correction inputs.
- Other module-specific calculations.

Individual items should be reusable and linkable without requiring complete project duplication.

---

# 4. Saving, Loading, Importing, Exporting, and Sharing

## 4.1 Persistent Data

A common persistence system should support all application modules.

### Requirements

- Save calculations.
- Load previous calculations.
- Save complete projects.
- Load projects.
- Preserve relationships between objects.
- Preserve relevant module-specific information.
- Support future data types.

Saved information should be suitable for later reference and additional editing.

## 4.2 Project Export

The user must be able to export an individual project.

### Requirements

- Export the complete project.
- Include associated calculation data.
- Include all relevant design objects.
- Preserve relationships between project objects.
- Produce a single file when practical.
- Make the file easy to store, share, and reference.
- Allow the exported project to be imported again.

## 4.3 Complete Application Export

A separate capability must support exporting everything.

The requested scope includes:

- All projects.
- Saved calculations.
- Other persisted application data.
- Settings.

The corresponding import operation should restore the exported information.

Project export and complete-application export are distinct operations.

## 4.4 Importing

### Requirements

- Import a previously exported project.
- Import a complete application backup.
- Restore saved calculations.
- Restore relationships between related objects.
- Support receiving files shared by other users.

The exact policy for duplicate projects or existing-data conflicts was not specified.

## 4.5 Sharing

The application should support easy sharing through:

- Text.
- Email.
- Exported files.
- Standard Android sharing mechanisms where practical.

Both individual calculation information and larger saved project data should be considered.

The format should remain understandable and convenient.

## 4.6 File Format

The developer was challenged for using a custom format when the underlying contents could be ordinary ZIP or JSON.

### Extracted preference

- Avoid unnecessary proprietary or opaque formats.
- Prefer familiar standard formats.
- Keep exported data portable.
- Keep data easily importable.
- Avoid requiring a special container format without a meaningful justification.

**JSON and ZIP were specifically mentioned as acceptable reference points, not mandated as a finalized schema.**

## 4.7 Compatibility Policy

The project is not yet in public use.

Explicit instructions:

- Do not prioritize backward compatibility.
- Existing formats and structures may be changed.
- Previous prototype data does not need to be preserved for users.
- Focus on improving the current implementation.
- Avoid compatibility layers that complicate development unnecessarily.

This is a development-stage policy, not necessarily a permanent release policy.

---

# 5. Integration and Reusable Calculations

## 5.1 Shared Calculation Infrastructure

The foot-inch calculator must not become an isolated calculation implementation.

Required properties:

- Calculation operations can be invoked by other modules.
- Outputs can be transferred between modules.
- Saved results can be retrieved and reused.
- Future modules can build on existing calculation systems.
- Business logic should be independently reusable.

## 5.2 Cross-Module References

Modules should be able to reference shared project objects rather than creating disconnected copies.

Examples:

- Conduit run references one or more circuits.
- A circuit belongs to a panel.
- A circuit references conductor requirements.
- Wire sizing uses data from the associated circuit.
- Thermal correction modifies conductor-sizing requirements.
- Wire-pull planning uses conductor and conduit data.

## 5.3 Integrated Workflows

The application should support workflows spanning multiple tools.

The explicitly described example involves:

**Panel → circuits → conduit run → conductor sizing → thermal correction**

The user wants connected information to be used automatically where applicable.

## 5.4 Reusability

Reusable systems should include:

- Measurement calculation functionality.
- Shared project selection.
- Calculation persistence.
- Import/export.
- Settings.
- Shared electrical reference data.
- Cross-module data access.

Do not reimplement equivalent foundational logic separately for each module.

---

# 6. User Interface and Experience

## 6.1 Overall Appearance

- Clean interface.
- Consistent design.
- Minimal unnecessary visual complexity.
- Appropriate usability for practical electrical work.
- Consistent interactions between modules.
- Straightforward access to commonly used tools.

## 6.2 Navigation

- Multiple modes/modules within the same application.
- One global active project.
- Project selector near the top.
- Central access to project management.
- Shared settings interface.
- Consistent module navigation.

## 6.3 Settings

There must be **one settings system**.

### Global settings

Examples explicitly referenced:

- Theming.
- Company name.
- General application preferences.

### Module-specific settings

- Module-specific configuration belongs within the shared settings system.
- Each module can have a dedicated settings section.
- Do not create independent, unrelated settings systems.
- When settings are opened from a module, navigate directly to that module's settings section where applicable.

### First-launch quick setup

- On first launch, show a short, skippable, plain-language setup: primary trade (Electrical;
  Plumbing and HVAC shown as coming later), where you work (jurisdiction and NEC edition), units
  (imperial or metric), and the Foot-Inch keypad (Classic or Dual-Split, and whether the split
  keypad marks keys with ' and " so feet and inches aren't distinguished by color alone).
- Every choice is stored in the one settings system and editable in Settings; Settings can run the
  setup again.

## 6.4 Animation

- Minimize animations.
- Do not add animations unnecessarily.
- If animations are included, provide an option to disable them.

## 6.5 Configuration Philosophy

- Do not overcomplicate the application with unnecessary company-management functionality.
- Company name and similar identification fields should be configurable through settings.
- Avoid excessive configuration for common operations.

---

# 7. Permissions and Platform Requirements

## 7.1 Platform

Target platform: **Android**.

No specific Android version, framework, UI toolkit, or architecture was mandated.

## 7.2 Permissions

Minimize requested application permissions.

The user specifically stated:

- The application should require essentially no special permissions.
- File access may be needed.
- Additional permissions are acceptable where genuinely necessary.
- Add permissions gradually as functionality requires them.

## 7.3 Platform Integration

Relevant expected integration points include:

- File selection.
- File export.
- File import.
- Android sharing.
- Text/email sharing.

Specific implementation APIs were not mandated.

---

# 8. Electrical Reference Data

## 8.1 Primary Code Reference

**NFPA 70 — National Electrical Code, 2023 edition**

Locally referenced PDF:

`documents/'NFPA 70 NEC 2023 National Fire Protection Association -- NFPA -- 2023 -- NFPA -- 832d6f3c4c3ba12bf82df4dcf8b13fe7 -- Anna’s Archive.pdf'`

The original path/name is preserved for source identification.

### Jurisdictions and amendments

- A jurisdiction is a base NEC edition (the one it adopted) plus regional amendments to that
  edition. Amendments override only specific rules, values or citations; everything else is pulled
  from the base edition.
- Never fabricate amendment contents or edition data. A jurisdiction with no entered amendments says
  "NEC 20xx, no local amendments entered". An edition without data (e.g. NEC 2026) is shown as
  unavailable and as using NEC 2023 values, never silently.
- Results show which edition and jurisdiction a value came from, and when an amended value is used.

### Multiple trades

- Electrical is built first. Plumbing and HVAC follow later with their own calculators, formulas
  and code books; the trade list is an extensible registry.

## 8.2 Authorized Research

The developer was allowed to:

- Consult the locally available codebook.
- Research equivalent electrical tables online.
- Import existing reference tables where suitable.
- Use reference materials to implement conductor calculations.

## 8.3 Specifically Requested Materials

- Copper.
- Aluminum.
- Copper-coated aluminum.

The user specifically called for material resistances.

## 8.4 Future Data Requirements

The design should accommodate electrical reference information needed for:

- Conductor sizing.
- Ampacity.
- Thermal correction.
- Voltage drop.
- Related electrical calculations.

Exact tables and supported calculation cases remain to be specified.

---

# 9. Development Process and Agent Instructions

## 9.1 Delegation

The developer was explicitly permitted to use sub-agents.

Permitted options included:

- Local sub-agents.
- Agents in other runtimes.
- Delegation through T3 Code.
- Gemini-based agents or models for testing.
- Free models available through OpenCode.

### Constraint

OpenAI agents available through the referenced T3 Code pathway were described as blocked during the session.

This is an environment-specific statement, not a permanent architectural requirement.

## 9.2 Initial Development Approach

Initial instruction:

- Begin with the foot-inch calculator.
- Design shared foundations.
- Prepare other modules for later implementation.
- Avoid spending initial development effort implementing all future tools.

A later instruction prioritized architectural implementation over producing a finished application build.

## 9.3 Independent Project Audit

The user requested a separate agent/thread to analyze the project independently.

### Required sequence

1. Finish the panel and circuit work first.
2. Start a separate thread or agent without prior project context.
3. Have that agent inspect the actual project independently.
4. Identify missing functionality.
5. Identify integration opportunities.
6. Identify possible simplifications.
7. Identify architectural improvements.
8. Identify bugs and other issues.
9. Allow the agent to finish its initial independent analysis.
10. Only then provide it with the instructions from the development session.
11. Have it compare the implementation against the user's requirements.
12. Identify discrepancies between implementation and requested behavior.

### Independence requirement

Do not give the independent reviewer the original requirements before it completes the initial project analysis.

This is intended to obtain an independent assessment before a requirements-compliance review.

## 9.4 Audit Capabilities

The review agent may:

- Use additional agents.
- Execute code.
- Create tests.
- Run tests.
- Compile the application.
- Build the application.
- Inspect actual behavior.
- Investigate defects.

The user explicitly authorized real execution and testing rather than limiting the review to source-code inspection.

## 9.5 Issue Resolution

After the analysis:

- Begin fixing identified issues.
- Address integration problems.
- Improve the application where the findings justify changes.
- Begin planning wire-pull functionality and its integration.
- Keep well/pump work deferred.

---

# 10. Development Priorities and Sequence

Priorities evolved during the session.

The following sequence reflects the final instructions rather than only the initial request.

## Phase 1 — Initial Foundation

- Foot-inch calculator.
- Fraction handling.
- Reusable measurement engine.
- Modular application architecture.
- Global settings.
- Project infrastructure.
- Save/load foundations.
- Import/export foundations.
- Initial shared interface.

## Phase 2 — Project and Electrical Planning

- Shared project selection.
- Project management.
- Archived projects.
- Panel designer.
- Circuit configuration.
- Circuit grouping.
- Conductor material data.
- Conductor-sizing support.
- Thermal correction.
- Cross-module references.

## Phase 3 — Independent Audit

**After panel and circuit work is complete:**

- Context-free project assessment.
- Architecture review.
- Bug investigation.
- Integration review.
- Simplification review.
- Requirements-compliance review.
- Build and test verification.

## Phase 4 — Improvements and Wire-Pull Planning

- Resolve identified problems.
- Improve integration.
- Plan wire-pull functionality.
- Prepare for deeper conductor/conduit integration.

## Phase 5 — Remaining Electrical Tools

Potential additions include:

- Expanded conduit calculations.
- Voltage drop.
- Partial-reel calculations.
- Conversions.
- SIMpull-related features.
- VFD sizing.

The exact ordering of these modules was not finalized.

## Phase 6 — Well and Pump

Well and pump functionality comes last.

---

# 11. External References

## 11.1 Android Applications

| Reference | URL | Intended relevance |
|---|---|---|
| Architectural Calculator | https://play.google.com/store/apps/details?id=evanwinograd.archcalc | Foot-inch calculator, fractions, interface |
| Southwire Voltage Drop | https://play.google.com/store/apps/details?id=com.southwire.voltageDrop | Future voltage-drop functionality |
| Conduit app | https://play.google.com/store/apps/details?id=com.conduit | Conduit calculation/bending |
| Southwire Partial Reel Calculator | https://play.google.com/store/apps/details?id=com.southwire.partialReelCalculator | Partial-reel calculations |
| Southwire Conversion | https://play.google.com/store/apps/details?id=com.southwire.conversion | Unit conversion |
| SIMpull | https://play.google.com/store/apps/details?id=com.simpull | Wire-pull-related functionality |
| Well Drilling | https://play.google.com/store/apps/details?id=com.welldrilling | Future well/pump functionality |
| Southwire VFD Sizing Calculator | https://play.google.com/store/apps/details?id=com.southwire.VFDSizingCalculator | VFD sizing |

**Reference status:** These URLs were supplied by the user. Their availability, downloadable APKs, and actual functionality have not been independently verified in this extraction.

## 11.2 Reference Documents

NFPA 70 — NEC 2023:

`documents/'NFPA 70 NEC 2023 National Fire Protection Association -- NFPA -- 2023 -- NFPA -- 832d6f3c4c3ba12bf82df4dcf8b13fe7 -- Anna’s Archive.pdf'`

## 11.3 Development Tools and Runtimes

- T3 Code — cross-runtime agent delegation.
- Gemini — potential independent testing.
- OpenCode — access to free models.
- Sub-agents — delegated development, analysis, and testing.

---

# 12. Explicit Exclusions and Constraints

## 12.1 Do Not Prioritize

- Well/pump development.
- Full wire-planner implementation during the initial panel/circuit phase.
- Implementing every reference application's functionality immediately.
- Extensive animations.
- Complex company-management features.
- Unnecessary permissions.
- Legacy compatibility.

## 12.2 Avoid Architectural Fragmentation

Do not independently duplicate:

- Settings.
- Project selection.
- Project persistence.
- Measurement calculations.
- Shared electrical reference information.
- Import/export foundations.

## 12.3 Development-Stage Freedom

Since the application had not yet been tested or adopted by users:

- Existing internal data structures can change.
- Formats can change.
- Modules can be reorganized.
- Compatibility with earlier prototypes is not required.

---

# 13. Everything Else Important

This section separates implied architectural consequences, unresolved questions, and transcription ambiguities from explicitly stated requirements.

## 13.1 Important Architectural Consequences

The following are implementation considerations inferred from the requirements, not independently requested technologies.

### Shared data model

The application will likely need consistent representations of:

- Projects.
- Saved calculations.
- Panels.
- Circuits.
- Circuit groups.
- Conduit runs.
- Conductors.
- Electrical reference data.
- Module-specific records.

### Stable object references

Cross-module relationships imply that project objects need reliable identifiers or equivalent persistent references.

These references should survive saving, loading, exporting, and importing.

### Calculation/data separation

Reusable calculation results should remain separate from display formatting.

For example, a measurement should be usable regardless of whether the interface displays it as inches or feet and inches.

### Project archive enforcement

Archived projects must actually prevent modification.

Disabling an edit button alone may not be sufficient if other modules can still modify archived project data.

### Integrated data ownership

Related modules should have a clear source of truth for shared data.

For example, changing a circuit's electrical requirements should not leave conflicting conductor requirements in connected planning tools.

### Export integrity

Exporting and importing projects should preserve relationships between related objects, not merely individual values.

### Common module interfaces

New modules should be able to access existing project, settings, storage, calculation, and sharing functionality without rebuilding those capabilities.

## 13.2 Unresolved Functional Questions

The original session does not establish final behavior for the following.

### Foot-inch calculator

- Exact supported operations.
- Input syntax and keypad behavior.
- Fraction denominator limits.
- Fraction rounding and precision.
- Negative measurements.
- Decimal/fraction conversion.
- Calculation-history behavior.
- Whether inches-only and feet/inches are input modes, output modes, or both.

### Projects

- Whether No Project calculations persist.
- Project deletion behavior.
- Project duplication.
- Project naming rules.
- Project-level overrides of global settings.
- Import conflict handling.
- Whether archived projects can still be exported.
- Whether archived projects permit calculations that do not modify stored data.

### Data and sharing

- Exact project file structure.
- Exact backup format.
- How global settings interact with imported projects.
- Whether full-backup import replaces or merges current data.
- Whether text sharing includes structured machine-readable data.
- File versioning for future public releases.

### Panels and circuits

- Supported panel configurations.
- Number of circuits and circuit numbering rules.
- Support for multiple poles.
- Panel schedule presentation.
- Whether circuit groups represent shared equipment, multi-pole breakers, or another grouping concept.
- Detailed panel sizing and capacity rules.
- Supported conductor types and insulation ratings.

### Wire calculations

- Exact supported wiring methods.
- Temperature assumptions.
- Applicable ampacity tables.
- Adjustment/correction rules.
- Termination temperature limitations.
- Voltage-drop calculation methods.
- Conductor resistance temperature assumptions.
- Treatment of copper-clad aluminum.
- Whether NEC-based calculations are advisory or intended for code-compliance checking.

These should be resolved before implementing the relevant functionality, not invented during requirements extraction.

## 13.3 External Reference Considerations

The supplied apps are examples rather than complete feature specifications.

Before implementing equivalent features:

- Inspect actual reference-app behavior where feasible.
- Identify which functions are relevant.
- Document needed input/output behavior.
- Avoid assuming the application requires every feature of a reference app.

For the NEC:

- Verify formulas and table applicability.
- Record the edition used.
- Distinguish code requirements from engineering assumptions.
- Verify rights before redistributing substantial copyrighted reference-table content.

## 13.4 Transcription Ambiguities

Several original phrases appear to contain speech-to-text errors.

**“Don't worry about making an actual bill”**

Likely refers to not prioritizing an actual application build, given the instruction to concentrate on architecture and implementation systems. The exact wording does not establish whether “bill” or “build” was intended.

**“It should get to be double pad in one settings menu”**

Meaning is unclear. The surrounding statements clearly require a single shared settings menu with module-specific sections.

**“Start around the rest of the components and the rest of the systems”**

Likely requests initial architectural work on future components, without necessarily implementing their full functionality.

**“Wire planner” versus “wire pull planner”**

These may refer to the same planned module, but the distinction was not explicitly resolved.

**“Copper coated aluminum”**

Standard technical terminology is generally “copper-clad aluminum.” The intended conductor classification should be confirmed when implementing electrical data.

## 13.5 Changes in Priority During the Session

| Earlier instruction | Later instruction | Resulting interpretation |
|---|---|---|
| Focus only on foot-inch calculator initially | Begin other shared components and architecture | Implement calculator plus reusable system foundations |
| Do not prioritize building the application | Permit compiling, building, and executing tests during independent audit | Build/test activities are authorized |
| Defer the wire planner | Begin planning and integrating the wire-pull planner | Planning authorized; full implementation still not clearly prioritized |
| Do not work on well/pump now | Well and pump should be the last thing touched | Explicit last priority |
| Make future modules later | Complete panels and circuits before independent audit | Panel/circuit work became an immediate milestone |
| Independent reviewer starts without context | Supply session requirements only after initial analysis | Two-stage independent review required |

## 13.6 Requirements Compliance Versus Implementation Quality

The requested independent audit has two different objectives.

**First pass — independent quality audit**

- Does the architecture make sense?
- Are modules organized properly?
- Are there bugs?
- Are there unnecessary complexities?
- Are there missing capabilities?
- Are integrations consistent?
- Can the application be simplified?
- Do builds and tests expose problems?

**Second pass — requirements compliance**

After the first review is complete:

- Compare implemented features against these session instructions.
- Identify missing requirements.
- Identify partially implemented requirements.
- Identify behavior that contradicts requirements.
- Identify requirements met by existing functionality.
- Prioritize remaining work.

This separation is deliberate and should be preserved.

---

# 14. High-Level Acceptance Criteria

These are suggested verification criteria derived from the explicit requirements.

The checked items reflect a review of the app entry points and local tests at commit `876459e`. The debug APK built, and the JVM and Android local unit test suites passed. File picking, sharing, and PDF generation still need a device check.

- Calculator and architecture evidence includes the shared measurement engine, tool registry, and exact-value interoperability tests. [Architecture](ARCHITECTURE.md)
- Project evidence includes the shared project switcher, project-file round trips, and repository tests for read-only archived projects. The backup service handles projects, settings, recents, and tool data. Full restore into an existing installation remains open. [Projects roadmap](ROADMAP.md#9-projects--runs-linking-everything-together)
- Panels and circuits use stored run and group IDs. The design engine handles conductor sizing and thermal correction. Copper-clad aluminum uses aluminum's resistance and impedance table values. [Panels and circuits](tools/panels.md)
- The app has an animation toggle. Whether motion is minimal still needs visual review.
- The Wire Pull Planner is implemented (v1) and integrated with project runs, circuits, Conduit Bending, Panels, Reel Length and exports. The independent review sequence and the timing of the plan relative to it remain unverified. [Wire Pull Planner](tools/pull-planner.md)

## Calculator

- [x] Feet/inch arithmetic works correctly.
- [x] Fractional measurements are handled correctly.
- [x] Inches-only output is supported.
- [x] Feet-and-inches output is supported.
- [x] Calculation engine is reusable outside the calculator module.

## Project System

- [x] Project selector is accessible at the top of the application.
- [x] Projects can be created and selected.
- [x] No Project option exists.
- [x] Project selection applies across modules.
- [x] Individual projects can be exported.
- [x] Exported projects can be imported.
- [ ] All application data can be exported and restored.
- [x] Projects can be archived.
- [x] Archived data remains intact.
- [x] Archived projects cannot be modified until unarchived.

## Architecture

- [x] Modules use common settings infrastructure.
- [x] Modules use common project infrastructure.
- [x] Modules can reuse calculation functionality.
- [x] Cross-module relationships survive persistence and export/import.
- [x] Additional modules can be implemented without replacing shared foundations.

## Electrical Planning

- [x] Panels can be created with specified sizes.
- [x] Circuits can be configured.
- [x] Circuits support labels and breaker sizes.
- [x] Circuits can be grouped.
- [x] Panel schedules can be produced.
- [x] Conduit runs can be associated with circuits from multiple panels.
- [x] Copper, aluminum, and copper-clad aluminum electrical properties are supported.
- [x] Conductor-sizing calculations can use relevant circuit requirements.
- [x] Thermal correction is supported within planning calculations.

## User Experience

- [x] There is one settings system.
- [x] Global and module-specific settings are clearly organized.
- [x] Module settings shortcuts open the relevant section.
- [x] Sharing through text/email or standard files is supported.
- [x] Unnecessary permissions are avoided.
- [ ] Animations are minimal.
- [x] Any optional animations can be disabled.

## Development Verification

- [ ] Panel/circuit milestone is completed before independent audit.
- [ ] Independent analysis occurs before requirements are disclosed to the reviewer.
- [ ] Requirements-compliance analysis follows the independent review.
- [x] Actual code execution, compilation, and testing are permitted.
- [x] Identified issues are investigated and prioritized.
- [ ] Wire-pull integration planning follows the initial development and review milestones.
- [x] Well/pump work remains deferred.

---

# 15. Final Consolidated Direction

Build a modular Android electrical toolkit with a shared project system at its center.

Begin with a capable feet/inches/fractions calculator and reusable application foundations. Expand into panel schedules, circuits, conductor sizing, thermal correction, conduit planning, and eventually wire-pull planning.

Information entered once should be available to related tools throughout the same project.

Provide simple project management, reliable persistence, standard-format export/import, archiving, and convenient sharing.

Keep the interface clean, permissions minimal, settings centralized, and modules well integrated.

After panel/circuit implementation, perform an independent project audit before supplying the reviewer with the development-session requirements. Use the combined findings to fix defects and guide additional integration.

Leave well and pump functionality until the end.

**The primary objective is an integrated electrical planning system—not merely a collection of independent calculators.**
