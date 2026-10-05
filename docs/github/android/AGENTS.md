<!-- Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/). -->

# AGENTS.md — conventions for this repository

Read this before you change anything. It is injected into every agent prompt, so it is short on
purpose: it answers the questions you would otherwise guess at.

## Stack

- **Android**, Kotlin, **Jetpack Compose** with **Material 3**. Min SDK 24, JDK 17.
- Single-Activity: `MainActivity` hosts Compose, one navigation root, no Fragments.
- Networking: **Retrofit + OkHttp + kotlinx.serialization**. No Gson, no Moshi.
- Async: **coroutines + Flow**. No RxJava, no `AsyncTask`, no callbacks on the UI layer.
- UI language is **Persian (fa-IR)** and the layout direction is **RTL**.
- A web product, if this repo has one, lives in `web/` and is published by `preview-web.yml`.

## Build and test — the exact commands

```
./gradlew assembleDebug      # what CI and the TEC build gate run; this must pass
./gradlew testDebugUnitTest  # host unit tests
./gradlew lint               # Android lint
```

If `./gradlew` does not exist yet, this repo has no Gradle project and your change is the one
that creates it. Never edit `gradle/wrapper/` or `gradlew` by hand.

## Where each kind of file lives

```
app/src/main/java/<package>/
├── MainActivity.kt              ← the only Activity
├── data/model/                  ← @Serializable data classes, enums, no logic
├── data/repository/             ← business logic; the only place that talks to network/
├── network/<service>/           ← one Retrofit interface + its models per service
├── ui/<feature>/                ← one folder per screen: XScreen.kt + XViewModel.kt
├── mia/design/                  ← THE DESIGN SYSTEM: Tokens.kt, MiaTheme.kt, MiaComponents.kt,
│                                  ExampleScreen.kt. Read ExampleScreen.kt before your first screen.
└── security/, text/, voice/     ← small single-purpose helpers
app/src/main/res/values/strings.xml   ← every user-visible string
app/src/test/java/<package>/…         ← unit tests, mirroring the main source tree
README.md                             ← what this is, and how to build and run it
```

New file goes in the folder its neighbours are already in. Do not invent a layout.

## Hard rules

1. **Screens are assembled from `mia/design/`, not designed.** Use `MiaScaffold`, `MiaCard`,
   `MiaButton` (PRIMARY / SECONDARY / DANGER), `MiaTextField`, `MiaEmptyState`, `MiaError`,
   `MiaSkeleton`, `MiaChip`, `MiaRow` — with `MaterialTheme.colorScheme.*` for colour and the
   tokens in `Tokens.kt` (`Spacing`, `Radius`, `Sizes`, `Elevation`, `Motion`) for every number.
   **No `Color(0x…)`, no bare `.dp` or `.sp`, and no bare Material component in a `*Screen.kt`.**
   CI greps every `*Screen.kt` for exactly this and fails the build naming the line. If a token you
   need is missing, add it to `Tokens.kt` — never inline the value.
2. **No hard-coded user-visible strings.** They go in `strings.xml` and are read with
   `stringResource(R.string.…)`. Persian text, not English.
3. **RTL is set once, by `MiaTheme` at the root.** Never re-apply `LayoutDirection.Rtl` inside a
   screen — it mirrors twice and comes out LTR again. Do give a *specific block* (code, a table,
   English) its own LTR direction when it would otherwise mangle.
4. **One ViewModel per screen.** State is a single `StateFlow<XUiState>` the screen collects;
   one-shot events (snackbars, navigation) go through a `Channel`. Composables get state and
   lambdas — they never touch a repository or Retrofit API directly.
5. **Every screen has four states**: loading, empty, error-with-retry, content. A screen that
   only renders the happy path is not finished.
6. **No new dependency** unless the change is impossible without it. Say why in the PR body. If
   you do add one, add it to `gradle/libs.versions.toml` — never a bare coordinate in
   `build.gradle.kts`.
7. **Secrets never land in the repo.** No API keys, tokens or URLs with credentials in source,
   in tests, or in committed resources. They come from Actions secrets or `local.properties`.
8. **Stay inside the issue.** Do not reformat files you did not need to change, do not rename
   things nobody asked you to rename, and never touch `.github/` unless the issue names a path
   there. A small diff that builds beats a large one that does not.
9. **Test the logic you add.** New repository or parsing code gets a host unit test next to the
   existing ones. UI-only changes do not need one.
10. **The README says how to run this, and it stays true.** `README.md` carries an **`## اجرا`
    ("How to run")** section: the exact commands that take someone from a fresh clone to the app
    on a device or emulator — `./gradlew assembleDebug`, how to install the APK, any
    `local.properties` key or Actions secret the build needs, and which JDK. Real copy-pasteable
    commands, never a placeholder. If that section is missing, add it in the pull request you are
    working on now, even if the issue did not ask. If your change alters how the project is built,
    configured or run — a new Gradle task, a new `local.properties` key, a new permission that has
    to be granted by hand, a changed `minSdk`, a new signing step — that section changes **in the
    same pull request**. A change that breaks the documented way to run the app is unfinished, not
    "documented later". `README.md` is always in scope for this, even when the issue lists the
    files you may touch and the README is not one of them. If nothing about running it changed,
    leave the README alone.
11. **Comments explain WHY, not what.** Match the density and voice of the file you are editing.

## How to add a new screen

1. Read `mia/design/ExampleScreen.kt`. It is this recipe, already written, in one file.
2. Create `ui/<feature>/` with `<Feature>Screen.kt` and `<Feature>ViewModel.kt`.
3. In `<Feature>UiState` (same file as the ViewModel) model the four states explicitly — a data
   class with `isLoading` / `errorMessage` / content fields, like `ExampleUiState`.
4. In the ViewModel: `private val _state = MutableStateFlow(...)`, expose `val state = _state.asStateFlow()`,
   load in `init` or an explicit `load()`, and catch failures into the error state — never let an
   exception escape into the UI.
5. In the screen: `@Composable fun <Feature>Screen(state, onAction, onBack)`, wrapped in
   `MiaScaffold`. Take state and lambdas as parameters — never a ViewModel, or the previews in
   step 8 are impossible to write.
6. Add the strings to `res/values/strings.xml`.
7. Wire it into the navigation root next to the existing destinations.
8. Add one `@Preview` per state: content, loading, empty, error.
9. Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` before you finish.

## Issue and PR shape

Issues carry `## شرح`, `## مشخصات فنی`, `## راهنمای طراحی (UI/UX)` and
`## معیارهای پذیرش` (a checkbox list). Implement every acceptance criterion, and nothing that is
not in one. The PR body says what changed and why, and names any dependency you added.
