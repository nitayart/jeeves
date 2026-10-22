// views/map.ts — MAP VIEW live (GRAPHS_SPEC): canvas from map.get, type registry
// from map.type.list (TYPES menu edits via map.type.set/add), node inspector from
// map.node (verdict proposals settled as human; registry verdict picker + node/
// edge delete behind the projmenu's two-step confirm — P0.2), cross-target
// jumps, ＋NODE/＋EDGE/EXPORT dialogs, the canvas's own empty state (P0.1).
import { $all, S, pushEscape, popEscape, ESC, errMsg, escHtml as esc, isHexColor } from '../ui/hub.js';
import { tryOp } from '../api.js';
import { updateCenter, jumpToCode, showCenter, mapSurfaceOn, renderMapChip, notice, activeTargetName } from '../ui/chrome.js';
import { loadSymbol, highlight } from './code.js';
import type { MapData, MapEdge, MapNode, MapSymbol, TypeKey, TypeRegistry } from '../types.js';

let mapview: HTMLElement, mapworld: HTMLElement, mapedges: SVGSVGElement, maplbl: SVGSVGElement;
let panX = 0, panY = 0, zoom = 1;
let mzLabelEl: HTMLElement | null = null;   /* applyView paints it at wheel rates — cache it */
/* content bounds: measured by repackLayers (filter-aware), consumed by
   computeFit (FIT + the zoom floor), the SVG layers, and the minimap. mmScale
   is the minimap's px-per-world-unit (click-to-nav inverts it). fittedFor
   makes focusMap refit only when the map identity changes. */
let contentW = 0, contentH = 0, mmScale = 0, fittedFor: string | null = null, mmView: HTMLElement | null = null;
const SVGNS = 'http://www.w3.org/2000/svg';
const KINDICON: Record<string, string> = { endpoint: '◉', frontdoor: '◈', allowlist: '▤', handler: '◆', native: '⬡', sink: '▼', source: '▲', custom: '◍' };
const SWATCHES = ['#0072B2', '#56B4E9', '#D55E00', '#E69F00', '#009E73', '#CC79A7'];

let mapId: string | null = null;
let MAP: MapData | null = null;   // live map JSON
let NODES: MapNode[] = [], EDGES: MapEdge[] = [];
let types: TypeRegistry | null = null;   // registry from map.type.list
let wired = false;
/* P0.1: the last failed map.get ({id, message}) — renderEmpty's failed arm.
   Set by loadMap's error path, cleared by the next load attempt */
let loadErr: { id: string; message: string } | null = null;
/* P0.2: the ONE armed delete confirm ({row, go, onCancel}) — node or edge.
   Arming another, a selection change, a rail blank or Esc disarms it */
interface Armed { row: HTMLElement; go: HTMLElement; onCancel?: () => void; nodeId?: string }
let armed: Armed | null = null;

/* ---- layout from layer (schema has no coords; layer is the only hint) ---- */
function layout(map: MapData){
  const perLayer: Record<number, number> = {};
  (map.nodes || []).forEach(function(n){
    const L = n.layer || 0;
    perLayer[L] = (perLayer[L] || 0) + 1;
    const i = perLayer[L] - 1;
    // pitch must exceed the widest card: 300px pitch under a 320px card made
    // adjacent columns overlap by 20px (the "labels overwrite boxes" report)
    n.x = 16 + L * 340;
    n.y = 16 + i * 64;
    n.w = 320;
    if (L < 2) n.w = 240;
  });
}

function typeEntry(key: TypeKey, name: string | undefined){
  const a = (types || {} as TypeRegistry)[key] || [];
  return a.find(t => t.name === name) || null;
}
function typeColor(key: TypeKey, name: string | undefined){
  const t = typeEntry(key, name);
  return t ? safeColor(t.color) : '#7d8794';
}
/* a registry colour is server text (map.type.add/set take it unvalidated,
   and agents can write it): only a hex literal may reach a style="…" —
   anything else falls back to the neutral grey (review 2026-09-09 HIGH-5;
   agents.ts verdictChip/ghostTag already apply the same rule) */
function safeColor(c: unknown): string {
  return isHexColor(c) ? String(c) : '#7d8794';
}
function typeVisible(key: TypeKey, name: string | undefined){
  const t = typeEntry(key, name);
  return t ? t.visible !== false : true;
}
function typeCount(key: TypeKey, name: string){
  let c = 0;
  if (key === 'nodeKinds') NODES.forEach(n => { if (n.kind === name) c++; });
  if (key === 'edgeKinds') EDGES.forEach(e => { if (e.kind === name) c++; });
  if (key === 'verdicts') NODES.forEach(n => { if ((n.verdict || 'none') === name) c++; });
  if (key === 'tags') NODES.forEach(n => { if ((n.tags || []).includes(name)) c++; });
  return c;
}
function chipText(hex: string){
  return (hex === '#E69F00' || hex === '#F0E442') ? '#1c2430' : '#ffffff';
}
function nodeById(id: string | null){ return NODES.find(n => n.id === id) || null; }

/* blank the WHOLE node inspector body — shared by loadMap (map switch),
   clearMap (project switch) and populateMapRel's error arm. The old two-field
   reset left the previous map's verdict/gates/code/fields/chain under a new
   title (opus map-r5 F5; in-map variant map-r7 F6) */
function blankMapRel(){
  const mrLabel = document.getElementById('mr-label');
  if (mrLabel) mrLabel.textContent = '—';
  const mrKind = document.getElementById('mr-kind');
  if (mrKind) mrKind.textContent = '—';
  /* the verdict chip is a <select> now (P0.2): a textContent write would wipe
     its options — reset to the disabled placeholder instead */
  const mrV = document.getElementById('mr-verdict') as HTMLSelectElement | null;
  if (mrV){
    mrV.innerHTML = '<option value="">—</option>';
    mrV.disabled = true;
    mrV.style.backgroundColor = ''; mrV.style.color = ''; mrV.classList.remove('dk');
  }
  const mrG = document.getElementById('mr-gates');
  if (mrG) mrG.textContent = '—';
  const mrH = document.getElementById('mr-code-head');
  /* clear the jump handler too — the panel can stay VISIBLE on the error arm
     (updateCenter owns hidden), and a stale onclick jumped the PREVIOUS node
     under an 'ENTRY · —' label (map-r8 #3) */
  if (mrH){ mrH.textContent = 'ENTRY · —'; mrH.onclick = null; }
  const mrB = document.getElementById('mr-code-body');
  if (mrB) mrB.innerHTML = '';
  const mrF = document.getElementById('mr-fields');
  if (mrF) mrF.innerHTML = '';
  const mrC = document.getElementById('mr-chain');
  if (mrC) mrC.innerHTML = '';
  const mrA = document.getElementById('mr-agent-text');
  if (mrA) mrA.textContent = '';
  const mrAct = document.getElementById('mr-actions');
  if (mrAct) mrAct.style.display = 'none';
  /* P0.2: an armed delete confirm never survives a blank — the row it owned is
     being rewritten under it (no onCancel: the DOM is reset right here) */
  armed = null;
  const mrCf = document.getElementById('mr-confirm');
  if (mrCf){ mrCf.hidden = true; mrCf.innerHTML = ''; }
  const mrE = document.getElementById('mr-edges');
  if (mrE) mrE.innerHTML = '';
}

/* ==================================================================
   LOAD + RENDER
================================================================== */
/* drop ALL map module state — maps and the type registry are project-scoped,
   so a project switch (or a mapless project) must not inherit the previous
   project's canvas, selection, fit latch or content bounds, and node jumps
   must not aim at the previous project's target ids (opus map-r4 #2) */
function clearMap(){
  mapId = null; S.mapId = null;
  MAP = null; NODES = []; EDGES = []; types = null;
  S.mapSel = null; fittedFor = null;
  contentW = 0; contentH = 0; mmScale = 0;
  renderAll();
  blankMapRel();
  document.getElementById('map-rel')!.hidden = true;
  document.getElementById('map-overview')!.hidden = false;
  const moV = document.getElementById('mo-verdicts');
  if (moV) moV.innerHTML = '';
  const moP = document.getElementById('mo-proven');
  if (moP) moP.textContent = '';
  const moT = document.getElementById('mo-title');
  if (moT) moT.textContent = '—';
  const moN = document.getElementById('mo-name');
  if (moN) moN.textContent = '—';
  const moS = document.getElementById('mo-sig');
  if (moS){ moS.textContent = '—'; moS.title = ''; }
  /* ＋NODE/＋EDGE/EXPORT are meaningless with mapId null (E_SYNTAX / silent
     no-op) — disabled until a map loads (opus map-r5 F19) */
  ['mapm-addnode', 'mapm-addedge', 'mapm-export'].forEach(function(b){
    const el = document.getElementById(b) as HTMLButtonElement | null;
    if (el) el.disabled = true;
  });
  /* the chip IS the activation readout: nothing open → no chip, instead of the
     ghost labelled 'MAP' this used to leave behind (map-lifecycle step 1) */
  renderMapChip(null);         /* …and returns the surface to code if the canvas was showing */
  /* UNCONDITIONAL surface recompute (was gated on wasOn): "＋ New project"
     from the code view is a mapless+targetless switch — updateCenter owns the
     map-rel/map-overview flags from S.mapSel, and without this call the just-
     unhidden overview block lingered in the CODE view's rail (opus map-r5 F3) */
  updateCenter();
}

