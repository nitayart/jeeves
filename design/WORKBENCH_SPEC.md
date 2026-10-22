# Workbench UI Specification (v0.1)

Status: draft for review · 2026-07-22
Scope: the first buildable slice of the new tool — the "Workbench" shell
(Mockup A), extended with three requirements beyond the mockup:

1. **Rapid target switching** — move between research targets (APKs, binaries,
   …) as fast as switching browser tabs.
2. **Pluggable analysis engines** — JEB, JADX, open-source engines, or a
   bridge to another reversing tool over MCP, selectable per target.
3. **Concurrent targets** — several research targets open and live at once,
   not the current one-project-at-a-time picker.

Name: TBD — the UI is nameless for now (candidates in §11); the mockup uses a
phosphor hex-print motif (`64 65 78 0a 30 33 35 00`) where a brand would go.

---

## 1. Core concepts

Everything in the UI is built from five nouns:

| Concept | Definition |
|---|---|
| **Project** | The workspace-level container: a named investigation wrapping multiple **targets** — APKs, native binaries, *and source-code projects* (real investigations span all three, e.g. an APK + the vendor library sources it links against). Annotations, flows, agents, and runtime sessions are project-scoped. The current project is always visible in the navbar (project chip). **Roadmap: several projects must be openable concurrently** — each with its own target set — with a fast project switcher. |
| **Workspace** | The running app instance. Owns all open projects and their targets, the event bus, and global services (palette, keymap, settings). |
| **Target** | One research artifact under analysis: an APK, DEX, ELF/.so, Mach-O, `.jdb2`, a source tree, or a remote session provided by another tool. A target is bound to exactly one **engine session**. |
| **Engine** | An analysis backend adapter (JEB, JADX, Ghidra-over-MCP, IDA-over-MCP, …). Engines are plugins behind one capability interface (§3). |
| **Session** | A live engine instance attached to a target: holds analysis state, persistence (project file), and the target's event channel. |
| **Action** | A named, registered command (`target.rename-symbol`, `nav.back`, `palette.open`). The single registry feeds the palette, menus, toolbar, hotkeys, and the MCP/scripting API. Nothing reaches the engine except through an action. |

The old Jeeves already obeys the last row (its `Commands` dispatcher is the
only path to the engine, shared by UI/MCP/VSCode). The spec keeps that law and
generalizes the engine behind it.

## 2. Multi-target model

### 2.1 What changes vs. old Jeeves

Old Jeeves technically supports multiple projects but forces a single visible
project (picker → one workspace → switcher dropdown that swaps the whole UI).
The new model makes targets **peers**: all open, all live, all switching
instantly.

### 2.2 Target strip

- A **target strip** sits at the very top of the window (above the top bar),
  visually like browser tabs: one chip per open target showing target icon
  (APK / ELF / remote), short name, engine badge (`JEB`, `JADX`, `IDA`),
  activity dot (analysis running / agent active / device attached), and dirty
  dot.
- `⌘1…⌘9` jumps to target N. `⌘⇧[` / `⌘⇧]` cycle. Clicking switches.
- Middle-click (or `⌘W` on an inactive editor) closes a target, with a
  save prompt if dirty.
- `+` at the end of the strip opens the **target launcher** (§2.4).
- Strip overflow collapses into a dropdown listing all targets with their
  full paths.

### 2.3 Per-target state isolation

Each target owns an independent UI state bundle, saved and restored on switch
with zero re-layout cost:

- open editor tabs + cursor/scroll positions
- navigation history (the unified back/forward stack — per target, never mixed)
- dock tab selection and panel sizes
- side-panel selection (tree node, filter text)
- runtime/frida session bindings (UI bindings only — sessions themselves
  are project-scoped, §8)

Switching targets is a view-model swap, not a reload. The engine session keeps
running in the background (analysis, agents, frida capture all continue).

### 2.4 Target launcher

Local JEB startup reads the selected installation's optional, whitespace-separated
`jvmopt.txt` before the explicit Jeeves JVM options. Shell, Gradle and native-test
launches use the same option source; none evaluates it as shell code or changes
the installation. See `docs/local-jeb.md` for precedence and supported formats.

The replacement for the old picker screen — a modal (or the zero-state view
when no target is open) with three zones:

1. **Recent targets** — MRU list with engine badge and last-opened time;
   number-key selection for the first 9.
2. **Open artifact** — path field + server-side file browser; file type
   detected from magic bytes, not extension.
3. **Engine picker** — shown once an artifact is chosen: a row of engine
   cards with capability summary (see §3.3), default chosen by
   type→engine rules in settings. "Open with…" is also available on an
   existing target to open *the same artifact* in a second engine as a new
   target (read comparison workflows).

### 2.5 Concurrency rules

- Each session runs its analysis/agents/devices independently; heap and
  thread budgets are per-session with a global ceiling surfaced in the
  status bar (one heap gauge per target, worst one shown in the strip chip
  tooltip).
- Search (§6.3) defaults to the active target; the ALL scope covers all
  open targets of the current project and groups results by target.
- Cross-target operations (diffing, copy flow) are v2; the model must not
  preclude them.

## 3. Engine abstraction

### 3.1 Capability interface

Implementation status: the stock JADX 1.5.6 adapter reports `persist: "sidecar"`,
`rename: true`, `comment: true`, and `smali: false`. APK/DEX inputs are supported;
`.jdb2` remains JEB-only. Renames (including virtual override families, locals, and
formal parameters) and class/member/instruction comments use JADX's public
`ICodeData` API and persist at `<sidecar>/jadx/code-data.json` through checkpointed
writes. The search corpus lives separately under `<sidecar>/jadx/search-index/` and
is invalidated per affected class. Raw reference offsets are explicitly labeled
`offsetUnit: "dex-code-unit"` (16-bit units), not JEB byte offsets or transformed
IR positions.

An engine adapter implements a single interface; every method is optional and
capability flags declare what's real. The UI renders from the flags — a
missing capability means the affordance is absent, never an error dialog.

```
interface Engine {
  caps(): CapabilitySet
  open(artifact, opts) / save() / close()
  tree(): PackageNode[]                     // navigable symbol hierarchy
  decompile(symbol, opts): SourceView       // text + token map (semantic spans)
  xrefsTo(symbol) / xrefsFrom(symbol)
  callees(symbol) / callers(symbol)         // may be capped; reports truncation
  implementations(symbol)
  search(query, scope): SearchHit[]         // strings, consts, code text
  strings(): StringEntry[]
  rename(symbol, name) / comment(symbol, text)   // write ops — optional
  tags / bookmarks / flows                       // annotation ops — optional
  callgraph(opts): Graph
  events(): EventStream                     // progress, analysis-done, external edits
}
```

`CapabilitySet` is a flat map: `{ decompile: true, rename: true, xrefs: true,
callgraph: false, tags: true, flows: false, persist: "native" | "sidecar" | "none" }`.

### 3.2 Engine matrix (initial set)

| Engine | Type | Strengths | Expected caps | Notes |
|---|---|---|---|---|
| **JEB adapter** | APK/DEX (+ native) | Best decompiler, full write ops, existing code | everything | Port of current `JebSession`, split along seams |
| **JADX adapter** | APK/DEX | Open source, fast, no license wall | decompile, xrefs, search, strings, rename (sidecar), comment (sidecar); no native | First proof the abstraction is real — must be built in v1 alongside JEB |
| **Ghidra MCP bridge** | ELF/Mach-O/etc. | Native binaries, mature analysis | decompile, xrefs, rename, comment; no flows | Drives an external Ghidra via its MCP server; latency budget per op |
| **IDA MCP bridge** | Native (.so etc.) | Best native decompiler | as Ghidra | Reuses current `ida-pro-mcp` integration path |
| **radare2/rizin bridge** | Native, exotic | Ubiquitous | decompile (via r2ghidra), xrefs | Optional, later |

The MCP bridges double as the pattern for "engine = another reversing tool":
the workbench treats an external tool exactly like an internal engine. This is
also the answer for formats JEB/JADX don't cover.

### 3.3 Capability-aware UI rules

- The engine card in the launcher shows caps as plain-language chips:
  "decompile · xrefs · rename · graphs" vs "read-only · no graphs".
- Write ops on a read-only engine: buttons absent, palette entries hidden
  (not disabled-with-tooltip — absent).
- `persist: sidecar` engines store engine edits beneath the target sidecar
  (`<artifact>.workbench/jadx/code-data.json` for JADX); engine-neutral tags,
  history, bookmarks, and flows remain alongside it. `persist: native` (JEB
  `.jdb2`) uses engine persistence.
