// app.ts — boot: wire the v7 shell to the live backend (M2: code view end-to-end,
// history dock, target launcher).
import { $all, S, hook, initEscape, errMsg, escHtml as escText, opT, tryOpT, pushEscape, popEscape, ESC, initClientLog } from './ui/hub.js';
import { op, tryOp, events, onLink } from './api.js';
import { initRibbon, refreshAgentChip, setHeapGauge, heapDisplay, setState } from './ui/ribbon.js';
import { initChrome, updateCenter, applyLayout, syncTabs, activeTarget, setCodeCrumb, jumpToCode, renderMapChip, focusCenter, showCenter, loadUiPrefs, notice } from './ui/chrome.js';
import { initCaret, renderCaret } from './ui/caret.js';
import { initPalette } from './ui/palette.js';
import { renderRelation, clearRelationSubject, clearRelationDock } from './ui/relation.js';
import { initCode, loadSymbol, markDirty, resetPresencePing } from './views/code.js';
import { initHistory, loadHistory } from './ui/history.js';
import { initTags, loadTags } from './ui/tags.js';
import { initFlows, loadFlows } from './ui/flows.js';
import { initBookmarks, loadBookmarks } from './ui/bookmarks.js';
import { initLauncher } from './ui/launcher.js';
import { initOpenStatus, trackOpen } from './ui/openstatus.js';
import { initJumpStatus } from './ui/jumpstatus.js';
import { initParams } from './ui/params.js';
import { initLayouts } from './ui/layouts.js';
import { initNavigation, setNavTarget } from './ui/navigation.js';
import { initMap } from './views/map.js';
import { initAgents, refreshProposalsForProject } from './views/agents.js';
import { initStrings, reloadStrings } from './views/strings.js';
import { initRuns, reloadRuns, refreshRuntimeDock } from './views/runs.js';
import { initSearchView, reloadSearch } from './views/search.js';
import { initDevice } from './views/device.js';
import type { OpError } from './api.js';
import type { OpParams, SseData, Target, TargetInfo, TreeNode, TreePage, WorkspaceState, TargetView } from './types.js';

const FQCN = 'com.google.android.gm.sync.MessageSyncHandler';

/* ---- target strip from workspace.state ---- */
function ledFor(t: Target, active?: boolean){
  if (active) return '<span class="led g"></span>';
  if (t.state && t.state !== 'ready') return '<span class="led o"></span>';
  /* no name-pattern fixtures (.so → "agent working", wallet → "device attached") —
     presence.whereis and device.list answer those for real now */
  return '';
}

function renderChips(state: WorkspaceState){
  const add = document.querySelector('.targets .tadd')!;    /* chips go left of ＋ */
  $all('.targets .tchip:not(#mapchip)').forEach(c => c.remove());
  state.targets.forEach(function(t){
    const chip = document.createElement('div');
    /* .on paints the VIEWED target (GUI review finding 8): t.active is the
       backend's shared cursor — any actor can yank it, and since the viewer
       ops now bind S.currentTid, painting from t.active left the chip and
       the code view pointing at two different apks */
    chip.className = 'tchip' + ((S.currentTid ? t.id === S.currentTid : t.active) ? ' on' : '');
    chip.setAttribute('data-tid', t.id);
    chip.setAttribute('data-pos', 'below');
    /* a re-keyed target says so on its chip for as long as the old database is on disk — the
       open card that announced it is long gone by then (review 2026-10-05 M4) */
    chip.setAttribute('data-tip', t.path + ' · ' + t.engine
      + (t.rekeyed && t.rekeyed.aside ? ' · re-keyed — the old database is kept: ' + t.rekeyed.aside
          + (t.rekeyed.asideBytes ? ' (' + (t.rekeyed.asideBytes >= 1 << 30 ? (t.rekeyed.asideBytes / (1 << 30)).toFixed(1) + ' GB' : Math.max(1, Math.floor(t.rekeyed.asideBytes / (1 << 20))) + ' MB') + ')' : '')
          + ' · ⌘K “Undo re-key” puts it back' : '')
      + (t.replayFailed ? ' · ' + t.replayFailed + ' edit' + (t.replayFailed === 1 ? '' : 's') + ' could not be re-applied (logs/workbench.log)' : ''));
    chip.innerHTML = ledFor(t, t.active) +
      '<span class="lbl-t">' + escText(t.name) + '</span><span class="dotsep">·</span><span class="eng">' + escText(t.engine) + '</span>' +
      '<span class="tx" role="button" tabindex="0" aria-label="Close target — asks before writing unsaved edits — ⌥W" title="Close target — asks before writing unsaved edits — ⌥W">×</span>';
    // hover-×: close the target — the backend auto-saves JEB targets on close
    // when dirty (Workspace.closeTarget → engine close). This click handler is
    // the ONE close path: ⌥W and the palette row (palette.js closeViewedTarget)
    // .click() this × rather than carrying a second copy of the sequence (P0-5)
    const tx = chip.querySelector<HTMLElement>('.tx')!;
    tx.addEventListener('click', async function(e){
      e.stopPropagation();
      /* a close already in flight must not fire a second target.close — Enter
         key-repeat on the focused × (below) or a second ⌥W landed one (P0-5) */
      if (tx.classList.contains('busy')) return;
      tx.textContent = '…';                      // a close can take 60s+ at GMS scale
      tx.classList.add('busy');
      const wasViewed = !!t.active || t.id === S.currentTid;   // the viewed target too, not only the backend's cursor (review 2026-09-09 MED-9)
      if (t.id === S.currentTid && S.hooks.saveViews) S.hooks.saveViews();   // remember where we were before the target goes
      /* ask first: a close that would WRITE unsaved edits gets the dialog (how many
         symbols, save / discard / cancel); a close with nothing to write is silent.
         DISCARD really discards: the backend marks the unsaved history so it does not
         replay on the next open. */
      const ch = await tryOp('target.changes', { target: t.id });
      let saveFlag: boolean | undefined = undefined;
      if (!ch.error && ch.data && ((ch.data.symbols || 0) > 0 || (ch.data.decompiled || 0) > 0)){
        const choice = await confirmClose(t.name, ch.data);
        if (choice === 'cancel'){ tx.textContent = '×'; tx.classList.remove('busy'); return; }
        saveFlag = choice === 'save';
      }
      const r = await tryOp('target.close', saveFlag === undefined ? { target: t.id } : { target: t.id, save: saveFlag });
      if (r.error){
        /* the backend's refusal is written for a human (e.g. a DISCARD whose history mark
           could not be persisted: "the target is unchanged and still open; save it, or retry")
           — a console line was a dead click (re-review 9ff8cb0 #1) */
        notice('close not applied — ' + r.error.message, { err: true, ms: 12000 });
        tx.textContent = '×';
        tx.classList.remove('busy');
        return;
      }
      // state propagation (review findings): ribbon + project chip, not just
      // the strip; and the app must FOLLOW the backend's active-target change
      const st = (await tryOp('workspace.state', {})).data;
      if (!st){ tx.textContent = '×'; tx.classList.remove('busy'); return; }   // the close happened; a failed state fetch must not wedge the chip on "…" (re-review #2)
      applyState(st);
      if (!st.targets.length){
        showEmptyTarget();                        // no ghost IDE behind 0 targets
      } else if (wasViewed){
        const next = st.targets.find(x => x.active) || st.targets[0];
        const nChip = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + next.id + '"]');
        if (nChip && S.hooks.targetSwitched) await S.hooks.targetSwitched(nChip);
      }
      /* keyboard activation (P0-5): the × that took Enter left with its chip
         (renderChips rebuilds the strip) and focus fell to <body> — every key
         dead, the P1-2 class. Return it to the surface only when it WAS
         orphaned: a user who moved into an input during a long close keeps it. */
      if (document.activeElement === document.body) focusCenter();
    });
    /* P0-5 (the P2-7 class): role=button with no tabindex was hover-only.
       Enter/Space run the SAME click handler — no second close path. Stopped
       here so the key never reaches the global key map (runs.js owns Space in
       its view) and Space does not scroll. !e.repeat: a held key would queue
       synthetic clicks behind the busy guard above. */
    tx.addEventListener('keydown', function(e){
      if (e.key !== 'Enter' && e.key !== ' ') return;
      e.preventDefault();
      e.stopPropagation();
      if (!e.repeat) tx.click();
    });
    add.parentNode!.insertBefore(chip, add);
  });
}