async function loadMap(id: string | null | undefined){
  loadErr = null;                     /* a new attempt clears the last failure (P0.1) */
  if (!id){
    const st = await tryOp('workspace.state', {});
    id = st.data && st.data.activeMap;
    /* NO map.list[0] fallback: it resurrected an arbitrary map on every boot
       and every project switch, which made map.close unrepresentable — the
       next reload silently re-opened something. Closed renders as: no chip,
       code view, the MAPS section still listing the inventory
       (map-lifecycle step 1). */
  }
  /* no resolvable map: CLEAR, don't return — the early return left the
     previous project's map rendered under the new project's breadcrumb
     (opus map-r4 #2) */
  if (!id){ clearMap(); return; }
  const [g, t] = await Promise.all([tryOp('map.get', { id }), tryOp('map.type.list', {})]);
  /* a failed get must not leave mapId/S.mapId pointing at an UNLOADED map
     (a phantom activeMap bypasses the no-id clear path — opus map-r5 F2/#12):
     clear instead of returning */
  if (g.error){
    console.warn('map.get', g.error.message);
    clearMap();
    /* P0.1 (+P1.14): a failed get was console-only and looked exactly like
       "this project has no map". Module state stays CLEARED — no write op may
       aim at an unloaded map (F2/#12 stands) — but the failure is painted on
       the canvas, and the chip goes BACK: the server still reports this id as
       activeMap, so the chip is the honest readout, and its × (map.close) is
       the recovery. Without a chip the canvas is unreachable (showCenter
       refuses a chipless map) and the failure stays invisible. */
    loadErr = { id: id, message: g.error.message };
    renderEmpty();
    renderMapChip(id);
    return;
  }
  mapId = id;
  S.mapId = id;                       // breadcrumb follows the open map (QA m9)
  MAP = g.data;
  types = t.data || {};
  /* a freshly-loaded map brings a NEW registry — an open TYPES menu's rows
     would stay bound to the old one, and a toggle would appear to do nothing
     (map-r6 #10) */
  if (S.hooks.closeTypes) S.hooks.closeTypes();
  layout(MAP);
  NODES = MAP.nodes || [];
  EDGES = MAP.edges || [];
  S.mapSel = null;                          // D9: a different map — drop the old selection/rail
  blankMapRel();
  ['mapm-addnode', 'mapm-addedge', 'mapm-export'].forEach(function(b){
    const el = document.getElementById(b) as HTMLButtonElement | null;
    if (el) el.disabled = false;
  });
  renderAll();
  renderOverview();
  renderMapChip(MAP.id);          // the chip follows the ACTIVE map, from state
}

function renderAll(){
  mapworld.querySelectorAll('.mnode').forEach(e => e.remove());
  document.getElementById('minimap')!.querySelectorAll('.mm').forEach(e => e.remove());
  mapedges.innerHTML = '';
  maplbl.innerHTML = '';

  NODES.forEach(function(n){
    const vc = typeColor('verdicts', n.verdict || 'none');
    const el = document.createElement('div');
    el.className = 'mnode' + (n.proven ? ' proven' : '');
    el.setAttribute('data-id', n.id);
    el.style.left = n.x + 'px';
    el.style.top = n.y + 'px';
    el.style.width = n.w + 'px';
    el.style.borderLeftColor = vc;
    const bolts = (MAP!.fields || []).filter(f => f.node === n.id && f.verdict === 'verbatim').length;
    el.innerHTML =
      '<div class="mhead"><span class="mkind" style="color:' + typeColor('nodeKinds', n.kind) + '">' +
        (KINDICON[n.kind!] || '◆') + '</span>' +
      '<span class="mlabel" title="' + esc(n.label) + '">' + esc(n.label) + '</span>' +
      (bolts ? '<span class="mbolt" data-tip="⚡ verbatim fields: ' + bolts + '">⚡</span>' : '') + '</div>' +
      (n.sublabel ? '<div class="msub" title="' + esc(n.sublabel) + '">' + esc(n.sublabel) + '</div>' : '') +
      '<div class="mchips">' +
      (n.verdict && n.verdict !== 'none'
        ? '<span class="vchip" style="background:' + vc + ';color:' + chipText(vc) + '">' + esc(n.verdict).toUpperCase() + '</span>' : '') +
      (n.proven ? '<span class="vchip pv" data-tip="dynamically proven — run evidence attached">PROVEN</span>' : '') +
      (n.pending ? '<span class="vchip vc-mixed" data-tip="verdict proposal pending review">⏳ ' + esc(n.pending.verdict).toUpperCase() + '</span>' : '') +
      '</div>' +
      (n.target ? '<span class="mtgt' + (n.target !== S.currentTid ? ' x' : '') + '">' + esc(n.target) + '</span>' : '');
    n.el = el;
    mapworld.appendChild(el);
    el.addEventListener('click', function(ev){
      ev.stopPropagation();
      mapview.focus();
      selectNode(n.id);
    });
    el.addEventListener('dblclick', function(ev){
      ev.stopPropagation();
      el.classList.add('nflash');
      setTimeout(function(){ el.classList.remove('nflash'); }, 400);
      jumpNode(n);
    });
    const mm = document.createElement('span');
    mm.className = 'mm';
    mm.style.background = vc;
    n.mmEl = mm;
    document.getElementById('minimap')!.appendChild(mm);
  });

  applyTypeFilters();   // node visibility BEFORE the repack measures bounds (r3 F3)
  repackLayers();       // measured card heights — no overlaps
  renderEdges();        // edges self-hide (edgeHidden) and self-label (labelLit)
}

/* edge state, decided at CREATION: a later pass (applyTypeFilters/selectNode)
   is lost whenever edges re-render (ResizeObserver, repack) — opus map-r3 F1 */
function edgeHidden(e: MapEdge){
  const a = nodeById(e.from), b = nodeById(e.to);
  return !typeVisible('edgeKinds', e.kind) ||
    (a && a.el && a.el.style.display === 'none') ||
    (b && b.el && b.el.style.display === 'none');
}

/* edge labels surface only around the selected node — capped: a fan-out hub
   (allowlist → 48 handlers) would relight the unreadable glyph stack */
function labelLit(e: MapEdge){
  if (!S.mapSel) return false;
  let related = 0;
  /* hidden edges don't count toward the cap: filtering a hub down to 3 visible
     edges should light their labels, not stay mute because 9 exist (map-r5 F17) */
  EDGES.forEach(function(x){ if (!edgeHidden(x) && (x.from === S.mapSel || x.to === S.mapSel)) related++; });
  return related <= 8 && (e.from === S.mapSel || e.to === S.mapSel);
}

/* Edges + labels from current node positions — extracted so focusMap can
   redraw after a repack (a render that happened while hidden leaves edges
   at the pre-repack geometry otherwise). */
function renderEdges(){
  mapedges.innerHTML = '';
  maplbl.innerHTML = '';
  EDGES.forEach(function(e){
    const a = nodeById(e.from), b = nodeById(e.to);
    if (!a || !b) return;
    const hidden = edgeHidden(e);
    const x1 = a.x + a.w, y1 = a.y + 24;
    const x2 = b.x, y2 = b.y + 24;
    const mx = (x1 + x2) / 2;
    const d = 'M' + x1 + ' ' + y1 + ' C' + mx + ' ' + y1 + ',' + mx + ' ' + y2 + ',' + x2 + ' ' + y2;
    const color = e.confidence === 'low' ? '#7d8794'
      : e.confidence === 'dyn' ? '#009E73' : typeColor('edgeKinds', e.kind);
    if (e.confidence === 'dyn'){
      const g = document.createElementNS(SVGNS, 'path');
      g.setAttribute('d', d);
      g.setAttribute('class', 'edge-glow');
      if (hidden) g.style.display = 'none';
      mapedges.appendChild(g);
      e.glowEl = g;
    }
    const p = document.createElementNS(SVGNS, 'path');
    p.setAttribute('d', d);
    p.setAttribute('class', 'edge ek-' + e.kind + ' conf-' + (e.confidence || 'static'));
    p.style.stroke = color;
    if (e.confidence === 'low') p.style.strokeDasharray = '4 3';
    if (hidden) p.style.display = 'none';
    mapedges.appendChild(p);
    e.el = p;
    if (e.label){
      const t = document.createElementNS(SVGNS, 'text');
      t.setAttribute('x', String(mx));
      t.setAttribute('y', String((y1 + y2) / 2 - 6));
      t.setAttribute('text-anchor', 'middle');
      t.setAttribute('class', 'elabel');
      t.textContent = e.label;
      if (hidden) t.style.display = 'none';
      t.classList.toggle('on', labelLit(e));
      maplbl.appendChild(t);
      e.labelEl = t;
    }
  });
}

/* row toggles: hide nodes by kind/verdict/tag invisibility. Edge visibility
   has exactly ONE owner — renderEdges decides at creation via edgeHidden; the
   old EDGES pass here ran on detached (renderAll) or just-redrawn (focusMap)
   elements and carried the file's last unguarded a.el deref (opus map-r5 F20) */
function applyTypeFilters(){
  NODES.forEach(function(n){
    const hide = !typeVisible('nodeKinds', n.kind) ||
                 !typeVisible('verdicts', n.verdict || 'none') ||
                 (n.tags || []).some(t => !typeVisible('tags', t));
    if (n.el) n.el.style.display = hide ? 'none' : '';
    if (n.mmEl) n.mmEl.style.display = hide ? 'none' : '';
  });
  /* a filter that hides the SELECTED node must not leave a live selection on
     an invisible card — the inspector, Enter-jump and moveSel all kept acting
     on it (opus map-r5 F13). Drop back to the overview. */
  if (S.mapSel){
    const n = nodeById(S.mapSel);
    if (n && n.el && n.el.style.display === 'none'){
      n.el.classList.remove('sel');
      S.mapSel = null;
      disarmConfirm();                 /* a confirm aimed at a now-hidden node (P0.2) */
      document.getElementById('map-rel')!.hidden = true;
      document.getElementById('map-overview')!.hidden = false;
    }
  }
  renderEmpty();   /* visibility decided → the empty-state arms follow (P0.1) */
}

