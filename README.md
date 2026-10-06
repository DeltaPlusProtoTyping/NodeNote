# NodeNote — braindump boards for process improvement

A shared, node-based workspace for two efficiency engineers at a machine shop. Map the shop
(machines, processes, quoting, customers, problems) as a graph of **nodes**; every node has its
own **chat**. Dump what you know, rate the **key details 1–10**, track **issues and improvements**
from *Open* to *Solved*, and have everything mirrored into an **Obsidian vault** as linked notes.

Runs as a **desktop app** (Windows/macOS/Linux) and in any **browser** — both talk to one small
server you host on your own PC and reach over **Tailscale**.

```
 desktop app ──┐                                    ┌─► SQLite (source of truth)
               ├── HTTPS (tailscale serve) ─► server├─► Obsidian vault (generated notes)
 browser app ──┘   REST + long-poll sync            └─► serves the browser app
```

## How it's organized

| Concept | What it is |
|---|---|
| **Board** | One map of related topics — *Shop floor*, *Estimating*, *Q4 improvements*. Listed on the left. |
| **Node** | A topic on the board: Issue, Improvement, Idea, Decision, Action, Project, Machine, Process, Tooling, Customer, Quote… Has a **status** (Open → In progress → Blocked → Solved / Parked), **importance** and **effort** 1–10, **owner**, and a **summary** of where it stands. The card shows the whole title and grows to fit it; drag its bottom-right corner (or pick a size) to make a node bigger. |
| **Mapping** | Select a node: **Tab** adds a child (placed on the side away from the goal, ready to type), **Enter** a sibling. While typing a new node's title, Enter starts the next sibling and Tab a child of it, so a branch can be typed out without the mouse. Click away from a new card and it stays, called "New Issue" (or whatever its type is) until you rename it; Escape drops it. The bar above the selected card sets type, status, rating, Private and size. **Tidy** lays out just the selected area (or the selected cards) around their hub and leaves the rest of the map where you put it; on a board without areas, a second click lays out the whole map around the goal (undo puts it back). |
| **Areas** | Named territories behind groups of cards (*Lead time*, *Sales pipeline*): select cards and press **Area** (`Ctrl+G`), use *+ Area* on a card's bar (the card and its children, named after it), or press Area with nothing selected and drag a box. A card belongs to the area it sits in, so dragging it in or out is all it takes; dragging an area's **name** or **border** moves the area with everything in it, and the grips on its corners and side middles resize it. Click the name or border for its bar: rename, color, Private (hides the whole area and its cards when presenting), fit to cards, tidy inside, remove (the cards stay). Zoomed far out, cards step back and the area names show big. |
| **Links** | Click a link on the map to say what it means: *Relates*, *Causes*, *Solves*, *Blocks*, *Part of* (or flip or delete it). Typed links get an arrow and their color. |
| **Color by** | The *Color:* switch in the map's top-right corner recolors every card by **status**, **importance**, **effort**, **person** (owner, else the first @name) or **No fix yet** (Issues with no *Solves* link: red; a fix on the map: amber; solved: green). A legend explains the colors; exports use the same colors. |
| **Chat** | Select a node → its thread opens on the right. Messages have a **kind** (Note, Issue, Idea, Decision, Action, Fix) and an optional **importance 1–10**. Actions get a done checkbox. |
| **Links across nodes** | Type `[[` in a message to link another node. The message then also shows in that node's chat ("mentioned in"), and the link is clickable. |
| **Dump & sort** | With nothing selected, the chat box is a dump bar: type a thought and press Enter, and it goes to **Unsorted**. As you type it suggests where the thought belongs; tap one to file it there directly. The **Sort** tab works through the Unsorted pile: each thought gets its best-matching nodes (with their parent branch) as one-tap chips, plus *Other node…* and *New node under…*, which hangs a new node off the right branch. Nothing is filed automatically. Matching is keyword relevance over titles, the node **Keywords** field, descriptions and everything already filed under each node, so it improves as you sort. |
| **Board feed** | Nothing selected = every message on the board, tagged with its node; filter by importance (5+/7+/9+), kind, or text. |
| **Priorities** | *Quick wins*: open work on a PICK chart (importance 6+ and effort 4 or less = quick win; big bets, fill-ins, rethink), plus what still needs rating. **Rate by placing** (toolbar *Rate*) shows every open card on an importance × effort board: drag a card up for more important, right for a bigger job, and both ratings are set in one go. *Wins*: every Solved node with the date it was solved — copy the list for an email. *Key details*: messages rated at/above a threshold. |
| **Private & presenting** | Mark a node or a whole area **Private** (the lock). **Present** hides private nodes (and everything in a private area), their links and their chat everywhere on that screen until you stop; exports leave them out unless you tick *Include private nodes and areas*. |
| **People** | Type `@Kurt` in a title or message. Everyone named gets a chip above the node list; clicking it shows every node and message that mentions them (with or without the @). |
| **Spelling** | Titles and messages get red squiggles under typos; the bar above a selected node offers the fix, and the node list's *Typos* filter fixes them in one click each. |
| **Unread** | Nodes, the Chat tab, and the node list show what the other person posted since you last looked. |

