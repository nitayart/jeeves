// views/strings.ts — STRINGS view (⌃3), live paged table from the strings op
// (two-layer classified rows), rail detail via string.xrefs, jump-to-first-use,
// double-click → the string's use (one xref jumps, several → the rail chooser).
// segU: the empty table says "populating…" (or why it is empty); every jump
// out of here shows the jump card ("decompiling <X>" + clock) while it loads —
// loadSymbol's own card since segV (code.js), nothing wrapped here.
// segX: the rail's XREFS section says "loading xrefs…" (+ clock) for the
// seconds one string.xrefs takes on a big target, and a jump that has to wait
// on that fetch wears the jump card's 'xrefs' phase — no dead clicks (user
// report 2026-09-09, below at loadXrefs / waitXrefs).
import { $all, S, escHtml as esc, tryOpT } from '../ui/hub.js';
import { jumpToCode, showCenter, pushPaneEscape, activatePanel } from '../ui/chrome.js';
import { loadSymbol, gotoLine } from './code.js';
import { beginJump } from '../ui/jumpstatus.js';
import type { JumpHandle } from '../ui/jumpstatus.js';
import type { OpParams, StringRow, StringType, XrefResult, XrefRow, XrefRowObj } from '../types.js';

const TYPES = ['url', 'crypto', 'intent', 'permission', 'content-uri', 'path', 'base64', 'hex', 'plain'];
const PAGE = 200;
let sortMode = 'priority';   // audit decision 5: classifier priority first; the toolbar select changes it

let rows: StringRow[] = [];
let total = 0, truncated = false;
let loading = false;
let loadSeq = 0;              // QA M9: only the latest reload may apply
let selSeq = 0;               // only the latest select renders its xrefs (a double-click selects twice)
let typeOn: Record<string, boolean> = Object.fromEntries(TYPES.map(t => [t, true]));
let unreachOnly = false;
let strSel = -1;
let wired = false;
/* segU (user report 2026-09-08: "it takes a while to get the list populated —
   a text should say so"): the table's own state while it is EMPTY. Blank used
   to mean four different things — populating, failed, no target, no match.
   ONE element inside the table (renderState, the runs feed's .hitempty idiom);
   rows present → nothing there, and a rebuild over stale rows only notes
   itself on the count line: the old rows stay until the new ones land. */
let stateMode = '';            // '' rows showing | loading | error | notarget | empty
let stateText = '';            // the message for `error`
let waitT0 = 0, waitTick = 0;  // the populating clock: text + elapsed, no bar (nothing to measure against)
let countNote = '', countErr = false;   // count-line aside: 'refreshing…' | a failed page's error
let rowsTid: string | null = null;      // the target the rows belong to (S.currentTid when they landed)
/* segX (user report 2026-09-09: "when clicking on a string line, it seems to
   take a long time to populate the xref panel … when clicking on the string
   when the xrefs have not yet been populated, nothing happens"): the rail's
   XREFS section has a state element of its own (renderXrState, the table's
   renderState idiom — one .xrstate inside #strd-xrefs) and a clock of its
   own: the two run at once (page 2 populating while a string's xrefs load),
   so neither borrows the other's. The inspected string is held as the OBJECT,
   not a table index — a filter change reminds the rows, and strSel used to
   keep pointing at whatever row took the index (↵ jumped from the wrong
   string); the index is re-found by id when rows land. */
let insp: StringRow | null = null;      // the string the rail shows
let xrMode = '';               // '' rows showing | loading | error | empty
let xrText = '';               // the message for `error`
let xrRowsId: string | null = null;     // the string id whose rows the list holds (null: none)
let xrT0 = 0, xrTick = 0;      // the loading clock: the fetch's own start (fetchXrefs), ticking 1/s
let xrCard: JumpHandle | null = null;   // the ONE strings-owned jump card (the 'xrefs' phase); a newer gesture drops it

function chipsHtml(s: StringRow): string {
  const types: StringType[] = s.types || [{ name: s.type || 'plain', confidence: 'lexical', why: s.why || '' }];
  const allLex = types.every(t => t.confidence !== 'usage');
  return types.map(t =>
    '<span class="styp ' + esc(t.name) + (allLex && t.name !== 'plain' ? ' lex' : '') +
    '" title="' + esc(t.why || '') + '">' + esc(t.name) + (allLex && t.name !== 'plain' ? '?' : '') + '</span>'
  ).join(' ');
}