/* the canvas's own honest empty state (P0.1) — the runs feed's .hitempty
   idiom (runs.js renderFeedEmpty). A zero-node map used to be a blank grey
   surface whose only text was the minimap's "MAP" — right after NEW MAP, the
   first thing anyone does with maps. Four arms, decided from module state:
   a failed map.get (loadErr), no map at all (chipless — normally unreachable,
   kept honest), a loaded map with zero nodes, and every node hidden by TYPES.
   Called from applyTypeFilters, the one owner of node visibility, so renderAll,
   the TYPES toggles and focusMap all keep it current. The block is
   click-transparent except its text (CSS): pan/wheel still reach the canvas. */
function renderEmpty(){
  const box = document.getElementById('mapempty');
  if (!box) return;
  let html = '', err = false;
  if (loadErr){
    err = true;
    html = '<b>map failed to load</b><span>' + esc(loadErr.id) + ' · ' + esc(loadErr.message) + '</span>' +
      '<span>× on the map chip closes it · project chip › MAPS lists the others</span>';
  } else if (!MAP){
    html = '<b>no map open</b><span>project chip › MAPS · ＋ New map…</span>';
  } else if (!NODES.length){
    const kinds = registryKinds();
    html = '<b>empty map · ' + esc(MAP.id) + '</b>' +
      '<span><button class="mbtn" data-go="mapm-addnode">＋NODE</button> adds a node by hand · ' +
      'right-click a symbol in the code › Add to map… · or spawn a mission in AGENTS (⌃5) and watch it map</span>' +
      (kinds.length ? '<span>node kinds in this project\'s registry: ' + esc(kinds.join(' · ')) + '</span>' : '<span>the type registry is empty — ⣿ TYPES adds kinds</span>');
  } else {
    let shown = 0;
    NODES.forEach(function(n){ if (n.el && n.el.style.display !== 'none') shown++; });
    if (!shown)
      html = '<b>all ' + NODES.length + ' node' + (NODES.length === 1 ? '' : 's') + ' hidden by TYPES</b>' +
        '<span><button class="mbtn" data-go="types-btn">⣿ TYPES</button> re-enables a kind, verdict or tag</span>';
  }
  box.hidden = !html;
  box.classList.toggle('err', err);
  box.innerHTML = html ? '<div class="me">' + html + '</div>' : '';
  $all('[data-go]', box).forEach(function(b){
    b.addEventListener('click', function(ev){
      /* stop here: the document click that closes an open TYPES menu would
         otherwise fire right after typesBtn opened it (same event) */
      ev.stopPropagation();
      const t = document.getElementById(b.getAttribute('data-go')!);
      if (t) t.click();
    });
  });
}

/* ==================================================================
   SELECTION + RAIL (map.node + excerpt)
================================================================== */
function selectNode(id: string){
  disarmConfirm();                     /* a confirm never outlives its selection (P0.2) */
  S.mapSel = id;
  NODES.forEach(function(n){ if (n.el) n.el.classList.toggle('sel', n.id === id); });
  EDGES.forEach(function(e){
    if (e.labelEl) e.labelEl.classList.toggle('on', labelLit(e));
  });
  populateMapRel(id);
}

