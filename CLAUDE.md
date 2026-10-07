# NodeNote fork — braindump boards for Ben & Nick

Forked from NodeNote (`Documents\nodeNote`) at 42b2845. Now a shared, node-based chat/issue board
for process improvement at the machine shop: per-node chats, 1–10 importance on key details,
issue status tracking, Obsidian vault mirror. Desktop + browser clients, one server over Tailscale.

- Remote `origin` = github.com/DeltaPlusProtoTyping/NodeNote-Boards (private, default branch
  `boards-and-chat`); `upstream` = NodeNote on GitHub, fetch-only (push disabled).
- Releases: push a `v*` tag; `.github/workflows/release.yml` builds the MSI/DMG/DEB (server and
  browser version built in, `-PwithWeb`) plus `NodeNote-Server-<tag>.zip` onto a GitHub Release.
- Windows `upgradeUuid` already changed so this app's MSI won't replace NodeNote's.
- Still named NodeNote (final name undecided): `packageName` / `bundleID` in
  `shared/build.gradle.kts`, client prefs dir `~/.nodenote` (`ProjectStorage.desktop.kt`),
  `com.nodenote` package.

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
  `-PwithWeb` bundles it via `tools/prepare-web.mjs` (Node from `~/.gradle/nodejs`): `.br`/`.gz`
  copies served by `preCompressed` in `Main.kt`, plus wasm preload hints in index.html.
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
  pass; add new ones. No nullable fields on board models: `server/.../PatchMerge.kt` keeps any
  field a client didn't send (an old tab must not reset `Node.isPrivate`/`effort`/`scale`), and
  with `explicitNulls = false` a null would read as "not sent".
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
- Kotlin/Wasm's regex engine rejects Unicode classes like `\p{L}` (PatternSyntaxException kills the
  whole page) — split text with `Char.isLetterOrDigit()` instead. Test web changes in the browser.
- Sorting: `core/.../sort/NodeMatcher.kt` (BM25 over node text + filed messages) and
  `BoardHierarchy` (parents = BFS from the most-connected Goal). Old open tabs reload themselves
  when `/api/info` reports a new `webBuild`; tabs from before 10/1 don't, hence PatchMerge.
- Cards: `Node.scale` multiplies the whole card (width/height include it; NodeCard lays out at
  width/scale and scales by zoom×scale, so drag deltas need ×scale). Heights auto-fit the title
  via `AppState.fitNodeHeight` — grow-only unless this device edited the title, so two clients
  measuring fonts differently can't ping-pong. A card's size IS its importance (0–12; 11–12 urgent):
  `core/.../model/ImportanceSize.kt` maps levels to scales, rating resizes around the centre,
  Size presets / corner drag set the importance, and `AppState.sizeCardsByImportance` puts a board
  in step on load. Never set `scale` without the matching importance. Effort stays 1–10.
- Exports: `core/.../export/MapScene.kt` builds one vector `Scene` (Helvetica metrics) that
  `SvgWriter`/`PdfWriter` write out and `MapExporter` rasterizes for PNG and the dialog preview.
  Private nodes are left out unless "Include private nodes" is ticked.
- Areas = `core/.../model/Area.kt` (`Project.areas`, synced as `upsertAreas`/`deleteAreas`; PatchMerge
  covers them). Membership is geometric (card centre inside), never stored. Effective privacy is
  `Project.privateNodeIds()` (private cards + cards in a private area): use it (or
  `AppState.privateIds`), not `Node.isPrivate`, wherever something is hidden or left out.
- Color by = `core/.../model/Lens.kt` (`ColorBy`, `Lens`): one place for card tints and legends, used by
  NodeCard, the canvas legend and MapScene. Person colors = `personColor` (chat author colors too).
- Tidy = `core/.../layout/RadialLayout.kt`; `AppState.tidyLayout` only lays out a selected area or
  selection once a board has areas (whole-map layout would scramble them). Spell check = `core/.../spell/SpellChecker.kt` over
  `server/src/main/resources/dict/en_US.txt` (built by `tools/build_dictionary.py` from
  LibreOffice's SCOWL en_US Hunspell files), served at `/api/dictionary`; bump
  `DICTIONARY_VERSION` in `ApiClient.kt` when it changes.
- The Bash tool mangles `\\n` and apostrophes inside `python - <<'PY'` heredocs: write edit
  scripts to the scratchpad with the Write tool and run them.
- Claude's sandbox virtualizes `%APPDATA%`; don't launch the app from Claude tools for real data.
  To try the packaged app, give it a throwaway home: `JAVA_TOOL_OPTIONS=-Duser.home=<scratch>` and a
  `<scratch>/.nodenote/server_url.txt` with a free port (e.g. `http://localhost:8872`).
- The desktop app has the server built in (`desktopMain/.../LocalServer.kt`): at launch it starts
  one when its server address is this computer and that port is free, else it uses the server
  already running (Ben's PC: the scheduled one). Data `~/.nodenote-server`; the vault comes from
  `--vault`, else the folder remembered in `<data>/vault-path.txt`. A fresh server's first account
  is made on the sign-in screen (`POST /api/auth/setup`, refused once any account exists).