Edits sync live: both people can move nodes and chat at the same time; changes merge per node.
Undo/redo still works locally.

## Running it

Requirements: **JDK 17+** (the Gradle wrapper fetches everything else).

### 1. The server (on the always-on PC)

```bash
./gradlew :shared:wasmJsBrowserDistribution     # build the browser app (once per update)
./gradlew :server:run --args="--vault 'C:/path/to/Obsidian/Vault/NodeNote'"
```

| Flag | Env var | Default |
|---|---|---|
| `--port` | `NODENOTE_PORT` | `8870` |
| `--host` | `NODENOTE_HOST` | `127.0.0.1` (only Tailscale / this PC can reach it) |
| `--data` | `NODENOTE_DATA` | `~/.nodenote-server` (SQLite database) |
| `--vault` | `NODENOTE_VAULT` | `~/Documents/NodeNote Vault` |
| `--web` | `NODENOTE_WEB` | `shared/build/dist/wasmJs/productionExecutable` if built |

For a self-contained install: `./gradlew :server:installDist -PwithWeb` →
`server/build/install/server/bin/server(.bat)`. On the way into the jar, `tools/prepare-web.mjs`
(run with the Node.js the Kotlin/Wasm build already downloaded) adds brotli/gzip copies of the
app and preload hints for its `.wasm` files, so a phone's first visit downloads about a quarter less
and starts on the big files at once.

### 2. Share it over Tailscale

```bash
tailscale serve --bg 8870
```

Everyone on the tailnet can now open `https://<pc-name>.<tailnet>.ts.net` in a browser (phone
layout included). Tailscale also tells the server who is connecting, which pre-fills your name.

### 3. The desktop app

```bash
./gradlew :shared:run                 # run it
./gradlew :shared:packageMsi          # Windows installer (.deb / .dmg on Linux / macOS)
```

On first launch it asks for your name; the server address (Settings, top-right) defaults to
`http://localhost:8870` — use the `https://….ts.net` address on other machines.

### 4. Accounts

Everyone signs in with a username and password. Change either one in **Settings** (click your
name, top-right). A rename also updates the author on your past messages and nodes. Admin
commands, run against the database (`--data` defaults to `~/.nodenote-server`):

```bash
server users                         # list accounts
server adduser <name> <password>     # create one
server passwd <name> <new password>  # reset a forgotten password (signs them out everywhere)
```

(`server` = `server/build/install/server/bin/server`, or `java -cp "lib/*" com.nodenote.server.MainKt`
inside `Apps
odenote-server`.) Passwords are stored as salted PBKDF2 hashes. Sessions last 60 days
of inactivity. Failed sign-ins are throttled (5 per username per 15 minutes).

### 5. Public access (Tailscale Funnel)

```bash
tailscale funnel --bg --https=10000 --set-path=/nodenote http://127.0.0.1:8870
```