async function populateMapRel(id: string){
  /* staleness guard: three sequential awaits then unconditional DOM writes —
     held-down arrow keys (or a map switch mid-flight) overlap calls, and an
     out-of-order response would paint a non-selected node's inspector
     (map-r6 #11) */
  const myMap = mapId;
  const stale = function(){ return S.mapSel !== id || mapId !== myMap; };
  const r = await tryOp('map.node', { id: mapId, node: id });
  if (stale()) return;
  if (r.error){
    /* a failed node read must not leave the PREVIOUS node's inspector under
       the new selection (map-r5 F5 class, in-map — map-r7 F6) */
    blankMapRel();
    document.getElementById('mr-kind')!.textContent = 'node unavailable: ' + r.error.message;
    return;
  }
  const d = r.data;
  document.getElementById('mr-label')!.textContent = d.label || id;
  // D8: the inspector's MAP mini-title follows the open map (was static fixture)
  const mt = document.querySelector('#map-rel .mini-title .meta');
  if (mt) mt.textContent = mapId;
  const kindEl = document.getElementById('mr-kind')!;
  kindEl.textContent = d.kind + (d.target ? ' · ' + d.target : '') + (d.sublabel ? ' · ' + d.sublabel : '');
  /* P0.2: the chip is a registry-driven picker — a human sets the node verdict
     by hand (the only write here used to be accept/reject of an AGENT's) */
  renderVerdictPick(d.verdict || 'none');
  // verdict proposal row (pending): accept/reject settles as human
  const agentText = document.getElementById('mr-agent-text')!;
  const actions = document.getElementById('mr-actions')!;
  if (d.pending){
    agentText.textContent = '⏳ proposed: ' + d.pending.verdict + ' · ' + (d.pending.actor || 'agent') +
      (d.pending.rationale ? ' · ' + d.pending.rationale : '') + ' · ';
    actions.style.display = '';
  } else {
    agentText.textContent = '';
    actions.style.display = 'none';
  }
  const gates = document.getElementById('mr-gates')!;
  gates.textContent = (d.gates || []).map(g => g.label + ' · ' + g.position).join(' · ') || '—';

  // CODE EXCERPT: decompile the entry symbol and slice its method (best-effort)
  const eh = document.getElementById('mr-code-head')!;
  const sym: MapSymbol = d.symbol || {};
  // D3: no stored symbol (promoted maps) — resolve the node label so the
  // excerpt and ENTRY jump still work. resolve returns sig/name, not addr.
  /* inspector resolve + decompile run on the node's OWN engine: unscoped they
     land on the shared activeTarget cursor and can render a DIFFERENT apk's
     source with no indication (opus map-r4 #4). undefined target is omitted
     from the request body (JSON.stringify drops undefined properties) */
  const tgt = d.target || S.currentTid || undefined;
  if (!sym.addr && d.label){
    const rs = await tryOp('symbol.resolve', { symbol: d.label, target: tgt });
    if (stale()) return;
    if (rs.data && (rs.data.sig || rs.data.name))
      Object.assign(sym, { addr: rs.data.sig || rs.data.name, entry: rs.data.name });
  }
  // no entry in the symbol? take the method from the label (Class.method) so
  // the excerpt shows the RELEVANT method, not the first constructor
  if (!sym.entry && d.label && d.label.includes('.'))
    sym.entry = d.label.substring(d.label.lastIndexOf('.') + 1);
  eh.textContent = 'ENTRY · ' + (sym.entry || sym.addr || '—');
  eh.onclick = function(){ jumpNode(nodeById(id)); };
  const eb = document.getElementById('mr-code-body')!;
  eb.innerHTML = '';
  let lines: [number, string][] | null = null;
  if (sym.addr){
    const dec = await tryOp('code.decompile', { symbol: sym.addr, target: tgt });
    if (stale()) return;
    if (dec.data && dec.data.text){
      const methods = dec.data.methods || [];
      const m = methods.find(mm => mm.name === sym.entry && mm.line)
          // no entry match (e.g. dotted-id labels): skip constructors, show
          // the first real method before falling back to anything with a line
          || methods.find(mm => mm.line && !String(mm.name).startsWith('<'))
          || methods.find(mm => mm.line);
      const all = dec.data.text.split('\n');
      if (m){
        lines = all.slice(m.line! - 1, Math.min(all.length, m.line! + 7)).map((t, i): [number, string] => [m.line! + i, t]);
      } else {
        // engines without method ranges (mock): locate the entry method textually
        const entry = sym.entry || String(sym.addr).split(/[.->]/).pop()!.replace(/\(.*$/, '');
        const at = all.findIndex(t => t.includes(entry + '('));
        if (at >= 0)
          lines = all.slice(at, Math.min(all.length, at + 8)).map((t, i): [number, string] => [at + i + 1, t]);
      }
    }
  }
  const lexState = { inBlockComment: false };
  (lines || ([[1, '// no resolvable symbol — open the class to annotate']] as [number, string][])).forEach(function(ln){
    const row = document.createElement('div');
    row.className = 'cx';
    row.innerHTML = '<span class="cxn">' + ln[0] + '</span><span>' + highlight(ln[1], lexState) + '</span>';
    eb.appendChild(row);
  });

  const f = document.getElementById('mr-fields')!;
  f.innerHTML = '<div class="fr head"><span>FIELD</span><span>VERDICT</span><span class="fe">EVIDENCE</span></div>';
  (d.fields || []).forEach(function(row){
    const fr = document.createElement('div');
    fr.className = 'fr';
    const ev = (row.evidence || []).map(e => e.run || e.note || '').filter(Boolean).join('▶ ') || '—';
    fr.innerHTML = '<span title="' + esc(row.field) + '">' + esc(row.field) + '</span>' +
      '<span class="fv-' + esc(row.verdict) + '">' + esc(row.verdict) + '</span>' +
      '<span class="fe" title="' + esc(ev) + '">' + esc(ev) + '</span>';
    f.appendChild(fr);
  });
  const c = document.getElementById('mr-chain')!;
  c.innerHTML = '';
  (d.chain || []).forEach(function(step, i){
    const s = document.createElement('div');
    s.className = 'cstep';
    s.innerHTML = '<div class="cs-l1"><span class="cn">' + (i + 1) + '</span><span>' + esc(step.label) + '</span></div>' +
      '<div class="cs-addr" title="' + esc((step.symbol || {} as MapSymbol).addr || '') + '">' + esc((step.symbol || {} as MapSymbol).addr || '') + '</div>';
    s.addEventListener('click', async function(){
      const addr = (step.symbol || {} as MapSymbol).addr;
      if (!addr) return;
      /* jump the STEP's own symbol — the old handler re-jumped the selected
         node, so a 5-step chain rendered five individually-hoverable rows that
         all did the same thing (map-r7 F7). Failure arms mirror jumpNode's
         (surface in #mr-kind, restore the map) — the no-chip/failed-load cases
         were silent after the r7 crumb drop (map-r8 #1) */
      const k = document.getElementById('mr-kind');
      const sw = await jumpToCode(d.target || undefined);
      if (sw === false){
        if (k) k.textContent = 'jump failed: no such target: ' + d.target;
        return;
      }
      try { await loadSymbol(addr); }
      catch (e){
        console.warn('chain jump', e);
        restoreMapSurface();
        if (k) k.textContent = 'jump failed: ' + errMsg(e);
      }
    });
    c.appendChild(s);
  });
  renderEdgeRows(id);                  /* P0.2: the node's edges, each with a hover-× */
  document.getElementById('map-rel')!.hidden = false;
  document.getElementById('map-overview')!.hidden = true;
}

/* ==================================================================
   HUMAN WRITES (P0.2): verdict picker · node/edge delete behind a confirm
   The map was add-only from the GUI — no delete, and a human could only
   rubber-stamp an agent's verdict. The ops existed (Ops.java map.setverdict /
   rmnode / rmedge); this is the missing half of MANUAL §9's "build maps by
   hand". Param shapes: setverdict {id, node, verdict} · rmnode {id, node} ·
   rmedge {id, from, to, kind}.
================================================================== */
/* the verdict <select>: options from the registry that also paints the chips
   (types.verdicts — authored/mixed/identity/gap/dead/none by default), the
   node's current verdict selected. A verdict the registry no longer lists is
   appended so the chip still reads TRUE, not the first registry entry. */
function renderVerdictPick(cur: string){
  const sel = document.getElementById('mr-verdict') as HTMLSelectElement | null;
  if (!sel) return;
  const names = ((types || {} as TypeRegistry).verdicts || []).map(t => t.name);
  if (!names.includes(cur)) names.push(cur);
  sel.innerHTML = names.map(n => '<option value="' + esc(n) + '"' + (n === cur ? ' selected' : '') + '>' +
    esc(String(n).toUpperCase()) + '</option>').join('');
  sel.value = cur;
  sel.disabled = false;
  /* backgroundColor, not the background shorthand — the shorthand would drop
     the CSS chevron image; .dk swaps it to the dark glyph on light chips */
  const vc = typeColor('verdicts', cur);
  sel.style.backgroundColor = vc;
  sel.style.color = chipText(vc);
  sel.classList.toggle('dk', chipText(vc) !== '#ffffff');
}

/* EDGES section: the selected node's edges from the loaded map (map.node
   carries fields/chain/gates, not edges), direction glyph + the far end's
   label + kind. Row click selects the far end when it is visible (the F13
   rule: never select a filtered-out card); hover-× arms the delete. */
function renderEdgeRows(id: string){
  const host = document.getElementById('mr-edges');
  if (!host) return;
  host.innerHTML = '';
  const mine = EDGES.filter(e => e.from === id || e.to === id);
  if (!mine.length){
    host.innerHTML = '<span class="dockhint">no edges · ＋EDGE in the map bar</span>';
    return;
  }
  mine.forEach(function(e){
    const out = e.from === id;
    const farId = out ? e.to : e.from;
    const far = nodeById(farId);
    const row = document.createElement('div');
    row.className = 'erow';
    /* paint restores THIS row only (cancel arm): re-rendering the whole host
       would detach the row being armed next — arming edge B while A's confirm
       is up runs A's cancel first, and B's confirm would land on a dead node */
    function paint(){
      row.innerHTML = '<span class="ed">' + (out ? '→' : '←') + '</span>' +
        '<span class="en" title="' + esc(farId) + '">' + esc(far ? far.label : farId) + '</span>' +
        '<span class="ek" style="color:' + typeColor('edgeKinds', e.kind) + '">' + esc(e.kind || '') +
          (e.label ? ' · ' + esc(e.label) : '') + '</span>' +
        '<span class="tx" role="button" aria-label="Delete edge" title="Delete edge (two-step)">×</span>';
      row.querySelector('.tx')!.addEventListener('click', function(ev){
        ev.stopPropagation();              /* never also select the far end */
        armConfirm(row, 'delete edge ' + e.from + ' → ' + e.to + ' · ' + (e.kind || 'any kind'), async function(){
          const myMap = mapId;
          /* kind narrows the match: MapStore.rmEdge drops EVERY from→to edge
             without it. Edges of one kind that differ only by label still go
             together — the op has no label param (reviewer caveat) */
          const r = await tryOp('map.rmedge', { id: mapId, from: e.from, to: e.to, kind: e.kind });
          if (r.error) return r.error.message;
          if (mapId !== myMap) return null;   /* the map switched under the await */
          armed = null;                    /* the rows are rebuilt by the reload — no restore */
          await loadMap(mapId);            /* sidecar truth, then re-inspect (settleProposal's shape) */
          selectNode(id);
          return null;
        }, paint);
      });
    }
    paint();
    /* on the row, not its children: it survives the confirm/paint rewrites */
    row.addEventListener('click', function(){
      if (row.classList.contains('pm-confirm')) return;   /* the confirm owns this row */
      if (far && far.el && far.el.style.display !== 'none') selectNode(far.id);
    });
    host.appendChild(row);
  });
}

/* the projmenu's two-step (chrome.js confirmDeleteMap / project.forget): the
   row BECOMES the confirm — first click arms, DELETE (or ⌫ again for a node)
   confirms, cancel restores the row. No modal, no typing, and never a bare
   one-click delete (GRAPHS_SPEC §4.7's rule for map.delete, applied to its
   contents). onGo returns an error string to show in place, or null. */
function armConfirm(row: HTMLElement, text: string, onGo: () => Promise<string | null>, onCancel?: () => void){
  disarmConfirm();
  row.classList.add('pm-confirm');
  row.innerHTML = '';
  const name = document.createElement('span');
  name.className = 'pm-name';
  name.textContent = text;
  const go = document.createElement('span');
  go.className = 'pm-go';
  go.textContent = 'DELETE';
  const cancel = document.createElement('span');
  cancel.className = 'pm-cancel';
  cancel.textContent = 'cancel';
  row.appendChild(name); row.appendChild(go); row.appendChild(cancel);
  cancel.addEventListener('click', function(e){ e.stopPropagation(); disarmConfirm(); });
  let busy = false;                        /* ⌫ held down: one rmnode, not one per repeat */
  go.addEventListener('click', async function(e){
    e.stopPropagation();
    if (busy) return;
    busy = true;
    go.textContent = '…';
    const err = await onGo();
    busy = false;
    /* a rejected write keeps the row armed with the reason (the projmenu's
       arm) — the sidecar still holds the node/edge, so nothing else moves */
    if (err){ name.textContent = err; go.textContent = 'DELETE'; }
  });
  armed = { row: row, go: go, onCancel: onCancel };
}
function disarmConfirm(){
  if (!armed) return;
  const a = armed;
  armed = null;
  a.row.classList.remove('pm-confirm');
  if (a.onCancel) a.onCancel();
}

/* Delete/⌫ on the canvas, or the inspector's hover-×: arm a confirm row under
   the node header; a second Delete/⌫ (or DELETE) confirms → map.rmnode, which
   cascades the node's edges, fields and chain server-side (MapStore.rmNode) —
   the count is shown so that is not a surprise. */
function armDeleteNode(viaKey: boolean){
  const id = S.mapSel;
  if (!id || !mapId || !nodeById(id)) return;
  if (armed && armed.nodeId === id){     /* second press = confirm */
    if (viaKey) armed.go.click();
    return;
  }
  const row = document.getElementById('mr-confirm');
  if (!row) return;
  let deg = 0;
  EDGES.forEach(function(e){ if (e.from === id || e.to === id) deg++; });
  row.hidden = false;
  armConfirm(row, 'delete ' + id + ' · ' + deg + ' edge' + (deg === 1 ? '' : 's') + ' cascade' +
    (viaKey ? ' · ⌫ again confirms' : ''), async function(){
    const myMap = mapId;
    const r = await tryOp('map.rmnode', { id: mapId, node: id });
    if (r.error) return r.error.message;
    if (mapId !== myMap) return null;
    armed = null;                          /* the rail is blanked by the reload — no restore */
    S.mapSel = null;
    await loadMap(mapId);                  /* sidecar truth — and the empty-state arm if it was the last node */
    updateCenter();                        /* map-rel/overview flags follow S.mapSel (chrome owns them) */
    return null;
  }, function(){ row.hidden = true; row.innerHTML = ''; });
  armed!.nodeId = id;
}

/* verdict proposal settle (human): accept applies the pending verdict, reject drops it */
async function settleProposal(accept: boolean){
  const id = S.mapSel;
  if (!id) return;
  const r = await tryOp('map.node', { id: mapId, node: id });
  const pending = r.data && r.data.pending;
  if (!pending) return;
  if (accept){
    await tryOp('map.setverdict', { id: mapId, node: id, verdict: pending.verdict,
      rationale: pending.rationale });
  } else {
    await tryOp('map.setverdict', { id: mapId, node: id, reject: true });
  }
  await loadMap(mapId);
  selectNode(id);
}

/* shared by jumpNode's and the chain jump's failure arms: the surface already
   switched to code — bring the map BACK so the failure text in #mr-kind is
   actually visible and selection/Esc state stays coherent (map-r4 verification
   regression; map-r8 #1) */
function restoreMapSurface(){
  if (!mapSurfaceOn()) showCenter('map');
}

/* cross-target jump: owning target active + class open at the entry symbol.
   Falls back to the owning class when the member itself won't decompile
   (e.g. mock fixtures serve whole-class text only). */
async function jumpNode(n: MapNode | null){
  if (!n) return;
  let sym: MapSymbol = n.symbol || {};
  // D3: promoted maps carry symbol:null — resolve the label into a real
  // symbol instead of giving up. symbol.resolve returns {name, kind, sig,
  // target, owner} — NO addr field; sig/name is the usable key
  if (!sym.addr && n.label){
    /* scoped like the probe below: an unscoped resolve mints the addr on
       whatever engine happens to be active, then "validates" it against
       n.target (opus map-r4 #5). undefined target is omitted from the request */
    const r = await tryOp('symbol.resolve', { symbol: n.label, target: n.target || S.currentTid || undefined });
    if (r.data && (r.data.sig || r.data.name)){
      sym = Object.assign({}, sym, { addr: r.data.sig || r.data.name, entry: r.data.name });
      if (!n.target && r.data.target) n = Object.assign({}, n, { target: r.data.target });
    }
  }
  if (!sym.addr){
    /* resolve failures surface like decompile failures — a silent no-op reads
       as "nothing is wired" (m4-flr's nodes land here; verification, map-r3) */
    if (n.label){
      console.warn('jumpNode: symbol not resolved:', n.label);
      const k = document.getElementById('mr-kind');
      if (k) k.textContent = 'jump failed: symbol not resolved: ' + n.label;
    }
    return;
  }
  let use = String(sym.addr);
  /* class fallback — Dalvik addrs keep the class part (L…; or L…;->m). The
     old lastIndexOf('.') version produced '' for them and never ran */
  const classPart = function(s: string){
    return s.includes('->') ? s.split('->')[0]
      : (s.startsWith('L') ? s
        : (s.lastIndexOf('.') > 0 ? s.substring(0, s.lastIndexOf('.')) : ''));
  };
  /* the decompile probe only earns its keep when the jump must CROSS targets:
     same-target jumps have nothing to protect, and the probe doubled the
     jump's cost — two full decompiles, seconds each at GMS scale (opus
     map-r4 #5). Cross-target: resolve BEFORE switching the user's view, and
     scoped to the node's target — the old order switched first, and a failed
     decompile left the code view on an unrelated class with the error
     swallowed (opus map-r3). n.target is a SESSION id persisted in map
     sidecars ('t5' remints every server run) — the error surface below is
     what makes a stale one visible; durable identity is a ROADMAP item. */
  if (n.target && n.target !== S.currentTid){
    const scoped = function(s: string){ return { symbol: s, target: n!.target }; };
    let r = await tryOp('code.decompile', scoped(use));
    if (r.error){
      const cls = classPart(use);
      if (cls && cls !== use){
        r = await tryOp('code.decompile', scoped(cls));
        if (!r.error) use = cls;
      }
    }
    if (r.error){
      console.warn('jumpNode', r.error.message);
      const k = document.getElementById('mr-kind');
      if (k) k.textContent = 'jump failed: ' + r.error.message;
      return;
    }
  }
  /* surface + target switch. jumpToCode returns false when the target has no
     chip — it used to fall through, and loadSymbol then bound the CURRENT
     target, a different engine than the probe green-lit (opus map-r4 #5) */
  const sw = await jumpToCode(n.target || undefined);
  if (sw === false){
    const k = document.getElementById('mr-kind');
    if (k) k.textContent = 'jump failed: no such target: ' + n.target;
    return;
  }
  try {
    await loadSymbol(use);
  } catch (e){
    /* loadSymbol THROWS (missing class; stale-tid reseed failure): an
       unhandled rejection read as a silent no-op (opus map-r4 #5). The probe
       path already class-fell-back; same-target jumps do it here — mock
       fixtures serve whole-class text only */
    const cls = classPart(use);
    let loaded = false;
    if (cls && cls !== use){
      try { await loadSymbol(cls); use = cls; loaded = true; }
      catch (e2){ console.warn('jumpNode class fallback', e2); }
    }
    if (!loaded){
      console.warn('jumpNode loadSymbol', e);
      restoreMapSurface();
      const k = document.getElementById('mr-kind');
      if (k) k.textContent = 'jump failed: ' + errMsg(e);
      return;
    }
  }
  S.cameFromMap = true;   // map-origin jump: loadSymbol cleared the flag — Esc returns here (QA-22)
}

/* map-overview panel: title/build, per-verdict counts, evidence summary */
function renderOverview(){
  const v = document.getElementById('mo-verdicts');
  /* types is null until the first loadMap resolves (and after clearMap), but
     updateCenter can call this hook first — the bare member read was a
     TypeError (opus map-r4 #14) */
  if (!v || !types || !types.verdicts) return;
  v.innerHTML = '';
  types.verdicts.forEach(function(t){
    const c = typeCount('verdicts', t.name);
    if (!c) return;
    const r = document.createElement('div');
    r.className = 'fr';
    /* registry text into innerHTML: escaped + colour-validated (HIGH-5) — the
       sibling renderTypesMenu always did; this copy had drifted */
    const col = safeColor(t.color);
    r.innerHTML = '<span><span class="vchip" style="background:' + col + ';color:' + chipText(col) + '">' +
      esc(t.name.toUpperCase()) + '</span></span><span class="fv-' + esc(t.name) + '">' + c + ' node' + (c > 1 ? 's' : '') + '</span><span class="fe"></span>';
    v.appendChild(r);
  });
  let p = 0;
  NODES.forEach(function(n){ if (n.proven) p++; });
  const mp = document.getElementById('mo-proven');
  if (mp) mp.textContent = p + ' nodes dynamically proven';
  const mo = document.getElementById('mo-title');
  if (mo && MAP) mo.textContent = (MAP.title || MAP.id) + (MAP.build ? ' · ' + MAP.build : '');
  // D8: the rail identity follows the OPEN map (was static fcm fixture text)
  const moName = document.getElementById('mo-name');
  if (moName && MAP) moName.textContent = MAP.id;
  const moSig = document.getElementById('mo-sig');
  if (moSig && MAP){
    const sig = (MAP.title || '') + (MAP.build ? ' · build ' + MAP.build : '');
    moSig.textContent = sig || '—';
    moSig.title = sig;
  }
}

/* ==================================================================
   TYPES menu (registry-driven; edits via map.type.set/add)
================================================================== */
function renderTypesMenu(){
  const typesMenu = document.getElementById('types-menu')!;
  typesMenu.innerHTML = '<div class="tm-title">MAP TYPES — display &amp; configuration</div>';
  ([['nodeKinds', 'NODE KINDS'], ['edgeKinds', 'EDGE KINDS'], ['verdicts', 'VERDICTS'], ['tags', 'TAGS']] as [TypeKey, string][])
    .forEach(function(sec){
      const key = sec[0], label = sec[1];
      const present = ((types || {} as TypeRegistry)[key] || []).filter(t => t.custom || typeCount(key, t.name) > 0);
      if (!present.length) return;
      const st = document.createElement('div');
      st.className = 'tm-sec';
      st.textContent = label;
      typesMenu.appendChild(st);
      present.forEach(function(t){
        const row = document.createElement('div');
        row.className = 'tm-row' + (t.visible !== false ? '' : ' off');
        row.innerHTML = '<span class="tm-sw" style="background:' + safeColor(t.color) + '"></span>' +
          '<span class="tm-name">' + esc(t.name) + '</span>' +
          '<span class="tm-count">' + typeCount(key, t.name) + '</span>';
        row.addEventListener('click', async function(){
          const r = await tryOp('map.type.set', { kind: key, name: t.name, visible: t.visible === false });
          /* a rejected write must not flip local visibility — canvas and the
             persisted registry would diverge until the next load (map-r5 F8) */
          if (r.error){ console.warn('map.type.set', r.error.message); return; }
          t.visible = t.visible === false;
          renderTypesMenu();
          applyTypeFilters();
          /* filters change the content bounds and the card heights — repack
             AFTER filtering, then redraw edges (opus map-r3 F2) */
          repackLayers();
          renderEdges();
        });
        typesMenu.appendChild(row);
      });
      const add = document.createElement('div');
      add.className = 'tm-add';
      add.textContent = '＋ add…';
      add.addEventListener('click', function(){
        add.style.display = 'none';
        const form = document.createElement('div');
        form.className = 'tm-form';
        let sel = SWATCHES[0];
        form.innerHTML = '<input placeholder="type name" spellcheck="false">' +
          '<span class="tm-swatches">' +
          SWATCHES.map((c, i) => '<span class="tm-sw' + (i === 0 ? ' sel' : '') +
            '" data-c="' + c + '" style="background:' + c + '"></span>').join('') + '</span>';
        const addBtn = document.createElement('button');
        addBtn.className = 'mbtn';
        addBtn.textContent = 'ADD';
        form.appendChild(addBtn);
        $all('.tm-sw', form).forEach(function(sw){
          sw.addEventListener('click', function(){
            $all('.tm-sw', form).forEach(x => x.classList.remove('sel'));
            sw.classList.add('sel');
            sel = sw.getAttribute('data-c')!;
          });
        });
        addBtn.addEventListener('click', async function(){
          const name = form.querySelector('input')!.value.trim().toLowerCase();
          if (!name || typeEntry(key, name)) return;
          const r = await tryOp('map.type.add', { kind: key, name: name, color: sel });
          if (!r.error){
            (types![key] = types![key] || []).push(r.data);
            renderTypesMenu();
          }
        });
        typesMenu.insertBefore(form, add.nextSibling);
        const nameInput = form.querySelector('input')!;
        nameInput.addEventListener('keydown', function(e){      // QA p3: Enter submits
          if (e.key === 'Enter'){ e.preventDefault(); addBtn.click(); }
          if (e.key === 'Escape'){ e.preventDefault(); e.stopPropagation(); form.remove(); add.style.display = ''; }
        });
        nameInput.focus();
      });
      typesMenu.appendChild(add);
    });
  const foot = document.createElement('div');
  foot.className = 'tm-foot';
  foot.textContent = 'types are per-project · map.type.list / map.type.add / map.type.set';
  typesMenu.appendChild(foot);
}

/* ==================================================================
   ＋NODE / ＋EDGE / EXPORT dialogs (generic map modal)
================================================================== */
type DialogField = { ph: string; value?: string; hide?: boolean };

function mapDialog(title: string, fields: DialogField[], onOk: (vals: string[]) => Promise<string | { id?: string } | null | undefined>){
  const modal = document.getElementById('mapmodal')!;
  document.getElementById('mapm-title')!.textContent = title;
  const err = document.getElementById('mapm-err')!;
  err.textContent = '';
  const inputs = fields.map(function(f, i){
    const el = document.getElementById('mapm-f' + i) as HTMLInputElement;
    el.value = f.value || '';
    el.placeholder = f.ph;
    el.style.display = f.hide ? 'none' : '';
    return el;
  });
  for (let i = fields.length; i < 4; i++) document.getElementById('mapm-f' + i)!.style.display = 'none';
  modal.hidden = false;
  inputs[0].focus();
  const ok = document.getElementById('mapm-ok')!;
  const escOff = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = null;
    popEscape(escOff);   /* was 'esc' — shadowed the module's HTML-escaper (map-r6 #14) */
  }
  const submit = async function(){
    const vals = inputs.map(el => el.value.trim());
    const res = await onOk(vals);
    if (typeof res === 'string'){ err.textContent = res; return; }   // error stays modal
    close();
    /* onOk may return {id} to load a DIFFERENT map than the current one — the
       NEW MAP flow creates + activates server-side, then loads and surfaces
       it here (opus map-r5 F18) */
    const nid = res && res.id ? String(res.id) : null;
    await loadMap(nid || mapId);
    if (nid) showCenter('map');    // loadMap rendered the chip; surface it (+ focusMap fit)
    renderOverview();
  };
  ok.onclick = submit;
  document.getElementById('mapm-cancel')!.onclick = close;
  inputs.forEach(function(inp){
    inp.onkeydown = function(e){
      if (e.key === 'Enter'){ e.preventDefault(); submit(); }
    };
  });
}

