// types.ts — the shared TYPE vocabulary of the frontend: the backend's wire
// shapes as the frontend reads them (MCP_SPEC §4 is the prose contract; the
// Java side builds untyped maps, so every field the UI does not read is left
// out and everything it reads defensively is optional), the cross-module state
// singleton `S` (hub.ts) with its late-bound hook bag, and the op-name → result
// map that types api.ts's op()/tryOp() at every call site.
//
// Conventions:
//   - Wire shapes are `interface`s of optional fields. A field is required only
//     where every producer is known to send it (ids, names the UI keys on).
//   - Client-side expandos the JS stores ON server objects (a map node's DOM
//     element, a string row's in-flight xref fetch) are declared here too,
//     marked "client-side", so the port stays behavior-identical.
//   - `OpMap` types RESULTS only. Params are `OpParams` (a loose record): the
//     modules build them dynamically (`params.target = …`), and the server
//     validates them — typing every shape here would fight that idiom.

/* ==================================================================
   Center surface + escape stack (hub.ts)
================================================================== */
export type CenterView = 'code' | 'map' | 'agents' | 'strings' | 'runs' | 'search' | 'device';

/** An escape-stack handler: return `false` to DECLINE (leave the event to the
 *  next owner); any other return claims and consumes the Escape. */
export type EscapeHandler = (e: KeyboardEvent) => boolean | void;

export interface EscapeHandle {
  fn: EscapeHandler;
  prio: number;
  seq: number;
}

/* ==================================================================
   Workspace / projects / targets
================================================================== */
export interface Target {
  id: string;
  name: string;
  path?: string;
  engine?: string;
  engineVersion?: string | number;
  /** engine session state: ready | loading | error | closed */
  state?: string;
  active?: boolean;
  dirty?: boolean;
  classes?: number;
  heap?: string;
  /** store key — speculative on workspace.state rows (chrome.ts chipFor) */
  key?: string;
  error?: string;
  /** a re-key's old database is still on disk beside this target's (name, path, size) */
  rekeyed?: Rekeyed;
  /** history entries the open could not re-apply */
  replayFailed?: number;
}

export interface Project {
  id: string;
  name: string;
  active?: boolean;
  targetIds?: string[];
}

/** workspace.state — the ONE snapshot every strip/ribbon/chip painter runs from */
export interface WorkspaceState {
  targets: Target[];
  projects?: Project[];
  activeMap?: string | null;
  activeProject?: string | null;
  /** carried only when > 0 — a sidecar write/park failed this session */
  sidecarIssues?: number;
}

/** project.known rows — ON DISK projects (sidecar markers) */
export interface KnownProject {
  name: string;
  open?: boolean;
  maps?: number;
}

/** project.targets member rows — the project editor (chrome.ts) */
export interface ProjectMember {
  key: string;
  name?: string;
  artifactName?: string;
  open?: boolean;
  state?: string;
  openElsewhere?: boolean;
  sizeBytes?: number;
  storePath?: string;
  /** the LIVE target id of an open member */
  target?: string;
}

/** target.info / target.open's accept payload / device.pull's target */
export interface TargetInfo {
  id: string;
  name?: string;
  path?: string;
  engine?: string;
  engineVersion?: string | number;
  state?: string;
  phase?: string;
  message?: string;
  error?: string;
  classes?: number;
  heap?: string;
  package?: string;
  deduped?: boolean;
  note?: string;
  basisWarn?: string;
  /** target.open: a human's open that may not fit the heap — said, not refused */
  warning?: string;
  recoveredEdits?: number;
  /** history entries the open could not re-apply (logged server-side) */
  replayFailed?: number;
  /** target.open: the saved database shared its JEB key with an open target, so it was set
   *  aside and the artifact is analysed again under a key of its own (`note` says it in words) */
  rekeyed?: Rekeyed;
}

export interface Rekeyed {
  /** the JEB key the two databases shared ('' when the old one's was never on record) */
  key?: string;
  /** the targets it clashed with, as the strip names them: "t1 (base.apk)" */
  with?: string[];
  /** the old database, kept beside the new one: file name, absolute path, size */
  aside?: string;
  asidePath?: string;
  asideBytes?: number;
  /** history entries to re-apply */
  edits?: number;
  notCarried?: number;
  at?: string;
  /** this open finishes a re-key begun earlier (an interrupted open, or backend/jdb2-rekey.sh) */
  resumed?: boolean;
}

export interface SysMem {
  totalBytes?: number;
  freeBytes?: number;
  usedBytes?: number;
  heapMaxBytes?: number;
  heapUsedBytes?: number;
  heapCommittedBytes?: number;
}

export interface FsEntry {
  name: string;
  path: string;
  dir: boolean;
  size: number;
}

export interface FsList {
  dir: string;
  parent?: string;
  entries: FsEntry[];
  truncated?: boolean;
}

/* ==================================================================
   Symbols / code
================================================================== */
/** symbol.tree items — packages carry `prefix` (lazy) or `children` */
export interface TreeNode {
  name: string;
  /** package | class | interface | method | field … */
  kind?: string;
  addr?: string;
  origSig?: string;   // a class row's re-open handle: the ORIGINAL signature resolves whatever the current name is
  fqcn?: string;
  origName?: string;
  prefix?: string;
  count?: number;
  children?: TreeNode[];
}

export interface TreePage {
  items: TreeNode[];
  truncated?: boolean;
  total?: number;
}

/** the `symbol` block of a decompile answer, and S.currentSym */
export interface SymbolInfo {
  addr?: string;
  fqcn?: string;
  owner?: string;
  name?: string;
  origName?: string;
  origSig?: string;   // the class's ORIGINAL signature — resolves whatever the current name is (the view's key)
  renamed?: boolean;
  kind?: string;
  line?: number;
}