function renderRibbon(state: WorkspaceState){
  const ts = state.targets || [];
  /* the VIEWED target (P0.1), the backend's active one before a cursor exists */
  const cur = activeTarget();
  const active = (cur && ts.find(t => t.id === cur.id)) || ts.find(t => t.active) || ts[0] || null;
  /* items are found by ID — the label-text match broke silently on any relabel */
  const set = function(id: string, v: string){ const el = document.getElementById(id); if (el) el.textContent = v; };
  set('tgt-n', String(ts.length));
  /* empty project (no targets): zero the readouts instead of crashing (QA m1) */
  set('cls-n', ((active && active.classes) || 0).toLocaleString('en-US'));
  set('engine-n', active
    ? active.engine + (active.engineVersion ? ' ' + String(active.engineVersion).split('.').slice(0,2).join('.') : '')
    : '—');
  /* one unit for the whole item — heapDisplay shares the tip's GiB divisor */
  set('heap-n', active ? heapDisplay(active.heap) : '—');
  /* the gauge next to HEAP reads the same number the text does — it was a
     hard-coded data-fill until now */
  setHeapGauge(active ? active.heap : null);
  renderSidecarIssues(state.sidecarIssues);
  /* markDirty is the UNSAVED choke (code.js) — hand it this snapshot so the
     ribbon count comes from the state we already have, not a second fetch */
  markDirty(!!(active && active.dirty), state);
}

/* sidecarIssues (backend persistence review): carried by workspace.state ONLY
   when > 0 — a sidecar write/park failed or a persist was skipped to protect
   evidence. Absent/0 → no item. A green shell over edits that did not land is
   the one lie the ribbon must never tell. */
function renderSidecarIssues(n: number | undefined){
  const item = document.getElementById('sidecar-item');
  if (!item) return;
  const count = Number(n) || 0;
  item.hidden = count <= 0;
  if (count <= 0) return;
  document.getElementById('sidecar-n')!.textContent = String(count);
  item.setAttribute('data-tip', count + ' sidecar persist issue' + (count === 1 ? '' : 's') +
    ' this session — see the server log; your latest edits may not be saved');
}

/* ONE landing for every workspace.state snapshot (boot, target.switch, close,
   project switch): the snapshot is kept in S.state (chrome.js activeTarget
   reads it) and every strip/ribbon/chip painter runs from the same object */
function applyState(st: WorkspaceState){
  S.state = st;
  renderChips(st);
  renderMapChip(st.activeMap);    // no activeMap → no chip (map-lifecycle §1)
  renderRibbon(st);
  refreshProjectChip(st);
}

/* a limited symbol.search answers {rows,total,truncated}; without a limit, a bare list (2026-09-18) */
function rowsOf<T>(d: unknown): T[] {
  if (Array.isArray(d)) return d as T[];
  const o = d as { rows?: T[] } | null | undefined;
  return o && Array.isArray(o.rows) ? o.rows : [];
}
export { rowsOf };

/* ---- symbol tree from symbol.tree ---- */
/* li → its first-expand fetch (P0.4): the keyboard's → and the open-class
   reveal expand a lazy package without a pointer click */
const lazyLoaders = new WeakMap<HTMLLIElement, () => Promise<void>>();

/* Dalvik class addr → the dotted fqcn the engine reports for it (JebEngine
   dottedName: '$' becomes '.' as well) */
function addrToDotted(a: string){
  const s = String(a);
  return s.slice(s.startsWith('L') ? 1 : 0, s.endsWith(';') ? -1 : undefined)
    .replace(/\//g, '.').replace(/\$/g, '.');
}

/* symbol.tree bound to the VIEWED target (review 2026-09-09 MED-7): every
   tree fetch in this file was target-less, so the tree could come from the
   server's active target while the code view was bound to S.currentTid.
   opT (hub.ts) carries the stale-tid recovery: ids remint across server
   restarts — a dead tid reseeds from the live state and retries once; a
   missing class/package rethrows to the caller. */
async function treeFetch(params: OpParams): Promise<TreePage> {
  if (params.limit === undefined) params.limit = 500;   // the tree's own cut (the backend no longer decides, 2026-09-18)
  return opT('symbol.tree', params);
}
/* the lazy loaders' rejection sink: an async listener's throw would be an
   uncaught rejection (HIGH-2) */
function treeFail(e: unknown){ console.warn('symbol.tree', errMsg(e)); }

/* the ONE open path for a tree row — click, ↵ (P0.4) and the context menu's
   Open (navigation.js, P0.2) all land here. Unresolvable node text
   (member-less mock classes, M6) → silent no-op, never an uncaught rejection. */
function openTreeClass(div: HTMLElement | null){
  const sym = div && div.dataset.sym;
  if (!sym) return;
  loadSymbol(sym).catch(function(){});
}

function treeNode(n: TreeNode, pkg?: string){
  const li = document.createElement('li');
  const hasKids = n.children && n.children.length;
  const div = document.createElement('div');
  div.className = 'tnode';
  if (hasKids || (n.kind === 'package' && n.prefix))
    div.setAttribute('data-t', '');      // lazy packages must collapse too (QA m3)
  /* the tree keys (P0.4) and the context menu (P0.2) read a row from the DOM,
     not from this closure: the open handle (the click's own loadSymbol
     argument), the dotted fqcn classOpened will report (JEB rows carry an
     addr; mock rows only a simple name under a dotted package), kind, name
     and original name. Stamped on exactly the rows that open on click. */
  const openable = (n.kind === 'class' || n.kind === 'interface') && (n.addr || n.fqcn || n.name);
  if (openable){
    div.setAttribute('data-sym', n.origSig || n.addr || n.fqcn || n.name);   // the original signature when the engine gives it: a renamed, reverted or undone class still opens from its row
    div.setAttribute('data-fqcn', n.fqcn || (n.addr ? addrToDotted(n.addr) : (pkg ? pkg + '.' + n.name : n.name)));
    div.setAttribute('data-kind', n.kind!);
    div.setAttribute('data-name', n.name);
    if (n.origName) div.setAttribute('data-orig', n.origName);
  }
  const was = n.origName ? '<span class="was">was: ' + escText(n.origName) + '</span>' : '';
  const dot = n.origName ? '<span class="tdot b"></span>' : (n.kind === 'class' ? '<span class="tdot n"></span>' : '');
  const cnt = hasKids ? '<span class="cnt">' + n.children!.length + '</span>' : (n.count ? '<span class="cnt">' + n.count + '</span>' : '');
  div.innerHTML = '<span class="caret' + (hasKids || n.prefix ? '' : ' leaf') + '">▾</span><span>' + escText(n.name) + '</span>' + was + dot + cnt;
  li.appendChild(div);
  if (hasKids){
    const ul = document.createElement('ul');
    ul.className = 'kids';
    n.children!.forEach(c => ul.appendChild(treeNode(c, n.kind === 'package' ? n.name : pkg)));
    li.appendChild(ul);
  } else if (n.kind === 'package' && n.prefix){
    /* lazy package node: fetch children on first expand (click, → key, or the
       open-class reveal — lazyLoaders hands the same fetch to the latter two) */
    li.setAttribute('data-prefix', n.prefix);
    const load = async function(){
      if (li.dataset.loaded) return;
      li.dataset.loaded = '1';
      let kids: TreeNode[];
      try {
        kids = (await treeFetch({ filter: n.prefix })).items;   // {items,truncated,total} since P0-3
      } catch (e) {
        /* a refused/failed fetch must not brick the package for the session
           (review 2026-09-09 HIGH-2): the latch is set before the await and
           nothing cleared it, so every later click and → was a no-op */
        delete li.dataset.loaded;
        throw e;
      }
      const ul = document.createElement('ul');
      ul.className = 'kids';
      kids.forEach(c => ul.appendChild(treeNode(c)));
      li.appendChild(ul);
      ul.classList.remove('cl');
      const caret = div.querySelector('.caret');
      if (caret) caret.classList.remove('cl');
    };
    lazyLoaders.set(li, load);
    div.addEventListener('click', function(){ load().catch(treeFail); });
  }
  if (openable){
    /* class nodes open on click whether or not they have member children (QA 16) */
    div.addEventListener('click', function(){ openTreeClass(div); });
  }
  return li;
}

/* ---- tree keyboard + open-class marker (P0.4) ----
   #tree is the focus owner (one tabindex on the host, not one per row): ↑↓
   move the .sel cursor over VISIBLE rows — filter-hidden and collapsed rows
   are skipped alike, offsetParent is null for both — ←→ collapse/expand
   exactly as the caret click does (chrome.js data-t toggle; a lazy package's
   first → fetches its children like its first click), ↵ opens through the
   click's own path. The cursor is an orientation marker, not a second caret:
   the keys fire only while focus sits in #tree, and an open through ANY path
   (classOpened) moves the marker to the open class — #code keeps its caret. */
function treeHost(){ return document.getElementById('tree'); }
function visibleNodes(){
  return $all('.tnode', treeHost()).filter(function(d){ return d.offsetParent !== null; });
}
function treeCursor(){
  const d = treeHost()!.querySelector<HTMLElement>('.tnode.sel');
  return d && d.offsetParent !== null ? d : null;       // hidden by filter/collapse → no cursor
}
/* null clears — an unlocatable class shows NO marker rather than a stale one */
function selectTreeNode(div: HTMLElement | null){
  $all('.tnode.sel', treeHost()).forEach(function(x){ if (x !== div) x.classList.remove('sel'); });
  if (!div) return;
  div.classList.add('sel');
  div.scrollIntoView({ block: 'nearest' });
}
function nodeKids(div: HTMLElement){ return div.parentElement!.querySelector(':scope > .kids'); }
function isExpanded(div: HTMLElement){
  const k = nodeKids(div);
  return !!k && !k.classList.contains('cl');
}
function setExpanded(div: HTMLElement, open: boolean){
  const li = div.parentElement as HTMLLIElement;
  const kids = nodeKids(div);
  if (!kids){
    const load = lazyLoaders.get(li);
    if (open && load && !li.dataset.loaded) load().catch(treeFail);     // first expand = the lazy fetch
    return;
  }
  kids.classList.toggle('cl', !open);
  const caret = div.querySelector('.caret');
  if (caret) caret.classList.toggle('cl', !open);
}
function parentNode(div: HTMLElement){
  const up = div.parentElement!.parentElement!.closest('li');   // li > ul.kids > li (parent); the root ul has none
  return up ? up.querySelector<HTMLElement>(':scope > .tnode') : null;
}

function initTree(){
  const host = treeHost();
  if (!host) return;
  host.tabIndex = 0;
  host.setAttribute('role', 'tree');
  host.addEventListener('click', function(e){
    /* the cursor follows a pointer on a PACKAGE row at once (→/← then act on
       it); a class row is marked by classOpened once it is really open —
       never before the decompile lands */
    const d = (e.target as HTMLElement).closest<HTMLElement>('.tnode');
    if (d && !d.dataset.sym) selectTreeNode(d);
  });
  host.addEventListener('keydown', function(e){
    const k = e.key;
    if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'Enter'].includes(k)) return;
    const nodes = visibleNodes();
    if (!nodes.length) return;
    e.preventDefault();
    const cur = treeCursor();
    const i = cur ? nodes.indexOf(cur) : -1;
    if (k === 'ArrowDown'){ selectTreeNode(nodes[Math.min(nodes.length - 1, i + 1)]); return; }
    if (k === 'ArrowUp'){ selectTreeNode(nodes[i <= 0 ? 0 : i - 1]); return; }
    if (k === 'Home'){ selectTreeNode(nodes[0]); return; }
    if (k === 'End'){ selectTreeNode(nodes[nodes.length - 1]); return; }
    if (!cur) return;
    if (k === 'ArrowRight'){
      if (cur.hasAttribute('data-t') && !isExpanded(cur)) setExpanded(cur, true);
      else if (nodes[i + 1] && cur.parentElement!.contains(nodes[i + 1])) selectTreeNode(nodes[i + 1]);   // open → first child
      return;
    }
    if (k === 'ArrowLeft'){
      if (cur.hasAttribute('data-t') && isExpanded(cur)) setExpanded(cur, false);
      else { const p = parentNode(cur); if (p) selectTreeNode(p); }   // leaf / closed → parent
      return;
    }
    /* Enter: a class opens (the click's path); a package toggles like its click */
    if (cur.dataset.sym) openTreeClass(cur);
    else if (cur.hasAttribute('data-t')) setExpanded(cur, !isExpanded(cur));
  });
}