/* the registry's node kinds (map.type.list) — the ADD NODE and "add to map" dialogs offer
   them as the kind field's picker (a datalist), the empty map lists them (audit decision 10) */
function registryKinds(): string[] {
  const reg = types as unknown as { nodeKinds?: ({ name?: string } | string)[] } | null;
  return ((reg && reg.nodeKinds) || []).map(k => typeof k === 'string' ? k : String(k.name || '')).filter(Boolean);
}
function kindPicker(){
  const kinds = registryKinds();
  let dl = document.getElementById('mapm-kinds') as HTMLDataListElement | null;
  if (!dl){ dl = document.createElement('datalist'); dl.id = 'mapm-kinds'; document.body.appendChild(dl); }
  dl.innerHTML = '';
  kinds.forEach(function(k){ const o = document.createElement('option'); o.value = k; dl.appendChild(o); });
  const f2 = document.getElementById('mapm-f2');
  if (f2) f2.setAttribute('list', 'mapm-kinds');
  return kinds;
}
function slugId(name: string, symbol: string){
  let h = 0;
  for (let i = 0; i < symbol.length; i++) h = ((h * 31) + symbol.charCodeAt(i)) | 0;
  const base = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'node';
  return base + '-' + (h >>> 0).toString(16).slice(0, 4);
}

