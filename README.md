# NodeNote

A node-based system documentation suite built with **Kotlin + Compose Multiplatform** — a
single tool to **build**, **document**, **edit**, **compare**, and **share** process and
system diagrams. Place nodes on an infinite canvas, connect them, annotate with text and
images, and save the whole project as human-readable JSON.

![Stack](https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-blue)

## Download

Prebuilt installers are on the **[Releases page](https://github.com/DeltaPlusProtoTyping/NodeNote/releases/latest)** — no build tools or Java required, the runtime is bundled:

| Platform | File |
|---|---|
| Windows | `NodeNote-<version>.msi` |
| macOS (Apple Silicon) | `NodeNote-<version>.dmg` |
| Linux (Debian/Ubuntu/Mint) | `nodenote_<version>_amd64.deb` |

The installers are unsigned, so on first launch you'll see a one-time prompt: Windows SmartScreen → **More info → Run anyway**; macOS → right-click the app → **Open**.

## Running the desktop app (Windows)

Requirements: JDK 17+ (the Gradle wrapper downloads everything else).

```
gradlew :shared:run
```

First build downloads Gradle and dependencies, so give it a few minutes.
To build a Windows installer: `gradlew :shared:packageMsi` (output under `shared/build/compose/binaries`).

## Current features

- **Canvas** — infinite-feeling dot-grid canvas; hold the middle mouse button (scroll wheel)
  and move to pan (touch drag on mobile), mouse-wheel to zoom (anchored at the cursor),
  toolbar zoom controls and reset view.
- **Nodes** — 11 engineering node types (System, Device, PCB, RF Path, Power Rail, Connector,
  Software Module, Note, Test Result, Requirement, Risk), each rendered as a rounded card with
  a type color. Drag to move, click to select (accent border highlight).
- **Inspector** (right panel) — edit title, type, description, position readout, free-form
  key/value properties, per-edge labels, delete node.
- **Attachments** — attach images, files, and text snippets to a node. Images show as
  thumbnails in the inspector, files show name/size with a *Save* (export) action, and text
  snippets are edited inline. Files and images are embedded in the project JSON as Base64
  (25 MB cap per file) so a saved project is one fully self-contained file. Nodes with
  attachments show a 📎 count on their card.
- **Connections** — select a node, press *Start connection*, then click the target node.
  A dashed line follows the cursor while connecting. Edges are drawn as grey arrows trimmed
  to the card borders; each edge has a type (Power, Data, Signal, RF, Mechanical, Custom)
  shown as a small colored connection node at the line's midpoint, with the optional label
  underneath. Type and label are edited per-connection in the inspector. Edges touching the
  selection are highlighted.
- **Multi-select** — Ctrl+click toggles nodes in and out of the selection; dragging a box on
  empty canvas selects everything inside it; dragging any selected card moves the whole group.
- **Align** — toolbar button that snaps all selected nodes to the nearest grid points.
- **Keyboard** — Ctrl+S save, Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) undo/redo, Ctrl+D duplicate,
  Delete removes the selection (all disabled while typing in a text field), Enter/Escape
  commit/cancel inline edits, Escape closes the export dialog.
- **Undo / redo** — up to 50 steps; structural changes and drags are snapshotted
  (per-keystroke text edits are not).
- **Canvas elements** — Insert ▾ adds free-floating **text blocks** (double-click to edit
  in place, font size adjustable in the inspector) and **images** (embedded as Base64 like
  attachments). Elements select, group-drag, align, duplicate, delete and export exactly
  like nodes; they just can't have connections.
- **Open Recent** — dropdown of the last 10 project files (persisted in
  `~/.nodenote/recent_files.json`); *Open File* shows the dialog as before.
- **Zoom to fit** — frames all content; sidebar also has a text search across node
  titles/descriptions.
- **Sidebar** (left) — project name, node list with type filter; clicking a node centers
  the canvas on it. Includes a one-click sample project.