/* the open class is marked in the tree whatever opened it (P0.4): classOpened
   fires from loadSymbol on every path — tree click, RELATION row, search hit,
   tab switch, ‹/›, jumpToCode. Match order: the dotted fqcn the engine
   reported, the handle that just resolved (a tree click's own data-sym), the
   class addr (stable across renames — JEB addrs spell original names). A JEB
   class under a package never expanded is not in the DOM yet: expand the
   longest unloaded prefix containing its addr, one level per round — exactly
   the fetches clicking down to it would make. No match at all (renamed
   class, foreign symbol) → no marker, never a stale one. */
function findTreeNode(fqcn: string, handle: string | undefined, addr: string | undefined){
  return $all('.tnode[data-sym]', treeHost()).find(function(d){
    return d.dataset.fqcn === fqcn || (handle && d.dataset.sym === handle) || (addr && d.dataset.sym === addr);
  }) || null;
}

async function revealTreeClass(fqcn: string, handle: string | undefined){
  const host = treeHost();
  if (!host) return;
  const addr = S.currentSym && S.currentSym.addr;
  let div = findTreeNode(fqcn, handle, addr);
  for (let depth = 0; !div && addr && depth < 12; depth++){
    const lis = $all<HTMLLIElement>('li[data-prefix]', host).filter(function(li){
      return !li.dataset.loaded && String(addr).startsWith(li.dataset.prefix!);
    });
    if (!lis.length) break;
    lis.sort(function(a, b){ return b.dataset.prefix!.length - a.dataset.prefix!.length; });
    const load = lazyLoaders.get(lis[0]);
    if (!load) break;
    try { await load(); } catch (e) { break; }
    if (S.currentFqcn !== fqcn) return;                  // a newer open owns the marker
    div = findTreeNode(fqcn, handle, addr);
  }
  if (div){
    /* reveal: every collapsed ancestor opens (the filter does the same for matches) */
    for (let el = div.parentElement!.parentElement; el && el !== host; el = el.parentElement){
      if (el.classList.contains('kids') && el.classList.contains('cl')){
        el.classList.remove('cl');
        const c = el.parentElement!.querySelector(':scope > .tnode > .caret');
        if (c) c.classList.remove('cl');
      }
    }
  }
  selectTreeNode(div);
}

function renderTabs(){
  const host = document.querySelector('.etabs');
  if (!host) return;
  host.innerHTML = '';
  S.openTabs.forEach(function(t, i){
    const tab = document.createElement('div');
    tab.className = 'etab' + (t.active ? ' on' : '');
    // mockup: a ● marks classes renamed this session
    tab.innerHTML = '<span class="lbl">' + escText(t.simple) + '</span>' + (t.renamed ? '<span class="moddot" title="renamed — not saved yet (Save clears it)">●</span>' : '') + '<span class="x">×</span>';
    tab.title = t.symbol || t.addr || t.simple;
    tab.addEventListener('click', async function(e){
      if ((e.target as HTMLElement).classList.contains('x')){
        // close: fall back to a neighbor tab (QA 16)
        S.openTabs.splice(i, 1);
        if (t.active){
          const next = S.openTabs[Math.min(i, S.openTabs.length - 1)];
          if (next){
            for (const x of S.openTabs) x.active = x === next;
            renderTabs();
            try { await loadSymbol(next.addr || next.symbol); } catch (e) { /* M6: silent */ }
            return;
          }
        }
        renderTabs();
        return;
      }
      for (const x of S.openTabs) x.active = x === t;
      renderTabs();
      try {
        await loadSymbol(t.addr || t.symbol);
      } catch (e) {
        /* the handle died (a name moved under an older tab): the fqcn is kept current by classRenamed —
           try it before giving the tab up (fix-review 3 U5) */
        if (t.addr && t.addr !== t.symbol){
          try { await loadSymbol(t.symbol); return; } catch (e2) { /* fall through: a stale/foreign tab */ }
        }
        /* stale/foreign tab (M6): drop it quietly instead of an uncaught rejection */
        const k = S.openTabs.indexOf(t);
        if (k >= 0) S.openTabs.splice(k, 1);   // the array may have been rebuilt under a slow load (fix-review r4)
        renderTabs();
      }
    });
    host.appendChild(tab);
  });
  /* the strip scrolls sideways when the tabs outgrow it (user 2026-09-19: a new tab "appears out
     of screen"); the active tab is always brought into view */
  const on = host.querySelector<HTMLElement>('.etab.on');
  if (on) on.scrollIntoView({ inline: 'nearest', block: 'nearest' });
}