/* "Add to map…" from the code right-click, the tree and the RELATION menu (audit decision 10):
   the caret symbol becomes a node of the OPEN map — id from the name (hash-suffixed so two
   obfuscated A00s stay two nodes), label, a registry kind, and the symbol + the viewed target
   the server normalises (MapStore.normalizeSymbol) */
export async function addSymbolToMap(sub: { symbol: string; name: string; kind: string; fqcn?: string }): Promise<void> {
  if (!mapId){ notice('no map open — project chip › MAPS · ＋ New map… first', { err: true }); return; }
  const kinds = kindPicker();
  const dflt = kinds.includes(sub.kind) ? sub.kind : (kinds.includes('class') && sub.kind === 'class' ? 'class' : (kinds[0] || 'custom'));
  mapDialog('ADD TO MAP · ' + mapId, [
    { ph: 'node id', value: slugId(sub.name, sub.symbol) },
    { ph: 'label', value: sub.name },
    { ph: 'kind (' + (kinds.join(' · ') || 'registry empty') + ')', value: dflt },
    { ph: 'layer (0-3)', value: '0' },
  ], async function(v){
    if (!v[0] || !v[1]) return 'id + label required';
    const node: Record<string, unknown> = { id: v[0], label: v[1], kind: v[2] || 'custom', layer: parseInt(v[3] || '0', 10) || 0, symbol: sub.symbol };
    if (S.currentTid) node.target = S.currentTid;
    const r = await tryOp('map.addnode', { id: mapId, node });
    if (!r.error) notice('added ' + v[1] + ' to ' + mapId + (S.currentTid ? ' · ' + (activeTargetName() || S.currentTid) : ''));
    return r.error ? r.error.message : null;
  });
}

function openAddNode(){
  const kinds = kindPicker();
  mapDialog('ADD NODE', [
    { ph: 'id (e.g. mdm-svc)' }, { ph: 'label' }, { ph: 'kind (' + (kinds.join(' · ') || 'handler…') + ')' }, { ph: 'layer (0-3)' },
  ], async function(v){
    if (!v[0] || !v[1]) return 'id + label required';
    const r = await tryOp('map.addnode', { id: mapId, node: {
      id: v[0], label: v[1], kind: v[2] || 'custom', layer: parseInt(v[3] || '0', 10) || 0,
    } });
    /* tryOp's OpError always carries .message (code prefixed) — the r6 #12
       '|| r.error.code' arm was dead (map-r7 F5) */
    return r.error ? r.error.message : null;
  });
}

function openAddEdge(){
  mapDialog('ADD EDGE', [
    { ph: 'from (node id)' }, { ph: 'to (node id)' }, { ph: 'kind (call…)' }, { ph: 'label (optional)' },
  ], async function(v){
    if (!v[0] || !v[1]) return 'from + to required';
    const r = await tryOp('map.addedge', { id: mapId, from: v[0], to: v[1],
      kind: v[2] || 'call', label: v[3] || undefined });
    return r.error ? r.error.message : null;
  });
}

/* NEW MAP (project menu ＋): create in the current project, activate it
   server-side (per-project activeMap); the dialog's {id} protocol loads and
   surfaces it (opus map-r5 F18 — the menu row used to be a dead button) */
function openNewMap(){
  let acked: string | null = null;   // the existing id the user has been shown
  mapDialog('NEW MAP', [
    { ph: 'title' }, { ph: 'build (optional)' },
  ], async function(v){
    if (!v[0]) return 'title required';
    const r = await tryOp('map.create', { title: v[0], build: v[1] || undefined });
    if (r.error) return r.error.message;   // OpError always carries .message (map-r7 F5)
    /* the backend guarantees a non-empty id (empty-slug titles are E_SYNTAX) —
       the old client-side slug fallback could mask a broken create (map-r6 #13) */
    const nid = r.data && r.data.id ? String(r.data.id) : null;
    if (!nid) return 'map.create returned no id';
    /* SLUG COLLISION (map-lifecycle step 6): create is idempotent on the id, so
       'FCM attack surface', 'FCM: attack, surface' and 'fcm-attack-surface' all
       return the FIRST map's summary. Opening it silently let the user believe
       a new map had been made — and with map.delete in the GUI, deleting "my
       new map" destroys the original. Stop and say so; a second OK opens it
       (the modal closes on success, so a message delivered after the open is a
       message nobody reads). */
    if (r.data.existing && acked !== nid){
      acked = nid;
      return 'map `' + nid + '` already exists (' + (r.data.nodes ?? 0)
        + ' nodes) — OK opens it';
    }
    await tryOp('map.open', { id: nid });
    return { id: nid };
  });
}