function renderRows(){
  const tbl = document.getElementById('str-table')!;
  tbl.innerHTML = '';
  rows.forEach(function(s, i){
    const r = document.createElement('div');
    const nx = s.xrefCount ?? s.xrefs ?? 0;
    /* jmp: a double-click on this row lands somewhere — hand, per the CURSOR
       CONTRACT (hand = navigates); a zero-xref row has nowhere to go: arrow.
       sel: a page append rebuilds every row — the selection used to vanish
       from the table while the rail still showed the string (segX) */
    r.className = 'srow' + (nx > 0 ? ' jmp' : '') + (i === strSel ? ' sel' : '');
    r.setAttribute('data-i', String(i));
    r.innerHTML = '<span class="sval" title="' + esc(s.value) + '">' + esc(s.value) + '</span>' +
      '<span class="styp-cell">' + chipsHtml(s) + '</span>' +
      '<span class="sref num">' + nx + '</span>' +
      '<span class="swho" title="' + esc(s.firstReferrer) + '">' + esc(s.firstReferrer) + '</span>';
    r.addEventListener('click', function(){ selectString(i); });
    tbl.appendChild(r);
  });
  renderCount();
  renderState();                // the innerHTML wipe above took the state element with it
}

/* the count line, plus its aside: a rebuild's 'refreshing…' over stale rows,
   or a failed page's error in the vermillion voice. Blank while the table is
   populating / failed / target-less — "0 strings · 0 shown" next to
   "populating…" would contradict it; the 'empty' arm keeps its honest zero */
function renderCount(){
  const c = document.getElementById('str-count')!;
  const order = sortMode === 'xrefs' ? 'by xrefs' : sortMode === 'order' ? 'pool order' : 'by priority: crypto › url › intent › permission › content-uri › path › base64 › hex › plain, confirmed before guessed, ties by xrefs';
  c.textContent = (!rows.length && stateMode && stateMode !== 'empty') ? '' :
    (truncated
      ? 'showing ' + rows.length.toLocaleString('en-US') + ' of ' + total.toLocaleString('en-US') + ' strings · scroll for more'
      : total.toLocaleString('en-US') + ' strings') + ' · ' + order;
  if (countNote){
    const n = document.createElement('span');
    n.className = countErr ? 'err' : 'note';
    n.textContent = (c.textContent ? ' · ' : '') + countNote;   // server text via textContent, never innerHTML
    c.appendChild(n);
  }
}

function setCountNote(text: string, isErr?: boolean){
  countNote = text || '';
  countErr = !!isErr;
  renderCount();
}

function setState(mode: string, text?: string){
  stateMode = mode || '';
  stateText = text || '';
  renderCount();                // the line blanks/returns with the mode
  renderState();
}

/* what is narrowing the list — the 'empty' arm names it, so "no strings
   match" never reads as "this target has no strings" */
function narrowing(){
  const parts = [];
  const q = (document.getElementById('str-filter') as HTMLInputElement).value.trim();
  if (q) parts.push('filter "' + q + '"');
  const off = TYPES.filter(function(t){ return !typeOn[t]; });
  if (off.length === TYPES.length) parts.push('every type chip off');
  else if (off.length) parts.push(off.length + ' type chip' + (off.length === 1 ? '' : 's') + ' off');
  if (unreachOnly) parts.push('unreachable only');
  return parts.join(' · ');
}

/* the ONE state element inside the table — created on demand, gone the
   moment rows show. Server text (the error) lands via textContent. The
   populating arm ticks its elapsed once a second (the open-status card's
   clock: whole seconds) and the clock stops the moment the mode changes;
   the tick re-finds its span because renderRows rebuilds the element. */