- Mixed-engine workspaces are normal: tag colors and flow data are
  engine-neutral and live in the workspace layer, so annotations survive an
  engine swap on the same artifact (matched by original signature, as today).

### 3.4 Op protocol

The wire protocol stays `op + params → JSON`, one dispatcher per session.
Every op declares: capability required, read-only or mutating, actor
(human/llm), cost hint (for concurrency scheduling). The MCP tool list and
palette are **generated** from the op registry — no hand-maintained parity.

## 4. Layout (from Mockup A, amended)

```
┌──────────────────────────────────────────────────────────────┐
│ TARGET STRIP  [Gmail·JEB] [libgmail.so·IDA] [wallet.apk·JADX] +│
├──────────────────────────────────────────────────────────────┤
│ TOP BAR  ‹ › history · brand · Jump Anywhere (G) · 🔍 · agent │
│          chip · Follow · Save ● · ⋯                            │
├──┬─────────┬──────────────────────────────────┬──────────────┤
│A │ SIDE    │  EDITOR TABS                      │ INSPECTOR    │
│C │ PANEL   │  code header (Rename F2 · Cmt ; · │ class info   │
│T │ tree /  │  Find refs X · ⚡ · 🤖)            │ access flags │
│I │ search /│ ──────────────────────────────    │ ──────────   │
│V │ strings │  MONACO (semantic highlight,      │ SELECTION    │
│I │ outline │  glyph margin: BP/tag/coverage,   │ xrefs to     │
│T │ agents  │  inlay hints, hover)              │ selection    │
│Y │         ├──────────────────────────────────┤ (live)       │
│  │         │  DOCK: Xrefs·Callees·Runtime·Tags·│              │
│  │         │  Flows·Bookmarks·History          │              │
├──┴─────────┴──────────────────────────────────┴──────────────┤
│ STATUS  target stats · engine · heap · busy · agent · dirty   │
└──────────────────────────────────────────────────────────────┘
```

Amendments to the mockup:
- **Scope-consolidated panels** (mockup v7): the right rail owns everything
  *symbol-scoped* — a single scrollable SELECTION pane that follows the
  caret: symbol header (signature, kind, rename badge), XREFS TO, CALLEES,
  IMPLEMENTATIONS. The bottom dock owns only *target-scoped* tabs: Runtime,
  Tags, Flows, Bookmarks, History. `X` focuses the Selection pane. This
  replaces the earlier split where Xrefs/Callees lived in the dock and
  duplicated the rail panel.
- **Target strip** added (§2.2) — the mockup predates the multi-target
  requirement.
- **Engine badge** in the top bar next to the target name; engine-specific
  actions (e.g. JEB "⚡Native", IDA-specific views) appear only when the
  active engine supports them.
- **Outline** lives in the side panel only (a side-panel mode, alongside
  tree / search / strings / agents) — keyboard-navigable.
- Panels are dockable in the VS Code sense (drag to rearrange, `⌘J` toggle
  dock, `⌘B` toggle side panel). There are no shipped layout presets — the
  default is the only canned state. **Roadmap: user view configurations are
  saveable and restorable as named layouts** (like IDA desktops), persisted
  per workspace and shareable across targets.
- One status ribbon only (bottom): telemetry readouts, agent chip + Follow,
  theme knob, hints, dirty state. No duplicated controls anywhere — each
  option exists in exactly one place.

## 5. Editor surface

Carried over from old Jeeves (these were already good): Monaco with
engine-provided semantic token maps, hover tooltips, parameter-name inlay
hints, go-to-def (F12 / ⌘-click / double-click), rename (F2), comment (;),
glyph margin for breakpoints/tags/coverage with a defined stacking order when
they collide, renamed-badge showing original obfuscated name.

New:
- **Java / Smali / Split view modes** (`Tab` cycles, segmented control in the
  code header). Split is the Ghidra-style synced dual view: decompiled Java
  left, smali right, cursor-synced via the engine's line mapping (linked
  lines get a distinct highlight band + `⇄` gutter glyph); a lock toggle
  disables sync. Panes scroll independently with a faint marker showing the
  other pane's viewport. The smali/disassembly pane is a full listing:
  method-relative **offset** column (always on) and a **raw opcode bytes**
  column (toggleable), matching what JEB and IDA users expect. Engine
  capability `smali` gates the feature (JEB: yes; JADX: via baksmali; native
  engines: disassembly view instead — same UI contract).
