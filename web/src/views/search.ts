// views/search.ts — SEARCH view (⌃2): decompiled-text search over the target's
// background index — groups, rail context excerpt, F4/⇧F4 cycling, stats +
// index progress + REBUILD.
import { $all, S, escHtml as esc, tryOpT } from '../ui/hub.js';
import { updateCenter, jumpToCode, showCenter, activeTarget, pushPaneEscape } from '../ui/chrome.js';
import { loadSymbol } from './code.js';
import type { SearchContextLine, SearchIndexInfo, SearchHit } from '../types.js';

/* one row of the flattened hit list (F4 cycling): the group's symbol/addr/
   target, and the hit itself */
interface FlatHit { group: string; addr?: string; gtarget?: string; hit: SearchHit }

let srchSel = -1;
let built = false;
let QUERY = '';
let flatHits: FlatHit[] = [];   // [{group, addr, hit}] for F4 cycling
let indexTimer = 0;

function markQuery(text: string){
  if (!QUERY) return esc(text);
  const reOn = document.getElementById('sopt-regex')?.classList.contains('on');
  const csOn = document.getElementById('sopt-case')?.classList.contains('on');
  let re: RegExp;
  try {
    re = new RegExp(reOn ? QUERY : QUERY.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), csOn ? 'g' : 'gi');
  } catch { return esc(text); }
  /* match on the RAW text, escape the pieces: the old replace ran the query
     over the escaped text, so a query with `"` / `&` / `<` never lit and one
     that matched inside an entity (`amp`, `quot`) split it (fix-review r1 #3) */
  let out = '', last = 0, m: RegExpExecArray | null;
  while ((m = re.exec(text))){
    if (m[0] === ''){ re.lastIndex++; continue; }        // an empty match must not spin
    out += esc(text.slice(last, m.index)) + '<span class="mk">' + esc(m[0]) + '</span>';
    last = m.index + m[0].length;
  }
  return out + esc(text.slice(last));
}

function srchHits(){ return $all('.shit').filter(r => r.offsetParent !== null); }   // a collapsed group hides its hits too (fix-review r1 LAT-3)

function selectHit(i: number, scroll: boolean, focus = true){
  const hits = srchHits();
  if (!hits.length) return;
  srchSel = ((i % hits.length) + hits.length) % hits.length;
  $all('.shit').forEach(r => r.classList.remove('sel'));
  const el = hits[srchSel];
  el.classList.add('sel');
  const openBtn = document.getElementById('srch-open') as HTMLButtonElement | null;
  if (openBtn) openBtn.disabled = false;
  if (scroll) el.scrollIntoView({ block: 'nearest' });
  /* F4/Enter/Esc live on the wrap — but only an explicit gesture (row click,
     F4) may move focus there: the auto-select at the end of every debounced
     runSearch stole the caret out of #sq 250 ms into a typing pause, and the
     next keystrokes fell through to the global shortcuts (review 2026-09-09
     HIGH-1). Keys typed in #sq still reach the wrap's handler by bubbling. */
  if (focus) document.getElementById('searchwrap')!.focus({ preventScroll: true });
  const f = flatHits[+el.getAttribute('data-f')!];
  const ctx = document.getElementById('srch-ctx')!;
  ctx.innerHTML = '';
  const lines: SearchContextLine[] = (f.hit.context || [{ n: f.hit.line, t: f.hit.preview, hit: true }]);
  lines.forEach(function(ln){
    const r = document.createElement('div');
    r.className = 'cx';
    r.innerHTML = '<span class="cxn">' + esc(ln.n ?? '·') + '</span>' +
      '<span class="' + (ln.hit ? 'mkline' : '') + '">' + markQuery(ln.t ?? '') + '</span>';
    ctx.appendChild(r);
  });
}