function renderState(){
  const tbl = document.getElementById('str-table')!;
  let el = tbl.querySelector('.strstate');
  if (rows.length || !stateMode){
    if (el) el.remove();
    stopWait();
    return;
  }
  if (!el){
    el = document.createElement('div');
    tbl.appendChild(el);
  }
  el.className = 'hitempty strstate ' + stateMode;
  el.innerHTML = '';
  const b = document.createElement('b');
  const s = document.createElement('span');
  if (stateMode === 'loading'){
    const led = document.createElement('span');       // the open-status card's pulsing orange LED: in flight
    led.className = 'led o';
    b.appendChild(led);
    b.appendChild(document.createTextNode('populating strings…'));
    s.className = 't';
    if (!waitT0) waitT0 = Date.now();
    s.textContent = waitText(waitT0);
    if (!waitTick) waitTick = setInterval(function(){
      const t = document.querySelector('#str-table .strstate.loading .t');
      if (t) t.textContent = waitText(waitT0); else stopWait();
    }, 1000);
  } else {
    stopWait();
    if (stateMode === 'error'){
      b.textContent = 'strings failed';
      s.textContent = stateText;
    } else if (stateMode === 'notarget'){
      b.textContent = 'no target open';
      s.textContent = 'open a target to list its strings';
    } else {
      const why = narrowing();
      b.textContent = why ? 'no strings match' : 'no strings';
      s.textContent = why;
    }
  }
  el.appendChild(b);
  el.appendChild(s);
}

/* "3s" once a whole second has passed since t0 — the loading line alone
   before that (the table's populating clock and the rail's xrefs clock) */
function waitText(t0: number){
  const sec = Math.max(0, Math.round((Date.now() - t0) / 1000));
  return sec < 1 ? '' : sec < 60 ? sec + 's' : Math.floor(sec / 60) + 'm ' + String(sec % 60).padStart(2, '0') + 's';
}

function stopWait(){
  if (waitTick) clearInterval(waitTick);
  waitTick = 0;
  waitT0 = 0;
}

async function loadMore(reset: boolean){
  /* QA M9: rapid chip toggles fire overlapping resets. Each reset bumps the
     sequence; a dropped/stale call never applies, and the in-flight call
     re-runs once at the end so the table always converges to the chip state. */
  const my = reset ? ++loadSeq : loadSeq;
  if (loading) return;
  loading = true;
  /* segU: an EMPTY table is populating (first page, target switch, a rebuild
     after an empty result) — said at once, nothing is there to flash over;
     a rebuild over stale rows only says so on the count line */
  if (!rows.length) setState('loading');
  else if (reset) setCountNote('refreshing…');
  const q = (document.getElementById('str-filter') as HTMLInputElement).value.trim();
  const offTypes = TYPES.filter(t => !typeOn[t]);
  const params: OpParams = {
    filter: q,
    unreachable: unreachOnly,
    offset: reset ? 0 : rows.length,
    count: PAGE,
    sort: sortMode,
  };
  if (offTypes.length && offTypes.length < TYPES.length)
    params.type = TYPES.filter(t => typeOn[t]).join(',');
  else if (!offTypes.length) { /* all on → no type param */ }
  else {
    if (my === loadSeq){ rows = []; total = 0; truncated = false; countNote = ''; stateMode = 'empty'; stateText = ''; strSel = -1; renderRows(); }
    loading = false;
    if (loadSeq !== my) loadMore(true);
    return;
  }
  const r = await tryOpT('strings', params);   // the VIEWED target's strings (fix-review r1 #7)
  loading = false;
  if (r.error){
    const msg = r.error.message || 'strings failed';
    if (/not open|no active target/.test(msg)){
      /* target-less project: the honest empty state, not a warn storm */
      if (my === loadSeq){ rows = []; total = 0; truncated = false; countNote = ''; stateMode = 'notarget'; stateText = ''; strSel = -1; renderRows(); }
    } else {
      /* segU: a failure is TEXT — in the table while it is empty, on the count
         line over stale rows (it used to be a console.warn and a blank) */
      console.warn('strings', msg);
      if (my === loadSeq){
        if (rows.length) setCountNote(msg, true); else setState('error', msg);
      }
    }
    if (loadSeq !== my) loadMore(true);
    return;
  }
  if (my === loadSeq){
    const d = r.data;
    if (reset) rows = [];
    rows = rows.concat(d.rows || []);
    total = d.total ?? rows.length;
    truncated = !!d.truncated;
    rowsTid = S.currentTid || null;
    countNote = '';
    stateMode = rows.length ? '' : 'empty'; stateText = '';   // rows landed → the state element goes
    /* a reset landed NEW row objects: the inspected string's table index is
       re-found by id (−1 when the narrowing dropped it — the rail keeps
       showing it either way, ↵ still jumps from it) */
    if (reset) strSel = insp ? rows.findIndex(function(s){ return s.id === insp!.id; }) : -1;
    renderRows();
  }
  if (loadSeq !== my) loadMore(true);       // a toggle landed mid-flight → converge
}