/* a mouse wheel over the strip scrolls it sideways (a trackpad already does) */
(function(){
  const host = document.querySelector<HTMLElement>('.etabs');
  if (!host) return;
  host.addEventListener('wheel', function(e){
    if (Math.abs(e.deltaY) <= Math.abs(e.deltaX)) return;   // a horizontal gesture scrolls natively
    if (host.scrollWidth <= host.clientWidth) return;
    host.scrollLeft += e.deltaY;
    e.preventDefault();
  }, { passive: false });
})();

function renderTree(nodes: TreeNode[]){
  const host = document.getElementById('tree')!;
  host.innerHTML = '';
  const ul = document.createElement('ul');
  nodes.forEach(n => ul.appendChild(treeNode(n)));
  host.appendChild(ul);
  filterTree(currentTreeFilter());           // re-apply an active filter after re-render
  treeFind(currentTreeFilter());             // …and the server-backed find, or it lists the PREVIOUS target's classes (fix-review r1 H1)
}

/* ---- tree filter (M2): substring match, ancestors of matches stay expanded,
   match highlight, count, Esc clears, '/' focuses ---- */
function currentTreeFilter(){
  const i = document.querySelector<HTMLInputElement>('#col-side .filter input');
  return i ? i.value : '';
}

function initTreeFilter(){
  const input = document.querySelector<HTMLInputElement>('#col-side .filter input');
  if (!input) return;
  const cnt = document.createElement('span');
  cnt.className = 'tfcnt num';
  input.parentNode!.insertBefore(cnt, input.nextSibling);
  input.addEventListener('input', function(){ filterTree(input.value); treeFind(input.value); });
  input.addEventListener('keydown', function(e){
    if (e.key === 'Escape'){
      e.preventDefault();
      input.value = '';
      filterTree('');
      treeFind('');                                  // the find panel goes with the query (fix-review r1 M4)
      input.blur();
      return;
    }
    /* P0.4: the "type /, narrow, ↵" flow opens the first VISIBLE class — one
       that matched itself (.hl) before one merely shown under a matching
       package; ↓ steps into the tree at its cursor (or the first row) */
    if (e.key === 'Enter'){
      e.preventDefault();
      const cls = visibleNodes().filter(function(d){ return !!d.dataset.sym; });
      const first = cls.find(function(d){ return d.querySelector('.hl'); }) || cls[0];
      if (first) openTreeClass(first);
      return;
    }
    if (e.key === 'ArrowDown'){
      e.preventDefault();
      const nodes = visibleNodes();
      if (!nodes.length) return;
      selectTreeNode(treeCursor() || nodes[0]);
      treeHost()!.focus({ preventScroll: true });
    }
  });
  S.hooks.focusTreeFilter = function(){ input.focus(); input.select(); };
}

function filterTree(q: string){
  q = (q || '').trim().toLowerCase();
  const host = document.getElementById('tree');
  const root = host && host.querySelector('ul');
  const cnt = document.querySelector('#col-side .tfcnt');
  if (!root){ if (cnt) cnt.textContent = ''; return; }
  let matches = 0;

  function paint(nameEl: HTMLElement, name: string, hit: string | boolean){
    if (nameEl.dataset.name === undefined) nameEl.dataset.name = name;
    if (!hit){ nameEl.textContent = nameEl.dataset.name; return; }
    const t = nameEl.dataset.name;
    const i = t.toLowerCase().indexOf(q);
    nameEl.innerHTML = escText(t.slice(0, i)) + '<em class="hl">' +
      escText(t.slice(i, i + q.length)) + '</em>' + escText(t.slice(i + q.length));
  }

  function walk(ul: Element): boolean {
    let any = false;
    for (const li of ul.children as HTMLCollectionOf<HTMLElement>) {
      const div = li.querySelector<HTMLElement>(':scope > .tnode');
      if (!div) continue;
      const kids = li.querySelector(':scope > .kids');
      const nameEl = div.children[1] as HTMLElement;
      const name = nameEl.dataset.name !== undefined ? nameEl.dataset.name : nameEl.textContent!;
      const self = q && name.toLowerCase().includes(q);
      const desc = kids ? walk(kids) : false;
      const show = !q || self || desc;
      li.style.display = show ? '' : 'none';
      paint(nameEl, name, self);
      if (self) matches++;
      if (desc && kids){
        kids.classList.remove('cl');
        const caret = div.querySelector('.caret');
        if (caret) caret.classList.remove('cl');
      }
      any = any || show;
    }
    return any;
  }
  walk(root);
  if (cnt) cnt.textContent = q ? matches + ' loaded' : '';
}

/* the DOM filter above sees only the rows that are rendered — lazy packages
   have no children until expanded, so on a 99k-class target it answered "0"
   for classes that exist (UI audit 2026-09-09, explorer HIGH). This second,
   server-backed pass asks the viewed target itself and lists the classes the
   tree has not loaded; a row opens the class. */
let findSeq = 0, findTimer = 0;
function treeFind(q: string){
  clearTimeout(findTimer);
  const host = document.getElementById('tfind');
  if (!host) return;
  q = (q || '').trim();
  if (q.length < 2 || !S.currentTid){ ++findSeq; host.hidden = true; host.innerHTML = ''; return; }   // an in-flight answer must not re-open it (fix-review r1 M2)
  findTimer = setTimeout(async function(){
    const my = ++findSeq;
    const r0 = await tryOpT('symbol.search', { query: q, limit: 50 });   // the find box shows 50; a limit answers {rows,total,truncated}
    const r = { ...r0, data: rowsOf(r0.data) } as typeof r0;
    if (my !== findSeq) return;
    const cnt = document.querySelector('#col-side .tfcnt');
    const chip = function(suffix: string){ if (cnt && cnt.textContent) cnt.textContent = cnt.textContent.replace(/ · .*$/, '') + ' · ' + suffix; };
    host.innerHTML = '';
    /* a failed search is not "0 in target" — the exact lie the DOM filter told (fix-review r1 M3) */
    if (r.error){ chip('find failed: ' + r.error.message); host.hidden = true; return; }
    const rows = (r.data || []).filter(function(x){ return !x.kind || x.kind === 'class' || x.kind === 'interface'; }).slice(0, 40);
    chip(rows.length + (rows.length === 40 ? '+' : '') + ' in target');
    if (!rows.length){ host.hidden = true; return; }
    const head = document.createElement('div');
    head.className = 'tfind-head';
    head.textContent = 'IN TARGET · ' + rows.length + (rows.length === 40 ? '+' : '') + ' — not all loaded in the tree';
    host.appendChild(head);
    rows.forEach(function(x){
      const row = document.createElement('div');
      row.className = 'tfind-row';
      const handle = String(x.addr || x.name);
      row.innerHTML = '<span class="tfind-name"></span><span class="tfind-owner"></span>';
      (row.firstElementChild as HTMLElement).textContent = x.name || handle;
      (row.lastElementChild as HTMLElement).textContent = x.owner || '';
      row.title = handle;
      row.addEventListener('click', function(){ loadSymbol(handle).catch(function(){}); });
      host.appendChild(row);
    });
    host.hidden = false;
  }, 250);
}

/* ---- which class to display: the mockup class when present, else the first
   class the active target's tree offers (real JEB targets) ---- */
function firstClass(nodes: TreeNode[]): TreeNode | null {
  for (const n of nodes){
    if ((n.kind === 'class' || n.kind === 'interface') && (n.addr || n.fqcn || n.name)) return n;
    if (n.children){
      const c = firstClass(n.children);
      if (c) return c;
    }
  }
  return null;
}

/* app-namespace packages first when drilling (framework prefixes last):
   the interesting first-screen class is the app's, not a support-library stub. */
const FRAMEWORK = /^L(android|androidx|java|javax|kotlin|kotlinx|dalvik|sun)\//;

async function anyClassSymbol(){
  let nodes = (await treeFetch({})).items;   // {items,truncated,total} since P0-3
  for (let depth = 0; depth < 8; depth++){
    const cls = firstClass(nodes);
    if (cls) return cls.addr || cls.fqcn || cls.name;
    const pkgs = nodes.filter(n => n.prefix);
    if (!pkgs.length) return null;
    pkgs.sort((a, b) => (FRAMEWORK.test(a.prefix!) ? 1 : 0) - (FRAMEWORK.test(b.prefix!) ? 1 : 0)
      || (b.count || 0) - (a.count || 0));
    nodes = (await treeFetch({ filter: pkgs[0].prefix })).items;
  }
  return null;
}