export interface MethodInfo {
  name: string;
  addr?: string;
  signature?: string;
  sig?: string;
  /** abstract/interface methods emit null ranges */
  line?: number | null;
  endLine?: number | null;
  renamed?: boolean;   // decompile.methods: the method carries a new name (origName is the original)
  origName?: string;
}

/** decompile token: mock {col,len,symbol} · jeb {cs,ce,addr} */
export interface Token {
  line: number;
  col?: number;
  len?: number;
  symbol?: string;
  cs?: number;
  ce?: number;
  addr?: string;
  kind?: string;
}

export interface DecompileData {
  text: string;
  agentComments?: { addr: string; text: string; by?: string; confidence?: number | string; historyId?: string }[];   // comments agents wrote (History) — the view marks, wraps and can delete them
  engine?: string;
  symbol?: SymbolInfo;
  methods?: MethodInfo[];
  tokens?: Token[];
  kind?: string;
  methodParameters?: MethodParams;   // method scope: the retained metadata beside the text
  methodParametersIndex?: { addr: string; original_method?: string; names?: (string | null)[]; countMatchesPrototype?: boolean; status?: string }[];   // class scope: which methods carry the annotation
}

export interface ClassInfo {
  fqcn?: string;
  renamed?: boolean;
  origName?: string;
  super?: string;
  interfaces?: string[];
  flags?: string;
  methods?: number;
  fields?: number;
  xrefCount?: number;
  addr?: string;
}

export interface SmaliRow {
  kind?: string;   // directive | insn | label | blank | comment | annotation (retained metadata, shown apart) | annotation-warn (its count mismatch / malformed row) | case (a switch payload's case line, an array element)
  text?: string;
  name?: string;
  jline?: number;
  offset?: string;
  bytes?: string;
}

export interface SmaliData {
  rows?: SmaliRow[];
  methodParameters?: MethodParams;   // a method listing: the annotation rows above, structured
  note?: string;
}

/** symbol.resolve → {name, kind, sig, target, owner} — NO addr on JEB */
export interface ResolvedSymbol {
  name?: string;
  kind?: string;
  sig?: string;
  target?: string;
  owner?: string;
  addr?: string;
}

export interface SymbolSearchHit {
  name?: string;
  addr?: string;
  kind?: string;
  owner?: string;
}

/** rename / comment answers (the human path) */
export interface EditResult {
  applied?: boolean;
  kind?: string;
  addr?: string;
  newAddr?: string;
  oldName?: string;
  newName?: string;
  symbol?: SymbolInfo;
  historyId?: string;
  restored?: boolean;   // rename.revert: the dex's own name is back
}

/* ==================================================================
   Relations
================================================================== */
/** xref.to / callees / impls rows: jeb rows carry name+owner (+addr/method);
 *  mock rows name+kind; 'from' sites carry a +offset */
export interface RelationRow {
  name?: string;
  method?: string;
  addr?: string;
  from?: string;
  sig?: string;
  owner?: string;
  kind?: string;
  type?: string;
  actor?: string;
  obfuscated?: boolean;
}

/* ==================================================================
   Strings / search
================================================================== */
export interface StringType {
  name: string;
  confidence?: string;
  why?: string;
}

/** string.xrefs row — a bare site string on mock engines */
export interface XrefRowObj {
  method?: string;
  addr?: string;
  from?: string;
  site?: string;
  owner?: string;
  name?: string;
  line?: number | string;
}
export type XrefRow = string | XrefRowObj;

/** the settled result of a string's xref fetch (strings.ts fetchXrefs) */
export type XrefResult = { rows: XrefRow[]; error?: undefined } | { error: string; rows?: undefined };

export interface StringRow {
  id: string;
  value: string;
  type?: string;
  why?: string;
  types?: StringType[];
  xrefCount?: number;
  xrefs?: number;
  firstReferrer?: string;
  xrefList?: XrefRow[];
  /* client-side (strings.ts): the shared in-flight fetch, its start, the last settled result */
  _xrefLoad?: Promise<XrefResult> | null;
  _xrefT0?: number;
  _xrefRes?: XrefResult;
}

export interface StringsPage {
  rows?: StringRow[];
  total?: number;
  truncated?: boolean;
}

export interface SearchContextLine {
  n?: number | string;
  t?: string;
  hit?: boolean;
}

export interface SearchHit {
  line?: number | string;
  preview?: string;
  kind?: string;
  addr?: string;
  stringId?: string;
  id?: string;
  context?: SearchContextLine[];
}

export interface SearchGroup {
  symbol: string;
  addr?: string;
  target?: string;
  engine?: string;
  hits?: SearchHit[];
}

export interface SearchIndexInfo {
  partialCoverage?: boolean;
  pendingReindex?: number;
  indexedClasses?: number;
  state?: string;
  built?: number;
  total?: number;
  ageMs?: number;
}

/* ==================================================================
   Annotations: tags / bookmarks / history / flows
================================================================== */
export interface TagFamily {
  name: string;
  color?: string;
}

export interface Tag {
  id: string;
  family: string;
  name: string;
  symbol: string;
  addr?: string;
  label?: string;
  note?: string;
  color?: string;
  ts?: string;
  line?: number;
  actor?: string;
  renamed?: boolean;
  origName?: string;
}

export interface TagList {
  tags?: Tag[];
  families?: TagFamily[];
  pendingCount?: number;
}

export interface Bookmark {
  id: string;
  symbol: string;
  addr?: string;
  line: number | string;
  note?: string;
  ts?: string;
  label?: string;
}