/* one string.xrefs fetch per string at a time, recorded on the row: the two
   clicks of a double-click both select (two fetches, two renders — the rail
   used to list every xref twice), and the double-click itself needs the same
   rows. A fetch in flight is shared (_xrefLoad, with its start time — the
   rail's clock reads the fetch's real age, not the latest click's); a settled
   one is kept as the row's _xrefRes ({rows} | {error}) — the rail's last
   known state for ↵, NOT a cache: a select always fetches again, and every
   reload / target switch remints the row objects (see waitXrefs for why no
   session cache sits here). Never rejects (tryOp). segX: a failure used to
   settle as an EMPTY list — the rail showed "nothing", silently. */
function fetchXrefs(s: StringRow): Promise<XrefResult>{
  if (!s._xrefLoad){
    s._xrefT0 = Date.now();
    s._xrefLoad = tryOpT('string.xrefs', { id: s.id }).then(function(r){
      s._xrefLoad = null;
      s._xrefRes = r.error ? { error: r.error.message || 'xrefs failed' }
                           : { rows: r.data || s.xrefList || [] };
      return s._xrefRes;
    });
  }
  return s._xrefLoad;
}

/* the ONE state element inside the rail's XREFS list (segX) — the table's
   renderState idiom, the same voices: loading = pulsing LED + "loading
   xrefs…" (+ the elapsed tick), or "refreshing xrefs…" over rows kept from
   the SAME string (they sit under it dimmed, #strd-xrefs.stale); error =
   the vermillion voice, server text via textContent; empty = the hint voice;
   '' = rows showing, the element is gone. First child, so a note row or the
   rows follow it. The tick re-finds its span (paintXrefs wipes the list). */
function setXrState(mode: string, text?: string){
  xrMode = mode || '';
  xrText = text || '';
  renderXrState();
}

function renderXrState(){
  const xr = document.getElementById('strd-xrefs')!;
  let el = xr.querySelector('.xrstate');
  if (!xrMode){
    if (el) el.remove();
    stopXrWait();
    return;
  }
  if (!el){
    el = document.createElement('div');
    xr.insertBefore(el, xr.firstChild);
  }
  el.className = 'hitempty xrstate ' + xrMode;
  el.innerHTML = '';
  const b = document.createElement('b');
  const s = document.createElement('span');
  if (xrMode === 'loading'){
    const led = document.createElement('span');       // the open-status card's pulsing orange LED: in flight
    led.className = 'led o';
    b.appendChild(led);
    b.appendChild(document.createTextNode((xrRowsId ? 'refreshing' : 'loading') + ' xrefs…'));
    s.className = 't';
    s.textContent = waitText(xrT0);
    if (!xrTick) xrTick = setInterval(function(){
      const t = document.querySelector('#strd-xrefs .xrstate.loading .t');
      if (t) t.textContent = waitText(xrT0); else stopXrWait();
    }, 1000);
  } else {
    stopXrWait();
    if (xrMode === 'error'){
      b.textContent = 'xrefs failed';
      s.textContent = xrText;
    } else {
      /* the honest zero: blank used to be this AND "still loading" (the report) */
      b.textContent = 'no xrefs';
      s.textContent = 'no referrer in this target — nothing to jump to';
    }
  }
  el.appendChild(b);
  el.appendChild(s);
}

function stopXrWait(){
  if (xrTick) clearInterval(xrTick);
  xrTick = 0;
  xrT0 = 0;
}

/* the rail's XREFS section for `s` — the ONE painter: the loading state at
   once (the report: the panel sat silent for the seconds the fetch takes),
   rows / error / empty when the fetch lands, unless a newer select owns the
   rail by then (selSeq: the segS rule — a stale fetch never paints). Returns
   the shared fetch so a jump can wait on the same promise (its reaction is
   attached after this one: the rows are painted before the jump reads them).
   The stale-content rule: ANOTHER string's rows must not sit under the new
   head looking current — cleared first; the SAME string's rows (a re-click,
   the double-click's two clicks on a loaded row) stay, dimmed, until the
   fresh ones land — the table's own rule for a rebuild over rows. */