async function displayTarget(){
  /* M7: no saved view state → CLEAN tab strip. Foreign tabs from the previous
     target never leak; the restore path (restoreViewState) sets its own. */
  S.openTabs = [];
  renderTabs();
  let tree: TreePage;
  try { tree = await treeFetch({}); }
  catch (e) { await showEmptyTarget(); return; }   // empty project / engine down
  renderTree(tree.items || []);
  const d = await tryOpT('code.decompile', { symbol: FQCN, scope: 'class', tokens: true });
  if (d.data){
    await loadSymbol(FQCN);
    return;
  }
  const sym = await anyClassSymbol();
  if (!sym){ await showEmptyTarget(); return; }
  try {
    await loadSymbol(sym);
  } catch (e) {
    await showEmptyTarget();                // fixture-less / no decompilable class
  }
}

/* graceful empty state (M3): a target with no displayable class — native binary,
   analysis-only engine, or no decompile fixture. Everything target-rooted,
   nothing stale from the previous target. */
async function showEmptyTarget(reason?: string){
  /* empty project (no viewed target): label with the project name, not a stale
     target. State-backed (P0.1) — the map chip can no longer masquerade as the
     active target (opus map-r4 #1/#14). `reason` (boot failure) replaces the
     target.info probe: the backend is not there to ask. */
  const t = activeTarget();
  const name = t ? t.name : document.getElementById('projname')!.textContent;
  /* empty workspace (no project at all) or a project with NO TARGETS (an ON
     DISK load lands here): the surface's job is the call to action, not a
     fixture obituary — and the target.info probe has nothing to ask
     (fix-review r1 #6 + r2b #2) */
  const noProject = !(S.state && (S.state.projects || []).length);
  const info: { data?: TargetInfo; error?: OpError } = (reason || noProject || !t) ? {} : await tryOpT('target.info', {});
  const engine = (info.data && info.data.engine) || '';
  const code = document.getElementById('code')!;
  const bar = code.querySelector('.caretbar');
  code.innerHTML = '';
  const empty = document.createElement('div');
  empty.className = 'codempty';
  // honest failure surface (review): an errored target is not an empty fixture —
  // say what actually happened instead of "no decompilation fixture"
  const failed = info.data && (info.data.state === 'error' || info.data.error);
  empty.innerHTML = '<b>' + escText(name) + (engine ? ' · ' + escText(engine) : '') + '</b>' +
    (reason ? '<span>' + escText(reason) + '</span>' : noProject
      ? '<span>no project open — ＋ picks a project, then the artifact · ⌘⇧O opens the project menu</span>'
      : !t ? '<span>no targets in this project — ＋ on the target strip opens an artifact</span>'
      : failed
      ? '<span>target failed to open — ' + escText(info.data!.error || info.data!.state || 'unknown error') + '</span>'
      : '<span>no decompilable classes in this target' +
        (/\.(so|dll|dylib|a)$/.test(name!) ? ' — native binary, analysis only' : ' — no decompilation fixture') + '</span>');
  code.appendChild(empty);
  if (bar) code.appendChild(bar);
  // the tree is target-scoped too — clear it or the previous target's
  // packages linger behind the honest empty state (review finding 7)
  const treeEl = document.getElementById('tree');
  if (treeEl) treeEl.innerHTML = '<div class="dockhint">no symbols — nothing open in this target</div>';
  const sm = document.getElementById('smali');
  if (sm) sm.innerHTML = '<div class="sr"><span class="sg"></span><span class="off"></span><span class="byt"></span>' +
    '<span class="slc"><span class="c"># ' + escText(name) + ' — nothing to disassemble</span></span></div>';
  const ph = document.querySelector('#pane-java .panehead');
  if (ph) ph.innerHTML = escText(name) + '<span class="phent">empty</span>';
  const sh = document.querySelector('#pane-smali .panehead');
  if (sh) sh.innerHTML = escText(name) + '<span class="phent">empty</span>';
  // breadcrumb roots at the target, no class segments
  setCodeCrumb('<span class="nav">' + escText(name) + '</span>');
  // inspector: target summary, not stale class data
  const ci = document.getElementById('class-info');
  if (ci){
    let rows = '<span class="k">TARGET</span><span class="v">' + escText(name) + '</span>';
    if (info.data){
      if (info.data.engine) rows += '<span class="k">ENGINE</span><span class="v">' + escText(info.data.engine) + '</span>';
      rows += '<span class="k">CLASSES</span><span class="v num">' + escText(info.data.classes ?? 0) + '</span>';
      rows += '<span class="k">STATE</span><span class="v">' + escText(info.data.state || 'ok') + '</span>';
    }
    ci.innerHTML = rows;
    ci.querySelectorAll<HTMLElement>('.v').forEach(function(v){ v.title = v.textContent!; });   // QA 25
  }
  const rn = document.getElementById('rel-name');
  if (rn) rn.textContent = name;
  const rs = document.getElementById('rel-sig');
  if (rs) rs.textContent = engine;
  renderRelation([], [], []);
  clearRelationSubject();
  const etab = document.querySelector('.etabs .etab.on') || document.querySelector('.etabs .etab');
  if (etab) etab.innerHTML = escText(name) + '<span class="x">×</span>';
  S.currentClass = null;
  S.currentSym = {};
  S.javaMethods = [];
  S.caretReady = false;
}

/* the viewed tid against the live state (the hello path): still listed →
   nothing; gone → reseedTarget (app boot) decides — the same artifact back
   under a new id moves the cursor and keeps the view (same class, same apk),
   so the docks and the gutter re-read here; gone for good → the landing ran
   inside the reseed (fix-review r2 #2, r3 #2). The hello carries the
   snapshot (WorkbenchServer: hello.state) — a fetch only when it does not. */
let revalidating = false;
async function revalidateTid(data: SseData){
  if (revalidating || !S.currentTid || !S.hooks.reseedTarget) return;
  revalidating = true;
  try {
    const tid0 = S.currentTid;
    const carried = data && typeof data === 'object' ? (data as { state?: WorkspaceState }).state : undefined;
    const st = carried && Array.isArray(carried.targets) ? carried : (await tryOp('workspace.state', {})).data;
    if (!st || S.currentTid !== tid0) return;                    // a switch landed meanwhile — its own chain repaints
    if (st.targets.some(function(t){ return t.id === tid0; })) return;
    const tid = await S.hooks.reseedTarget();
    if (!tid) return;
    await loadHistory(); await loadTags(); await loadFlows(); await loadBookmarks();
    hook('marksChanged');
    if (S.center === 'strings') await reloadStrings();
    if (S.center === 'search') await reloadSearch();
  } finally { revalidating = false; }
}

async function refreshState(){
  const state = await op('workspace.state');
  applyState(state);
  return state;
}

/* (7) the project chip always shows the LIVE workspace counts (fixture names may stay) */
function refreshProjectChip(state: WorkspaceState){
  const projs = state.projects || [];
  const proj = projs.find(p => p.active) || projs[0];      // QA m1: the ACTIVE project, not [0]
  if (proj){
    document.getElementById('projname')!.textContent = proj.name;
    document.getElementById('projcount')!.textContent = state.targets.length + ' targets';
  } else {
    /* empty workspace (fresh boot / last project closed): say so — never a
       stale name from the project that was just closed */
    document.getElementById('projname')!.textContent = '—';
    document.getElementById('projcount')!.textContent = '';
  }
}

let bootFailed = false;   // the boot fetch threw: the hello path's hooks never registered — the next hello reloads (fix-review r3 LOW-2)
let linkUp = false;       // the last transport verdict (api.ts onLink)