- **Split editors** (`⌘\`) — two classes side by side, or same class at two
  scroll positions; each split has its own cursor but shares the target's
  nav history.
- **Peek view** for xrefs/implementations (`⇧F12` / `⌥F12`) instead of
  dock-only, VS Code style.
- **Wrap/zoom/font** controls per editor, persisted per target.

## 6. Navigation & search

### 6.1 Unified history
One back/forward stack per target covering *every* location change: editor
jumps, tree clicks, dock xref clicks, strings-table jumps, graph drills.
`Esc`/`⌘[` back, `⇧Esc`/`⌘]` forward; chevron dropdown shows the last 20
entries with symbol + time. (This is IDA 9.2's most praised fix — day-one
feature, not a patch.)

### 6.2 Jump Anywhere (`G`)
One fuzzy dialog over: symbols (classes/methods/fields), strings, dock
targets (bookmarks, tags, flows), and actions. Prefix filters: `@` symbols,
`#` tags, `>` actions (same as palette), `%` strings.

### 6.3 Search (`⌘⇧F`)
Full-text across all decompiled classes (background index, cancellable,
progress in status bar). Default scope is the active target; the ALL scope
covers all open targets of the current project — never across projects.
Results grouped by class (and by target under the ALL scope), click
navigates, `F4`/`⇧F4` cycle hits.

### 6.4 Strings & consts
The old strings overlay becomes a **full-table surface** (mockup S), not a
dialog: heuristic type classification, multi-select type filters,
unreachable-only and reachable-from-selection filters, regex filter, and a
preview rail per string showing the full value + xref list with
jump-to-first-use. Same table contract reused for const search and natives.

**String classification** is a two-layer pipeline, and confidence is a
first-class part of the UI:

1. **Lexical layer** (engine-neutral, runs at index time): format patterns
   (URI schemes, `android.permission.*`, `content://`, cert/key headers,
   file paths), a crypto-algorithm dictionary (`AES/GCM/NoPadding`,
   `SHA-256`, …), and base64/hex detection via charset + length + entropy.
2. **Usage layer** (engine-enriched, via xrefs): the string's role at its
   call sites decides — arg 0 of `Cipher.getInstance` ⇒ crypto, arg of
   `Uri.parse`/`Intent.setData` ⇒ URL/uri, manifest-constant comparison ⇒
   permission — regardless of what the bytes look like.

Type chips carry a tooltip stating the rationale and confidence
(`crypto · usage-confirmed: arg 0 of Cipher.getInstance (3 call sites)` vs
`base64? · lexical only — unconfirmed`); lexical-only guesses render with a
`?` and dashed chip border. Classifiers are pluggable per engine; the
lexical layer ships in the workspace so even read-only MCP-bridge engines
get basic typing.

## 7. Command palette & keymap

- `⌘K` (and `⌘⇧P` alias) opens the palette: all actions, fuzzy, with hotkey
  badges, scope hints, and recently-used boost. The palette is generated from
  the action registry — an action that exists but isn't in the palette is a
  build error.
- Hotkeys are single-key where the editor doesn't have focus-risk
  (IDA/BN style: `N`/`X`/`I`/`G`), chorded otherwise. All bindings live in
  `keybindings.json`; the palette row for any action shows its binding and
  offers rebind. The old single-letter focus-leak bug class is fixed
  structurally: bindings are dispatched by the registry with explicit
  context guards (`editorFocus`, `findWidgetOpen`, `modalOpen`), not ad-hoc
  DOM handlers.
- `⌘/` opens a searchable shortcuts overlay (the discoverability P1-2 fix).

### Default keymap (core)

| Key | Action | | Key | Action |
|---|---|---|---|---|
| `⌘K` | palette | | `F2` | rename |
| `G` | jump anywhere | | `;` | comment |
| `X` | xrefs (dock+peek) | | `⌘B` | toggle side panel |
| `I` | implementations | | `⌘D` | bookmark line |
| `F12` | go to def | | `⌘J` | toggle dock |
| `Esc`/`⌘[` | back | | `⌘S` | save target |
| `⇧Esc`/`⌘]` | forward | | `⌘Z` | undo rename (History) |
| `⌘⇧F` | search | | `⌘1…9` | switch target |
| `⌘⇧O` | method outline | | `⌃1…6` | rail views (⌃1 Explorer · ⌃2 Search · ⌃3 Strings · ⌃4 Runs · ⌃5 Agents · ⌃6 Device) |

## 8. Runtime / dynamic analysis

Unchanged in spirit from old Jeeves: Frida bridge, device attach, method
breakpoints, hit feed, coverage overlays, run diffing. Scope rules for the
new model:

- Runtime/frida sessions are **project-scoped** (RunStore) — all sessions of
  the current project live in one store, not per target.
- Breakpoints bind to a **target's symbols** (they reference that target's
  engine session), even though the sessions they belong to are
  project-scoped.