async function openHit(){
  if (srchSel < 0) return;
  const el = srchHits()[srchSel];
  if (!el) return;
  const f = flatHits[+el.getAttribute('data-f')!];
  let addr = f.addr || (f.hit && f.hit.addr);
  if (!addr && f.hit && f.hit.kind === 'strings' && f.hit.stringId != null){
    const xr = await tryOpT('string.xrefs', { id: String(f.hit.stringId) });
    const rows = xr.data || [];
    for (const x of rows){
      if (x && typeof x === 'object'){
        const sym = x.method || x.addr || (x.from ? String(x.from).split('+')[0] : '');
        if (sym){ addr = String(sym); break; }
      }
    }
  }
  if (!addr) return;
  const line = f.hit && Number.isFinite(+f.hit.line!) ? +f.hit.line! : 0;
  /* the hit's target is the backend's label; resolve it against the state
     snapshot (exact id or name — never a substring, P0.1) to the ID jumpToCode
     takes. A hit on the viewed target loads in place — no backend target.switch,
     tree rebuild or display restore for nothing (D11/F3). */
  const cur = activeTarget();
  const ts = (S.state && S.state.targets) || [];
  const hitT = f.gtarget ? ts.find(function(t){ return t.id === f.gtarget || t.name === f.gtarget; }) : null;
  try {
    if (f.gtarget && (!hitT || !cur || hitT.id !== cur.id)){
      // ALL-scope hit on another target: full jump path (M3), then the line
      const ok = hitT ? await jumpToCode(hitT.id, addr) : false;
      /* stale target id — surface as a transient note in the results list.
         Writing #sstats directly would wipe the result counts and the REBUILD
         affordance with no restore (map-r8 #5) */
      if (ok === false){
        const host = document.getElementById('sresults');
        if (host){
          const n = document.createElement('div');
          n.style.color = '#e07060';
          n.style.padding = '4px 8px';
          n.textContent = 'jump failed: target not open — ' + f.gtarget;
          host.insertBefore(n, host.firstChild);
          setTimeout(function(){ n.remove(); }, 5000);
        }
        return;
      }
    } else {
      showCenter('code');
      try {
        await loadSymbol(addr);
      } catch (e) {
        const cls = String(addr).split('->')[0];
        if (cls !== String(addr)) await loadSymbol(cls);
        else throw e;
      }
    }
    if (line > 0 && S.hooks.gotoLine) S.hooks.gotoLine(line);
  } catch (e) { /* unresolvable hit (stale index) — silent no-op per M6 bar */ }
}

function kindCsv(){
  const on: (string | null)[] = [];
  $all('.vtoolbar [data-ct]').forEach(function(c){
    if (c.classList.contains('on')) on.push(c.getAttribute('data-ct'));
  });
  return on.join(',');
}

/* the engine's result caps (JebEngine.search: hitCap, and groupCap/perGroup derived from
   it) — mirrored here so a capped answer says so instead of posing as the total (UI audit
   2026-09-09). CAP is the hit cap the query is sent with: 200 by default, raised from the
   stats line (audit decision 6: a raise-cap control, not pagination — one bigger scan). */
let CAP = 200;
const CAP_STEPS = [200, 1000, 5000];
function caps(){ return { hits: CAP, groups: Math.max(50, Math.floor(CAP / 4)), perGroup: Math.max(20, Math.floor(CAP / 10)) }; }

async function updateStats(ms: number, nGroups: number, nHits: number, opts?: { truncated?: boolean; perGroup?: boolean; noQuery?: boolean }){
  const idx = await tryOpT('search.index', {});
  const d: SearchIndexInfo = idx.data || {};
  const st = document.getElementById('sstats')!;
  const c = caps();
  const raise = CAP_STEPS.filter(n => n > CAP).map(n => '<span class="rblink scap" data-cap="' + n + '">' + n + '</span>').join(' · ');
  const reset = CAP > CAP_STEPS[0] ? ' · <span class="rblink scap" data-cap="' + CAP_STEPS[0] + '">back to ' + CAP_STEPS[0] + '</span>' : '';
  let line = opts && opts.noQuery ? 'type a query'
    : (opts && opts.truncated
        ? 'showing ' + nHits.toLocaleString('en-US') + ' hits in ' + nGroups + ' classes · capped at ' + c.hits.toLocaleString('en-US') + ' / ' + c.groups + ' · narrow the query'
          + (raise ? ' · raise the cap to ' + raise : '') + reset
        : nHits + ' results in ' + nGroups + ' classes' + (opts && opts.perGroup ? ' · some classes show ' + c.perGroup + ' of more' : '')
          + (CAP > CAP_STEPS[0] ? ' · cap ' + CAP + reset : '')) + ' · ' + ms + ' ms';
  if (d.state === 'building') {
    line += ' · indexing… ' + (d.built || 0).toLocaleString('en-US') + '/' + (d.total || 0).toLocaleString('en-US') + ' classes';
    scheduleIndexPoll();
  } else if (d.state === 'built') {
    const age = d.ageMs != null ? Math.round(d.ageMs / 1000) + 's ago' : '';
    line += ' · index: ' + (d.built || 0).toLocaleString('en-US') + ' classes ' + age +
      ' · <span class="rblink" id="sreindex">REBUILD</span>';
  } else if (d.state) {
    line += ' · index: ' + d.state + ' · <span class="rblink" id="sreindex">REBUILD</span>';
  }
  if (d.partialCoverage) {
    line += ' · PARTIAL text coverage: ' + (d.indexedClasses || 0).toLocaleString('en-US') + '/' + (d.total || 0).toLocaleString('en-US')
      + ' classes · ' + (d.pendingReindex || 0) + ' pending · zero matches do not establish absence';
    if (d.pendingReindex) scheduleIndexPoll();
  }
  st.innerHTML = line;
  st.querySelectorAll<HTMLElement>('.scap').forEach(function(el){
    el.addEventListener('click', function(){ CAP = parseInt(el.dataset.cap || '200', 10) || 200; runSearch(); });
  });
  const rb = document.getElementById('sreindex');
  if (rb) rb.addEventListener('click', async function(){
    st.textContent = 'reindexing…';
    await tryOpT('search.reindex', {});
    scheduleIndexPoll();
  });
}

