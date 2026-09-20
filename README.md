# Mind Journal — ქართული დღიური

A privacy-first Android journal that asks you short Georgian questions at unpredictable-but-evenly-spread
moments during the day, and lets you answer straight from the notification — without ever opening the app.

- **Kotlin + Jetpack Compose**, MVVM over a Clean-ish layering (`data` → `repository` → `ui`)
- **Room** for local storage, **EncryptedSharedPreferences** for the API key
- **Georgian-first UI**, typography tuned for Mkhedruli (17sp body / 25sp leading)
- **Zero-data-retention LLM calls** to OpenRouter, with a separate model per task
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

**Three prompts a day by default** (`NotificationConfig.DEFAULT_DAILY_COUNT`), not five. A prompt
that gets ignored teaches you to ignore the next one, and three across a twelve-hour window still
leaves a four-hour gap — often enough to catch the shape of a day, sparse enough that each one is
still worth reading. The slider still goes to 12.

Covered by `app/src/test/java/.../NotificationHelperTest.kt` (one slot per chunk, strictly
increasing, no clustering, midnight wrap, window bounds), `PromptBankTest.kt` (bank integrity,
reroll never repeats), `JournalExporterTest.kt` (export round-trip fidelity, reflection source
links), `FuzzySearchTest.kt` (the scoring ladder), and `AiSettingsTest.kt` (shipped defaults,
model slugs, tag normalisation).

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

**Tapping the body** opens the app *into the new-entry sheet with that notification's question
already at the top*, rather than dumping you on the timeline to press **+** and remember what was
asked. The content `PendingIntent` carries `EXTRA_OPEN_COMPOSER` plus the prompt;
`MainActivity.consumeLaunchRequest` turns it into a one-shot `LaunchRequest` (a bumped sequence
number, so a second tap still registers) and clears the extras so a rotation does not replay the
navigation. The sheet's open/closed state therefore lives in `JournalViewModel`, not in
`HomeScreen` — it has to be openable from outside the composition. Because **🔄 შეცვლა** re-posts
the same id with a different question, the composer intent is rebuilt with `FLAG_UPDATE_CURRENT`
so the body tap and the inline reply always agree on what is being asked.

**Long text.** The platform truncates any notification `CharSequence` at
`Notification.MAX_CHARSEQUENCE_LENGTH` (5 KiB) and drops the rest, so the risk is an ugly banner,
not a crash. What actually breaks the collapsed row is a newline. Every one-line field — title,
reply history — goes through `oneLine()`, which collapses whitespace runs and caps at 120 chars;
expanded bodies go through `bounded()` at 1 800 and are rendered with `BigTextStyle`, which is
built to scroll. None of this touches storage: the entry is already committed to Room before the
confirmation is built, so the caps are purely cosmetic. Android exposes no API to request a
multi-line inline-reply editor — the field accepts newlines from the keyboard and grows on its
own, but its height is the shade's decision.

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

**One model per task.** The three calls have opposite cost profiles, so `AiSettings` carries two
slugs and `AiTask` maps calls onto them:

| Task | Slot | Default | Why |
|---|---|---|---|
| `TAGS` | `tagModel` | `openai/gpt-4o-mini` | Runs on **every** entry for a 20-token answer. Essentially all the spend lives here, and two Georgian nouns is the least model-sensitive thing the app asks for. |
| `PROMPT` | `tagModel` | — | Same shape of work — one short sentence, many times. A third dropdown would explain a difference nobody can see. |
| `REFLECTION` | `reflectionModel` | `anthropic/claude-haiku-4.5` | Runs **once a week** for a few hundred words the user actually reads. The one call where a stronger model is both visible and nearly free. |

Upgrading carries a pre-split `openrouter_model` into *both* slots rather than replacing it with
the new defaults — a stored slug was an explicit choice, and silently moving someone off the model
they picked would be worse than not splitting at all. Only a user who never touched the setting
gets the split defaults.

**Models.** The picker ships five verified-present, inexpensive slugs, ordered by Georgian quality
rather than price — Mkhedruli is low-resource and the cheapest open models degrade on it. Own slugs
can be typed in and are persisted into the list. The current catalogue:

| Slug | Price /1M | Note |
|---|---|---|
| `anthropic/claude-haiku-4.5` | $1.00 → $5.00 | best Georgian balance; reflection default |
| `google/gemini-2.5-flash` | $0.30 → $2.50 | |
| `openai/gpt-5-mini` | $0.25 → $2.00 | |
| `openai/gpt-4o-mini` | $0.15 → $0.60 | cheapest reliable option; tag default |
| `mistralai/mistral-small-2603` | $0.15 → $0.60 | Mistral Small 4 |

`mistralai/mistral-small-2603` replaced `mistralai/mistral-small-3.2-24b-instruct`: Small 4
(released 2026-03-16) is the current small model, and at $0.15 → $0.60 it now matches GPT-4o mini
rather than undercutting it. A user who had explicitly selected the 3.2 slug keeps it — it still
resolves on OpenRouter, it is simply no longer offered.

### 3b. Background reflections — `notification/WeeklyReflectionWorker.kt`

The weekly reflection used to exist only if you happened to press the button. A 7-day
`PeriodicWorkRequest` with a `CONNECTED` constraint now generates it in the background and posts
**„შენი კვირის რეფლექსია მზად არის"** on a separate, quieter channel (`IMPORTANCE_DEFAULT`,
`VISIBILITY_PRIVATE` — a summary of someone's journal does not belong on a lockscreen); tapping it
lands on the **ანალიზი** tab.

`WorkManager`, not `AlarmManager`, precisely because this one is allowed to be late: it needs the
network, takes real time, and nobody is waiting on it. Prompts keep using exact alarms for the
opposite reason. An empty week returns `Result.success()` rather than `retry()` — re-running every
few minutes would burn battery to reach the same conclusion. It is enqueued with `KEEP`, so
opening the app does not reset the period and push the reflection permanently out of reach.

### 3c. Reflection provenance — `data/local/Reflection.kt`

Reflections live in their own Room table (schema **v3**) instead of a single overwritten
preference string, so each one keeps its period, the model that wrote it, and — the point —
`source_entry_ids`: the ids of the entries actually sent in that request. Not "everything in the
window", which would be a guess.

The ids are a comma-separated list, **not** a foreign key. A reflection is a record of what was
sent; a real FK would either block deleting a source entry or cascade the link away, and the
export would then claim the reflection came from fewer entries than it did. The UI shows the
difference honestly: "3 source entries" with a note that two of them have since been deleted.

Migration 2 → 3 creates the table and moves the one legacy preference reflection into it with an
empty source list — pre-v3 storage never recorded them, and an empty list is more truthful than a
plausible reconstruction.

**Where a reflection is shown, and why not in full.** A 150–220 word Georgian reflection at
`bodyLarge` 17sp/25sp is roughly a screen and a half. The weekly worker makes ~52 a year, so
rendering them all turned **ანალიზი** into an archive you had to scroll past to reach anything
else. Three surfaces now, each showing a different amount:

- **Timeline** — a slim accent-tinted `ReflectionMarker`, placed on the day it was *generated*
  and labelled with the week it *covers*. Deliberately a signpost, not a card: a wall of AI prose
  every seventh scroll position would bury the entries the journal is for. Tapping opens it in a
  sheet rather than jumping tabs, which would throw away the scroll position. Markers vanish the
  moment any filter is active — a reflection has no tags, so a tag filter could never match one,
  and an unranked row among relevance-ranked results is a lie about the ordering.
- **ანალიზი** — the latest in full, then three collapsed `ReflectionRow`s and `ყველას ნახვა (N)`.
  Fixed height regardless of how many exist.
- **History sheet** — the whole archive, grouped by month, every row collapsed to one line that
  expands in place. Fifty one-line rows is scannable; fifty full reflections is eighty screens.

The tag cloud got the same treatment for the same reason: `tagCounts` is every distinct tag ever
and the model emits 2–4 per entry, so a year in there is a long tail used exactly once. It shows
the top twelve by frequency with `კიდევ N თემა` to expand.

Neither is a performance fix — `LazyColumn` only composes what is visible. Both are findability
fixes.

**Truncation is the renderer's job where there is one.** Source citations and the delete-dialog
preview use `maxLines` + `TextOverflow.Ellipsis`, so the `…` lands where the text actually stops
fitting on that device rather than at an arbitrary character. The Markdown export has no
renderer, so it cuts by character — but marks the cut, because an export that silently presents
a truncated entry as the whole thing is worse than one that is visibly abridged.

### 4. Search, filtering and the calendar