function loadXrefs(s: StringRow, my: number): Promise<XrefResult>{
  const xr = document.getElementById('strd-xrefs')!;
  if (xrRowsId !== s.id){ xr.innerHTML = ''; xrRowsId = null; }
  xr.classList.toggle('stale', xrRowsId === s.id);
  const p = fetchXrefs(s);
  xrT0 = s._xrefT0!;                            // the clock is the fetch's: a second click on the row does not reset "3s"
  setXrState('loading');
  p.then(function(r){
    if (my !== selSeq) return;                  // a later select owns the rail (loadMore's loadSeq idiom)
    paintXrefs(s, r);
  });
  return p;
}

function paintXrefs(s: StringRow, r: XrefResult){
  const xr = document.getElementById('strd-xrefs')!;
  xr.classList.remove('stale');
  if (r.error){
    /* the failure is TEXT in the section (the segU rule); rows kept from a
       refresh stay under it — the last known list, the line says the refresh
       failed — and the counts stay the strings pass's: nothing new was read */
    setXrState('error', r.error);
    return;
  }
  xr.innerHTML = '';
  const list = r.rows!;
  list.forEach(function(x){
    const row = document.createElement('div');
    row.className = 'xrow';
    const label = typeof x === 'string' ? x
      : (x.method ? (x.owner ? x.owner + '.' : '') + (x.name || x.method) : (x.site || x.from || '?'));
    row.innerHTML = '<span class="led b"></span><span>' + esc(label) + '</span>';
    /* seg1 nav-jumps bug 2: rows were labels only — JUMP TO FIRST USE was the
       sole way out, and it discards every referrer but the first. The landing
       is landOnXref (one code path with the table's double-click). Mock rows
       are bare site strings — nothing to load. Inline pointer is the rail's
       clickable-row cue (.xrow is arrow by default — cursor discipline,
       gui-audit D2) */
    const sym = xrefSym(x);
    if (sym){
      row.style.cursor = 'pointer';
      row.title = String(sym);
      row.addEventListener('click', function(){ landOnXref(x, sym); });
    }
    xr.appendChild(row);
  });
  xrRowsId = s.id;
  setXrState(list.length ? '' : 'empty');
  syncXrefCount(s, list.length);
}

/* the counts follow the rows (the report's third ask: the count line and the
   xref-count cell must not contradict the panel): the head's "N xrefs" and
   the table's cell came from the strings pass — a fixture (or a pass older
   than the code it counted) can disagree with what the panel now lists.
   Written onto the row object (renderRows reads xrefCount first), into the
   cell in place (no rebuild: a rebuild would drop the scroll position), and
   the .jmp hand follows: a row whose only referrer is gone has nowhere to go. */
function syncXrefCount(s: StringRow, n: number){
  s.xrefCount = n;
  const i = rows.indexOf(s);
  const row = i >= 0 ? document.querySelector('#str-table .srow[data-i="' + i + '"]') : null;
  if (row){
    row.querySelector('.sref')!.textContent = String(n);
    row.classList.toggle('jmp', n > 0);
  }
  if (insp === s) paintHead(s);
}

/* the rail's STRING head: value + type chips + the xref count as known now */
function paintHead(s: StringRow){
  document.getElementById('strd-val')!.textContent = s.value;
  document.getElementById('strd-chips')!.innerHTML = chipsHtml(s) +
    '<span class="pmeta">' + (s.xrefCount ?? s.xrefs ?? 0) + ' xrefs</span>';
}

/* the strings-owned jump card goes the moment a newer gesture happens — not
   seconds later when the disowned fetch lands with "reading xrefs of <old>…"
   still up (the corner is the newest gesture's, jumpstatus.js) */
function dropCard(){
  if (xrCard) xrCard.dismiss();
  xrCard = null;
}

/* the referrer a rail row can load: the JEB xrefRow's 'method' (the
   +offset-stripped site), else addr, else the 'from' site minus its +offset.
   null for a mock row (a bare site string) — nothing to load. */
function xrefSym(x: XrefRow | null | undefined): string | null {
  if (!x || typeof x === 'string') return null;
  return x.method || x.addr || (x.from ? String(x.from).split('+')[0] : null);
}