async function doExport(){
  const r = await tryOp('map.export', { id: mapId, fmt: 'md' });
  if (r.error){ console.warn('export', r.error.message); return; }
  const blob = new Blob([r.data.text], { type: 'text/markdown' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = mapId + '.md';
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ==================================================================
   pan / zoom / keyboard
================================================================== */
function applyView(){
  mapworld.style.transform = 'translate(' + panX + 'px,' + panY + 'px) scale(' + zoom + ')';
  if (!mzLabelEl) mzLabelEl = document.getElementById('mz-label');
  if (mzLabelEl){
    const s = Math.round(zoom * 100) + '%';
    if (mzLabelEl.textContent !== s) mzLabelEl.textContent = s;   /* pan frames don't rewrite the label */
  }
  /* minimap viewport rect — with continuous wheel zoom, "where am I" is a
     real question (pan/zoom → world rect → minimap px via mmScale) */
  if (mmView){
    mmView.style.display = mmScale > 0 ? '' : 'none';   /* pre-repack: no 2px orphan dot (r3 F9) */
    if (mmScale > 0){
      mmView.style.left = (4 + (-panX / zoom) * mmScale) + 'px';
      mmView.style.top = (4 + (-panY / zoom) * mmScale) + 'px';
      mmView.style.width = ((mapview.clientWidth / zoom) * mmScale) + 'px';
      mmView.style.height = ((mapview.clientHeight / zoom) * mmScale) + 'px';
    }
  }
}
/* The static layout assumes one-line cards at a fixed 64px pitch — real
   cards wrap (sublabels, chips) and overlap the card below ("labels
   overwrite the boxes"). After render, measure each card and repack every
   layer top-down with real heights; also rescales the minimap to the true
   content bounds. */
function repackLayers(){
  /* a hidden pane measures 0-height cards and a negative mmScale — poison
     bounds (opus map-r3 F6). loadMap renders while hidden; focusMap repacks
     on show. */
  if (!mapview.clientWidth) return;
  const byLayer: Record<number, MapNode[]> = {};
  NODES.forEach(function(n){
    const L = n.layer || 0;
    (byLayer[L] = byLayer[L] || []).push(n);
  });
  /* the bounds are module state (computeFit/SVG sizing/minimap all consume
     them) and skip type-filtered nodes, so FIT responds to the TYPES panel */
  contentW = 0; contentH = 0;
  Object.values(byLayer).forEach(function(list){
    list.sort(function(a, b){ return a.y - b.y; });
    let y = 16;
    /* two passes (map-r7 F8): measuring offsetHeight right after writing
       style.top forced one synchronous layout PER CARD per repack (52 on the
       fcm map — on every TYPES toggle and focusMap). Card heights don't
       depend on top (absolute positioning, inline-pinned width) — read all
       heights first, then write positions. */
    list.forEach(function(n){
      n._h = (n.el && n.el.style.display !== 'none') ? n.el.offsetHeight : 0;
    });
    list.forEach(function(n){
      /* filtered-out cards take NO space: the old order measured 0 height but
         still advanced y by the 12px gutter per hidden node — 30 hidden nodes
         left 360px of dead column and inflated contentH, so FIT zoomed out
         well past the visible content (opus map-r4 #3). n.y still tracks the
         running position so a later unhide re-sorts correctly. */
      if (n.el && n.el.style.display === 'none'){ n.y = y; return; }
      n.y = y;
      if (n.el) n.el.style.top = y + 'px';
      y += (n._h || 56) + 12;
      if (n.x + n.w > contentW) contentW = n.x + n.w;
      if (y > contentH) contentH = y;
    });
  });
  /* the SVG edge/label layers were pinned at 1280×720 (UA overflow:hidden
     clipped every edge past that — 37/51 on the 52-node map): size them to
     the real content bounds (opus map-r1 #2) */
  const cw = Math.max(contentW, 200), ch = Math.max(contentH, 200);
  [mapedges, maplbl].forEach(function(s){
    if (!s) return;
    s.setAttribute('viewBox', '0 0 ' + cw + ' ' + ch);
    s.style.width = cw + 'px';
    s.style.height = ch + 'px';
  });
  const mm = document.getElementById('minimap')!;
  /* the 200px floor matches computeFit: with every node filtered out (or a
     cleared map) contentW/H are 0 and the old /1 divisor exploded mmScale to
     ~70px per world unit — a ~60,000px viewport rect painting the minimap as
     a solid blue wash (opus map-r4 #8) */
  mmScale = Math.max(0, Math.min((mm.clientWidth - 8) / Math.max(contentW, 200),
                                 (mm.clientHeight - 8) / Math.max(contentH, 200)));
  NODES.forEach(function(n){
    if (!n.mmEl) return;
    n.mmEl.style.left = (4 + n.x * mmScale) + 'px';
    n.mmEl.style.top = (4 + n.y * mmScale) + 'px';
    n.mmEl.style.width = Math.max(3, n.w * mmScale) + 'px';
  });
  applyView();   /* mmScale changed — refresh the viewport rect (r3 F9) */
}

/* the fit computation, view-agnostic — also clampZoom's floor source. A cached
   fitZoom goes stale on any window resize or splitter drag, and the floor then
   sits ABOVE the real fit (zoom-out becomes a no-op — opus map-r1 #1) */
function computeFit(){
  const vw = mapview.clientWidth, vh = mapview.clientHeight;
  if (!vw || !vh) return null;
  /* bounds come from repackLayers (measured, filter-aware). The old duplicate
     loop hardcoded 236×64 — 84px short on deep-layer nodes (w=320), so FIT
     clipped the right column whenever WIDTH bound (opus map-r2 #1) */
  const w = Math.max(contentW, 200), h = Math.max(contentH, 200);
  const z = Math.min(vw / w, vh / h, 1.25) * 0.97;
  return { z: z, px: (vw - w * z) / 2, py: (vh - h * z) / 2 };
}

function fitMap(){
  const f = computeFit();
  if (!f) return false;
  zoom = f.z; panX = f.px; panY = f.py;
  applyView();
  return true;
}

function moveSel(dx: number, dy: number){
  if (!S.mapSel){ if (NODES.length) selectNode(NODES[0].id); return; }
  const cur = nodeById(S.mapSel);
  if (!cur) return;
  let best = null as MapNode | null, bestScore = 1e9;
  NODES.forEach(function(n){
    if (n.id === S.mapSel || !n.el || n.el.style.display === 'none') return;
    const ddx = (n.x + n.w / 2) - (cur.x + cur.w / 2);
    const ddy = (n.y + 24) - (cur.y + 24);
    const primary = dx !== 0 ? ddx * dx : ddy * dy;
    if (primary <= 2) return;
    const cross = dx !== 0 ? Math.abs(ddy) : Math.abs(ddx);
    const score = primary + cross * 3;
    if (score < bestScore){ bestScore = score; best = n; }
  });
  if (best) selectNode(best.id);
}

export function initMap(): void {
  S.hooks.addToMap = addSymbolToMap;
  mapview = document.getElementById('mapview')!;
  mapworld = document.getElementById('mapworld')!;
  mapedges = document.getElementById('mapedges') as unknown as SVGSVGElement;   // port: getElementById is typed HTMLElement
  maplbl = document.getElementById('maplabels') as unknown as SVGSVGElement;    // port: getElementById is typed HTMLElement

  S.hooks.renderOverview = renderOverview;
  S.hooks.closeTypes = function(){ document.getElementById('types-menu')!.hidden = true; };
  S.hooks.focusMap = function(){
    mapview.focus({ preventScroll: true });
    // a render that happened while the view was hidden measured zero-height
    // cards (12px pitch, overlapping): repack with real heights AND redraw
    // the edges — they were built from the pre-repack positions. Node filters
    // run FIRST so the repack measures the visible set (renderAll's order,
    // map-r5 F20)
    applyTypeFilters();
    repackLayers();
    renderEdges();
    /* the ⇄ cross-target badges were baked at renderAll — a target switch
       since (cross-target jump, the r4 restore path) leaves them stale until
       the next loadMap (opus map-r5 F12) */
    NODES.forEach(function(n){
      if (!n.el) return;
      const b = n.el.querySelector('.mtgt');
      if (b) b.classList.toggle('x', n.target !== S.currentTid);
    });
    if (S.mapSel) selectNode(S.mapSel);   // re-assert selection + its edge labels
    /* fit only when the map IDENTITY changed — an unconditional refit threw
       away the user's zoom/pan on every code→map round trip (opus map-r2 #2).
       Latch only on a REAL fit: a hidden-pane fitMap no-ops (r3 F5) */
    if (fittedFor !== mapId && fitMap()) fittedFor = mapId;
  };
  /* project switch (opus map-r4 #2): drop the selection + fit latch and load
     the NEW project's map — or clear everything when it has none */
  S.hooks.mapProjectSwitched = async function(){
    S.mapSel = null;
    fittedFor = null;
    await loadMap(null);
  };
  S.hooks.reloadMap = loadMap;
  /* project menu ＋NEW MAP → create/activate/open the surface (map-r5 F18) */
  S.hooks.newMap = openNewMap;

  if (wired) return;
  wired = true;

  const ZOOM_MIN = 0.4, ZOOM_MAX = 1.75, ZOOM_STEP = 0.1;
  /* the out-floor is dynamic: never below the FIT zoom — on a map that fits
     at 0.19, a static 0.4 floor made the first zoom-OUT snap IN to 40%.
     Computed LIVE (computeFit), not cached — a cache goes stale on resize */
  function clampZoom(z: number){
    const f = computeFit();
    /* never clamp UPWARD past the live zoom: with no fit (a 0-width pane) a
       hard floor snaps a zoomed-out view back IN on a zoom-out key (r2 #4) */
    const lo = f ? Math.min(f.z, ZOOM_MIN) : Math.min(zoom, ZOOM_MIN);
    return Math.max(lo, Math.min(ZOOM_MAX, z));
  }
  /* every zoom input funnels here: buttons/keys anchor at the viewport center,
     the wheel passes the cursor. (Anchoring formula: screen = pan + world·zoom
     with transform-origin 0 0, so pan' = m − (m−pan)·z2/zoom.) */
  function zoomStep(d: number, ax?: number, ay?: number){
    const z2 = clampZoom(zoom + d);
    if (z2 === zoom) return;
    const mx = ax !== undefined ? ax : mapview.clientWidth / 2;
    const my = ay !== undefined ? ay : mapview.clientHeight / 2;
    const k = z2 / zoom;
    panX = mx - (mx - panX) * k;
    panY = my - (my - panY) * k;
    zoom = z2;
    applyView();
  }
  document.getElementById('mz-in')!.addEventListener('click', function(){ zoomStep(ZOOM_STEP); });
  document.getElementById('mz-out')!.addEventListener('click', function(){ zoomStep(-ZOOM_STEP); });
  document.getElementById('mz-fit')!.addEventListener('click', fitMap);

  /* wheel zoom, cursor-anchored. Step is proportional with a notch cap — one
     mouse notch ≈ the buttons' 0.1, a trackpad's small deltas stay smooth.
     deltaMode: 1 is lines (×33 to notch-ish pixels), 2 is pages (×400);
     ctrlKey = trackpad pinch, whose deltas are tiny (×8). preventDefault
     needs passive:false. */
  mapview.addEventListener('wheel', function(e){
    if ((e.target as HTMLElement).closest('#minimap')) return;
    e.preventDefault();
    let d = e.deltaY;
    if (e.deltaMode) d *= (e.deltaMode === 1 ? 33 : 400);
    if (e.ctrlKey) d *= 8;
    const step = Math.max(-1, Math.min(1, d / 100)) * ZOOM_STEP;
    if (!step) return;
    const r = mapview.getBoundingClientRect();
    zoomStep(-step, e.clientX - r.left, e.clientY - r.top);
    /* a wheel zoom mid-drag would be discarded by the next pointermove, which
       recomputes pan from the pointerdown capture — re-base it */
    if (panning){ panning.px = panX; panning.py = panY; panning.x = e.clientX; panning.y = e.clientY; }
  }, { passive: false });

  let panning: { x: number; y: number; px: number; py: number } | null = null;
  mapview.addEventListener('pointerdown', function(e){
    if (e.button !== 0) return;      /* left button only */
    /* …and the empty-state block (P0.1): with pointer capture the click would
       land on #mapview, not on the block's ＋NODE/TYPES button */
    if ((e.target as HTMLElement).closest('.mnode') || (e.target as HTMLElement).closest('#minimap') || (e.target as HTMLElement).closest('.mapempty')) return;
    /* no preventDefault here: it suppresses the compat mousedown and with it
       canvas click-to-focus (the map keys went dead after clicking a button
       — opus map-r3 F4). Text selection is handled by user-select in CSS. */
    panning = { x: e.clientX, y: e.clientY, px: panX, py: panY };
    mapview.setPointerCapture(e.pointerId);
    mapview.classList.add('panning');
  });
  mapview.addEventListener('pointermove', function(e){
    if (!panning) return;
    panX = panning.px + (e.clientX - panning.x);
    panY = panning.py + (e.clientY - panning.y);
    applyView();
  });
  function endPan(){
    panning = null;
    mapview.classList.remove('panning');
  }
  mapview.addEventListener('pointerup', endPan);
  /* a lost capture (system gesture) must not strand the pan state/cursor */
  mapview.addEventListener('pointercancel', endPan);
  mapview.addEventListener('lostpointercapture', endPan);

  /* minimap: viewport rect + click-to-navigate. The rect is created once here
     (renderAll clears only .mm dots); clicking inverts mmScale and centers
     the view on the clicked world point. */
  const mm = document.getElementById('minimap')!;
  if (!mmView){
    mmView = document.createElement('div');
    mmView.id = 'mm-view';
    mmView.style.display = 'none';   /* hidden until a real mmScale paints it (r3 F9) */
    mm.appendChild(mmView);
  }
  function mmNav(e: PointerEvent){
    if (!mmScale) return;
    const r = mm.getBoundingClientRect();
    /* dots and the viewport rect are positioned from the PADDING box —
       getBoundingClientRect is the border edge, so subtract the border (r3 F8) */
    const wx = (e.clientX - r.left - mm.clientLeft - 4) / mmScale;
    const wy = (e.clientY - r.top - mm.clientTop - 4) / mmScale;
    panX = mapview.clientWidth / 2 - wx * zoom;
    panY = mapview.clientHeight / 2 - wy * zoom;
    applyView();
  }
  mm.addEventListener('pointerdown', function(e){
    if (e.button !== 0) return;
    mm.setPointerCapture(e.pointerId);   /* drag-to-scrub follows */
    mmNav(e);
  });
  mm.addEventListener('pointermove', function(e){
    /* only a drag that STARTED on the minimap scrubs (it holds pointer
       capture): a drag begun on a node card takes no capture, and wandering
       over the minimap used to teleport the viewport (opus map-r5 F10) */
    if (mm.hasPointerCapture(e.pointerId)) mmNav(e);
  });

  /* a resized window or a splitter drag changes the pane. Card heights are
     viewport-independent (widths are inline-pinned) and mmScale depends only
     on content bounds, so the only thing to refresh is the viewport rect —
     NOT a repack+edge rebuild per resize frame (opus map-r3 F7). Hidden map
     (clientWidth 0): skip. */
  new ResizeObserver(function(){
    if (mapview.clientWidth) applyView();
  }).observe(mapview);

  const typesMenu = document.getElementById('types-menu')!;
  const typesBtn = document.getElementById('types-btn')!;
  /* closing the menu hands the keyboard back to the canvas: clicking TYPES focuses
     the button (outside #mapview), and the map keys were then dead until the user
     clicked the canvas (F6). NOT used by S.hooks.closeTypes — updateCenter calls
     that one with the map still on, where stealing focus would be wrong. */
  function closeTypesMenu(){
    typesMenu.hidden = true;
    if (mapSurfaceOn()) mapview.focus({ preventScroll: true });
  }
  typesMenu.addEventListener('click', function(e){ e.stopPropagation(); });
  typesBtn.addEventListener('click', function(e){
    e.stopPropagation();
    if (typesMenu.hidden){ renderTypesMenu(); typesMenu.hidden = false; }
    else closeTypesMenu();
  });
  document.addEventListener('click', function(e){
    if (!typesMenu.hidden && !(e.target as HTMLElement).closest('#types-menu') && !(e.target as HTMLElement).closest('#types-btn'))
      closeTypesMenu();
  });
  /* TYPES menu: Esc closes it ahead of the view-level handlers (menu priority) */
  pushEscape(function(){
    if (typesMenu.hidden) return false;
    closeTypesMenu();
  }, ESC.menu);

  document.getElementById('mr-accept')!.addEventListener('click', function(){ settleProposal(true); });
  document.getElementById('mr-reject')!.addEventListener('click', function(){ settleProposal(false); });
  document.getElementById('mapm-addnode')!.addEventListener('click', openAddNode);
  document.getElementById('mapm-addedge')!.addEventListener('click', openAddEdge);
  document.getElementById('mapm-export')!.addEventListener('click', doExport);

  /* P0.2: human verdict edit. change → map.setverdict {id, node, verdict}: a
     human actor applies directly (and settles any pending proposal on the
     node — MapStore.setVerdict). Revert on error: the sidecar and the canvas
     still hold the old verdict, and a chip showing the new one would lie
     (the map.type.set rule, map-r5 F8). Stale guard as populateMapRel's. */
  document.getElementById('mr-verdict')!.addEventListener('change', async function(){
    const sel = this as HTMLSelectElement, id = S.mapSel, want = sel.value;
    if (!id || !mapId || !want) return;
    const was = (nodeById(id) || {} as MapNode).verdict || 'none';
    if (want === was) return;
    const myMap = mapId;
    sel.disabled = true;
    const r = await tryOp('map.setverdict', { id: mapId, node: id, verdict: want });
    if (S.mapSel !== id || mapId !== myMap) return;   /* the inspector moved on */
    if (r.error){
      renderVerdictPick(was);
      document.getElementById('mr-kind')!.textContent = 'verdict not set: ' + r.error.message;
      return;
    }
    await loadMap(mapId);                  /* card colour/chip + overview counts from the sidecar */
    selectNode(id);
  });
  document.getElementById('mr-del')!.addEventListener('click', function(e){
    e.stopPropagation();
    armDeleteNode(false);
  });

  mapview.addEventListener('keydown', function(e){
    /* ⌘/⌃ +/- is the browser's own page zoom (not cancelable — we'd double
       zoom); ⌥←/⌘← are word/history nav — modifier chords aren't ours */
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key;
    if (k === 'ArrowLeft'){ e.preventDefault(); moveSel(-1, 0); }
    else if (k === 'ArrowRight'){ e.preventDefault(); moveSel(1, 0); }
    else if (k === 'ArrowUp'){ e.preventDefault(); moveSel(0, -1); }
    else if (k === 'ArrowDown'){ e.preventDefault(); moveSel(0, 1); }
    else if (k === '+' || k === '='){ e.preventDefault(); zoomStep(ZOOM_STEP); }
    else if (k === '-' || k === '_'){ e.preventDefault(); zoomStep(-ZOOM_STEP); }
    else if (k === '0'){ e.preventDefault(); fitMap(); }
    else if (k === 'Enter' && S.mapSel){
      e.preventDefault();
      jumpNode(nodeById(S.mapSel));
    }
    /* P0.2: delete the selected node — arm on the first press, confirm on the
       second (armDeleteNode); never a bare one-key delete */
    else if ((k === 'Delete' || k === 'Backspace') && S.mapSel){
      e.preventDefault();
      armDeleteNode(true);
    }
    /* Esc: the central dispatcher (below) leaves the map for the code view */
  });

  /* map Esc — back to code. Keyed on the map surface being VISIBLE (updateCenter's
     mapOn rule), not on focus containment: a search-hit open, an agents close or a
     click on TYPES parks focus outside the canvas, and the old focus test then left
     Esc dead with the map on screen (F6). A pane view or agents owns the center
     itself — mapSurfaceOn() is false for both — and a modal/menu outranks us by
     priority, so nothing is stolen. */
  pushEscape(function(){
    if (!mapSurfaceOn()) return false;
    S.cameFromMap = false;
    showCenter('code');
  }, ESC.view);
  /* P0.2: an armed delete confirm owns the first Esc — pushed AFTER the leave
     handler at the same priority, so the later seq runs first; the second Esc
     then leaves as before */
  pushEscape(function(){
    if (!armed || !mapSurfaceOn()) return false;
    disarmConfirm();
  }, ESC.view);

  loadMap(null);
}