**Fuzzy search** — `data/search/FuzzySearch.kt`. Not `LIKE '%…%'`, and not FTS5. Georgian is
heavily inflected and has no capitals, so the form you remember is rarely the form you type
(`მუშაობა` in the entry, `მუშაობ` in the box); a substring match finds neither, and FTS5's
stemmers know nothing about Mkhedruli while its `unicode61` case folding is a no-op on a script
without case. So matching runs in memory over the list the feed already holds — a journal is
thousands of rows, not millions.

Every whitespace-separated token must match somewhere (AND, not OR — an OR search over free text
returns everything), scored on a ladder: whole-word 200, word-starts-with-token 120,
token-starts-with-word 110 (the inflection case, both directions), substring 100, and a bounded
Levenshtein match at 40–70. The edit budget scales with length — 0 below four characters, since a
typo in a three-letter word is indistinguishable from a different word — and the distance
function bails as soon as a row exceeds it. An exact phrase adds a large bonus on top, so it
always outranks scattered near-misses. While a query is active the feed switches from day groups
to a flat relevance-ordered list: day headers would claim an order the list does not have.

**Tag and date filters** — `FeedFilter` in `JournalViewModel`. The three dimensions compose
rather than replace: tag and date narrow the set, then the query ranks what is left, so "search
within this topic" reads the way it should. Each active filter gets its own dismissible chip
(dropping just the date after narrowing to `#ძილი` on the 3rd is the common next move) plus a
blanket **გასუფთავება**. Tags are tappable on every entry card and in the **ანალიზი** tag cloud —
from there the tap also switches tabs, because filtering a list you cannot see looks like a tap
that did nothing.

**Tags are editable** — `TagEditor` in `ui/components/EntryCard.kt`. Now that a tag decides where
an entry files, a wrong one is no longer cosmetic. Hand-edited tags set `analyzed = 1`, which is
what stops the background tagger reverting the correction; editing an entry's *text* still clears
and regenerates them, but only when the tags were left alone.

**Calendar heatmap** — `ui/components/JournalCalendar.kt`. A Monday-first month grid where each
day is tinted by entry count in four buckets, with the count printed under the date. Buckets, not
a ramp against the month's own maximum — that would make a quiet month look identical to a busy
one. Counts are grouped in Kotlin rather than SQL because which day an entry belongs to depends
on the device's current time zone, which SQLite cannot know. Days outside the month are blanks,
not greyed neighbours: a mis-tap would otherwise filter to a day you were not looking at.

### 5. Export & import — `data/export/`

Two formats, written through the Storage Access Framework (no storage permission):

- **JSON** — the backup. Round-trips losslessly: epoch-millis + ISO timestamps, prompt, AI tags,
  source, edit marker, analysis flag, and every reflection with its source entry ids. The only
  format still readable if this app disappears.
- **Markdown** — the reading copy, grouped by day, with the reflections and the entries behind
  them at the end. Sources are cited by timestamp, not row id — a number means nothing to a
  reader, `14 მარტი, 21:40` can be found in the timeline above.

Not CSV (entries are multi-line free text and every CSV consumer disagrees about embedded
newlines); not a raw `.db` copy (opaque and schema-locked).

Format **v2** adds the `reflections` array; v1 files (entries only) still import unchanged.

Import is **additive and idempotent**. Ids from the file are discarded so the local database
assigns its own; an entry whose timestamp and text already exist is skipped. Importing the same
backup twice changes nothing, importing an old backup alongside newer entries merges them, and an
import never deletes anything. The file is fully parsed and validated *before* a single row is
written, so picking the wrong file cannot damage the journal.

Reflection links survive the move. `ParsedBackup.sourceIds` reports each entry's *file* id
alongside the entries (kept out of the entries themselves, which stay at `id = 0` so an import
still cannot address a local row); the repository builds a file-id → local-id map as it inserts,
resolving skipped duplicates to the row already present, and re-points every reflection through
it. Ids with no local counterpart are dropped rather than kept as dangling numbers. This is why
import inserts one entry at a time instead of using `insertAll` — a backup is a few thousand
rows, once.

### 6. App lock — `ui/lock/AppLock.kt`

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

