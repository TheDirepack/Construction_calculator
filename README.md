<img src="assets/icon.svg" width="64" height="64" alt="">

# Construction Calculator

An Android toolbox for electricians and builders. It starts with a feet-inch-fraction calculator;
conduit bending, conduit fill, voltage drop, pull planning and linked "runs" come next. See
[docs/ROADMAP.md](docs/ROADMAP.md).

## Foot-Inch Calculator

| Key sequence | Means |
| --- | --- |
| `5` `'` `3` `½` | 5' 3 1/2" (fraction keys sit above the keypad) |
| `5` `'` `3` | 5' 3" |
| `7/16` | 7/16" |
| `5` `'` `3` `"` `1` `/` `2` | 5' 3 1/2", typing the fraction by hand |
| `2` `.` `5` `'` | 2' 6" |
| `5` `'` `+` `6` | 5' 6" (a number with no unit mark, next to a length, counts as inches) |

- Exact fraction math. Results round to the chosen precision (1/2 through 1/64). An ≈ marks a rounded result, with a note saying whether it was rounded up or down.
- **Auto** display (the default): work in inches and the answer comes back in inches (96" − 55 7/8" = 40 1/8"); use feet and it answers in feet-inches. You can also force ft-in or in from the row above the keypad.
- Tracks dimensions: ft × ft gives sq ft, × ft again gives cu ft, ft ÷ ft gives a plain count.
- Shows a live result while you type, saves history, and converts to decimal feet, inches, mm and m.
- Tap the result or a conversion to copy it, for example as `7' 11-7/8"`. Long-press ⌫ to clear.

## Building

Requires JDK 17+ (21 recommended) and the Android SDK with platform `android-37.2`.

```sh
scripts/gw :app:assembleDebug           # APK → app/build/outputs/apk/debug/
scripts/gw :core:measure:test :core:model:test :core:electrical:test   # engine/model tests
scripts/gw :app:compileDebugKotlin      # everything compiles
```

`scripts/gw` is `./gradlew` with the JDK picked and a lock so concurrent agents don't run builds
at the same time.

## Testing

```sh
./gradlew test testDebugUnitTest          # JVM and Android local unit tests
./gradlew :app:connectedDebugAndroidTest # App tests on a connected Android device
```

[Android CI](.github/workflows/android-ci.yml) runs these in parallel jobs on pull requests and
pushes to `main`, and supports manual runs. Both jobs compile with Android SDK 37.2 and JDK 21.
The device tests run on a fresh API 36 x86_64 emulator. Use a disposable emulator for local
instrumentation runs because the tests exercise app storage. On failures, the Actions job uploads
available Gradle logs and test reports. The instrumentation job also captures device logs before
shutting down the emulator when the test command fails. APK build and emulator boot failures are
reported in their own steps.

The app tests drive `MainActivity` through Compose semantics. PDF import and export substitute a
local URI for the system picker's result, then use the app's real file handling. The two-page
`field-plan.pdf` fixture is an original synthetic test asset. It contains two source text labels,
one line per sheet, and a 90° rotation on page two. Some existing JVM reference tests in
`core/plans` skip when their optional reference corpus is absent. The device tests use the
checked-in fixture.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): module map, how tools share values, projects
(plain JSON files) and the single Settings screen, and how to add a tool.