export interface HistoryEntry {
  id: string;
  op: string;
  kind?: string;                    // class | method | field | local | parameters (an analyst's argument mapping)
  undone?: boolean;
  newText?: string;
  oldName?: string;
  newName?: string;
  actor?: string;
  owner?: string;
  addr?: string;
  newAddr?: string;
  ts?: string | number;
  changes?: ParamMappingChange[];   // kind parameters: the per-index changes (the row shows them, not two comma lists)
  source?: string;
  revert?: boolean;                 // a rename.revert: the dex's own name put back
  matchesRetainedName?: boolean;    // an agent's argument name equal to a retained MethodParameters entry
}

export interface Flow {
  id: string;
  name: string;
  sourceLabel?: string;
  sinkLabel?: string;
  hopCount?: number;
  crossTarget?: boolean;
}

export interface FlowHop {
  addr?: string;
  label?: string;
  stale?: boolean;
}

export interface FlowDetail extends Flow {
  status?: string;
  otherTarget?: string;
  hopsResolved?: FlowHop[];
}

/* ==================================================================
   Layouts (layouts.ts owns the snapshot shape; the server stores it opaquely)
================================================================== */
export interface LayoutUi {
  side: boolean;
  dock: boolean;
  sideW: number;
  rightW: number;
  dockH: number;
}

export interface TabdockState {
  panels: (string | null)[];
  active: string | null;
}

export interface FloatPanel {
  id: string | null;
  left: number;
  top: number;
}

export interface LayoutSnapshot {
  ui?: Partial<LayoutUi>;
  zones?: {
    side?: (string | null)[];
    right?: (string | null)[];
    dock?: Partial<TabdockState>;
    cdock?: Partial<TabdockState>;
    float?: FloatPanel[];
  };
}

export interface LayoutRow {
  name: string;
  ts?: string;
}

/* ==================================================================
   Maps (GRAPHS_SPEC)
================================================================== */
export interface MapSymbol {
  addr?: string;
  entry?: string;
}

export interface MapPending {
  verdict?: string;
  field?: string | null;
  actor?: string;
  rationale?: string;
}

export interface MapNode {
  id: string;
  label?: string;
  kind?: string;
  sublabel?: string;
  layer?: number;
  verdict?: string;
  proven?: boolean;
  pending?: MapPending | null;
  tags?: string[];
  target?: string;
  symbol?: MapSymbol | null;
  /* client-side (map.ts): layout + the rendered card / minimap dot / measured height */
  x: number;
  y: number;
  w: number;
  el?: HTMLElement;
  mmEl?: HTMLElement;
  _h?: number;
}

export interface MapEdge {
  from: string;
  to: string;
  kind?: string;
  label?: string;
  /** static | dyn | low */
  confidence?: string;
  gate?: string;
  /* client-side (map.ts) */
  el?: SVGPathElement;
  glowEl?: SVGPathElement;
  labelEl?: SVGTextElement;
}

export interface MapField {
  node: string;
  field: string;
  verdict?: string;
}

export interface MapData {
  id: string;
  title?: string;
  build?: string;
  nodes?: MapNode[];
  edges?: MapEdge[];
  fields?: MapField[];
}

export interface MapSummary {
  id: string;
  nodes?: number;
}

export interface TypeEntry {
  name: string;
  color: string;
  visible?: boolean;
  custom?: boolean;
}

export type TypeKey = 'nodeKinds' | 'edgeKinds' | 'verdicts' | 'tags';
export type TypeRegistry = Partial<Record<TypeKey, TypeEntry[]>>;

export interface MapEvidence {
  run?: string;
  note?: string;
}

export interface NodeField {
  field: string;
  verdict?: string;
  evidence?: MapEvidence[];
}

export interface ChainStep {
  label?: string;
  symbol?: MapSymbol | null;
}

/** map.node — the inspector's view of one node */
export interface NodeDetail {
  label?: string;
  kind?: string;
  target?: string;
  sublabel?: string;
  verdict?: string;
  pending?: MapPending | null;
  gates?: { label?: string; position?: string }[];
  symbol?: MapSymbol | null;
  fields?: NodeField[];
  chain?: ChainStep[];
}

export interface MapCreateResult {
  id?: string;
  existing?: boolean;
  nodes?: number;
}

/* ==================================================================
   Agents (AGENTS_SPEC)
================================================================== */
export interface MissionStep {
  text?: string;
}

export interface Mission {
  id: string;
  agentId: string;
  state: string;
  goal?: string;
  model?: string;
  mode?: string;
  providerLabel?: string;
  local?: boolean;
  tokensUsed?: number;
  budget?: number;
  budgetUnlimited?: boolean;
  inputTokens?: number;
  outputTokens?: number;
  tokensRaw?: number;      // raw prompt+completion tokens (tokensUsed is cost-weighted: cache hits at 1/10)
  cacheHitTokens?: number;
  budgetNeeded?: number;   // >0 while paused at the ceiling: the raise the next turn needs (RAISE & RESUME prefills it)
  budgetFloor?: number;    // the least budget that lets the loop send the next request; below it a resume is refused
  turnCap?: number;        // the effective turn cap (explicit maxTurns or the depth default)
  turnsUnlimited?: boolean; // headless execution: turnCap=0, no turn ceiling
  turnsDone?: number;      // turns completed so far
  displayState?: string;   // the workflow name for the state: queued/running/paused/draining/cancelled/failed/completed
  stopReason?: string;     // why the run ended or stands where it does, in words (AgentLoop)
  cancellationUnconfirmed?: boolean;   // the loop's thread has not returned yet: the upstream may still be answering
  loopActive?: boolean; // live HTTP loop still exists, including terminal-state finalization
  turnsNeeded?: number;    // >0 while paused AT the turn cap: the suggested raise (RAISE THIS MISSION & RESUME prefills it)
  dismissed?: boolean;     // removed from the view by the user (× on a finished card); the record stays
  scope?: string;
  depth?: string;          // quick | deep — a reversing mission's brief (llm.Depth)
  writes?: number;         // applied (not undone) renames + comments of this mission — what ↶ REVERT undoes
  effort?: string;         // low … max — the CLI's --effort / reasoning_effort
  maxTurns?: number;       // an explicit turn cap (0/absent = the depth's default)
  target?: string;         // the target id the mission is pinned to (Mission.target)
  reason?: string;
  progress?: MissionStep[];
  following?: boolean;
}