function scheduleIndexPoll(){
  if (indexTimer) return;
  indexTimer = setInterval(async function(){
    const idx = await tryOpT('search.index', {});
    const d: SearchIndexInfo = idx.data || {};
    if (d.state !== 'building') {
      clearInterval(indexTimer);
      indexTimer = 0;
      if ((document.getElementById('sq') as HTMLInputElement).value.trim()) runSearch();
    } else {
      const st = document.getElementById('sstats')!;
      const cur = st.textContent!.split('· index')[0];
      st.textContent = cur + ' · indexing… ' + (d.built || 0).toLocaleString('en-US') +
        '/' + (d.total || 0).toLocaleString('en-US') + ' classes';
    }
  }, 2000);
}

async function runSearch(){
  QUERY = (document.getElementById('sq') as HTMLInputElement).value.trim();
  const t0 = performance.now();
  const openBtn = document.getElementById('srch-open') as HTMLButtonElement | null;
  if (openBtn) openBtn.disabled = true;                       // nothing selected yet
  /* the states the view was silent about (UI audit 2026-09-09): no target,
     no kinds, no results — each says what would change the answer */
  if (!S.currentTid){
    document.getElementById('sresults')!.innerHTML = '<div class="sgh">no target open · open a target (＋ on the target strip, ⌘O) to search its code</div>';
    document.getElementById('sstats')!.textContent = 'no target';
    return;
  }
  if (!QUERY){
    document.getElementById('sresults')!.innerHTML = '';
    await updateStats(0, 0, 0, { noQuery: true });            // the index line shows before the first query
    return;
  }
  if (!kindCsv()){
    document.getElementById('sresults')!.innerHTML = '<div class="sgh">no kinds selected — turn CODE, STRINGS or COMMENTS back on</div>';
    document.getElementById('sstats')!.textContent = 'no kinds selected';
    return;
  }
  const r = await tryOpT('search', {
    query: QUERY,
    kind: kindCsv(),
    regex: document.getElementById('sopt-regex')?.classList.contains('on') || false,
    case: document.getElementById('sopt-case')?.classList.contains('on') || false,
    allTargets: S.srchScope === 'all',          // QA M4: the chip actually scopes the query
    limit: CAP,                                 // the hit cap (200 default; raised from the stats line)
  });
  const ms = Math.round(performance.now() - t0);
  const host = document.getElementById('sresults')!;
  host.innerHTML = '';
  flatHits = [];
  srchSel = -1;
  if (r.error){
    host.innerHTML = '<div class="sgh">' + esc(r.error.message) + '</div>';
    document.getElementById('sstats')!.textContent = 'search failed';
    return;
  }
  let nHits = 0;
  (r.data || []).forEach(function(g, gi){
    const grp = document.createElement('div');
    grp.className = 'sgroup';
    grp.innerHTML = '<div class="sgh" data-t><span class="caret">▾</span><span>' + esc(g.symbol) + '</span>' +
      '<span class="eng">' + esc(g.target || '') + ' · ' + esc(g.engine || '') + ' · ' + (g.hits || []).length + ((g.hits || []).length >= caps().perGroup ? '+' : '') + '</span></div>' +
      '<div class="sg-kids"></div>';
    const kids = grp.querySelector('.sg-kids')!;
    (g.hits || []).forEach(function(h){
      nHits++;
      const row = document.createElement('div');
      row.className = 'shit';
      row.setAttribute('data-ct', h.kind || 'code');
      row.setAttribute('data-f', String(flatHits.length));
      row.innerHTML = '<span class="sln">' + esc(h.line || '·') + '</span>' +
        '<span class="spv">' + markQuery(h.preview || '') + '</span>';
      row.addEventListener('dblclick', openHit);
      flatHits.push({ group: g.symbol, addr: g.addr, gtarget: g.target, hit: h });
      kids.appendChild(row);
    });
    host.appendChild(grp);
  });
  const groups = r.data || [];
  if (!nHits){
    host.innerHTML = '<div class="sgh">no results for “' + esc(QUERY) + '” · ' + (S.srchScope === 'all' ? 'all targets' : 'this target')
      + ' · kinds: ' + esc(kindCsv()) + (document.getElementById('sopt-regex')?.classList.contains('on') ? ' · regex' : '')
      + (document.getElementById('sopt-case')?.classList.contains('on') ? ' · case-sensitive' : '') + '</div>';
  }
  /* the total caps and the per-class cap are different sentences (fix-review r1 M7) */
  const truncated = nHits >= caps().hits || groups.length >= caps().groups;
  const perGroup = !truncated && groups.some(function(g){ return (g.hits || []).length >= caps().perGroup; });
  await updateStats(ms, groups.length, nHits, { truncated: truncated, perGroup: perGroup });
  if (flatHits.length) selectHit(0, false, false);
}