- **Notes panel** (bottom) — larger multiline editor for the selected node.
- **Save / Load** — native Windows file dialogs, pretty-printed JSON via kotlinx.serialization.
- **Adjustable panels** — drag the inner border of the sidebar, inspector, or notes panel to
  resize it (the divider highlights on hover); the chevron in each panel header collapses
  the panel to a thin strip, and clicking the strip reopens it at its previous size.
- **PNG export studio** — the Export button opens a dialog with a live preview (checkerboard
  shows transparency): crop to current view / all content / selection with adjustable padding,
  1–3× resolution, dark or light rendering theme, transparent or solid background with optional
  grid. The **legend and project title block are dragged into place directly on the preview**;
  the legend's size and title are editable, the project can be renamed in-dialog, and there are
  toggles for connection labels / node descriptions. Named presets (save, update/rename, delete)
  persist to `~/.nodenote/export_presets.json`.
- **Share** — alongside saving a PNG, the export dialog can **copy the rendered image straight
  to the OS clipboard** (same theme/legend/crop settings) for one-click paste into chat, email,
  or docs.
- **Compare** — the Compare button diffs the open project against any project file and shows a
  read-only summary: nodes/connections added, removed, or changed (matched by id, so a rename
  reads as "changed", not add+remove). The open project is never modified.
- **Unsaved-changes awareness** — the window title shows `NodeNote — <name> •` and the status
  bar flags `unsaved` whenever there are changes since the last save.
- **Shortcuts help** — the `?` button (or **F1**) opens a cheat sheet of every keyboard and
  mouse shortcut.
- **Status bar** — last action, unsaved flag, node/edge counts, zoom level.

## Project structure

```
shared/src/commonMain/kotlin/com/nodenote/
  App.kt                     # shared entry point
  model/                     # Project / Node / Edge / NodeType — plain @Serializable data classes
  state/AppState.kt          # all app state: one class, Compose mutableStateOf, no frameworks
  ui/                        # MainScreen, TopBar, LeftSidebar, InspectorPanel, GraphCanvas, NodeCard, Widgets
  storage/                   # ProjectSerializer (JSON) + ProjectStorage (expect)
  theme/AppTheme.kt          # dark palette + per-type colors
shared/src/desktopMain/      # main.kt window + ProjectStorage actual (AWT file dialogs)
shared/src/iosMain/          # MainViewController + ProjectStorage actual (Documents dir)
```

The canvas transform is `screen = world * (zoom * density) + pan`. Node positions are stored in
density-independent world units, so saved projects lay out identically across displays/platforms.

## iOS status

The `shared` module declares `iosX64` / `iosArm64` / `iosSimulatorArm64` targets and exports a
static framework named `shared`, with a `MainViewController()` entry point and a Documents-folder
storage actual already in place. Building requires a macOS host with Xcode; the Xcode app project
itself is not included yet. On Windows the iOS targets are simply skipped.

## Known limitations

- Node size is fixed (no resize handles yet; element size is adjustable via inspector sliders).
- Undo covers structure and moves, not per-keystroke text edits.
- Edge labels/types are edited in the inspector, not on the canvas.
- One project open at a time; no recent-files list; no unsaved-changes warning.
- Attachments are embedded as Base64, so projects with many large files get big and the JSON
  is less pleasant to read; no click-to-enlarge image viewer yet.
- iOS save/load uses a fixed `project.json` in the app's Documents directory (no document
  picker), and attaching new files is desktop-only for now (existing attachments still
  display and text is editable on iOS).
- Layout is fully manual (by design for V1 — no auto-layout).

## Next recommended features

1. Node resize handles (model already stores width/height).
2. Confirm-on-discard dialog when closing with unsaved changes (dirty state is already tracked).
3. Edge label/type editing directly on the canvas (click the midpoint connection node).
4. Per-keystroke text undo.
5. SVG export alongside PNG; side-by-side visual diff in the Compare view.
6. iOS app project (Xcode) + document picker storage.