This publishes the app at `https://master.impala-bass.ts.net:10000/nodenote/` for anyone with an
account; no Tailscale is needed on their side. Turn it off with
`tailscale funnel --https=10000 --set-path=/nodenote off`.

## The Obsidian vault

Generated by the server, a second or two after each change — **don't hand-edit** these notes;
change things in the app. Every note carries `nodenote: generated` in its frontmatter.

```
<vault>/Boards.md                   index of all boards
<vault>/<Board>/<Board>.md          open work table, all nodes by type, key details, recent activity
<vault>/<Board>/<Node title>.md     frontmatter (type, status, importance, owner, counts, tags),
                                    summary, connections, key details, action items, the full chat
                                    (each message has a ^block id), and "mentioned in"
<vault>/<Board>/Inbox.md            unfiled braindumps
```

A board keeps its folder when it's renamed, so links you make to its notes keep working. Node
frontmatter also carries `effort`, `solved` (date), `area` and `private: true` (also for cards in a
private area); the board note shows the map, lists the areas with their cards, quick wins and wins.

Frontmatter makes the notes queryable with Obsidian Bases/Dataview (e.g. every open Issue with
importance ≥ 7 across boards).

## Your data survives updates

- **The data lives outside the app.** Boards and chats are in `~/.nodenote-server/nodenote.db`
  (SQLite). Rebuilding or redeploying the app never touches that folder.
- **Automatic backups.** A full copy goes to `~/.nodenote-server/backups/` every time the server
  starts (so before any new build reads the data) and every 24 h after that. The newest 30 are
  kept. To restore, stop the server and copy a backup over `nodenote.db`.
- **Board history.** Before an edit changes a board, its previous state is saved, at most once
  every 30 minutes.
- **Versioned database.** Schema changes run as numbered migrations. An older build refuses to
  open a database written by a newer one, so it can't damage it.
- **Format tests.** `core` has golden samples of today's saved JSON. The build fails if a change
  would stop old boards or messages from loading.

## Exporting the map

*Export → Map as PDF, SVG or PNG.* PDF and SVG are vectors: text and lines stay sharp at any zoom
and any size. PDF opens on every computer and phone; SVG opens in any browser and imports into
PowerPoint, Word and Visio. PNG is for pasting into a chat. Areas are drawn behind their cards, and
the cards take the map's *Color by* colors. Private nodes and areas are left out unless you
include them.

## Keyboard

| Action | Shortcut |
|---|---|
| New child / new sibling of the selected node | `Tab` / `Enter` |
| Rename the selected node / area | `F2` (or double-click) |
| Area around the selected cards (none selected: drag a box) | `Ctrl+G` |
| Send message / new line | `Enter` / `Shift+Enter` |
| Link a node in a message | `[[` then pick |
| Undo / Redo | `Ctrl+Z` / `Ctrl+Y` |
| Copy / Cut / Paste / Duplicate | `Ctrl+C` / `Ctrl+X` / `Ctrl+V` / `Ctrl+D` |
| Delete selection (cards, else the selected area or link) | `Delete` |
| Pan / zoom | middle- or right-drag / mouse wheel |
| Add node | double-click empty canvas |
| Cheat sheet | `F1` |

## Project structure

```
core/     models (Project = board, Node, Edge, Message), BoardPatch diff/merge, wire DTOs (api/)
shared/   Compose UI — desktop (JVM) and browser (Kotlin/Wasm) from one codebase
  state/    Session (server + identity), Workspace (tabs, boards), AppState (one board),
            BoardSync (push/long-poll/rebase)
  ui/       MainScreen, BoardsPanel, GraphCanvas, NodeCard, RightPanel, ChatView, DetailsView,
            CompactLayout (phones), dialogs
  net/      ApiClient (Ktor client)
server/   Ktor + SQLite: REST API, long-poll sync, Obsidian writer, static web app
```

## Next up

- AI summaries: fill each node's **Summary** (and a board digest) from its thread and key details.
- Photos in chat (shop-floor pictures of parts, setups, faults).
- Search across all boards.
- Guest accounts that never receive private nodes (today Private hides them on screen and in exports).