/** agent.proposals rows — rename / comment / tag / verdict / catalog bodies
 *  share one envelope; fieldRows() walks the rest generically */
export interface Proposal {
  id: string;
  kind?: string;
  state?: string;
  ts?: string | number;
  agentId?: string;
  confidence?: number | string;
  rationale?: string;
  symbol?: string;
  target?: string;
  targetKey?: string;     // batch 2: filed with the project + the durable target identity
  targetName?: string;
  project?: string;
  map?: string;
  node?: string;
  field?: string | null;
  verdict?: string;
  oldName?: string;
  newName?: string;
  proposedName?: string;   // the agent's original when the reviewer edited the name (server)
  editedName?: string;     // the reviewer's pending edit (client, until accepted)
  refusal?: string;        // the last accept's error, shown on the row (client)
  historyId?: string;      // the History entry an applied write got (server; revert / redo go through it)
  newAddr?: string;        // where a renamed symbol now lives (server; a later human rename targets it)
  reverted?: boolean;      // the applied write was undone (server, from History)
  auto?: boolean;          // applied by the policy, not by a ✓ (server)
  settledTs?: string;
  newText?: string;
  name?: string;
  note?: string;
  line?: number | string;
  [extra: string]: unknown;
}

export interface ActivityRow {
  ts?: string | number;
  agent?: string;
  kind?: string;
  text?: string;
  mission?: string;   // the mission the row belongs to (AgentManager.act)
  turn?: number;      // >0 inside the http loop's turn: the row opens that turn's transcript (agent.turn)
}

/** One tool call inside a recorded turn (agent.turn). args/result are JSON text, capped server-side. */
export interface TurnCall { tool: string; args: string; ok?: boolean; result?: string; resultText?: string; ms?: number; n?: number; of?: number }
export interface TurnRecord {
  turn: number; ts?: string; ms?: number; tokens?: number; tokensUsed?: number; outcome?: string; error?: string; cacheHit?: number; cacheMiss?: number;
  reasoning?: string; text?: string; calls: TurnCall[];
  context?: { messages?: number; elided?: number; stripped?: number; promptEst?: number; limit?: number };
}
export interface TurnSummary { turn: number; ts?: string; ms?: number; tokens?: number; tokensUsed?: number; outcome?: string; calls: number; ok: number; reasoning: number; text: number }

export interface Provider {
  id: string;
  label: string;
  driver?: string;
  model?: string;          // the default model
  models?: string[];       // the family (pick list)
  auth?: 'api' | 'cli';    // how the provider connects — present ONLY when authOptions has >1 entry (anthropic: API key | Claude Code login)
  authOptions?: string[];  // the choices offered; a single-option provider omits both fields (llm-anthropic-vendor design #6)
  signedIn?: boolean;      // CLI mode: `claude auth status` says signed in (cached); configured ⇒ installed AND signed in (B2)
  installed?: boolean;     // CLI mode: the claude binary is on PATH — lets the card say "not installed" vs "installed, not signed in" (B10)
  keyStored?: boolean;     // a connection-choosing provider: a key is stored in the ring even while the CLI connection is active — so CLEAR KEY can reach it (review B10)
  baseUrl?: string;
  local?: boolean;
  needsKey?: boolean;
  configured?: boolean;
  builtin?: boolean;       // shipped (editable, not removable) vs user-added
  extraParams?: Record<string, unknown>;   // vendor extras merged into every request (already returned by llm.providers)
}

/** task.list rows — the built-in tasks and each depth's settings */
export interface TaskDepth {
  depth: string;
  model: string | null;      // provider:model, null = the server default
  effort: string | null;
  turns: number;             // 0 = default
  turnsUnlimited?: boolean; // headless execution overrides stored turns
  budget: number;            // 0 = default
  budgetUnlimited?: boolean; // unattended HTTP execution meters usage without a token ceiling
  defaults: { turnsCli: number; turnsLoop: number; budget: number };
  resolved: { provider?: string; providerLabel?: string; model?: string; driver?: string; local?: boolean; configured?: boolean; unconfigured?: boolean; unresolved?: boolean; unlisted?: boolean };   // unlisted: the pinned model was dropped from its provider's list (llm-config §6.4)
}
export interface TaskRow {
  task: string;
  label: string;
  description?: string;
  depths: TaskDepth[];
}

/** presence.whereis */
export interface Whereis {
  present?: boolean;
  actor?: string;
  addr?: string;
  member?: string;
  name?: string;
  memberName?: string;
  ts?: string | number;
  ageMs?: number;
  /* client-side (ribbon.ts): receipt time on the LOCAL clock */
  localTs?: number;
}

/* ==================================================================
   Runs (VIEWS_SPEC §2)
================================================================== */
export interface FridaServer {
  running?: boolean;
  version?: string;
}

/** device.list / runs.devices row */
export interface Device {
  serial: string;
  model?: string;
  android?: string;
  adbdRoot?: boolean;
  root?: string;          // adbd-root | su | none — the capability probe (audit decision 14)
  package?: string;
  target?: string;
  project?: string;
  live?: boolean;
  fridaServer?: FridaServer;
}