- The **Runs view** (center surface) shows all sessions of the project with
  target badges; the **Runtime dock tab** shows only the active target's
  subset, labeled as such.

UI adjustments for the new model:
- The Runtime dock shows the active target's session subset (labeled); the
  target strip chip shows a live dot when a device is attached to that
  target.
- Coverage decorates the editor glyph margin on any target whenever runtime
  data exists — additive law kept: no device ⇒ pixel-identical static UI.
- Hit detail panel borrows x64dbg density: selected hit shows decoded args,
  return value, and backtrace in one glance.

## 9. Agent presence (Workbench-level)

The full Agent Cockpit is a later view; the Workbench ships with:
- presence chip in the top bar (`🤖 at sync.c`), Follow toggle;
- agent-tinted editor tabs; live rename streaming (existing EventBus);
- a review toast when an agent proposes edits (`⌘R` opens the review dock —
  a simple accept/reject list, not the full cockpit);
- write-scope enforcement stays server-side, unchanged.

## 10. Non-functional requirements

- **Cold switch** between loaded targets < 50 ms view-model swap; no reflow
  flicker (target states pre-rendered off-DOM).
- Decompile display < 100 ms for a cached class; background indexing of a
  Gmail-scale APK must never block typing (whole-DEX scans off the session
  lock — fixes old P1-12).
- All long operations cancellable with visible progress (Ghidra's freeze
  anti-pattern).
- Two first-class themes — light default, dark optional — with an Okabe–Ito
  signal palette in both; lettered type tags, color never the only signal.
- The UI consumes only the public op protocol; anything the UI can do, MCP
  can do (no Cutter-drift).

## 11. Naming

"Jeeves" is retired. Candidates (all checked for obvious collisions in the
RE/security space):

| Name | Rationale |
|---|---|
| **GIMLET** *(recommended)* | A small tool for boring precise holes; "gimlet-eyed" = piercing scrutiny. Short, unix-y, unclaimed in RE tooling, drinks-tray continuity with the butler theme without the Wodehouse baggage. |
| **Loupe** | Jeweler's magnifier — close inspection of small artifacts. Clean, but generic; several software collisions. |
| **Scalpel** | Precise dissection. Strong metaphor, somewhat overused in security tooling. |
| **Burin** | Engraver's chisel — cutting lines into metal. Distinctive, obscure (may need explaining). |
| **Picklock** | Lockpicking — offensive-security flavor; a bit on-the-nose and long. |
| **Mandrel** | The spindle work is mounted on — "the thing everything attaches to". Apt for a multi-engine hub, weak imagery. |

Recommendation: **Gimlet** — used as the working name throughout this spec
(`<target>.gimlet/` sidecar dirs, `gimlet` CLI).

## 12. Migration map (old Jeeves → Workbench)

| Old | New |
|---|---|
| `JebSession` monolith | `engines/jeb/*`, split: decompile / xrefs / rename / annotations / graph |
| `Commands` dispatcher | per-session op dispatcher + global action registry (same protocol) |
| Picker screen | Target launcher modal + zero-state |
| Project switcher dropdown | Target strip |
| Strings overlay | Dock-maximized table view |
| Atlas hand-rolled SVG | deferred — Investigation Board view (v2) |
| `app.js` + global-scope scripts | component modules over the action registry; Monaco kept |
| Tag families hardcoded to Gmail | user-defined tag families in settings |

## 13. Resolved defaults (2026-07-22, shown in mockup A2)