/* the xref landing (seg1 nav-jumps bug 1 fix): the referrer's method loads and
   lands on that method; an unresolvable member falls back to its owner class;
   a row that carries a line lands on it. Shared by the rail's xref-row click
   and the table's double-click when the string has exactly one referrer. */
async function landOnXref(x: XrefRow, sym: string){
  showCenter('code');                           // the code view is hidden behind this center view
  /* the jump card ("decompiling <X>" + clock, jumpstatus.js) is loadSymbol's
     own since segV ("The jump progress should be on every xref jump, not just
     every string jump") — segU began it here; a wrap on top of code.js's
     would be two cards for one gesture. Plain loads again: a refused member
     throws AFTER its card painted the error, and the owner fallback's
     loadSymbol supersedes that card before a frame paints (newest gesture
     owns the corner); a member with no owner, or an owner that also fails,
     leaves the vermillion line up — the row is still there to retry. */
  try {
    await loadSymbol(String(sym));
  } catch (e) {
    const cls = String(sym).split('->')[0];
    if (cls === String(sym)) return;            // no owner to fall back to — stay; the card said why
    try { await loadSymbol(cls); } catch (e2) { return; /* unresolvable — stay; the card said why */ }
  }
  const line = Number.isFinite(+(x as XrefRowObj).line!) ? +(x as XrefRowObj).line! : 0;
  if (line > 0) gotoLine(line);
}

function selectString(i: number){
  strSel = i;
  const my = ++selSeq;
  const s = rows[i];
  insp = s;
  (document.getElementById('strd-jump') as HTMLButtonElement).disabled = false;
  dropCard();
  $all('#str-table .srow').forEach(function(r){ r.classList.toggle('sel', +r.getAttribute('data-i')! === i); });
  paintHead(s);
  return loadXrefs(s, my);
}

/* the chooser reveal: #str-insp is un-hidden whenever the strings view owns
   the center (chrome.js updateCenter) and its rows already jump — what is left
   is making sure the eye lands on the list: the INSPECTOR comes forward when
   it was tabbed behind another panel (drag-docked — and only then: activatePanel
   clears every tabdock's active tab and syncTabs re-picks the first, so calling
   it for the rail-resident panel would flip the dock to RUNTIME), the XREFS
   section scrolls into view, and the list flashes (relation.js focusSelection's
   .sel-flash, same 700ms). */
function revealXrefs(){
  const panel = document.querySelector('.panel[data-panel="inspector"]');
  if (panel && panel.closest('.tabdock') && !panel.classList.contains('active')) activatePanel('inspector');
  const xr = document.getElementById('strd-xrefs')!;
  xr.scrollIntoView({ block: 'nearest' });
  const title = xr.previousElementSibling;      // the XREFS mini-title
  if (title) title.scrollIntoView({ block: 'nearest' });
  xr.classList.add('sel-flash');
  setTimeout(function(){ xr.classList.remove('sel-flash'); }, 700);
}

/* the rail's note idiom (runs.js noteRow): a transient row at the top of the
   list, gone after 5s; one at a time — a repeated gesture replaces it */
function noteXrefs(msg: string){
  const xr = document.getElementById('strd-xrefs')!;
  const old = xr.querySelector('.strd-note');
  if (old) old.remove();
  const n = document.createElement('div');
  n.className = 'dockhint strd-note';
  n.textContent = msg;
  xr.insertBefore(n, xr.firstChild);
  setTimeout(function(){ n.remove(); }, 5000);
}

/* the card's message line: the value in quotes, cut at 60 chars — a
   multi-KB blob would balloon the card (.os-msg wraps anywhere) */
function quote(v: unknown){
  const s = String(v ?? '');
  return '“' + (s.length > 60 ? s.slice(0, 57) + '…' : s) + '”';
}