async function boot(){
  initClientLog();                          // the browser's errors persist in <home>/logs/workbench.log
  /* initChrome installs the tooltip's window-capture keydown hider; it must
     register BEFORE the Escape dispatcher — hub.js claims Escapes with
     stopImmediatePropagation, which would starve a later-registered hider
     (opus r4 F6). The hider never stops propagation, so the swap costs the
     dispatcher nothing. */
  initChrome();
  /* the Escape dispatcher owns the key app-wide (hub.js escape stack) */
  initEscape();
  initCaret();
  initPalette();
  initCode();
  initHistory();
  initTags();
  initFlows();
  initBookmarks();
  initTree();
  initTreeFilter();
  initLayouts();
  loadUiPrefs();                  // the operator's chrome from the server (audit decision 17) — async, defaults stand meanwhile
  initNavigation();
  initMap();
  initAgents();
  initRuns();
  initDevice();
  initRibbon();
  /* the open-status cards' host (openstatus.js): every accepted target.open —
     launcher or device pull — gets a card there, so it must exist before
     either path can run (2026-09-08) */
  initOpenStatus();
  /* the jump status card shares that corner (jumpstatus.js): "decompiling <X>"
     + clock while a strings jump's loadSymbol runs (segU, 2026-09-08). After
     initOpenStatus only for the reading order — it binds the same host. */
  initJumpStatus();
  /* retained MethodParameters in the inspector + the analyst's mapping dialogs (ui/params.ts) */
  initParams();
  syncTabs();
  applyLayout();

  /* STATE follows the transport: every op that answers paints READY, a fetch
     that never lands paints DOWN (api.js onLink) */
  onLink(function(up, detail){ setState(up ? 'READY' : 'DOWN', detail); linkUp = up; });

  /* live data */
  try {
    const state = await refreshState();
    try { sessionStorage.removeItem('wb.bootReloads'); } catch (e) { /* storage may be unavailable */ }

    S.hooks.refreshProjectCount = async function(){
      refreshProjectChip(await op('workspace.state'));
    };

    /* ---- real class tabs (QA 16): open = add/activate, click = load, × = close ---- */
    S.openTabs = [];
    S.hooks.renderTabs = renderTabs;
    S.hooks.classOpened = function(symbol, simple, renamed, handle, prevFqcn){
      // symbol (resolved FQCN) is the dedupe key; handle (the input that just
      // resolved) is the re-open handle — a default-package class's FQCN can
      // be ambiguous on re-open (review: class 'a' silently lost its tab)
      const reopen = handle || symbol;
      /* a re-render of the SAME class after it was renamed resolves to a new fqcn: re-key the
         tab it came from (same handle, or the fqcn the view showed a moment ago) — never a twin */
      const existing = S.openTabs.find(t => t.symbol === symbol)
        || (prevFqcn ? S.openTabs.find(t => t.symbol === prevFqcn || t.addr === reopen) : undefined);
      if (!existing) S.openTabs.push({ symbol, simple, renamed: !!renamed, addr: reopen });
      else if (existing.symbol !== symbol){ existing.symbol = symbol; existing.simple = simple; existing.renamed = true; }
      else if (renamed) existing.renamed = true;
      for (const t of S.openTabs) t.active = t.symbol === symbol;
      renderTabs();
      revealTreeClass(symbol, handle).catch(function(){});   // the tree marks + reveals the open class (P0.4)
      scheduleViewSave();                                     // the position follows the artifact (target.view.set)
    };

    /* the open class was renamed (F2, or an accepted proposal): the tree row it came from
       gets the new name and a "was:" marker in place — the row's data-sym (the handle the
       backend still resolves through origSig) stays, so re-opening it keeps working */
    S.hooks.classRenamed = function(oldFqcn, oldAddr, newFqcn, newSimple, restored, key){
      /* the tree row the class came from: it remembers the ORIGINAL name (dataset.orig), which
         decides whether this change restores it — a rename.revert says so itself, an undo or a redo
         of a rename does not know */
      const host = treeHost();
      const div = host ? $all('.tnode[data-sym]', host).find(function(d){
        return d.dataset.fqcn === oldFqcn || (!!oldAddr && d.dataset.sym === oldAddr) || d.dataset.fqcn === newFqcn;
      }) : undefined;
      const orig = div ? (div.dataset.orig || div.dataset.name || oldFqcn.split('.').pop() || oldFqcn) : undefined;
      const restoredEff = restored === true || (restored !== false && !!orig && newSimple === orig);
      /* every tab of the class follows (the active one or not): re-keyed to the new fqcn, its
         re-open handle kept (the view's key — the original signature when the engine gives it),
         dotted as unsaved — a RESTORATION (the dex's own name back) clears the renamed mark instead */
      let tabs = false;
      for (const t of S.openTabs){
        if (t.symbol === oldFqcn || t.symbol === newFqcn || (!!oldAddr && (t.addr === oldAddr || t.symbol === oldAddr))){
          t.symbol = newFqcn;
          t.simple = newSimple;
          t.renamed = !restoredEff;
          tabs = true;
        }
      }
      if (tabs) renderTabs();
      if (div){
        div.dataset.fqcn = newFqcn;
        div.dataset.name = newSimple;
        if (key) div.dataset.sym = key;   // the row re-opens by the stable handle, never by a name that dies on the next change (fix-review 3 M1)
        const spans = div.querySelectorAll(':scope > span');
        const nameSpan = spans[1] as HTMLElement | undefined;
        if (nameSpan){ nameSpan.textContent = newSimple; nameSpan.dataset.name = newSimple; }   // the filter paints from the span's own cached name (persona pass 3, M1)
        let was = div.querySelector<HTMLElement>(':scope > .was');
        const dot = div.querySelector<HTMLElement>(':scope > .tdot');
        if (restoredEff){
          /* the original name again: no "was:", no renamed dot, no remembered original */
          delete div.dataset.orig;
          if (was) was.remove();
          if (dot) dot.className = 'tdot n';
        } else {
          div.dataset.orig = orig;
          if (!was && nameSpan){ was = document.createElement('span'); was.className = 'was'; nameSpan.after(was); }
          if (was) was.textContent = 'was: ' + orig;
          if (dot) dot.className = 'tdot b';
        }
      }
      /* the side find list is a server search: rows for the old name are dead after the change —
         re-run the active query (persona pass 3, M4) */
      const q = currentTreeFilter();
      if (q){ filterTree(q); treeFind(q); }
    };

    /* ---- per-target view state (QA 11): save on switch-away, restore on return ---- */
    S.targetViews = {};
    /* seed the viewed-target cursor from the BACKEND (GUI review finding 1,
       CRITICAL): the 't1' literal was mock-scene-only — Workspace mints real
       ids from nextTargetNum (this workspace: t5/t6/t30) — so the first
       loadSymbol sent target:"t1" and the code view rendered EMPTY on every
       real workspace. state is already in hand from refreshState() above */
    /* the target THIS browser was viewing wins over the server's active cursor: another client
       (an MCP researcher on a second apk) opening a target makes it active server-wide, and a
       reload used to land here on their apk (user 2026-09-20) */
    let remembered: string | null = null;
    try { remembered = localStorage.getItem('viewedTid'); } catch (e) { /* storage may be unavailable */ }
    const rememberedOk = remembered && state.targets.some(t => t.id === remembered) ? remembered : null;
    S.currentTid = rememberedOk || (state.targets.find(t => t.active) || state.targets[0] || ({} as { id?: string })).id || null;
    setNavTarget(S.currentTid);
    /* recovery path for a stale tid (ids remint across server restarts):
       re-seed from the live state (GUI review finding 9) */
    /* fix-review r2 #2, r3 #1/#2: a reseed follows the ARTIFACT, not the
       cursor. The target the viewer is SHOWING (the S.state snapshot taken
       before the fetch) is looked for under its new id by PATH first — a name
       is the file's basename, and every device pull is a base.apk — and by
       name only when the row carried no path. Found → the cursor moves, the
       view stays (same apk, same class) and its saved view is re-keyed. NOT
       found → the shell is LANDED right here (projectSwitched: the backend's
       active target through the full switch chain, or the honest empty state)
       and null comes back: no caller retries a gesture on a different apk,
       and S.currentTid is never left null behind a stale code pane. One
       reseed at a time — overlapping callers (a hello, a failing op) share it. */
    let reseeding: Promise<string | null> | null = null;
    let landing = false;
    S.hooks.reseedTarget = function(){
      if (landing) return Promise.resolve(null);   // a failure INSIDE the landing must not wait on the landing (fix-review r4 #2)
      if (!reseeding) reseeding = reseedTarget0().finally(function(){ reseeding = null; });
      return reseeding;
    };
    async function reseedTarget0(): Promise<string | null> {
      const tid0 = S.currentTid;
      const old = (S.state && S.state.targets.find(t => t.id === tid0)) || null;
      const st = await op('workspace.state');
      if (S.currentTid !== tid0) return null;     // a switch landed meanwhile: that cursor is live, nothing to reseed (fix-review r4 #3)
      /* UNIQUE, or nothing: two engines over one artifact (the shipped mock
         scene: a JEB and a JADX gmail.apk on one path) tie on path and name,
         and a first-row pick would land every retried WRITE on the other
         engine's target (fix-review r5 #1) — an ambiguous match lands instead */
      const byPath = old && old.path ? st.targets.filter(t => t.path === old.path) : [];
      const byName = old && !old.path ? st.targets.filter(t => t.name === old.name) : [];
      const hits = byPath.length ? byPath : byName;
      const same = hits.length === 1 ? hits[0] : undefined;
      if (!same){
        /* AWAITED, and never re-entered: the landing IS the guarantee that a
           dead cursor ends in a repainted shell (fix-review r4 #2) */
        landing = true;
        try { if (S.hooks.projectSwitched) await S.hooks.projectSwitched(st, { awaitSwitch: true }); }
        catch (e) {
          console.warn('landing', errMsg(e));
          /* a throw before the chip branch (the map reload) skipped the switch
             AND the empty state — the shell must still land (fix-review r5 #4) */
          if (!S.currentTid) { try { await emptyLanding('landing failed — ' + errMsg(e) + ' · click a target chip'); } catch (e2) { console.warn('landing', errMsg(e2)); } }
        }
        finally { landing = false; }
        return null;
      }
      if (old && old.id !== same.id && S.targetViews[old.id]){   // the saved view follows the artifact
        S.targetViews[same.id] = S.targetViews[old.id];
        delete S.targetViews[old.id];
      }
      S.currentTid = same.id;
      setNavTarget(same.id);
      applyState(st);                 // the strip's .on follows the re-seeded cursor
      updateCenter();                 // readouts follow the re-seeded cursor
      return same.id;
    }

    function saveViewState(){
      if (!S.currentTid || !S.currentClass) return;
      const code = document.getElementById('code');
      S.targetViews[S.currentTid] = {
        class: S.currentClass,
        caret: { line: S.caret.line, col: S.caret.col },
        scroll: code ? code.scrollTop : 0,
        tabs: S.openTabs.map(t => ({ ...t })),
      };
      persistView(S.currentTid);
    }
    /* the position lives on the server beside the artifact (target.view.set → view.json): a
       project close, a reload, a backend restart or another machine all land back on it. Debounced
       — a burst of class opens writes once. */
    let viewPersistTimer = 0;
    function persistView(tid: string){
      window.clearTimeout(viewPersistTimer);
      viewPersistTimer = window.setTimeout(function(){
        const v = S.targetViews[tid];
        if (!v || !v.class) return;
        tryOp('target.view.set', { target: tid, view: v }).then(function(r){ if (r.error) console.warn('target.view.set', r.error.message); });
      }, 300);
    }
    /* after a class opens, remember the position once the view settles */
    let viewSaveTimer = 0;
    function scheduleViewSave(){
      window.clearTimeout(viewSaveTimer);
      viewSaveTimer = window.setTimeout(saveViewState, 1500);
    }
    S.hooks.saveViews = function(){ window.clearTimeout(viewSaveTimer); saveViewState(); };
    window.addEventListener('beforeunload', function(){
      /* best effort: the page is going away — persist synchronously via a beacon */
      saveViewState();
      const tid = S.currentTid; const v = tid ? S.targetViews[tid] : null;
      if (tid && v && v.class && navigator.sendBeacon) {
        try { navigator.sendBeacon('/api/target.view.set', new Blob([JSON.stringify({ params: { target: tid, view: v }, actor: 'human' })], { type: 'application/json' })); } catch (e) { /* ignore */ }
      }
    });

    async function restoreViewState(){
      if (!S.currentTid) return false;
      const tid = S.currentTid;                  // a treeFetch below can reseed the cursor — key on the one we started with
      let v = S.targetViews[tid];
      if (!v || !v.class){
        /* nothing in the page (a project reopened, a reload, a restart): ask the artifact's sidecar */
        const r = await tryOp('target.view', { target: tid });
        const pv = r.data ? (r.data as { view?: TargetView | null }).view : null;
        if (pv && pv.class){ v = { class: pv.class, caret: pv.caret || { line: 1, col: 1 }, scroll: pv.scroll || 0, tabs: pv.tabs || [] }; S.targetViews[tid] = v; }
      }
      if (!v || !v.class) return false;
      try {
        renderTree((await treeFetch({})).items);   // restore path must re-render too
        /* restore replays saved state, it is not a navigation — no history push */
        if (S.hooks.navGuard) await S.hooks.navGuard(function(){ return loadSymbol(v.class!); });
        else await loadSymbol(v.class);
        S.openTabs = (v.tabs || []).map(t => ({ ...t }));
        for (const t of S.openTabs) t.active = t.symbol === v.class || t.addr === v.class || t.symbol === S.currentClass;
        if (S.openTabs.length && !S.openTabs.some(t => t.active)) S.openTabs[0].active = true;   // the saved class is an addr, tabs key on the FQCN — never a tab strip with nothing active
        renderTabs();
        /* the caret goes back to the remembered line and column and the view is CENTRED on it —
           not the raw scroll offset from last time, which lands a little above the caret whenever
           the text re-rendered longer (an agent's comments) or the pane's height changed
           (user 2026-09-20: "the view is somewhere a bit up and you need to scroll down") */
        const line = Math.max(0, Math.min(S.codeLines.length - 1, v.caret.line));
        S.caret.line = line;
        S.caret.col = v.caret.col;
        S.caret.goal = v.caret.col;
        if (S.hooks.measureRender) S.hooks.measureRender();
        renderCaret(false, false);
        const centre = function(){ const L = S.codeLines[line]; if (L && L.cl) L.cl.scrollIntoView({ block: 'center', inline: 'nearest' }); };
        centre();
        requestAnimationFrame(centre);            // once more after layout settles (fonts, the dock, a late measure)
        window.setTimeout(centre, 250);
        return true;
      } catch (e) {
        delete S.targetViews[tid];                 // class gone (closed target) → default
        return false;
      }
    }

    /* one switch at a time (review 2026-09-09 MED-6): two chip clicks inside
       one decompile used to interleave, and whichever displayTarget landed
       last owned the tree/code/tabs while S.currentTid said the other target.
       A newer switch supersedes an older one at every await — the older chain
       stops painting; the backend's cursor is the last switch's anyway. */
    let switchSeq = 0, switching = false;
    S.hooks.targetSwitched = async function(chip){
      const tid = chip.getAttribute('data-tid');
      if (!tid) return;
      const seq = ++switchSeq;
      const live = function(){ return seq === switchSeq; };
      try {
        if (!switching) saveViewState();   // a chain still loading has nothing worth saving under its target (fix-review r4 #6)
        switching = true;
        const st = await op('target.switch', { target: tid });
        if (!live()) return;
        S.currentTid = tid;
        setNavTarget(tid);
        try { localStorage.setItem('viewedTid', tid); } catch (e) { /* ignore */ }   // this browser's own view, kept across reloads
        applyState(st);
        updateCenter();                 // crumb root, THIS chip, dock scope follow the viewed target (P0.1)
        refreshRuntimeDock();           // RUNTIME dock = the viewed target's runs (batch 2)
        clearRelationDock();            // the dock's RELATION list belonged to the old target (persona M6)
        S.covRun = null;                // the coverage run is the old target's; the code view reseeds from this one's (r4 M2)
        if (!await restoreViewState()){ if (!live()) return; await displayTarget(); }
        if (!live()) return;
        await loadHistory(); if (!live()) return;
        await loadTags(); if (!live()) return;
        await loadFlows(); if (!live()) return;
        await loadBookmarks(); if (!live()) return;
        if (S.center === 'strings') await reloadStrings();
        if (S.center === 'search') await reloadSearch();
      } catch (e) { console.warn('target.switch failed', e); }
      finally { if (live()) switching = false; }
    };

    /* views re-query their target when opened */
    S.hooks.viewOpened = async function(v){
      if (v === 'strings') await initStrings();
      if (v === 'search') await initSearchView();
      if (v === 'runs') reloadRuns();
      /* no SSE channel announces adb plug/unplug (api.js channels: progress,
         presence, activity, proposals, runs) — opening DEVICE is when the ribbon
         count can honestly be re-checked (loadDevices feeds the ribbon too) */
    };

    /* project switch/close/open (QA m1/m2): strip + surface follow the new
       project — full chip path when it has targets, honest empty state when not */
    S.hooks.projectSwitched = async function(state, opts){
      /* null the stale tid BEFORE renderChips: it paints .on from S.currentTid
         when set, and the OLD project's id matches no chip in the new project
         — the map chip was then the only .tchip.on (the map-r4 #1 trap) */
      S.currentTid = null;
      applyState(state);                    // the new project's strip, map chip (or none), ribbon
      refreshProposalsForProject();         // PROPS + the review queue are project-scoped (batch 2)
      refreshRuntimeDock();                 // no target yet: the dock says so until a chip lands
      clearRelationDock();
      if (S.center === 'runs') reloadRuns();
      refreshAgentChip();                   /* pins are project-scoped — the chip must follow */
      resetPresencePing();                  /* and the human's last-sent addr is meaningless now */
      S.targetViews = {};                     // saved views belong to the old project's targets
      S.openTabs = [];
      renderTabs();
      /* the map module is project-scoped state too: load the NEW project's map
         (or clear a mapless one) BEFORE the chip logic, or the canvas, type
         registry and node→target jumps keep pointing at the CLOSED project
         (opus map-r4 #2) */
      if (S.hooks.mapProjectSwitched) await S.hooks.mapProjectSwitched();
      /* never the map chip — it carries .tchip.on while the map is open and
         its click just toggles the map closed, so the target reset below
         never ran (opus map-r4 #1) */
      const chip = document.querySelector<HTMLElement>('.targets .tchip.on:not(#mapchip)');
      if (chip && opts && opts.awaitSwitch){
        /* the dead-cursor landing (reseedTarget): wait for the whole switch
           chain. A chain SUPERSEDED by a newer switch is that switch's to
           paint; a chain that FAILED must not leave a null cursor behind the
           old class — the empty state, saying so (fix-review r4 #2, r5 #2/#3) */
        if (S.center === 'map') showCenter('code');
        const seqBefore = switchSeq;
        if (S.hooks.targetSwitched) await S.hooks.targetSwitched(chip);
        if (S.currentTid || switchSeq !== seqBefore + 1) return;
        await emptyLanding('target switch failed — click a target chip to retry');
        return;
      } else if (chip){
        chip.click();                       // full targetSwitched path
        return;
      }
      await emptyLanding();
    };

    /* the no-target landing: cursor cleared, tree cleared, the empty card
       (with `reason` when the landing itself failed), readouts to '—' */
    async function emptyLanding(reason?: string){
      S.currentTid = null;
      setNavTarget(null);
      document.getElementById('tree')!.innerHTML = '';
      await showEmptyTarget(reason);
      updateCenter();                     // readouts: no viewed target → '—'
    }

    /* target launcher: "+" → open artifact → the launcher CLOSES on accept →
       the open-status card polls (openstatus.js) → READY → this callback: strip
       refresh + the new chip. It runs from the card, never from the launcher,
       so an Escape mid-analysis can no longer orphan the finished target
       (2026-09-08) */
    initLauncher(async function(tid: string){
      await refreshState();
      const chip = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + tid + '"]');
      if (chip) chip.click();               // goes through targetSwitched
    });

    /* refreshState + the readiness wait, shared by targetOpened and callers that
       must touch the engine BEFORE the jump (the device view resolves the
       component class first). True only when the target reaches 'ready'. */
    S.hooks.targetReady = async function(t){
      await refreshState();
      if (!t || !t.id) return false;
      /* the open-status card owns the poll (one card per open in flight, the
         device pull's included — its progress used to be invisible) and this
         waits on its verdict instead of running a second poller. The 10-minute
         cap stays: a device chain must not hold its pull guard forever behind
         a backend that never answers; the card itself keeps reporting. */
      const deadline = new Promise<boolean>(function(res){ setTimeout(function(){ res(false); }, 600000); });
      return Promise.race([trackOpen(t as TargetInfo).done, deadline]);
    };

    /* device pull → target opened: refresh the strip + activate the new chip
       (after analysis completes — displayTarget can't read a busy engine).
       With a SYMBOL the caller means "open AND land there": jumpToCode runs the
       switch as one awaited chain, so the default-class display can never race
       the navigation and overwrite it (the bare chip.click() path did exactly
       that to the device-view component double-click) */
    S.hooks.targetOpened = async function(t, symbol){
      if (!await S.hooks.targetReady!(t)) return;
      if (symbol){ await jumpToCode(t.id, symbol); return; }
      const chip = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + t.id + '"]');
      if (chip) chip.click();
    };

    if (!await restoreViewState()) await displayTarget();   // the remembered position first (view.json); the default class only without one
    refreshRuntimeDock();           // boot lands on a target without a chip click: the dock follows it too (batch 2)
    await loadHistory();
    await initStrings();
    await initSearchView();
  } catch (e) {
    /* honest failure (P0.5): the shell ships blank, so a failed boot shows '—'
       readouts + this message — never a fixture session behind a DOWN state
       item (api.js onLink already painted STATE) */
    console.error('backend unavailable', e);
    bootFailed = !linkUp;   // only a TRANSPORT failure earns the reload — a backend that answers but errors must show its message, not loop (fix-review r4 #4)
    try { await showEmptyTarget('backend unavailable — ' + errMsg(e)); }
    catch (err) { console.warn('empty state', err); }
  }

  /* SSE event bus (log for M2). A RECONNECT hello (the boot hello lands before
     this subscriber exists) is the one "you may have missed things" signal
     (ribbon.ts): a backend restart remints target ids, so a viewed tid the
     live state no longer lists is re-validated (fix-review r1 #1). A tid that
     still lives is never moved — the server's active cursor is not the
     viewed target (P0.1). */
  events(function(name, data){
    console.log('[sse]', name, data);
    if (name !== 'hello') return;
    if (bootFailed){                                 // the backend is back and this shell never booted — capped: a shell that keeps failing must stay readable
      let n = 0;
      try { n = +(sessionStorage.getItem('wb.bootReloads') || 0); sessionStorage.setItem('wb.bootReloads', String(n + 1)); } catch (e) { n = 99; }
      if (n < 3) location.reload();
      return;
    }
    revalidateTid(data).catch(function(e){ console.warn('reseed', errMsg(e)); });
  });

  updateCenter();
}