export interface Breakpoint {
  id: string;
  name: string;
  cond?: string;
  target?: string;
  hits?: number;
  enabled?: boolean;
  origSig?: string;
  addr?: string;
  kind?: string;            // "native" for a native (module+export|offset) bp; absent/other for a JEB method bp
}

export interface HitArg {
  repr?: string;
  type?: string;
}

export interface HitFrame {
  owner?: string;
  method?: string;
}

export interface Hit {
  monotonic_ns?: string;
  sourceSeq?: number;
  timestampBasis?: string;
  type?: string;
  ts?: string | number;
  name?: string;
  addr?: string;
  bpId?: string;
  target?: string;
  argsBrief?: string;
  args?: HitArg[];
  ret?: HitArg | null;
  thread?: { name?: string };
  frames?: (HitFrame | string)[];   // Java frames are {owner,method}; native frames are symbolized strings
}

export interface RunInfo {
  execId: string;
  producerMode?: string;
  fixtureBinding?: boolean;
  completion?: string;
  cleanup?: string;
  sourceRunId?: string;
  sourceManifestSha256?: string;
  projectionTruncated?: boolean;
  startTs?: string | number;
  endTs?: string | number | null;
  device?: string;
  package?: string;
  hitCount?: number;
  coverage?: unknown[];
  color?: number;
  target?: string;        // the live target id at record time (remints per boot)
  targetKey?: string;     // the durable store identity (batch 2)
  targetName?: string;
}

export interface RunDiff {
  a?: string;
  b?: string;
  onlyA?: { label?: string; addr?: string }[];
  onlyB?: { label?: string; addr?: string }[];
  both?: unknown[];
}

/* ==================================================================
   Devices (DEVICE_SPEC)
================================================================== */
export interface Annotation {
  note?: string;
  by?: string;
}

export interface CatalogEntry {
  kind: string;
  name: string;
  path?: string;
  version?: string;
  size?: number;
  iface?: string;
  apkPath?: string;
  host?: string;
  pkg?: string;
  pid?: number | string;
  uid?: string;
  perms?: string;
  link?: string;
  annotations?: Annotation[];
  changed?: string;
  removed?: boolean;
  /** the pulled copy's path — written by the server, and by device.ts after a pull */
  pulled?: string;
  [extra: string]: unknown;
}

export interface CatalogDiff {
  added?: number;
  removed?: number;
  changed?: number;
}

export interface Catalog {
  entries?: CatalogEntry[];
  removed?: CatalogEntry[];
  totalAll?: number;
  kindTotals?: Record<string, number> | null;
  cappedKinds?: string[];
  built?: string;
  diff?: CatalogDiff;
  removedTruncated?: boolean;
}

export interface ProcThread {
  tid?: number | string;
  comm?: string;
  state?: string;
  main?: boolean;
}

export interface ProcInfo {
  state?: string;
  ppid?: number | string;
  threads?: number;
  mem?: Record<string, number>;
  oomScoreAdj?: number | string;
  oomScore?: number | string;
  uptime?: number;
  uid?: string;
  gid?: string;
  cpuSeconds?: number;
  utime?: number;
  stime?: number;
  fds?: number;
  ctxtVoluntary?: number;
  ctxtNonvoluntary?: number;
  processor?: number;
  threadList?: ProcThread[];
  threadsTruncated?: boolean;
  threadsShown?: number;
  cmdline?: string;
  exe?: string;
  cwd?: string;
}

export interface Component {
  kind: string;
  class?: string;
  permission?: string;
  authorities?: string[];
  actions?: string[];
  fromFilterOnly?: boolean;
}

export interface Components {
  found?: boolean;
  note?: string;
  total?: number;
  versionName?: string;
  counts?: Record<string, number>;
  components?: Component[];
}

export interface PullResult {
  target?: TargetInfo;
  opened?: boolean;
  openError?: string;
  warn?: string;
  note?: string;
  skippedPull?: boolean;
  file?: string;
}

/* ---- retained parameter metadata (ui/params.ts; backend code.params / param.rename, 2026-10-08) ---- */
/** one formal argument as it stands: position, type, the decompiler's current name and where it comes from */
export interface ParamCurrent {
  index: number;
  type: string;
  register?: number;
  name?: string;
  nameSource?: string;   // analyst | debug_info | annotation (applied by the engine by position — unverified) | generic | engine
}
export interface ParamAnnotation {
  source: string;
  status: 'present' | 'absent' | 'malformed' | 'unsupported' | string;
  why?: string;
  problems?: string[];
  names?: (string | null)[];
  accessFlags?: (number | null)[];
  accessFlagsDecoded?: (string[] | null)[];
  namesCount?: number;
  flagsCount?: number;
  countMatchesPrototype?: boolean;
  namesCountMatchesPrototype?: boolean;
  flagsCountMatchesPrototype?: boolean;
  mismatch?: string;
  nonEmptyNames?: number;
  nullNames?: number;
  duplicates?: string[];
  notJavaIdentifiers?: string[];
  argumentMapping?: string;
}
export interface ParamDebugInfo {
  source: string;
  status: string;        // present (≥1 name) | empty (a table of null slots — names nothing) | absent | no-code | unsupported
  why?: string;
  names?: (string | null)[];
  count?: number;
  nonEmpty?: number;
  countMatchesPrototype?: boolean;
}
export interface ParamMappingChange { index: number; oldName?: string; newName?: string; cleared?: boolean; oldSource?: string; annotationIndex?: number; from?: string; adopted?: boolean }
export interface ParamMapping { id?: string; actor?: string; ts?: string; source?: string; changes?: ParamMappingChange[]; argumentMapping?: string }
/** code.params — the method's retained metadata, a read */
export interface MethodParams {
  schema?: string;
  method: string;
  original_method?: string;
  dex?: string | null;
  prototype?: { parameterTypes?: string[]; count?: number; static?: boolean; registerWords?: number; returnType?: string | null };
  annotation?: ParamAnnotation;
  debugInfo?: ParamDebugInfo;
  sourcesAgree?: boolean;
  parameters?: ParamCurrent[];
  locals?: string[];                                                                               // the body's own names (a mapping onto one is a collision)
  /** which arguments JEB currently names from the annotation by position: none / some / all of them —
   *  appliesAnnotationByPosition is the compatibility boolean for ALL; null: not computed in this view (the raw block) */
  decompiler?: { engine?: string; appliesAnnotationByPosition?: boolean | null; appliesAnnotationToAny?: boolean; annotationNamedArguments?: { status: 'none' | 'some' | 'all'; count: number; of: number; indices: number[] } | null; note?: string };
  mappings?: ParamMapping[];
  argumentMapping?: string;
  note?: string;
}
/** param.rename / param.rename.bulk — the applied mapping (one History entry) */
export interface ParamRenameResult {
  addr?: string;
  newAddr?: string;
  kind?: string;
  changes?: ParamMappingChange[];
  applied?: boolean;
  historyId?: string;
  mappings?: ParamMapping[];
}
/** param.rename.bulk {preview: true} — what WOULD happen, nothing applied */
export interface ParamPreviewRow { index?: number; name?: string; annotationIndex?: number; position?: number; currentName?: string; currentNameSource?: string; type?: string; problem?: string; noop?: string; adopts?: string; overwrites?: string; collidesWith?: number }
export interface ParamPreview {
  method?: string;
  original_method?: string;
  rows?: ParamPreviewRow[];
  problems?: string[];
  effective?: number;
  applicable?: boolean;
  why?: string;            // inapplicable without a problem: nothing would change
  argumentMapping?: string;
  note?: string;
}