function initSearch(){
  document.getElementById('sresults')!.addEventListener('click', function(e){
    const gh = (e.target as HTMLElement).closest('.sgh');
    if (gh){
      const kids = gh.parentElement!.querySelector<HTMLElement>('.sg-kids')!;
      const caret = gh.querySelector('.caret')!;
      kids.style.display = kids.style.display === 'none' ? '' : 'none';
      caret.classList.toggle('cl', kids.style.display === 'none');
      return;
    }
    const hit = (e.target as HTMLElement).closest<HTMLElement>('.shit');
    if (hit) selectHit(srchHits().indexOf(hit), false);
  });
  document.getElementById('srch-open')!.addEventListener('click', openHit);

  $all('[data-ss]').forEach(function(c){
    c.addEventListener('click', function(){
      S.srchScope = c.getAttribute('data-ss') === 'all' ? 'all' : 'target';
      $all('[data-ss]').forEach(function(x){ x.classList.toggle('on', x === c); });
      updateCenter();
      runSearch();                                  // QA M4: re-query under the new scope
    });
  });
  $all('.vtoolbar [data-ct]').forEach(function(c){
    c.addEventListener('click', function(){
      c.classList.toggle('on');
      runSearch();
    });
  });
  $all('[data-sopt]').forEach(function(c){
    c.addEventListener('click', function(){
      c.classList.toggle('on');
      runSearch();
    });
  });
  let deb = 0;
  document.getElementById('sq')!.addEventListener('input', function(){
    clearTimeout(deb);
    deb = setTimeout(runSearch, 250);
  });
  document.getElementById('searchwrap')!.addEventListener('keydown', function(e){
    if (e.key === 'F4' && !e.shiftKey){ e.preventDefault(); selectHit(srchSel + 1, true); }
    else if (e.key === 'F4' && e.shiftKey){ e.preventDefault(); selectHit(srchSel - 1, true); }
    else if (e.key === 'Enter'){ e.preventDefault(); openHit(); }
    /* Escape: the central dispatcher owns it (pushPaneEscape below) — a query with
       no hits never focused this wrap, which left Esc dead here too. */
  });
  pushPaneEscape('search');
}

export async function initSearchView(): Promise<void>{
  if (!built){
    built = true;
    S.srchScope = 'target';
    initSearch();
  }
  await runSearch();
}

/* called on target switch so the view re-queries the new target */
export async function reloadSearch(): Promise<void>{
  if (!built) return;
  await runSearch();
}
