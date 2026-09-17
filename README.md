# Mind Journal — ქართული დღიური

A privacy-first Android journal that asks you short Georgian questions at unpredictable-but-evenly-spread
moments during the day, and lets you answer straight from the notification — without ever opening the app.

- **Kotlin + Jetpack Compose**, MVVM over a Clean-ish layering (`data` → `repository` → `ui`)
- **Room** for local storage, **EncryptedSharedPreferences** for the API key
- **Georgian-first UI**, typography tuned for Mkhedruli (17sp body / 25sp leading)
- **Zero-data-retention LLM calls** to OpenRouter (`anthropic/claude-3.5-haiku` by default)
- **minSdk 26 · targetSdk 34 · compileSdk 34 · JDK 17**

---

## The three pieces worth reading first

### 1. Spaced-random scheduling — `notification/NotificationHelper.kt`

`calculateSpacedRandomTimes(start, end, count, dayStartMillis)` divides the configured window into
`count` equal chunks and draws exactly one time inside each chunk, keeping a 12% padding at every
chunk edge and enforcing a 10-minute minimum gap. That is what stops the "two prompts three minutes
apart, then six hours of silence" failure mode of naive uniform sampling. Windows that end before
they start (22:00 → 02:00) wrap past midnight.

Scheduling itself:

- one `AlarmManager` exact alarm per slot (`setExactAndAllowWhileIdle`), falling back to
  `setWindow` when the user has not granted *Alarms & reminders* on Android 12+
- a planner alarm at 00:05 re-chunks the next day
- `NotificationReceiver` re-plans after boot, package replacement and time/timezone changes
- a 12-hour `WorkManager` job as a safety net against OEM battery managers dropping alarms

Covered by `app/src/test/java/.../NotificationHelperTest.kt` (one slot per chunk, strictly
increasing, no clustering, midnight wrap, window bounds).

### 2. Inline lockscreen replies — `notification/InlineReplyReceiver.kt`

The prompt notification carries a `RemoteInput` action with a **mutable** `PendingIntent` and
`VISIBILITY_PUBLIC`, so the text field is usable on the lockscreen. `InlineReplyReceiver` keeps the
broadcast alive with `goAsync()`, writes to Room off the main thread, then rebuilds the same
notification as a quiet **„შენახულია ✓"** confirmation (with the saved text as reply history) that
times out on its own. AI tagging is kicked off afterwards on the repository scope, so a failed
network call never affects whether the entry was saved.

### 3. ZDR OpenRouter client — `data/remote/OpenRouterClient.kt`

Every request sends:

```
Authorization: Bearer <key>          // read from EncryptedSharedPreferences
HTTP-Referer:  https://github.com/mind-journal/android
X-Title:       Mind Journal
{ "provider": { "data_collection": "deny" } }
```

Two calls are used: per-entry Georgian topic-tag extraction (`#მუშაობა`, `#დაღლილობა`, …) and a
weekly reflection over the trailing seven days. Failures leave `analyzed = 0` so the entry is simply
retried later.

---

## Design system

| Token | Value |
|---|---|
| Background | `#121212` (deep matte gray) |
| Card surface | `#1E1E1E` |
| Card border | `#2C2C2C`, 1px hairline |
| Corner radius | `14dp` |
| Body text | `#E0E0E0`, `bodyLarge` 17sp / 25sp |
| Accents | Cyan `#00E5FF` · Green `#00E676` · Amber `#FFC400` · Purple `#D500F9` · Coral `#FF6E40` |

The app is dark-only by design and never follows the system light theme.

---

## Build

The repository intentionally does not commit `gradle/wrapper/gradle-wrapper.jar` (a binary). Generate
the wrapper once, then build normally:

```bash
gradle wrapper --gradle-version 8.9
```

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Local builds need an Android SDK — either open the project in Android Studio (Koala or newer) or
point `local.properties` at one:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

CI (`.github/workflows/build.yml`) installs JDK 17 + the Android SDK, generates the wrapper, runs
the unit tests, and uploads both the debug and release APKs as artifacts. The release build is
signed with the debug keystore so the artifact is installable without any repository secrets —
replace `signingConfig` in `app/build.gradle.kts` before shipping to a store.

## Setup in the app

1. **პარამეტრები** → paste an OpenRouter API key (stored encrypted; AI features stay off without it).
2. **გრაფიკი** → set the daily window and how many prompts you want, then save. Grant
   *notifications* and, on Android 12+, *Alarms & reminders* from the warning cards.
3. Answer the prompts from the notification, or add entries with the **+** button.
4. **ანალიზი** → topic tags and the weekly reflection.

## Layout

```
app/src/main/java/com/journal/app/
├── MainActivity.kt                  # Compose host, bottom nav, snackbar, permission request
├── JournalApplication.kt            # singletons, channel creation, plan-on-start
├── data/
│   ├── local/                       # Room entity/DAO/db + EncryptedSharedPreferences
│   ├── remote/OpenRouterClient.kt   # ZDR chat client
│   └── repository/                  # single source of truth, AI enrichment
├── notification/                    # spaced-random engine, alarm + inline-reply receivers
└── ui/
    ├── JournalViewModel.kt
    ├── theme/                       # Color / Type / Theme
    ├── components/EntryCard.kt      # entry card, section card, tag chips
    └── screens/                     # Home, NotificationConfig, Insights, Settings
```
