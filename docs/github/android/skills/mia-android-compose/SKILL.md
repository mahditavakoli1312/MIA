---
name: mia-android-compose
description: >
  Build and review an Android screen the way this repository builds them — the design system,
  the four states, the ViewModel contract, RTL, and what CI greps for. Use it for any change
  that touches Compose, a ViewModel or a string resource.
roles: [tec, qc]
stacks: [android]
always: true
---

# A screen in this repository

Screens here are **assembled from `mia/design/`, not designed**. Read `ExampleScreen.kt` before
your first one — it is this whole procedure already written, in one file, with previews.

## The recipe

1. `ui/<feature>/<Feature>Screen.kt` + `ui/<feature>/<Feature>ViewModel.kt`. Nothing else, and
   nowhere else.
2. `<Feature>UiState` lives beside the ViewModel and models the four states **explicitly** —
   `isLoading`, `errorMessage`, and the content fields. Not one nullable field doing three jobs.
3. The ViewModel owns a `private val _state = MutableStateFlow(...)` exposed as
   `val state = _state.asStateFlow()`, loads in `init` or an explicit `load()`, and catches every
   failure into the error state. An exception must never reach the UI.
4. The composable takes **state and lambdas** — `@Composable fun XScreen(state, onAction, onBack)`
   — never a ViewModel, never a repository, never a Retrofit API. If it takes a ViewModel the
   previews in step 7 cannot be written, which is how the four states stop being checked.
5. Wrap in `MiaScaffold`. Build from `MiaCard`, `MiaButton` (PRIMARY / SECONDARY / DANGER),
   `MiaTextField`, `MiaEmptyState`, `MiaError`, `MiaSkeleton`, `MiaChip`, `MiaRow`.
6. Every user-visible string goes in `res/values/strings.xml` and is read with
   `stringResource(R.string.…)`. Persian, not English.
7. One `@Preview` per state: content, loading, empty, error.
8. `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` before you stop.

## What CI greps for, and fails the build over

Every `*Screen.kt` is scanned. These are build failures, not review comments:

- `Color(0x…)` — colour comes from `MaterialTheme.colorScheme.*`.
- a bare `.dp` or `.sp` — every number comes from `Tokens.kt`: `Spacing`, `Radius`, `Sizes`,
  `Elevation`, `Motion`.
- a bare Material component where a `Mia*` one exists.

If a token you need does not exist, **add it to `Tokens.kt`**. Inlining the value is the one
thing this rule exists to prevent, and "just this once" is how the tenth screen stops matching
the first.

## RTL, once

`MiaTheme` applies `LayoutDirection.Rtl` at the root. Never re-apply it inside a screen — it
mirrors twice and comes out left-to-right again, which looks like a layout bug and is not one.
Do give a *specific block* its own LTR direction when it would otherwise mangle: code, a URL, an
English identifier, a table of Latin digits.

## The four states are not optional

| State | What must be on screen |
|---|---|
| loading | `MiaSkeleton` in the shape of the content, not a centred spinner on an empty page |
| empty | `MiaEmptyState`: what would be here, and the action that creates the first one |
| error | `MiaError`: what went wrong in Persian, and a **retry** that actually retries |
| content | the thing itself |

A screen that renders only the happy path is not finished, however many acceptance criteria are
ticked.

## Reviewing one

Read for the states first, then for hard-coded values, then for what the ViewModel swallows. A
`try { … } catch (e: Exception) { }` in a repository is the single most common defect here: it
turns a network failure into a permanently empty screen with no error and no retry.

Rotation and process death are real on Android and are forgotten in almost every first draft:
state in the ViewModel survives rotation, state in a `remember` does not.