boot();

/* the close-target dialog (index.html #closemodal): shown ONLY for a close that would
   write unsaved edits. Resolves 'save' | 'discard' | 'cancel'; Esc = cancel. The count
   comes from target.changes — the same rule the crash-window replay uses. */
function confirmClose(name: string, ch: { symbols?: number; renamed?: number; commented?: number; decompiled?: number; lastSaved?: string | null }): Promise<'save' | 'discard' | 'cancel'> {
  return new Promise(function(resolve){
    const modal = document.getElementById('closemodal')!;
    if (!modal.hidden){ resolve('cancel'); return; }   // a close dialog is already up — never stack a second on the shared modal (review B1)
    const title = document.getElementById('close-title'), msg = document.getElementById('close-msg');
    const parts: string[] = [];
    if (ch.renamed) parts.push(ch.renamed + (ch.renamed === 1 ? ' symbol renamed' : ' symbols renamed'));
    if (ch.commented) parts.push(ch.commented + (ch.commented === 1 ? ' comment' : ' comments'));
    /* decompilations live in the JEB database too: a first-time decompile is worth saving —
       the next open then has the code ready instead of starting "fresh" (user rule) */
    if (ch.decompiled) parts.push(ch.decompiled + (ch.decompiled === 1 ? ' class/method newly decompiled' : ' classes/methods newly decompiled'));
    const editsOnly = !ch.decompiled, decompOnly = !!ch.decompiled && !(ch.symbols || 0);
    if (title) title.textContent = 'CLOSE ' + name.toUpperCase();
    if (msg) msg.textContent = (parts.length ? parts.join(', ') : (ch.symbols || 0) + ' symbols changed')
      + ' since the last save' + (ch.lastSaved ? ' (' + new Date(ch.lastSaved).toLocaleString() + ')' : '')
      + '. Save before closing? DISCARD closes without writing — '
      + (editsOnly ? 'these edits will not come back.' : decompOnly ? 'the decompiled code is simply produced again next time.' : 'the edits will not come back; the decompiled code is produced again next time.');
    let h: ReturnType<typeof pushEscape> | null = null;
    const done = function(v: 'save' | 'discard' | 'cancel'){ modal.hidden = true; if (h) popEscape(h); resolve(v); };
    h = pushEscape(function(){ if (modal.hidden) return false; done('cancel'); return true; }, ESC.modal);
    (document.getElementById('close-save') as HTMLButtonElement).onclick = function(){ done('save'); };
    (document.getElementById('close-discard') as HTMLButtonElement).onclick = function(){ done('discard'); };
    (document.getElementById('close-cancel') as HTMLButtonElement).onclick = function(){ done('cancel'); };
    modal.hidden = false;
    (document.getElementById('close-save') as HTMLButtonElement).focus();
  });
}
