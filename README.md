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

Prompts come from [PromptBank](app/src/main/java/com/journal/app/notification/PromptBank.kt) — 14
conversational Georgian questions in four groups (ზრდა / კრეატივი / ფოკუსი / უცნაური). A day draws
from a shuffled bag, so with a 12-slot daily maximum a question never repeats within a day.

Covered by `app/src/test/java/.../NotificationHelperTest.kt` (one slot per chunk, strictly
increasing, no clustering, midnight wrap, window bounds), `PromptBankTest.kt` (bank integrity,
reroll never repeats), and `JournalExporterTest.kt` (export round-trip fidelity).

### 2. Inline lockscreen replies + reroll — `notification/InlineReplyReceiver.kt`

The prompt notification carries a `RemoteInput` action with a **mutable** `PendingIntent` and
`VISIBILITY_PUBLIC`, so the text field is usable on the lockscreen. `InlineReplyReceiver` keeps the
broadcast alive with `goAsync()`, writes to Room off the main thread, then rebuilds the same
notification as a quiet **„შენახულია ✓"** confirmation (with the saved text as reply history) that
times out on its own. AI tagging is kicked off afterwards on the repository scope, so a failed
network call never affects whether the entry was saved.

A second action — **🔄 შეცვლა** — swaps the question for a different one in place. It cancels and
re-posts the same notification id (so the new prompt re-alerts instead of silently updating), draws
from [PromptBank](app/src/main/java/com/journal/app/notification/PromptBank.kt) with a
guaranteed-different result, and never opens the app.

### 3. ZDR OpenRouter client — `data/remote/OpenRouterClient.kt`

Every request sends:

```
Authorization: Bearer <key>          // read from EncryptedSharedPreferences
HTTP-Referer:  https://github.com/mind-journal/android
X-Title:       Mind Journal
{ "provider": { "data_collection": "deny", "sort": "price" } }
```

Three calls are used: per-entry Georgian topic-tag extraction (`#მუშაობა`, `#დაღლილობა`, …), a
weekly reflection over the trailing seven days, and on-demand prompt generation for the quick-add
sheet. Failures leave `analyzed = 0` so the entry is simply retried later.

**Cost routing.** With low-priority mode on (the default), the model slug gets OpenRouter's
`:floor` variant suffix and the request carries `provider.sort = "price"`. Per OpenRouter's
provider-routing docs, `:floor` is "a superset of setting `provider.sort` to `price`" and
additionally "makes flex service tier endpoints eligible" — cheapest route, lower priority, still
synchronous. (The `:batch` variants are exactly half price but deliver asynchronously, so they are
deliberately not used: a journal prompt can't wait hours for its tags.)

**Models.** The picker ships five verified-present, inexpensive slugs, ordered by Georgian quality
rather than price — Mkhedruli is low-resource and the cheapest open models degrade on it. Default
is `anthropic/claude-haiku-4.5`. Own slugs can be typed in and are persisted into the list.

### 4. Export & import — `data/export/`

Two formats, written through the Storage Access Framework (no storage permission):

- **JSON** — the backup. Round-trips losslessly: epoch-millis + ISO timestamps, prompt, AI tags,
  source, edit marker, analysis flag. The only format still readable if this app disappears.
- **Markdown** — the reading copy, grouped by day, opens anywhere.

Not CSV (entries are multi-line free text and every CSV consumer disagrees about embedded
newlines); not a raw `.db` copy (opaque and schema-locked).

Import is **additive and idempotent**. Ids from the file are discarded so the local database
assigns its own; an entry whose timestamp and text already exist is skipped. Importing the same
backup twice changes nothing, importing an old backup alongside newer entries merges them, and an
import never deletes anything. The file is fully parsed and validated *before* a single row is
written, so picking the wrong file cannot damage the journal.

### 5. App lock — `ui/lock/AppLock.kt`

Deliberately **not** a home-grown PIN. It delegates to `BiometricPrompt` with `DEVICE_CREDENTIAL`
fallback: fingerprint or face when enrolled, otherwise the phone's own PIN / pattern / password.
This app stores no secret and implements no lockout logic of its own. The lock screen is opaque
and composed *instead of* the journal, so entry text never renders behind it.

Re-locks when the app has been in the background for more than a couple of seconds — long enough
that returning from the file picker or the biometric sheet itself doesn't re-prompt.

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
the unit tests, and uploads both the debug and release APKs as artifacts.

### Making updates installable (one-time setup)

Android rejects an update whose signing key differs from the installed app — that is why a new
APK previously had to be uninstalled before it would install. CI used to mint a throwaway debug
key on every runner, so every build had a different signature.

The build signs with `keystore/mindjournal.jks` when present. That file is **git-ignored on
purpose** — a signing key does not belong in a repository — so CI receives it through a secret.

The key already exists at `keystore/mindjournal.jks` (PKCS12, alias `mindjournal`, password
`mindjournal`, valid until 2054). Publish it to CI once:

```bash
base64 -i keystore/mindjournal.jks | pbcopy
```

Paste the clipboard into a repository secret named `KEYSTORE_BASE64` (Settings → Secrets and
variables → Actions → New repository secret). If you ever regenerate the key with a different
password or alias, also set `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

Without that secret CI falls back to a generated, cached key — which works until the cache is
evicted, at which point installs start failing again. The secret is the durable answer.

To recreate the key from scratch without a JDK, run
[`tools/generate_keystore.py`](tools/generate_keystore.py) — it writes an equivalent PKCS12
keystore using Python's `cryptography` package. Note that replacing the key changes the signing
identity, so installed builds would need one more uninstall.

Each run prints the key's SHA-256 fingerprint under "Report signing identity" — if that value is
stable across runs, updates will install. This is a self-signed personal key; a Play Store
release needs a properly protected one, never committed.

Because the signature changes once when you switch to a stable key, you will need to uninstall
one final time after this change.

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