/* ==================================================================
   Op registry: op name → RESULT shape (api.ts). Params stay loose.
================================================================== */
export type OpParams = Record<string, unknown>;

export interface LabArtifact { sha256: string; bytes: number; format: string; private: boolean }
export interface LabFixture { id: string; kind: string; synthetic: boolean; conversionSupported: boolean; replaySupported: boolean; artifact: LabArtifact; lineage?: unknown }
export interface LabTrial { id: string; state: string; attempt: string; processGeneration: string; request: { fixture: string; mode: string }; result?: { artifact: LabArtifact; stages: string[]; error?: string } }
export interface LabStatus { state: string; available?: boolean; projectOpen?: boolean; apkMatch?: boolean; ownerProject?: string; ownerTarget?: string; ownerActive?: boolean; ownerState?: string; reason?: string; bindingReason?: string; session?: string; processGeneration?: string; runtime?: unknown; binding?: unknown; identity?: unknown; cleanup?: string | { state?: string; errors?: string[]; postDetachHealth?: { sameProcess: boolean | null; seconds: number } } }
export interface LabExpansion { text: string; bytes: number; offset: number; next: number | null; private: boolean }

export interface OpMap {
  'code.params': MethodParams;
  'param.rename': ParamRenameResult;
  'param.rename.bulk': ParamRenameResult | ParamPreview;
  'rename.revert': EditResult;
  'workspace.state': WorkspaceState;
  'workspace.sysmem': SysMem;
  'project.list': Project[];
  'project.known': KnownProject[];
  'project.open': WorkspaceState;
  'project.close': WorkspaceState;
  'project.switch': WorkspaceState;
  'project.forget': { parked?: string };
  'project.delete': { orphanedTargets?: { sizeBytes?: number }[] };
  'project.targets': { items?: ProjectMember[] } | ProjectMember[];
  'target.open': TargetInfo;
  'target.close': WorkspaceState;
  'target.rekeyUndo': { target: string; closed: boolean; restored: string; note: string };
  'target.view': { target: string; view: TargetView | null };
  'target.view.set': { target: string; saved: boolean };
  'target.changes': { target: string; symbols: number; renamed: number; commented: number; decompiled: number; dirty: boolean; lastSaved: string | null };   // what a close would write: distinct symbols since the last save (drives the close dialog)
  'target.switch': WorkspaceState;
  'target.remove': WorkspaceState;
  'target.save': unknown;
  'target.info': TargetInfo;
  'fs.list': FsList;
  'symbol.resolve': ResolvedSymbol;
  'symbol.tree': TreePage;
  'symbol.search': SymbolSearchHit[];
  'code.decompile': DecompileData;
  'code.smali': SmaliData;
  'code.classinfo': ClassInfo;
  'xref.to': RelationRow[];
  'callees': RelationRow[];
  'impls': RelationRow[];
  'strings': StringsPage;
  'string.xrefs': XrefRow[];
  'search': SearchGroup[];
  'search.index': SearchIndexInfo;
  'search.reindex': SearchIndexInfo;
  'rename': EditResult;
  'comment': EditResult;
  'tag.add': unknown;
  'tag.rm': unknown;
  'tag.list': TagList;
  'bookmark.add': unknown;
  'bookmark.rm': unknown;
  'bookmark.list': Bookmark[];
  'history.list': HistoryEntry[];
  'history.undo': unknown;
  'history.redo': unknown;
  'flow.list': Flow[];
  'flow.get': FlowDetail;
  'flow.add': unknown;
  'flow.rm': unknown;
  'flow.promote': { map?: string };
  'runs.import': RunInfo;
  'runs.export': { valid: boolean; manifest_sha256: string; files: number; bytes: number; destination: string; selected_only: boolean };
  'runs.devices': { devices?: Device[] };
  'runs.attach': { pid?: number };
  'runs.spawn': { pid?: number };
  'runs.detach': unknown;
  'runs.bp.list': Breakpoint[];
  'runs.bp.add': unknown;
  'runs.bp.rm': unknown;
  'runs.bp.toggle': unknown;
  'runs.hits': { hits?: Hit[] };
  'runs.runs': RunInfo[];
  'runs.diff': RunDiff;
  'runs.coverage': { addrs?: { addr: string }[] };
  'map.list': MapSummary[];
  'map.get': MapData;
  'map.node': NodeDetail;
  'map.type.list': TypeRegistry;
  'map.type.add': TypeEntry;
  'map.type.set': unknown;
  'map.create': MapCreateResult;
  'map.addnode': unknown;
  'map.addedge': unknown;
  'map.rmnode': unknown;
  'map.rmedge': unknown;
  'map.setverdict': unknown;
  'map.export': { text: string };
  'map.open': unknown;
  'map.close': unknown;
  'map.delete': { id?: string; parked?: string };
  'agent.missions': Mission[];
  'agent.spawn': unknown;
  'agent.pause': unknown;
  'agent.resume': unknown;
  'agent.stop': unknown;
  'agent.activity': ActivityRow[];
  'agent.turns': TurnSummary[];
  'agent.turn': TurnRecord;
  'agent.proposals': Proposal[];
  'agent.review': unknown;
  'agent.follow': { following?: string };
  'agent.unfollow': unknown;
  'llm.providers': Provider[];
  'llm.configure': Provider;
  'llm.setkey': unknown;
  'llm.remove': unknown;
  'llm.rename': Provider & { renamedFrom?: string; tasksRewritten?: { task: string; depth: string; model: string }[]; effectiveKeyChanged?: boolean; hint?: string };
  'llm.models': { id: string; models: string[] };
  'llm.probe': { id: string; label?: string; driver?: string; model: string; ok: boolean; latencyMs?: number; reply?: string; servedModel?: string; usage?: unknown; error?: string; hint?: string; billing?: string };   // the wet test: one tiny completion (or a bare `claude -p`) — key valid? credit? replies?
  'task.list': TaskRow[];
  'task.configure': TaskDepth;
  'layout.list': LayoutRow[];
  'layout.save': unknown;
  'layout.apply': { layout: LayoutSnapshot };
  'layout.delete': unknown;
  'device.list': { devices?: Device[] };
  'device.catalog': Catalog;
  'device.components': Components;
  'device.proc': ProcInfo;
  'device.pull': PullResult;
  'device.spawn-mission': unknown;
  'presence.ping': { pinged?: boolean };
  'presence.whereis': Whereis;
}