1. **Target strip: yes, always visible** — 32px browser-tab strip; instant
   switching is a core requirement, worth the pixels.
2. **Same artifact in two engines = two targets** — simpler state model;
   mockup shows `gmail.apk · JEB` and `gmail.apk · JADX` side by side.
3. **JADX write persistence: stock `ICodeData` sidecar** at
   `<artifact>.workbench/jadx/code-data.json`, keyed by original JADX node/code
   references. It retains symbol/local/parameter renames and comments without
   forking JADX; checkpointed saves and history replay protect the crash window.
4. **Heap: per-session budgets + global ceiling**, both surfaced in the
   status bar (`heap 3.2g` per target, `Σ 9.1g / 24g` global).
5. **v1 ships JEB + JADX adapters**; Ghidra/IDA MCP bridges land in v1.1
   (the mockup shows the end state).

Remaining open questions: none blocking v1.

---

## 14. Locked decisions — Workbench v1 (2026-07-23)

The workbench UI is LOCKED as of this date. Reference implementation:
`design/mockups/vocab/v7-helio-dock.html`. Summary of everything decided:

**Visual vocabulary — "Helio"**
- Light-first (warm paper `#f4f2ec`, ink `#1c2430`, hairline borders) with a
  first-class dark variant; LIGHT/DARK knob in the status ribbon.
- Okabe–Ito signals in both themes, always with redundant non-color coding:
  blue `#0072B2`/`#56B4E9` selection, vermillion `#D55E00` markup/redlines,
  bluish green `#009E73` live, orange `#E69F00` caution, reddish purple
  `#CC79A7` agent, yellow `#F0E442` caret-symbol highlight.
- Flight-deck discipline: squared 2px controls, labeled bays, 90ms motion,
  tabular numerals; phosphor glow only on live elements.
- No uninformative titles, no duplicated controls, one bottom status ribbon
  (telemetry + theme + agent + Follow + hints + dirty).

**Interaction contract**
- Cursor contract: arrow = reading, hand = navigates, I-beam only in real
  inputs, col/row-resize on splitters, grabbing during panel drags.
- IDA-style keyboard caret in the listing: `←→` char, `⌥←→` word, Home/End,
  click places, double-click navigates; symbol under caret lights ALL its
  occurrences in `#F0E442` yellow (identifiers *and* keywords — every token
  is addressable).
- Command palette `⌘K`; jump-anywhere `G`; unified back/forward history;
  breadcrumb = hierarchy, history chevrons = time.

**Layout**
- Workspace → **Projects** → **Targets** → engine sessions; every level
  concurrently open. Project chip (navbar) = project menu (switch/open/new/
  close); target strip = target switching; per-target UI state isolated.
- Symbol-scoped vs target-scoped split: the right rail RELATION pane
  (scrollable: symbol header, XREFS TO, CALLEES, IMPLEMENTATIONS) follows
  the caret; the bottom dock holds only target-scoped tabs (Runtime, Tags,
  Flows, Bookmarks, History). `X` focuses RELATION.
- View modes: `Tab` cycles split → java → smali → split (one key, one
  direction — no `⇧Tab`), `\` swaps pane sides, draggable divider
  (col-resize, double-click resets 50/50); smali pane = offsets +
  `b`-toggleable opcode bytes + `s`-toggleable caret-synced `⇄` band,
  `c` cycles the coverage run. No visible mode switcher and no code-view
  chrome at all — the pane controls are keyboard-only, discoverable via
  the `?` shortcuts sheet.
- Docking: drag tabs/panels between dock/rails, `⌘J`/`⌘B` collapse; no
  shipped presets; **roadmap: named user layouts, saveable/restorable
  (IDA-desktop-style)**.
- **Explorer-only chrome**: the class tab bar and the file navigator
  (symbol tree) exist ONLY in the Explorer/code view. Every other view
  (map, agents, strings, runs, search, device) hides both and reclaims
  the space; state (tree expansion, tab selection) survives the round
  trip. The rack, navbar, and status ribbon persist in all views.

**Still open (next discussions)**
- Graph integration for complex cross-module flows (the Investigation
  Board successor) — fcm_map-informed; must live inside this UI, not as a
  separate screen, with MCP coverage.
- Smali-side caret; native/disassembly pane variants; minimap.