/* a jump's wait on the string's xrefs — the report's second complaint: the
   double-click and ↵ awaited the fetch with zero feedback ("clicking on the
   string when the xrefs have not yet been populated, nothing happens").
   Settled rows → returned at once, no card. A fetch in flight — or none yet,
   or a FAILED one, fetched again through loadXrefs (so the rail's error
   retries here and repaints) → the jump card (jumpstatus.js beginJump) in
   an 'xrefs' phase: "reading xrefs of <value>…" + the clock, in the
   open-status corner where the eye already is, until the rows land. THE
   CHOICE (the two on offer): the jump goes through when the rows arrive,
   on the rows as fetched — a jump on rows the fetch has not confirmed could
   land on a renamed-away referrer; the card is the wait's receipt. When
   they land the card goes (done) and the decompile that follows wears
   loadSymbol's own card (segV): the corner may blank for that card's 300ms
   mount delay — by design, under the delay the landing beats the paint.
   The card mounts past the same delay, so a warm fetch never blinks one.
   A newer select disowns the wait (selSeq) — dropCard dismissed its card
   the moment that happened; a failed fetch is the card's vermillion voice
   (fail + 6s linger) on top of the section's. The head shows the value the
   way jumpLabel reads it (a '+' cuts it, dots shorten it) — the message
   line carries it whole, the tooltip raw. Returns the rows, or null when
   there is nothing to jump on (the card or the section said why). */
async function waitXrefs(s: StringRow, my: number): Promise<XrefRow[] | null>{
  let p = s._xrefLoad;
  if (!p && !(s._xrefRes && s._xrefRes.rows)) p = loadXrefs(s, my);
  if (!p) return s._xrefRes!.rows!;
  const j = xrCard = beginJump(s.value);
  j.phase('xrefs', 'reading xrefs of ' + quote(s.value) + '…');
  const r = await p;
  if (xrCard === j) xrCard = null;
  if (my !== selSeq){ j.dismiss(); return null; }
  if (r.error){ j.fail(r.error); return null; }
  j.done();
  return r.rows!;
}

/* Double-click on a table row = go to the string's use (the user report: the
   gesture only selected a word of the value). One loadable referrer jumps at
   once — the xref-row landing, no chooser. Several → the rail's XREFS list IS
   the chooser (its rows already jump): brought forward, scrolled into view,
   flashed. None with rows (mock rows are bare sites) → the reveal plus a
   note; no rows at all → the reveal alone: the section's own empty state
   already reads "no xrefs … nothing to jump to", a note on top would say it
   twice. The gesture's two single clicks already ran selectString — this
   adds the jump on top; the fetch they started is waited on through
   waitXrefs (the card), and its reaction was attached first, so the rail's
   rows are painted before the reveal / the jump reads them. */
async function jumpFromRow(i: number){
  const s = rows[i];
  if (!s) return;
  if (strSel !== i) selectString(i);            // belt and braces: the clicks select before dblclick fires
  const my = selSeq;
  const list = await waitXrefs(s, my);
  if (!list) return;                            // superseded, or failed — the card / the section said why
  const usable = list.filter(function(x){ return !!xrefSym(x); });
  if (usable.length === 1){ await landOnXref(usable[0], xrefSym(usable[0])!); return; }
  revealXrefs();
  if (list.length && !usable.length) noteXrefs('no loadable referrer — nothing to jump to');
}

/* jump to the class owning the first referrer, in the code view — from the
   INSPECTED string (the rail's), whatever the table shows now. segX: a
   fetch in flight is waited on with the card (it used to find no rows yet
   and fall through to jumpToCode: "nothing happens") */
async function jumpFirstUse(){
  const s = insp;
  if (!s) return;
  const my = selSeq;
  const list = await waitXrefs(s, my);
  if (!list) return;                            // superseded, or failed — the card / the section said why
  let classAddr: string | null = null;
  for (const x of list as XrefRowObj[]) {
    if (!x) continue;
    const ref = x.method || x.addr || x.from;
    if (ref && String(ref).includes('->')){
      classAddr = String(ref).split('->')[0];
      break;
    }
  }
  showCenter('code');
  if (classAddr) {
    /* the ↵ / button path wears loadSymbol's own card (segV); a refused class
       is its vermillion line, not an unhandled rejection out of the click
       handler (segU) */
    try { await loadSymbol(classAddr); } catch (e) { /* stay — the card said why */ }
  } else {
    jumpToCode();                                   // mock fixture: no real addr — back to code
  }
}

/* the target changed under the table: the OLD target's rows must not sit
   under the new chip for the seconds its first page takes — cleared first,
   so the populating state shows (segU). A same-target reopen keeps its rows
   (a quiet refresh: the count line's 'refreshing…'). The rail follows (segX):
   the old target's string must not sit in the INSPECTOR as if selected. */
function clearForLoad(){
  rows = []; total = 0; truncated = false; countNote = '';
  stateMode = 'loading'; stateText = '';
  clearInspector();
  renderRows();
}