export type OpName = keyof OpMap;
/** the result type of an op — `unknown` for a name the map does not know
 *  (a dynamically built name); a union of names distributes */
export type OpResult<K extends string> = K extends OpName ? OpMap[K] : unknown;

/* ==================================================================
   SSE event bus (api.ts)
================================================================== */
/** `closed` is the server's last word on a stream it ends ({reason});
 *  `connection` is synthetic, from api.ts: {state: open | retrying | reconnecting, inMs?} */
export type SseChannel = 'hello' | 'heartbeat' | 'progress' | 'presence' | 'activity' | 'proposals' | 'runs' | 'closed' | 'connection' | 'message' | string;
/** event payloads are heterogeneous per channel and read defensively — a
 *  loose record by design (JSON.parse's output, or the raw string when it
 *  was not JSON) */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type SseData = { [key: string]: any } | string | null;
export type SseHandler = (channel: SseChannel, data: SseData) => void;

/* ==================================================================
   Cross-module state (hub.ts `S`) and the late-bound hook bag
================================================================== */
export interface Caret {
  line: number;
  col: number;
  goal: number;
}

/** one caret-highlightable token of a rendered line (caret.ts buildCodeModel) */
export interface CodeToken {
  start: number;
  end: number;
  el: HTMLElement;
  name: string;
}

export interface CodeLine {
  cl: HTMLElement;
  lc: HTMLElement | null;
  text: string;
  tokens: CodeToken[];
}

export interface OpenTab {
  symbol: string;
  simple: string;
  renamed: boolean;
  addr: string;
  active?: boolean;
}

export interface TargetView {
  class: string | null;
  caret: { line: number; col: number };
  scroll: number;
  tabs: OpenTab[];
}

/** glyph-margin marks per 1-based line (code.ts loadMarks) */
export interface LineMark {
  tags: string[];
  bm: boolean;
  cov?: boolean;
  bp?: boolean;       // a breakpoint on the method declared on this line (runs.bp.list)
  bpOff?: boolean;    // …that is disabled
}

/** the symbol under the caret (code.ts symbolAtCaret): a token hit carries
 *  its line; the class fallback does not */
export interface CaretHit {
  name?: string;
  symbol: string | null;
  kind?: string;
  line?: number;
  local?: string;          // a local variable / parameter: its current name; symbol is then the enclosing METHOD
}

/** hooks registered by feature modules; updateCenter + friends call these
 *  through hook(name, …) or S.hooks.x directly. Every member is optional:
 *  the owning module registers it at init. */
