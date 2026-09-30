# NodeNote fork — braindump boards for Ben & Nick

Forked from NodeNote (`Documents\nodeNote`) at 42b2845. Now a shared, node-based chat/issue board
for process improvement at the machine shop: per-node chats, 1–10 importance on key details,
issue status tracking, Obsidian vault mirror. Desktop + browser clients, one server over Tailscale.

- Remote `upstream` = NodeNote on GitHub, fetch-only (push disabled). No `origin` yet — create a
  new GitHub repo before pushing.
- Windows `upgradeUuid` already changed so this app's MSI won't replace NodeNote's.
- Still named NodeNote (final name undecided): `packageName` / `bundleID` in
  `shared/build.gradle.kts`, client prefs dir `~/.nodenote` (`ProjectStorage.desktop.kt`),
  `com.nodenote` package, `.github/workflows/release.yml` (still desktop-only).

## Modules
- `core` (KMP: jvm, wasmJs, ios) — models + `BoardPatch` diff/apply/rebase + wire DTOs (`api/Api.kt`,
  which lists every endpoint). Tests: `gradlew :core:jvmTest`.
- `shared` (Compose MP) — UI for desktop (`jvm("desktop")`) and browser (`wasmJs`, module `app`).
- `server` (Ktor 3.1.3 + SQLite) — API, long-poll sync, Obsidian writer, serves the wasm build.

## Build / run
Kotlin 2.1.21, Compose MP 1.8.2, Ktor 3.1.3, Gradle 8.13, JDK 17 (PATH `java` is 25 — Gradle uses
JAVA_HOME/JDK 17).
- Server: `gradlew :server:run --args="--port 8870 --vault <dir>"` (serves the web app from
  `shared/build/dist/wasmJs/productionExecutable` if built). Tests: `gradlew :server:test`.
- Web app: `gradlew :shared:wasmJsBrowserDistribution` (slow — production webpack + binaryen).
- Desktop: `gradlew :shared:run`. MSI: `gradlew :shared:packageMsi`.
- Test with scratch `--data`/`--vault` dirs, never the real `~/.nodenote-server` or vault.

## Sync model (don't break it)
Clients never upload whole boards. `BoardSync` sends `BoardDiff.diff(syncedProject, project)` with
the base rev; server applies (idempotent upsert/delete by id) and returns the merged board when the
base was stale. Incoming boards are merged with `rebase()` (local edits + undo/redo stacks replayed
on the new base). Messages are separate rows with a global `seq`; clients long-poll
`/sync?rev&seq`.

## Data safety (Ben's standing requirement: updates must never corrupt entered data)
- Model changes: add fields only WITH defaults; never rename/remove a serialized field or enum
  value. `core/src/commonTest/.../SavedFormatTest.kt` holds golden samples — never edit them to
  pass; add new ones.
- DB changes: bump `Database.SCHEMA_VERSION` and append a migration; never edit a released step.
- Server backs up the DB on every start + daily (`~/.nodenote-server/backups/`, 30 kept).
- Real deployment: `C:\Users\Benjamin\Apps\nodenote-schedule.ps1` (task "NodeNote Server", port
  8870, vault `IdeaVerse\02 Areas\Work Boards`, tailscale serve https :8870). Port 8787 is taken
  by one of Ben's Python apps.
- Public: Tailscale Funnel `https://master.impala-bass.ts.net:10000/nodenote/` → 127.0.0.1:8870
  (Funnel ports are only 443/8443/10000, all used; `/` on :10000 is another app on 8092). The web
  client derives its API base from the page path, so the app works under a path prefix.
- Auth: every /api call except /api/info and /api/auth/login needs a bearer session. Accounts are
  managed in the app (Settings) or via `MainKt users|adduser|passwd`. Never test with real accounts.
- Git Bash rewrites args like `/nodenote` into `C:/Program Files/Git/nodenote` — run
  `tailscale serve/funnel --set-path` from PowerShell.

## Gotchas
- `pointerInput` must be keyed on the `AppState` (not `Unit`) — tabs swap AppState instances,
  and a `Unit` key freezes handlers onto the old one (pan/zoom went dead).
- File pick/save are callback-based (`ProjectStorage.pickFile/saveFile`) because the browser is async.
- KDoc containing `/*` (e.g. `"image/*"`) opens a nested comment in Kotlin and breaks the file.
- Compose wasm renders its own fonts: avoid emoji / unusual symbols in UI text.
- Claude's sandbox virtualizes `%APPDATA%`; don't launch the app from Claude tools for real data.