/* nothing inspected: head and list blank, and the seq moves on (the segS
   rule) so an in-flight xref fetch of the old target's string never paints
   into the new target's rail; its card goes with it */
function clearInspector(){
  insp = null; strSel = -1; ++selSeq;
  (document.getElementById('strd-jump') as HTMLButtonElement).disabled = true;   // nothing to jump to (UI audit 2026-09-09)
  dropCard();
  xrRowsId = null;
  /* an honest empty state, like every sibling rail (persona LOW: the STRING/XREFS heads were
     blank with no "nothing selected" line). The chips row carries the hint — strd-xrefs is
     owned by setXrState below, which removes its state element when the mode is blank. */
  document.getElementById('strd-val')!.innerHTML = '<span style="color:var(--ink-faint)">no string selected</span>';
  document.getElementById('strd-chips')!.innerHTML = '<span style="color:var(--ink-faint)">click a string in the table to inspect its value and xrefs</span>';
  const xr = document.getElementById('strd-xrefs')!;
  xr.innerHTML = '';
  xr.classList.remove('stale');
  setXrState('');
}

/* the target switch while STRINGS owns the center (app.js targetSwitched) */
export async function reloadStrings(): Promise<void>{
  strSel = -1;
  clearForLoad();
  await loadMore(true);
}

export async function initStrings(): Promise<void>{
  if (!wired){
    wired = true;
    document.getElementById('str-filter')!.addEventListener('input', function(){ loadMore(true); });
    $all('[data-st]').forEach(function(c){
      c.addEventListener('click', function(){
        const t = c.getAttribute('data-st')!;
        typeOn[t] = !typeOn[t];
        c.classList.toggle('on', typeOn[t]);
        loadMore(true);
      });
    });
    const sortSel = document.getElementById('str-sort') as HTMLSelectElement | null;
    if (sortSel) sortSel.addEventListener('change', function(){ sortMode = sortSel.value || 'priority'; loadMore(true); });
    document.getElementById('str-unreach')!.addEventListener('click', function(){
      unreachOnly = !unreachOnly;
      this.classList.toggle('on', unreachOnly);
      loadMore(true);
    });
    document.getElementById('strd-jump')!.addEventListener('click', jumpFirstUse);
    document.getElementById('str-table')!.addEventListener('scroll', function(){
      const el = this;
      if (truncated && !loading && el.scrollTop + el.clientHeight > el.scrollHeight - 120) loadMore(false);
    });
    /* the double-click gesture, delegated (renderRows rebuilds the rows). The
       complaint was the browser's own word-select of the value: it is made on
       the SECOND mousedown (detail 2), before dblclick ever fires, so a
       preventDefault in the dblclick handler alone is too late — the mousedown
       below suppresses it for detail > 1 only. A single-click drag (detail 1)
       over a value still selects it — users copy values. Primary button only:
       a right-double-click keeps its context menu. */
    const tbl = document.getElementById('str-table')!;
    tbl.addEventListener('mousedown', function(ev){
      if (ev.button === 0 && ev.detail > 1 && (ev.target as HTMLElement).closest('.srow')) ev.preventDefault();
    });
    tbl.addEventListener('dblclick', function(ev){
      ev.preventDefault();
      const r = (ev.target as HTMLElement).closest('.srow');
      if (r) jumpFromRow(+r.getAttribute('data-i')!);
    });
    /* ↵ jumps from the INSPECTED string (the rail's) — it stays inspected
       when a filter change drops it from the table (strSel −1) */
    document.getElementById('stringswrap')!.addEventListener('keydown', function(e){
      if (e.key === 'Enter' && insp){ e.preventDefault(); jumpFirstUse(); }
    });
    /* Escape lives on the central dispatcher, keyed on S.center — ⌃3 and the rail
       button never focus this wrap, so the listener above never saw it (F5). */
    pushPaneEscape('strings');
  }
  /* the target switched while another view was up (targetSwitched reloads
     only when strings owns the center): those rows are another target's */
  if (rows.length && rowsTid !== (S.currentTid || null)){ strSel = -1; clearForLoad(); }
  else if (!insp) clearInspector();   // fresh/empty open: paint the rail's "no string selected" placeholder
  await loadMore(true);
}