export interface Hooks {
  addToMap?: (sub: { symbol: string; name: string; kind: string; fqcn?: string }) => void;   // "Add to map…" (audit decision 10)
  // ui/params.ts — retained MethodParameters (2026-10-08)
  refreshParams?: (method: string | null | undefined) => void;   // the caret's method changed: paint its metadata (code.ts caretFollow)
  applyRetained?: (local?: string) => void;                       // the context menu's "Apply retained name to argument…" on the argument under the caret
  afterEdit?: () => Promise<void>;                                // code.ts: dirty + HISTORY + re-render after any write (what the rename dialog does)
  resetUiPrefs?: () => void;   // ⌘K "Reset layout & theme" (audit decision 17)
  // app.ts
  targetSwitched?: (chip: HTMLElement) => Promise<void>;
  focusTreeFilter?: () => void;
  renderTabs?: () => void;
  saveViews?: () => void;   // persist the viewed target's position NOW (before a project close/switch)
  classOpened?: (symbol: string, simple: string, renamed: boolean | string | undefined, handle: string, prevFqcn?: string) => void;
  /** the open class itself was renamed (a human F2 or an accepted proposal): retitle its tree row */
  classRenamed?: (oldFqcn: string, oldAddr: string | undefined, newFqcn: string, newSimple: string, restored?: boolean, key?: string) => void;   // restored: the dex's own name is back (rename.revert / undo) — tabs and the tree row lose their renamed mark; key: the stable re-open handle (the original signature) the tree row takes
  refreshProjectCount?: () => Promise<void>;
  reseedTarget?: () => Promise<string | null>;
  viewOpened?: (v: CenterView) => Promise<void> | void;
  /** opts.awaitSwitch: the dead-cursor landing (app.ts reseedTarget) waits for the
   *  full switch chain and falls to the empty state when the switch fails */
  projectSwitched?: (state: WorkspaceState, opts?: { awaitSwitch?: boolean }) => Promise<void>;
  targetReady?: (t: { id?: string } | null | undefined) => Promise<boolean>;
  targetOpened?: (t: TargetInfo | { id: string }, symbol?: string) => Promise<void>;
  // chrome.ts
  openProjMenu?: () => void;          // ⌘⇧O — the project menu (palette PROJECT rows)
  newProject?: () => void;
  closeProject?: () => void;
  toggleDock?: () => void;
  toggleSide?: () => void;
  confirmDeleteMap?: (id: string) => void;
  /** NOT a hook: the guided open-target flow's resume FLAG (launcher.ts sets
   *  it, the projmenu reads/clears it). Lives in the bag by history. */
  needProjectThenOpen?: boolean;
  // caret.ts
  measureRender?: () => void;
  // code.ts
  caretFollow?: () => void;
  openRename?: () => void;
  openComment?: () => void;
  proposalsChanged?: () => void;   // the EDITS pane repaints (a comment deleted from the code view)
  autoNameLocals?: () => void;   // ⇧N: name the caret method's generated locals after their types (code.ts)
  saveTarget?: () => Promise<void>;
  toggleBp?: () => Promise<void>;
  gotoDefAtCaret?: () => Promise<void>;
  implementations?: () => Promise<void>;
  showOutline?: () => Promise<void>;
  panelShown?: (id: string) => void;
  reloadCode?: () => Promise<void>;
  gotoLine?: (line: number) => void;
  marksChanged?: () => Promise<void> | void;
  covCycle?: () => Promise<void>;
  // navigation.ts
  navBack?: () => void;
  navFwd?: () => void;
  gotoDef?: (sym: string | null | undefined) => Promise<void>;
  navPush?: (symbol: string, simple: string, line?: number | null) => void;
  navGuard?: <T>(fn: () => Promise<T> | T) => Promise<T>;
  setNavTarget?: (tid: string | null) => void;
  // relation.ts
  focusSelection?: () => void;
  // ribbon.ts
  jumpDone?: () => void;
  // tags.ts / bookmarks.ts / history.ts
  addTag?: () => void;
  toggleBookmark?: () => Promise<void>;
  historyChanged?: () => Promise<void>;
  undoHistory?: () => Promise<void>;
  redoHistory?: () => Promise<void>;                              // ⇧⌘Z: the newest undone entry back (history.ts)
  // layouts.ts
  layoutSaveDialog?: () => void;
  layoutApplyDialog?: () => void;
  layoutDeleteDialog?: () => void;
  // launcher.ts
  openLauncher?: () => void;
  // map.ts
  renderOverview?: () => void;
  closeTypes?: () => void;
  focusMap?: () => void;
  mapProjectSwitched?: () => Promise<void>;
  reloadMap?: (id: string | null) => Promise<void>;
  newMap?: () => void;
  // agents.ts / device.ts
  agentsOpened?: () => void;
  openProviders?: () => void;         // the PROVIDERS dialog (palette)
  openReverseSettings?: () => void;   // the REVERSING AGENT window (palette, the ⚡ menu)
  deviceOpened?: () => Promise<void>;
}

/** the callable members of Hooks (hub.ts hook() takes only these) */
export type HookFns = {
  [K in keyof Hooks as NonNullable<Hooks[K]> extends (...args: never[]) => unknown ? K : never]-?: NonNullable<Hooks[K]>;
};

/** the cross-module mutable state — see hub.ts for the initial values and
 *  the ownership notes on each field */
export interface WorkbenchState {
  center: CenterView;
  state: WorkspaceState | null;
  currentTid: string | null;
  caret: Caret;
  codeLines: CodeLine[];
  charW: number;
  caretReady: boolean;
  hooks: Hooks;
  // code view (code.ts / app.ts)
  openTabs: OpenTab[];
  targetViews: Record<string, TargetView>;
  currentClass: string | null;
  currentSym: SymbolInfo;
  currentFqcn: string | null;
  javaMethods: MethodInfo[];
  crumbBase: string;
  lineMarks: Record<number, LineMark>;
  covRun: string | null;
  // shell (chrome.ts / caret.ts) — DOM refs bound at init
  codewrap?: HTMLElement;
  rbtns: HTMLElement[];
  syncOn: boolean;
  bytesOn: boolean;
  cameFromMap: boolean;
  jumpInFlight: number;
  dzActive: boolean;
  jumpToCode?: (target?: string | null, symbol?: string | null) => Promise<boolean>;
  // map / search view state read by the shell
  mapId: string | null;
  mapSel: string | null;
  srchScope: 'target' | 'all';
}