**Bottom-nav labels auto-size.** `პარამეტრები` is eleven Mkhedruli glyphs in a cell a quarter of
the screen wide. It fits at 11sp on a 1440px S24 Ultra and is a few pixels too wide on a narrower
or denser phone, where Compose wrapped it and pushed the trailing `ი` onto a second line — making
that one nav item taller than the other three. `maxLines = 1` with ellipsis would render
`პარამეტრებ…`, `softWrap = false` alone would let it run under its neighbour, and Georgian has no
abbreviation convention to shorten the string with. So `NavigationLabel` measures the text against
the cell it was actually given and steps the size down 0.5sp at a time to a floor of 8.5sp. Every
other label clears 11sp comfortably, so only the offending one changes, and only by as much as
that device needs. (Compose 1.7 has no `autoSize`, hence measuring by hand.)

---

## Installing — which APK

CI uploads **two** artifacts and they are *different apps*, not two copies of one:

| Artifact | Package | Size | Use it for |
|---|---|---|---|
| `mind-journal-release` | `com.journal.app` | ~2.5 MiB | **everyday use, and anything you hand to someone else** |
| `mind-journal-debug` | `com.journal.app.debug` | ~19 MiB | development only |

The `.debug` suffix (`applicationIdSuffix`, set since the first commit) exists so both can sit on
one phone during development. The size gap is just `isMinifyEnabled` + `isShrinkResources` being
release-only — unminified `material-icons-extended` is most of those extra 16 MiB.

**Pick one and stay on it.** Installing the other one does not update your app; Android sees an
unrelated package, installs it alongside, and you get an empty journal plus *two* copies both
firing prompts. Nothing is lost when this happens — the entries are still in whichever package
you were using — but the two never share data. To check what is actually on a phone:

```bash
adb shell pm list packages | grep journal
```

Switching packages on purpose means exporting a JSON backup from the old one and importing into
the new one. That moves **entries only**. The API key, schedule, accent, app lock and — for
backups written by v1.1.0 or earlier, whose format has no `reflections` array — the weekly
reflection all live in per-package storage and have to be set up again. Uninstall the old package
afterwards, or both keep scheduling notifications.

**Updates only install over the top if the signing key is unchanged.** After a CI run, expand
*Report signing identity* in the log and check the SHA-256 is the same as last time. If it drifts,
the `KEYSTORE_BASE64` secret is not set and CI is falling back to a cached generated key — see
*Making updates installable* below. That matters more once more than one person is installing
these builds, because a key change forces everyone to uninstall and re-import.

**Sharing a build.** The APK contains no personal data — the OpenRouter key is entered per device
and stored encrypted — so each person needs their own key, or they are spending yours. They will
also need to allow installs from unknown sources, since this is not a Play Store release.

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

1. **პარამეტრები** → paste an OpenRouter API key (stored encrypted; AI features stay off without
   it), then pick the tag model and the reflection model.
2. **გრაფიკი** → set the daily window and how many prompts you want (three by default), then save.
   Grant *notifications* and, on Android 12+, *Alarms & reminders* from the warning cards.
3. Answer the prompts from the notification — inline, or by tapping the body to open the app with
   that question pre-filled — or add entries with the **+** button.
4. **დღიური** → search, tap a tag to filter, or open the calendar to jump to a day.
5. **ანალიზი** → topic tags (tappable), the weekly reflection, and which entries produced it.

## Layout

```
app/src/main/java/com/journal/app/
├── MainActivity.kt                  # Compose host, bottom nav, notification deep links
├── JournalApplication.kt            # singletons, channels, plan-on-start, weekly worker
├── data/
│   ├── local/
│   │   ├── JournalEntry.kt          # entry + DAO, tag normalisation
│   │   ├── Reflection.kt            # reflection + DAO, source entry links
│   │   ├── JournalDatabase.kt       # Room v3, migrations 1→2→3
│   │   └── PreferenceManager.kt     # EncryptedSharedPreferences, AiSettings, AiTask
│   ├── remote/OpenRouterClient.kt   # ZDR chat client, per-task model routing
│   ├── search/FuzzySearch.kt        # Georgian-tolerant scoring
│   ├── export/                      # JSON v2 + Markdown, additive import
│   └── repository/                  # single source of truth, AI enrichment
├── notification/                    # spaced-random engine, receivers, weekly reflection worker
└── ui/
    ├── JournalViewModel.kt          # feed filter, composer state, AI actions
    ├── theme/                       # Color / Type / Theme
    ├── components/
    │   ├── EntryCard.kt             # entry card, section card, tag chips, tag editor
    │   └── JournalCalendar.kt       # month grid with per-day counts
    └── screens/                     # Home, NotificationConfig, Insights, Settings
```
