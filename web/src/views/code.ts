// views/code.ts — code view: live decompile render (text + token map), live smali
// pane (offsets+bytes, sync band), rename/comment dialogs (F2/;), save (⌘S),
// RELATION follow from the caret. loadSymbol is every jump's funnel and wears
// the jump card ("decompiling <X>" + clock, jumpstatus.js) on every load (segV).
import { $all, S, hook, pushEscape, popEscape, ESC, escHtml as esc, opT, tryOpT, isHexColor, methodAt, explainError } from '../ui/hub.js';
import { tryOp } from '../api.js';
import { startCaret, renderCaret, cancelFollow, paintPaneHeads } from '../ui/caret.js';
import { loadRelationFor, renderLocalUsages, refreshRelationTree } from '../ui/relation.js';
import { loadHistory } from '../ui/history.js';
import { activeTargetName, focusCenter, setCodeCrumb, updateDockScope, notice, showDock, activatePanel, syncTabs } from '../ui/chrome.js';
import { navPush, navRemap } from '../ui/navigation.js';
import { refreshUnsaved } from '../ui/ribbon.js';
import { beginJump } from '../ui/jumpstatus.js';
import { invalidateParams } from '../ui/params.js';
import type { CaretHit, CodeLine, CodeToken, DecompileData, EditResult, LineMark, MethodInfo, RelationRow, SymbolInfo, Token, WorkspaceState, OpParams } from '../types.js';

const KEYWORDS = new Set(('package import public private protected static final abstract synchronized volatile ' +
  'transient native strictfp default class interface enum record implements extends permits ' +
  'return if else switch case break continue goto for while do throw throws ' +
  'try catch finally new this super instanceof assert yield var').split(' '));

const PRIM_KEYWORDS = new Set(('void boolean byte char short int long float double null true false').split(' '));

/* highlight a source segment [from, to) inside `line`, using the full line for
   context (e.g. a trailing `(` or preceding `.`) and `state.inBlockComment` to
   track multi-line `/* … * /` comments across lines. Returns HTML. */
function highlightRange(line: string, from: number, to: number, state?: { inBlockComment: boolean; inLineComment?: boolean }): string {
  const out: string[] = [];
  let i = from;
  const isIdentStart = (c: string) => /[A-Za-z_$]/.test(c);
  const isIdent = (c: string) => /[A-Za-z0-9_$]/.test(c);
  const isDigit = (c: string) => /[0-9]/.test(c);
  const isHexOrNumPart = (c: string) => /[0-9A-Fa-f_xXbBlLfFdDpP.]/.test(c);

  if (state && state.inLineComment && i < to){
    out.push('<span class="c">' + esc(line.slice(i, to)) + '</span>');
    return out.join('');
  }

  if (state && state.inBlockComment && i < to){
    const close = line.indexOf('*/', i);
    if (close < 0 || close + 2 > to){
      out.push('<span class="c">' + esc(line.slice(i, to)) + '</span>');
      return out.join('');
    }
    out.push('<span class="c">' + esc(line.slice(i, close + 2)) + '</span>');
    state.inBlockComment = false;
    i = close + 2;
  }

  while (i < to){
    if (line[i] === '/' && line[i + 1] === '/'){
      if (state) state.inLineComment = true;
      out.push('<span class="c">' + esc(line.slice(i, to)) + '</span>');
      break;
    }
    if (line[i] === '/' && line[i + 1] === '*'){
      const close = line.indexOf('*/', i + 2);
      if (close < 0){
        if (state) state.inBlockComment = true;
        out.push('<span class="c">' + esc(line.slice(i, to)) + '</span>');
        break;
      }
      const end = Math.min(to, close + 2);
      if (close + 2 > to && state) state.inBlockComment = true;
      out.push('<span class="c">' + esc(line.slice(i, end)) + '</span>');
      i = end;
      continue;
    }
    if (line[i] === '"' || line[i] === '\''){
      const q = line[i];
      let j = i + 1;
      while (j < to && line[j] !== q){ if (line[j] === '\\') j++; j++; }
      j = Math.min(to, j + 1);
      out.push('<span class="' + (q === '"' ? 's' : 'n') + '">' + esc(line.slice(i, j)) + '</span>');
      i = j;
      continue;
    }
    if (line[i] === '@' && i + 1 < to && isIdentStart(line[i + 1])){
      let j = i + 1;
      while (j < to && (isIdent(line[j]) || (line[j] === '.' && j + 1 < to && isIdentStart(line[j + 1])))) j++;
      out.push('<span class="an">' + esc(line.slice(i, j)) + '</span>');
      i = j;
      continue;
    }
    if (isDigit(line[i]) || (line[i] === '.' && i + 1 < to && isDigit(line[i + 1]) && (i === 0 || !isIdent(line[i - 1])))){
      let j = i + 1;
      while (j < to && isHexOrNumPart(line[j])){
        if ((line[j] === '+' || line[j] === '-') && !/[eEpP]/.test(line[j - 1])) break;
        j++;
      }
      out.push('<span class="n">' + esc(line.slice(i, j)) + '</span>');
      i = j;
      continue;
    }
    if (isIdentStart(line[i])){
      let j = i;
      while (j < to && isIdent(line[j])) j++;
      const w = line.slice(i, j);
      if (KEYWORDS.has(w)){
        out.push('<span class="k">' + esc(w) + '</span>');
      } else if (PRIM_KEYWORDS.has(w)){
        out.push('<span class="kp">' + esc(w) + '</span>');
      } else {
        // Look ahead in the full line for call parens `foo(`, or classify UpperCamel / UPPER_CONST
        let k = j;
        while (k < line.length && line[k] === ' ') k++;
        if (line[k] === '('){
          out.push('<span class="fn">' + esc(w) + '</span>');
        } else if (w.length > 1 && /^[A-Z][A-Z0-9_]+$/.test(w) && /[A-Z]/.test(w)){
          out.push('<span class="cn">' + esc(w) + '</span>');
        } else if (/^[A-Z]/.test(w)){
          out.push('<span class="ty">' + esc(w) + '</span>');
        } else if (i > 0 && line[i - 1] === '.' && !(i >= 5 && line.slice(i - 5, i) === 'this.')){
          // Qualified member access without parens (e.g. msg.kind), excluding package/import lines
          const trimmed = line.trimStart();
          if (!trimmed.startsWith('package ') && !trimmed.startsWith('import '))
            out.push('<span class="fl">' + esc(w) + '</span>');
          else
            out.push(esc(w));
        } else if (i >= 5 && line.slice(i - 5, i) === 'this.'){
          out.push('<span class="fl">' + esc(w) + '</span>');
        } else {
          out.push(esc(w));
        }
      }
      i = j;
      continue;
    }
    out.push(esc(line[i]));
    i++;
  }
  return out.join('');
}

/* highlight one source line: comments, strings, keywords, types, calls, numbers. Returns HTML. */
export function highlight(line: string, state?: { inBlockComment: boolean }): string {
  return highlightRange(line, 0, line.length, state);
}

/* normalize server token shapes: mock {col,len,symbol} · jeb {cs,ce,addr} */
function tokCol(t: Token): number { return t.col !== undefined ? t.col : t.cs!; }
function tokLen(t: Token): number { return t.len !== undefined ? t.len : (t.ce! - t.cs!); }
function tokSym(t: Token): string | undefined { return t.symbol !== undefined ? t.symbol : t.addr; }

/* one java source line → HTML with navigable token spans (data-sym for follow/rename;
   data-kind survives empty-sym tokens — locals carry no addr (review: local xrefs)) */
function renderLine(lineText: string, tokens: Token[] | undefined, state?: { inBlockComment: boolean; inLineComment?: boolean }): string {
  if (state) state.inLineComment = false;
  const lineState = state || { inBlockComment: false, inLineComment: false };
  const marks = (tokens || []).slice().sort((a, b) => tokCol(a) - tokCol(b));
  let html = '', pos = 0;
  marks.forEach(function(t){
    const col = tokCol(t), len = tokLen(t);
    if (col < pos || len <= 0) return;                    // overlapping token — skip
    html += highlightRange(lineText, pos, col, lineState);
    if (lineState.inBlockComment || lineState.inLineComment){
      html += highlightRange(lineText, col, col + len, lineState);
    } else {
      html += '<span class="nav" data-sym="' + esc(tokSym(t) || '') + '"' +
              ' data-kind="' + esc(t.kind || '') + '">' +
              esc(lineText.slice(col, col + len)) + '</span>';
    }
    pos = col + len;
  });
  html += highlightRange(lineText, pos, lineText.length, lineState);
  if (state) state.inLineComment = false;
  return html;
}

/* the METHOD a symbol names inside the loaded class — null for a class-only
   symbol (seg1 nav-jumps bug 1). JEB's decompile resolves any member to its
   OWNER (symbol.kind is always 'class', symbol.addr the class addr), so the
   member has to be re-found client-side in data.methods. Forms on the wire:
   JEB addr/sig `Lpkg/C;->name(desc)ret` (addr = original names, signature =
   effective names — rows hand us either), a 'from' site with a +offset, and
   the dotted `pkg.C.name` form the mock/token map uses. Exact addr|signature
   first, then the `name(desc)` tail (owner spelled under the other naming),
   then the bare member name (first overload WITH a line wins — no descriptor
   to pick by). A true match that carries no line (abstract/interface, decl not
   found by methodRanges) yields null: land on the class, never a sibling. */
function methodFor(symbol: string | null | undefined, methods: MethodInfo[] | undefined): MethodInfo | null {
  const s = String(symbol || '').split('+')[0];
  let tail = null, name = null;
  const arrow = s.indexOf('->');
  if (arrow >= 0){
    tail = s.slice(arrow + 2);
    name = tail.split('(')[0];
  } else if (!s.endsWith(';') && s.includes('.')){
    const last = s.slice(s.lastIndexOf('.') + 1);
    // JEB's lookup heuristic: a lowercase (or <init>) last segment is a member
    if (last && (/^[a-z]/.test(last) || last === '<init>')) name = last;
  }
  if (!name) return null;
  const list = methods || [];
  const memberOf = (a: string | undefined) => { const i = String(a || '').indexOf('->'); return i >= 0 ? String(a).slice(i + 2) : null; };
  const m = list.find(x => x.addr === s || x.signature === s || x.sig === s)
      || (tail && list.find(x => memberOf(x.addr) === tail || memberOf(x.signature) === tail))
      || list.find(x => x.line && (x.name === name || x.origName === name));
  return m && m.line ? m : null;
}

/* ==================================================================
   DECOMPILE + RENDER
================================================================== */
/* loadSymbol is the ONE funnel every jump takes — RELATION rows, the tree, map
   nodes, bookmark/tag/flow/history rows, search hits, dbl-click go-to-def (the
   gotoDef hook jumpToCode lands on), ‹/›, tab switches, the target-switch
   restore, the strings paths, the post-rename reload — so the jump card lives
   HERE (directive, segV 2026-09-08: "The jump progress should be on every xref
   jump, not just every string jump"; segU had wrapped the strings paths only).
   The card tracks exactly what a caller awaits: begun at entry, done() when
   the promise resolves (java painted, class info, smali pane — the instant
   segU's strings wrap called it, after its await), fail() handed the thrown
   error and the throw re-raised, so callers keep their owner fallbacks and
   tab drops (a fallback's own loadSymbol supersedes the failed card before a
   frame paints — jumpstatus.js header). The wrapper/0 split is jumpToCode's
   idiom (chrome.js): one place to read, the body not re-indented. */
/* A rename or a restoration answered with the symbol's identity change (addr → newAddr). Everything
   that keys on the OLD identity follows BEFORE any reload, so the reload asks for a class that still
   exists: the viewed class (S.currentClass — a search result's handle is the class's CURRENT addr,
   which a restoration makes unresolvable: afterwards it is neither the current nor the original
   name), its tabs and tree row (the classRenamed hook), the ‹ › history (navRemap). A member's
   change never makes the view load the member: its owning class is what reloads afterwards. Returns
   false when the viewed target changed while the request was in flight — then nothing of the view
   is touched, the caller does not reload (independent review 2026-10-08: a revert after
   re-navigating to the renamed class left `BrowserMessageKey` on screen and threw an unhandled
   E_NOTFOUND from the reload of the dead handle). */
export function followIdentityChange(res: EditResult | undefined, hitSymbol: string, tidAtCall: string | null): boolean {
  if (tidAtCall !== S.currentTid) return false;
  if (!res) return true;
  const kind = String(res.kind || (res.symbol && res.symbol.kind) || '');
  const oldAddr = String(res.addr || hitSymbol || '');
  let newAddr = String(res.newAddr || '');
  if (kind !== 'class' || !oldAddr) return true;
  const dot = function(a: string){ return /^L.*;$/.test(a) ? a.slice(1, -1).replace(/\//g, '.').replace(/\$/g, '.') : a; };
  if (!newAddr || newAddr === oldAddr){
    /* a mock engine keeps the handle and moves only the display name: the tabs and the tree row
       still have to follow (the dropped fallback of the rename dialog, fix-review 3 L1) */
    if (!res.newName || res.newName === String(res.oldName || '')) return true;
    newAddr = oldAddr;
  }
  const newFqcn = newAddr !== oldAddr ? dot(newAddr) : dot(oldAddr).replace(/[^.]+$/, String(res.newName));
  const newSimple = String(res.newName || newFqcn.split('.').pop() || '');
  /* three-valued on purpose: true (rename.revert says so), false (a redo of a rename), undefined (an
     undo of a plain rename — whether the name that comes back is the original is the tree row's to
     decide, it remembers it); coercing it to false silenced that heuristic */
  const restored: boolean | undefined = res.restored === undefined ? undefined : !!res.restored;
  const viewFqcn = S.currentFqcn ? String(S.currentFqcn) : '';
  const oldHandles = [oldAddr, hitSymbol, dot(oldAddr)].filter(function(h){ return !!h; });
  const viewed = !!S.currentClass && (oldHandles.indexOf(S.currentClass) >= 0 || (S.currentSym && S.currentSym.addr === oldAddr) || (!!viewFqcn && viewFqcn === dot(oldAddr)));
  /* the key to reload by: the viewed class's ORIGINAL signature (it resolves after this change and
     after its undo); never the new analyst addr, which the next undo kills */
  const origSig = viewed && S.currentSym && S.currentSym.origSig ? String(S.currentSym.origSig) : '';
  const key = /^L.*;$/.test(origSig) ? origSig : newAddr;
  if (viewed) S.currentClass = key;
  const rowKey = /^L.*;$/.test(origSig) ? origSig : undefined;   // the tree row's re-open handle, when the view knows the stable one
  hook('classRenamed', dot(oldAddr), oldAddr, newFqcn, newSimple, restored, rowKey);
  if (viewed && viewFqcn && viewFqcn !== dot(oldAddr) && viewFqcn !== newFqcn)
    hook('classRenamed', viewFqcn, oldAddr, newFqcn, newSimple, restored, rowKey);   // the view showed the class under another spelling (a tree handle)
  if (newAddr !== oldAddr) navRemap(viewed && viewFqcn && viewFqcn !== dot(oldAddr) ? oldHandles.concat([viewFqcn]) : oldHandles, key, newSimple);
  return true;
}

export async function loadSymbol(symbol: string): Promise<DecompileData>{
  const j = beginJump(symbol);
  try {
    const data = await loadSymbol0(symbol);
    j.done();                                   // the landing is the receipt: the card goes at once
    return data;
  } catch (e) {
    j.fail(e);                                  // the vermillion line + linger; the caller still sees the throw
    throw e;
  }
}

async function loadSymbol0(symbol: string){
  // any class load un-earns the map Esc-return: cameFromMap means "this view
  // was JUST entered from the map". Map-origin entries (jumpToCode, map
  // jumpNode) re-set it themselves after their load completes.
  S.cameFromMap = false;
  /* bind the VIEWED target: target-less ops resolve the server's activeTarget,
     a shared cursor any actor can yank mid-gesture (review: the BP.apk desync —
     the viewer painted one apk while ops 404'd against another) */
  /* a STALE tid must not dead-end the view (GUI review finding 9): ids
     remint across server restarts — opT (hub.ts) reseeds from the live state
     and retries once; a missing CLASS rethrows */
  const sameClass = S.currentClass === symbol;   // a re-render (an accepted proposal, F2): the tab must follow a class rename, not twin
  const prevFqcn = S.currentFqcn;
  /* a re-render must not move the reader (user 2026-09-19: every agent rename threw the view
     somewhere else): remember the caret, the method it sits in and the pane's scroll offset,
     and put them back after the rows are rebuilt — shifted by any lines a new comment inserted
     above (the method's declaration line is the anchor). */
  const codeEl0 = document.getElementById('code');
  const keep = sameClass && codeEl0 && S.codeLines.length ? {
    line: S.caret.line, col: S.caret.col,
    scrollTop: codeEl0.scrollTop, scrollLeft: codeEl0.scrollLeft,
    method: methodAt(S.javaMethods, S.caret.line + 1),
    smaliTop: (document.getElementById('smali') || { scrollTop: 0 }).scrollTop,
  } : null;
  const data = await opT('code.decompile', { symbol: symbol, scope: 'class', tokens: true });   // whole class + token map — a bare member symbol decompiles the method only (agents/MCP)
  S.currentSym = data.symbol || {};
  /* the view keys itself on the class's ORIGINAL signature when the engine gives it: the one handle
     that resolves whatever the current name is — a rename, its undo, a restoration each kill the
     analyst's addr, and every reload (afterEdit, undo/redo, the saved view, a tab, ‹ ›) goes through
     this key (persona pass 3, C1: keyed on the new analyst addr, ⌘Z after a rename reloaded a dead
     handle). A mock engine gives none: the handle that just resolved stays the key. */
  const origSig = S.currentSym.origSig ? String(S.currentSym.origSig) : '';
  S.currentClass = /^L.*;$/.test(origSig) ? origSig : symbol;
  S.javaMethods = data.methods || [];

  const byLine: Record<number, Token[]> = {};
  (data.tokens || []).forEach(function(t){
    (byLine[t.line] = byLine[t.line] || []).push(t);
  });

  const code = document.getElementById('code')!;
  const bar = code.querySelector('.caretbar');
  code.innerHTML = '';
  const lines = data.text.split('\n');
  /* comments an agent wrote (data.agentComments, from History): a `//` line carrying one of
     their texts renders as an agent comment — set apart in the agent colour with a left rule,
     the author named at its start, and wrapped at ~96 columns inside its own numbered row
     (line numbers stay the engine's). user 2026-09-19: one endless line needing a horizontal
     scroll, indistinguishable from a human's or the engine's comment. */
  const agentCm = (data.agentComments || []).map(function(c){ return { text: String(c.text || '').replace(/\s+/g, ' ').trim(), by: String(c.by || 'agent'), confidence: c.confidence, historyId: c.historyId ? String(c.historyId) : '', addr: String(c.addr || '') }; }).filter(function(c){ return c.text.length >= 8; });
  const agentCommentOf = function(text: string){
    const m = /^(\s*)\/\/\s?(.*)$/.exec(text);
    if (!m || !agentCm.length) return null;
    const body = m[2].replace(/\s+/g, ' ').trim();
    if (body.length < 8) return null;
    const hit = agentCm.find(function(c){ return c.text === body || (body.length >= 24 && (c.text.includes(body) || body.includes(c.text))); });
    return hit ? { indent: m[1], body: body, by: hit.by, confidence: hit.confidence, historyId: hit.historyId, addr: hit.addr } : null;
  };
  /* a call-site comment: JEB prints an instruction comment TRAILING the statement
     (`foo(x);  // text`); an agent's is lifted onto its own visual line above the call inside the
     same numbered row (user 2026-09-20: "right above the call") — the code's columns are untouched,
     so the caret and the token map still line up */
  const siteCommentOf = function(text: string){
    if (!agentCm.length || /^\s*\/\//.test(text)) return null;
    const m = /^(.*\S)\s+\/\/\s?(.+)$/.exec(text);
    if (!m) return null;
    const body = m[2].replace(/\s+/g, ' ').trim();
    if (body.length < 8) return null;
    const hit = agentCm.find(function(c){ return c.text === body || (body.length >= 24 && (c.text.includes(body) || body.includes(c.text))); });
    return hit ? { code: m[1], body: body, by: hit.by, confidence: hit.confidence, historyId: hit.historyId, addr: hit.addr } : null;
  };
  const lexState = { inBlockComment: false };
  lines.forEach(function(text, idx){
    const ln = idx + 1;
    const row = document.createElement('div');
    row.className = 'cl';
    const ac = lexState.inBlockComment ? null : agentCommentOf(text);
    const sc = (lexState.inBlockComment || ac) ? null : siteCommentOf(text);
    if (sc){
      row.className = 'cl llmsite';
      row.dataset.cmHid = sc.historyId; row.dataset.cmBy = sc.by; row.dataset.cmAddr = sc.addr; row.dataset.cmText = sc.body;   // the comment's own menu (delete)
      row.innerHTML = '<span class="gm"></span><span class="ln">' + ln + '</span>' +
        '<div class="lcm" title="written by ' + esc(sc.by) + (sc.confidence != null ? ' · confidence ' + esc(String(sc.confidence)) : '') + '"><span class="llmtag">' + esc(sc.by) + '</span>// ' + esc(sc.body) + '</div>' +
        '<span class="lc">' + renderLine(sc.code, byLine[ln], lexState) + '</span>';
    } else if (ac){
      row.className = 'cl llmcm';
      row.dataset.cmHid = ac.historyId; row.dataset.cmBy = ac.by; row.dataset.cmAddr = ac.addr; row.dataset.cmText = ac.body;
      row.innerHTML = '<span class="gm"></span><span class="ln">' + ln + '</span>' +
        '<span class="lc">' + esc(ac.indent) + '<span class="c llm" title="written by ' + esc(ac.by) + (ac.confidence != null ? ' · confidence ' + esc(String(ac.confidence)) : '') + '">' +
        '<span class="llmtag">' + esc(ac.by) + '</span>// ' + esc(ac.body) + '</span></span>';
    } else {
      row.innerHTML = '<span class="gm"></span><span class="ln">' + ln + '</span>' +
        '<span class="lc">' + renderLine(text, byLine[ln], lexState) + '</span>';
    }
    code.appendChild(row);
  });
  if (bar) code.appendChild(bar);
  /* a gutter mark on the methods that carry retained MethodParameters (the class-scope index): where
     the lists are, before the caret gets there — the mark says metadata, not names (persona pass 2026-10-08) */
  (data.methodParametersIndex || []).forEach(function(ix){
    const jm = (S.javaMethods || []).find(function(x){ return x.addr === ix.addr || x.signature === ix.addr || (ix.original_method !== undefined && x.addr === ix.original_method); });
    const ln = jm && jm.line ? Number(jm.line) : 0;
    const row = ln > 0 ? code.querySelectorAll<HTMLElement>('.cl')[ln - 1] : undefined;
    if (!row) return;
    row.classList.add('mpann');
    const g = row.querySelector<HTMLElement>('.ln');
    if (g) g.title = 'retained MethodParameters: ' + (ix.names || []).filter(function(n){ return n != null; }).length + ' name(s) — metadata, argument mapping unverified (the inspector shows the list)';
  });

  // pane header + editor tab follow the engine. Header anchors on the
  // CLASS: a method symbol resolving to class text must show its owner, not the
  // method name (MockEngine returns the resolved member in `symbol`).
  const sym: SymbolInfo = data.symbol || {};
  const className = sym.fqcn || sym.owner || sym.name || '?';
  const simple = className.split('.').pop()!;
  const ph = document.querySelector('#pane-java .panehead');
  if (ph) ph.innerHTML = esc(simple) + '.java<span class="phent">decompiled · ' + esc(data.engine || '') + '</span>';
  const sh = document.querySelector('#pane-smali .panehead');
  if (sh){ sh.innerHTML = esc(simple) + '.smali<span class="phent" data-engine="' + esc(data.engine || '') + '"></span>'; paintPaneHeads(); }
  // breadcrumb + CLASS inspector follow the displayed class (no fixture rows)
  const fqcn = className;
  const pkg = fqcn.includes('.') ? fqcn.substring(0, fqcn.lastIndexOf('.')) : '';
  S.crumbBase = '<span class="nav">' + esc(activeTargetName()) + '</span><span class="sep">›</span>' +
    (pkg ? '<span class="nav">' + esc(pkg) + '</span><span class="sep">›</span>' : '') +
    /* the class segment carries the FULL fqcn: click copies it (chrome.js) */
    '<span class="cur" data-fqcn="' + esc(fqcn) + '" title="' + esc(fqcn) + ' — click to copy">' +
      esc(simple) + '</span>' +
    (sym.origName ? '<span class="was"> (was: ' + esc(sym.origName) + ')</span>' : '');   // a real space + parens: the copied text reads "MediaDownloadManager (was: 15Q)"
  setCodeCrumb(S.crumbBase);
  const ci = document.getElementById('class-info');
  if (ci){
    /* classinfo gets the CLASS, not the raw input (GUI review finding 14:
       arriving from a RELATION row handed it a method signature — it only
       worked because the backend resolves leniently) */
    const cinfo = await tryOpT('code.classinfo', { class: (sym.fqcn || sym.addr || symbol) });   // viewed-target binding (review: desync)
    if (cinfo.data){
      const c = cinfo.data;
      let rows = '<span class="k">CLASS</span><span class="v">' + esc(c.fqcn || fqcn) + '</span>';
      if (c.renamed && c.origName)
        rows += '<span class="k">ORIGINAL</span><span class="v v2">' + esc(c.origName) +
          ' <span style="color:var(--ink-faint)">(renamed)</span></span>';
      if (c.super) rows += '<span class="k">SUPER</span><span class="v">' + esc(c.super) + '</span>';
      if (c.interfaces && c.interfaces.length)
        rows += '<span class="k">INTERFACES</span><span class="v">' + esc(c.interfaces.join(', ')) + '</span>';
      if (c.flags) rows += '<span class="k">FLAGS</span><span class="v">' + esc(c.flags) + '</span>';
      rows += '<span class="k">METHODS</span><span class="v num">' + (c.methods ?? S.javaMethods.length) + '</span>';
      if (c.fields != null) rows += '<span class="k">FIELDS</span><span class="v num">' + esc(c.fields) + '</span>';
      if (c.xrefCount != null) rows += '<span class="k">XREFS</span><span class="v num">' + esc(c.xrefCount) + '</span>';
      rows += '<span class="k">ENGINE</span><span class="v">' + esc(data.engine || '') + '</span>';
      if (c.addr) rows += '<span class="k">ADDR</span><span class="v" style="font-size:10px">' + esc(c.addr) + '</span>';
      ci.innerHTML = rows;
      ci.querySelectorAll<HTMLElement>('.v').forEach(function(v){ v.title = v.textContent!; });   // QA 25: ellipsis tooltip
    } else {
      let rows = '<span class="k">CLASS</span><span class="v">' + esc(fqcn) + '</span>';
      rows += '<span class="k">METHODS</span><span class="v num">' + (S.javaMethods.length || '0') + '</span>';
      rows += '<span class="k">ENGINE</span><span class="v">' + esc(data.engine || '') + '</span>';
      ci.innerHTML = rows;
      ci.querySelectorAll<HTMLElement>('.v').forEach(function(v){ v.title = v.textContent!; });
    }
  }

  // park the caret on the first method declaration (fallback: top of file)
  // park the caret on the first REAL method (constructors are noise — the
  // crumb segment reads it verbatim)
  const m0 = S.javaMethods.find(m => m.line && !String(m.name).startsWith('<'))
          || S.javaMethods.find(m => m.line);
  /* a METHOD symbol lands on THAT method (seg1 nav-jumps bug 1): xref/callee
     rows, dbl-click go-to-def and search hits all hand a member here, and every
     one of them parked on m0 — the class's FIRST method — because the requested
     member was never looked up. The engine only names the owner (methodFor
     re-finds the member in data.methods); the mock resolves the member itself
     (symbol.kind 'method' + line). Class-only symbols keep the m0 parking. */
  const mm: { name?: string; line?: number | null } | null = methodFor(symbol, S.javaMethods)
      || (sym.kind === 'method' && sym.line ? { name: sym.name, line: sym.line } : null);
  const land = mm || m0;
  if (keep){
    /* the re-render: same line (shifted with its method's declaration), same column, same scroll */
    let shift = 0;
    if (keep.method && keep.method.line){
      const again = S.javaMethods.find(x => x.addr === keep.method!.addr || x.signature === keep.method!.signature)
          || S.javaMethods.find(x => x.name === keep.method!.name && x.origName === keep.method!.origName);
      if (again && again.line) shift = again.line - keep.method.line;
    }
    const line = Math.max(0, Math.min(S.codeLines.length - 1, keep.line + shift));
    startCaret(null, line + 1);
    S.caret.col = keep.col;
    renderCaret(false, false);
    const rowH = S.codeLines[0] && S.codeLines[0].cl ? S.codeLines[0].cl.getBoundingClientRect().height || 17 : 17;
    code.scrollTop = keep.scrollTop + shift * rowH;
    code.scrollLeft = keep.scrollLeft;
  } else {
    startCaret(land ? land.name : null, land ? land.line : 1);
    // startCaret never scrolls (m0 sits near the top anyway) — a member deep in
    // the file needs gotoLine's centering, or the caret lands off-screen
    if (mm && S.codeLines[S.caret.line]) S.codeLines[S.caret.line].cl.scrollIntoView({ block: 'center' });
  }

  await loadSmali(symbol);
  if (keep){ const sm = document.getElementById('smali'); if (sm) sm.scrollTop = keep.smaliTop; }
  loadMarks();
  // the OUTLINE dock panel follows the open class — but only refresh it while it is visible,
  // so an unopened outline costs no code.outline round-trip on every jump
  if (document.querySelector('.panel[data-panel="outline"]')?.classList.contains('active')) renderOutline();
  invalidateParams();   // the retained-metadata section re-reads for the method under the caret (an undo/redo changed the names)
  hook('caretFollow');
  // key tabs on the RESOLVED class, not the raw input — the same class reached
  // by name, addr, or FQCN must not open duplicate tabs (verify-round finding).
  // The re-open handle is the raw input that just resolved successfully.
  S.currentFqcn = className;
  pingPresence(sym.addr || className);          // co-presence: the human opened a class
  /* the re-open handle and the ‹ › entry are the view's key (the original signature when known) —
     a tab or a history entry must still open the class after its name moved */
  hook('classOpened', className, simple, sym.renamed || sym.origName, S.currentClass || symbol, sameClass && prevFqcn ? prevFqcn : undefined);
  if (!keep) navPush(S.currentClass || symbol, simple, land ? land.line : 1);  // unified location history (QA 9) — the LANDED line, so ‹/› return to the member; a re-render is not a navigation
  return data;
}

/* ==================================================================
   GLYPH MARGIN — real tags (square) + bookmarks (tick) for the open class
================================================================== */
function classPartOf(addr: string | undefined): string { return String(addr || '').split('->')[0]; }

async function loadMarks(){
  S.lineMarks = {};
  const classAddr = classPartOf(S.currentSym && (S.currentSym.addr || S.currentSym.fqcn));
  if (!classAddr) return;
  /* viewed-target binding (GUI review finding 5): target-less list ops paint
     another apk's margin squares against THIS apk's line numbers under a
     yanked server cursor */
  const [tags, bms, bps] = await Promise.all([
    tryOpT('tag.list', {}),
    tryOpT('bookmark.list', {}),
    tryOpT('runs.bp.list', {}),
  ]);
  ((tags.data && tags.data.tags) || []).forEach(function(t){
    if (t.line! > 0 && classPartOf(t.addr || t.symbol) === classAddr){
      const m = S.lineMarks[t.line!] = S.lineMarks[t.line!] || { tags: [], bm: false };
      m.tags.push(t.color || '#0072B2');
    }
  });
  /* breakpoints: the method's declaration line carries the glyph — keyed the
     way the toggle keys them, by addr OR original sig (fix-review r1 M5) */
  (bps.data || []).forEach(function(bp){
    const mm = (S.javaMethods || []).find(function(x){ return x.addr === bp.addr || x.addr === bp.origSig; });
    if (!mm || !mm.line) return;
    const mk = S.lineMarks[mm.line] = S.lineMarks[mm.line] || { tags: [], bm: false };
    mk.bp = true;
    mk.bpOff = bp.enabled === false;
  });
  (bms.data || []).forEach(function(b){
    if (classPartOf(b.addr || b.symbol) === classAddr){
      const m = S.lineMarks[b.line as number] = S.lineMarks[b.line as number] || { tags: [], bm: false };
      m.bm = true;
    }
  });
  // coverage stripes: methods of this class covered in the selected run (runs.coverage)
  const simple = classAddr.split('/').pop()!.replace(/;.*$/, '');
  /* N5: seed the selection from the LATEST run (runs.runs is startTs-descending,
     the same run the server picks for run:'') — otherwise the readout says
     'no run selected' while covered lines are already striped, and the first
     'c' press only relabels the same run. */
  if (!S.covRun){
    const rr = await tryOp('runs.runs', S.currentTid ? { target: S.currentTid } : {});   // the VIEWED target's latest run, not the project's (r4 M2)
    const first = (rr.data || [])[0];
    if (first && first.execId){ S.covRun = first.execId; updateDockScope(); }
  }
  let covMethods = 0;
  /* no run for the VIEWED target → no coverage to paint. Skip the call rather than send run:''
     (b2-r5 M6: a blank run once resolved to the whole store's latest run — another target's — and
     the gutter striped this class with that run's coverage). The server guards this too now. */
  if (S.covRun){
    const covParams: OpParams = { run: S.covRun, class: simple };
    if (S.currentTid) covParams.target = S.currentTid;
    const cov = await tryOpT('runs.coverage', covParams);   // viewed-target binding (finding 5)
    const covered = new Set((((cov.data || {} as { addrs?: { addr: string }[] }).addrs) || []).map(a => a.addr));
    (S.javaMethods || []).forEach(function(m){
      if (m.addr && covered.has(m.addr)){
        covMethods++;
        if (m.line && m.endLine)
          for (let l = m.line; l <= m.endLine; l++){
            const mk = S.lineMarks[l] = S.lineMarks[l] || { tags: [], bm: false };
            mk.cov = true;
          }
      }
    });
  }
  applyMarks();
  renderCovRow(covMethods);
}

/* CLASS-inspector COVERAGE row — real covered% of the open class in the
   SELECTED run, off the same runs.coverage call the glyph stripes use (so a
   class load and every 'c' cycle both refresh it). This is the run readout
   that survives a ⌘J-collapsed dock: the inspector lives in the right rail. */
function renderCovRow(covMethods: number){
  const ci = document.getElementById('class-info');
  if (!ci) return;
  const total = (S.javaMethods || []).filter(function(m){ return !!m.addr; }).length;
  const pct = (!covMethods || !total) ? null : Math.round(covMethods / total * 100);
  let v = ci.querySelector<HTMLElement>('.v.cov');
  if (!v){
    const k = document.createElement('span');
    k.className = 'k';
    k.textContent = 'COVERAGE';
    v = document.createElement('span');
    v.className = 'v cov';
    ci.appendChild(k);
    ci.appendChild(v);
  }
  v.className = 'v cov' + (pct ? ' g' : '');
  v.innerHTML = (pct == null ? '—' : pct + '%') +
    ' <span style="color:var(--ink-faint)">· run ' + esc(S.covRun || '—') + '</span>';
  v.title = v.textContent!;
}

function applyMarks(){
  $all('#code .cl').forEach(function(row, idx){
    const gm = row.querySelector('.gm');
    if (!gm) return;
    const m = (S.lineMarks || {} as Record<number, LineMark>)[idx + 1];
    gm.innerHTML = !m ? ''
      : (m.bp ? '<span class="gbp' + (m.bpOff ? ' off' : '') + '" title="breakpoint' + (m.bpOff ? ' (disabled)' : '') + ' — click the gutter to toggle"></span>' : '') +
        (m.cov ? '<span class="gcov" title="covered in run"></span>' : '') +
        (m.bm ? '<span class="gbm" title="bookmark"></span>' : '') +
        m.tags.map(c => '<span class="gtag" style="background:' + (isHexColor(c) ? c : 'var(--blue)') + '" title="tag"></span>').join('');
  });
}

/* land the caret on the SITE where `subject` refers to `target` in the OPEN
   listing — the RELATION pane's rows (2026-09-09): a CALLEES row is a symbol
   the subject calls, an XREFS row a method that references the subject, and
   in both cases the place to look is the call inside the referencing method,
   not the callee's definition (which is what loadSymbol on the row's addr
   opens). The decompiled text already carries a navigable token for every
   invocation (classTokens), so the site is found client-side.
   WHERE: the subject method's line range when methodRanges knows it; a method
   with no range is a lambda's synthetic method (JEB maps the whole expression
   to ONE token bearing that method's addr — its lines ARE the body) or a body
   the decompiler folded ("[...]" — no site exists, false); a CLASS subject is
   the whole listing (any site in the class). Never a declaration line: JEB's
   classTokens name the declaration too, and that is the definition, the very
   thing this function exists to avoid.
   WHAT, in order: the exact addr; the owner-less `name(desc)ret` tail (an
   interface/virtual call's token carries the DECLARED target, the call graph's
   row the RESOLVED one); a method token by name (a callee is a method — a
   same-named FIELD must not win: X.00C has a field A03 and a method A03; an
   xref's subject may itself be a field, hence non-local for those); inside a
   lambda, the bare identifier within the host's own token; and, in a known
   range only, a bare identifier that is part of no token at all.
   The caret does NOT re-target the RELATION pane on landing (renderCaret's
   follow flag): the pane holds the subject whose row was clicked. */
export function gotoRefSite(subject: string | null, target: RelationRow): boolean {
  if (!S.caretReady || !S.codeLines.length) return false;
  const hostSym = subject ? String(subject).split('+')[0] : '';
  const hostIsMember = hostSym.includes('->') || /\.[a-z<][^.]*$/.test(hostSym);
  const m = subject ? methodFor(subject, S.javaMethods) : null;
  const declLines = new Set<number>();
  (S.javaMethods || []).forEach(function(mm){ if (mm.line) declLines.add(mm.line); });
  const symOf = function(t: CodeToken){ return t.el.getAttribute('data-sym') || ''; };
  const kindOf = function(t: CodeToken){ return t.el.getAttribute('data-kind') || ''; };
  const isNav = function(t: CodeToken){ return t.el.classList.contains('nav'); };
  /* the host's OWN token on a line methodRanges could not place: the
     declaration when the line reads `[modifiers] [type] name(params) {` —
     only words before the name, the opener after the parameter list — else a
     self-call (`foo(x);`, `if (foo(x)) {`, `return foo(x)`) or a lambda's
     expression-wide token (`list.map(x -> …`), whose line IS the body
     (fix-review relation r2 M3: an unranged self-recursive method's hull
     began at its declaration, and the exact-addr pass landed there) */
  const looksLikeDecl = function(L: CodeLine, t: CodeToken){
    if (/[(>]/.test(t.name)) return false;
    const pre = L.text.slice(0, t.start);
    if (!/^\s*(?:[\w$<>\[\],.?]+\s+)*$/.test(pre) || /\b(?:return|new|throw|else|case|yield)\s*$/.test(pre)) return false;
    return /\)\s*(?:throws\s+[\w$.,\s]+)?\{\s*$/.test(L.text);
  };
  let lo: number, hi: number;
  const ranged = !!(m && m.line);
  if (ranged){
    lo = m!.line!; hi = m!.endLine || m!.line!;
  } else if (hostIsMember && (S.javaMethods || []).length){
    /* no range: the lines that carry the host's own token (a lambda's
       expression), else the body is not in the listing at all. An engine
       that ranges NO method (the mock) has no lambdas to place this way:
       it falls through to the whole listing (fix-review relation r2 M5) */
    let first = -1, last = -1;
    S.codeLines.forEach(function(L, i){
      if (declLines.has(i + 1)) return;
      const own = L.tokens.find(function(t){ return isNav(t) && symOf(t) === hostSym; });
      if (!own) return;
      if (looksLikeDecl(L, own)){ declLines.add(i + 1); return; }
      if (first < 0) first = i;
      last = i;
    });
    if (first < 0) return false;
    lo = first + 1; hi = last + 1;
  } else {
    lo = 1; hi = S.codeLines.length;
  }
  const addr = String(target.addr || target.method || target.sig || '').split('+')[0];
  const tail = addr.includes('->') ? addr.slice(addr.indexOf('->') + 2) : '';
  const name = target.name || '';
  const methodOnly = target.kind === 'call' || /->[^:]*\(/.test(addr);
  const pick = function(test: (t: CodeToken) => boolean){
    for (let i = Math.max(0, lo - 1); i < hi && i < S.codeLines.length; i++){
      if (declLines.has(i + 1)) continue;
      const t = S.codeLines[i].tokens.find(test);
      if (t) return { i: i, t: t };
    }
    return null;
  };
  const hit = (addr ? pick(function(t){ return isNav(t) && symOf(t) === addr; }) : null)
    || (tail ? pick(function(t){ return isNav(t) && symOf(t).endsWith('->' + tail); }) : null)
    || (name ? pick(function(t){ return isNav(t) && t.name === name && kindOf(t) !== 'local' && (!methodOnly || kindOf(t) === 'method'); }) : null)
    || (name && hostSym ? pick(function(t){
         if (isNav(t) || t.name !== name) return false;
         const outer = t.el.closest<HTMLElement>('.nav');
         return !!outer && (outer.getAttribute('data-sym') || '') === hostSym;
       }) : null)
    /* a bare identifier that belongs to NO token — an identifier inside a
       local's token is that local, exactly what the method-token pass refused */
    || (name && ranged ? pick(function(t){ return !isNav(t) && t.name === name && !t.el.closest('.nav'); }) : null);
  if (!hit) return false;
  S.caret.line = hit.i;
  S.caret.col = hit.t.start;
  S.caret.goal = hit.t.start;
  cancelFollow();                 // a follow the row's click found already armed would re-target the held pane (r2 M1)
  renderCaret(true, false);
  S.codeLines[hit.i].cl.scrollIntoView({ block: 'center' });
  /* the row's click left focus on <body> (an .xrow is not focusable): the
     caret it landed must answer the arrow keys, as it does after a cross-class
     landing (loadSymbol's render focuses the pane) — r2 L13, decided */
  focusCenter();
  return true;
}

/* toggle the breakpoint of ONE method (F9 at the caret, a gutter click): an
   existing BP flips enabled/disabled, none adds one. The ack is the gutter
   glyph repainting — the RunStore is not the target's dirty state. */
async function toggleBpFor(symbol: string, name?: string): Promise<void> {
  const list = await tryOpT('runs.bp.list', {});   // the VIEWED target's list (fix-review r1 #2)
  if (list.error){ notice('breakpoints unavailable — ' + list.error.message, { err: true }); return; }
  const existing = (list.data || []).find(function(bp){ return bp.origSig === symbol || bp.addr === symbol; });
  const r = existing ? await tryOp('runs.bp.toggle', { id: existing.id })
                     : await tryOpT('runs.bp.add', { symbol: symbol });   // writes bind the viewed target (finding 3)
  /* a failed op is said, not swallowed; a BP placed off-screen (F9 with no
     method at the caret falls back to the class's first method) is named,
     because the glyph alone would be invisible (fix-review r1 M6) */
  if (r.error){ notice('breakpoint: ' + r.error.message, { err: true }); return; }
  if (name) notice((existing ? (existing.enabled === false ? 'breakpoint enabled on ' : 'breakpoint disabled on ') : 'breakpoint on ') + name);
  await loadMarks();
}

/* jump the caret to a line (bookmarks/flows/tags navigation) */
export function gotoLine(line: number): void {
  if (!S.caretReady || !S.codeLines.length) return;
  S.caret.line = Math.max(0, Math.min(S.codeLines.length - 1, line - 1));
  S.caret.col = 0;
  S.caret.goal = 0;
  renderCaret(true);
  S.codeLines[S.caret.line].cl.scrollIntoView({ block: 'center' });
}

/* ==================================================================
   SMALI PANE — live rows (offsets + opcode bytes), ⇄ sync band
================================================================== */
function smaliTextHtml(text: string, kind: string | undefined){
  if (kind === 'annotation')                        // retained metadata (MethodParameters, debug names): apart from the code, never a rename — its own ink, even for its # status row
    return '<span class="ann">' + esc(text) + '</span>';
  if (kind === 'annotation-warn')                   // the metadata's count mismatch / malformed row: a warning, not a faint comment
    return '<span class="annw">' + esc(text) + '</span>';
  if (kind === 'comment' || text.trim().startsWith('#'))
    return '<span class="c">' + esc(text) + '</span>';
  if (kind === 'label')
    return '<span class="lbl">' + esc(text) + '</span>';
  if (kind === 'case')                              // a switch payload's case line (its destination label) or an array element
    return '<span class="case">' + esc(text).replace(/(:l_[0-9a-f]+)/g, '<span class="lbl">$1</span>') + '</span>';
  if (kind === 'directive'){
    const m = text.match(/^(\s*)(\.\S+)([\s\S]*)$/);
    if (m) return esc(m[1]) + '<span class="d">' + esc(m[2]) + '</span>' + esc(m[3]);
  }
  if (kind === 'insn'){
    // order matters: color source strings/registers/labels FIRST (they see no
    // injected markup), mnemonic span LAST (its attribute quotes would otherwise
    // be re-matched by the string regex)
    let h = esc(text);
    h = h.replace(/(&quot;.*?&quot;)/g, '<span class="as">$1</span>');
    h = h.replace(/\b([vp]\d+)\b/g, '<span class="r">$1</span>');
    h = h.replace(/(:(?:l|pswitch_data|sswitch_data|array|try_start|try_end|catch|catchall)_[0-9a-f]+)/g, '<span class="lbl">$1</span>');
    h = h.replace(/^(\s*)([a-z][a-z0-9\-\/]*)/, '$1<span class="op">$2</span>');
    return h;
  }
  return esc(text);
}

async function loadSmali(symbol: string){
  const r = await tryOpT('code.smali', { symbol: symbol });   // viewed-target binding (review: desync)
  const host = document.getElementById('smali');
  if (!host) return;
  if (r.error){
    host.innerHTML = '<div class="sr"><span class="sg"></span><span class="off"></span><span class="byt"></span>' +
      '<span class="slc"><span class="c"># smali unavailable: ' + esc(r.error.message) + '</span></span></div>';
    return;
  }
  const rows = r.data.rows || [];
  // java→smali sync mapping (heuristic per M2): rows of a method block inherit the
  // java declaration line of the method with the same name (mock rows carry jline already)
  let curJ: number | null | undefined = null;
  host.innerHTML = '';
  rows.forEach(function(row){
    if (row.kind === 'directive' && /^\s*\.method/.test(row.text!)){
      curJ = null;
      const jm = (S.javaMethods || []).find(m => m.name === row.name && m.line);
      if (jm) curJ = jm.line;
    }
    if (/^\s*\.end method/.test(row.text || '')) { /* keep curJ for the .end row */ }
    const j = row.jline !== undefined ? row.jline : curJ;
    const div = document.createElement('div');
    div.className = 'sr';
    if (j != null) div.setAttribute('data-j', String(j));
    div.innerHTML = '<span class="sg"></span><span class="off">' + esc(row.offset || '') + '</span>' +
      '<span class="byt">' + esc(row.bytes || '') + '</span>' +
      '<span class="slc">' + smaliTextHtml(row.text || '', row.kind) + '</span>';
    host.appendChild(div);
    if (/^\s*\.end method/.test(row.text || '')) curJ = null;
  });
  hook('measureRender');
}

/* ==================================================================
   SYMBOL UNDER CARET → rename / comment / relation
================================================================== */
export function symbolAtCaret(): CaretHit | null {
  const L = S.codeLines[S.caret.line];
  if (L){
    let hit: CaretHit | null = null;
    L.tokens.forEach(function(t){
      if (S.caret.col >= t.start && S.caret.col <= t.end && t.el.classList.contains('nav')){
        const sym = t.el.getAttribute('data-sym');
        const kind = t.el.getAttribute('data-kind') || '';
        /* locals carry NO addr (backend classifyElement) — an empty data-sym
           used to drop them into the CLASS fallback below, so clicking a local
           loaded the CLASS's relations (and offered to rename the class).
           'this' is a local-kind token too — clicking it must not list every
           'this' in the method (GUI review finding 13) */
        if (sym || (kind === 'local' && t.name !== 'this'))
          hit = { name: t.name, symbol: sym, kind: kind, line: S.caret.line + 1 };
        /* a local (no addr of its own): the rename target is the ENCLOSING METHOD plus the
           local's current name — rename {symbol: method, local, name} (user 2026-09-19: no
           rename on locals) */
        if (hit && kind === 'local' && !sym){
          const m = methodAt(S.javaMethods, S.caret.line + 1);   // the ONE resolver (finding 11)
          if (m && (m.addr || m.signature)){ hit.symbol = String(m.addr || m.signature); hit.local = t.name; }
        }
      }
    });
    if (hit) return hit;
  }
  // fallback: the displayed class itself — JEB's document emits locations for
  // usages, not declarations, so the class/method decl lines carry no nav token
  if (S.currentSym && (S.currentSym.addr || S.currentSym.fqcn))
    return { name: S.currentSym.name, symbol: (S.currentSym.addr || S.currentSym.fqcn)!, kind: 'class' };
  return null;
}

/* the symbol a BREAKPOINT gesture means — F9 and the Runs view's ＋BP form
   (fix-review r1 #4: the form had dropped the caret): the METHOD under the
   caret, else the open class's first method that carries an addr. BPs are
   method-level, and S.currentSym is the CLASS on JEB — a class symbol can
   only ever answer "breakpoints are method-level". */
export function bpSymbolAtCaret(): CaretHit | null {
  let hit = symbolAtCaret();
  if (!hit || !String(hit.symbol).includes('->')){
    const m0 = (S.javaMethods || []).find(function(m){ return !!m.addr; });
    if (m0 && m0.addr) hit = { name: m0.name, symbol: m0.addr };
  }
  return hit;
}

/* ==================================================================
   HUMAN CO-PRESENCE — presence.ping
==================================================================
   The other half of the agent chip: an agent asking presence.whereis {who:human}
   gets a real answer, so co-presence reads both ways.

   Debounced on the TRAILING edge: a caret walk down a method fires caretFollow
   per line, and what the other side wants is where the reader STOPPED, not a
   keystroke log. Identical consecutive positions send nothing. A ping that fails
   is dropped silently — presence is never worth a dialog. */
const PING_MS = 2000;
let pingTimer = 0, pingPending: string | null = null, pingSent: string | null = null;

/* pins are project-scoped — a switch makes the last-sent addr meaningless */
export function resetPresencePing(): void {
  pingPending = null;
  pingSent = null;
  if (pingTimer){ clearTimeout(pingTimer); pingTimer = 0; }
}

export function pingPresence(addr: string | undefined): void {
  if (!addr) return;
  if (addr === pingSent){
    /* A→B→A: cancel the pending B ping AND its timer — an armed timer would fire
       the next distinct position early, under the debounce */
    pingPending = null;
    if (pingTimer){ clearTimeout(pingTimer); pingTimer = 0; }
    return;
  }
  pingPending = addr;
  if (pingTimer) return;                       // one in flight: it will take the latest
  pingTimer = setTimeout(function(){
    pingTimer = 0;
    const a = pingPending;
    pingPending = null;
    if (!a || a === pingSent) return;
    pingSent = a;
    /* a DECLINED ping (empty workspace → pinged:false) must not suppress the
       retry — otherwise the first symbol after a project opens never pins
       (r2b #7) */
    tryOp('presence.ping', { addr: a }).then(function(r){
      if (!r || !r.data || !r.data.pinged) pingSent = null;
    });
  }, PING_MS);
}

/* caret follow: RELATION re-queries for the symbol under the caret */
export function initCaretFollow(): void {
  S.hooks.caretFollow = function(){
    const hit = symbolAtCaret();
    if (hit){
      /* locals have no backend addr — their uses are computed client-side
         from the loaded document (relation.js renderLocalUsages) */
      if (hit.kind === 'local') renderLocalUsages(hit);
      else loadRelationFor(hit.symbol!, hit.name);
      if (hit.symbol) pingPresence(hit.symbol);          // symbol navigation moves the human's pin
    }
    /* the method the caret sits in: its retained MethodParameters, as a separate list in the
       inspector (ui/params.ts) — a read, nothing applied */
    const m = S.caretReady ? methodAt(S.javaMethods, S.caret.line + 1) : null;
    hook('refreshParams', m && m.addr ? m.addr : null);
  };
  /* what every write dialog does afterwards — the parameter mapping dialogs (ui/params.ts) share it */
  S.hooks.afterEdit = async function(){
    markDirty(true);
    await loadHistory();
    if (S.currentClass) await loadSymbol(S.currentClass);
  };
}

/* ==================================================================
   RENAME / COMMENT DIALOG
================================================================== */
function editDialog(kind: 'rename' | 'comment', hit: CaretHit){
  const modal = document.getElementById('editmodal')!;
  const title = document.getElementById('wbm-title')!;
  const symEl = document.getElementById('wbm-sym')!;
  const input = document.getElementById('wbm-input') as HTMLInputElement;
  const err = document.getElementById('wbm-err')!;
  const ok = document.getElementById('wbm-ok') as HTMLButtonElement;
  const cancel = document.getElementById('wbm-cancel')!;
  title.textContent = hit.local ? 'RENAME LOCAL' : kind.toUpperCase();
  symEl.textContent = hit.local ? hit.local + ' in ' + hit.symbol : hit.symbol;
  input.value = kind === 'rename' ? hit.name! : '';
  err.textContent = '';
  modal.hidden = false;
  input.focus();
  input.select();
  /* a COMMENT edits the existing comment: the dialog used to open empty, so
     the old text could only be overwritten blind (UI audit 2026-09-09). The
     hover model carries it; a late answer must not clobber typing. */
  if (kind === 'comment' && hit.symbol){
    const forSym = hit.symbol;   // a late answer for ANOTHER symbol's dialog must not land here (fix-review r1 H2)
    tryOpT('code.hover', { symbol: forSym, offset: 0 }).then(function(h){
      const cur = h.data && (h.data as { comment?: string }).comment;
      if (cur && !modal.hidden && !input.value && symEl.textContent === forSym){ input.value = String(cur); input.select(); }
    });
  }

  /* N11: Esc closes the dialog wherever focus sits (input, APPLY, CANCEL, or
     outside it) — the central dispatcher owns it at modal priority. */
  const esc = pushEscape(function(): boolean | void { if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = cancel.onclick = input.onkeydown = null;
    popEscape(esc);
    document.getElementById('code')!.focus({ preventScroll: true });
  }
  async function apply(){
    const val = input.value;
    ok.disabled = true;
    const tidAtCall = S.currentTid;   // the target this write belongs to: a switch while it is in flight leaves the other target's view alone
    /* WRITES bind the viewed target too (GUI review finding 3): under a
       yanked server cursor a target-less rename/comment lands in the WRONG
       apk — silently, when the symbol exists in both */
    const r = await tryOpT(kind, kind === 'rename'
      ? (hit.local ? { symbol: hit.symbol, local: hit.local, name: val } : { symbol: hit.symbol, name: val })
      : { symbol: hit.symbol, text: val });
    ok.disabled = false;
    if (r.error){ err.textContent = explainError(r.error); return; }
    close();
    if (tidAtCall !== S.currentTid) return;   // the viewed target changed while the write was in flight: its own state shows when the user returns to it
    markDirty(true);
    await loadHistory();
    /* M7: renaming the open CLASS retitles + re-keys its tab (every tab and tree row of the class
       follows — the open one or not, user 2026-09-13), and the ‹ › history. Mock engines omit the
       top-level kind (it's on symbol.kind) — read both. The identity follows FIRST, then the reload
       asks for the class by its key (followIdentityChange — the original signature when known). */
    const isClassRename = kind === 'rename' && !hit.local && r.data
        && (r.data.kind || (r.data.symbol && r.data.symbol.kind)) === 'class';
    if (isClassRename) followIdentityChange(r.data as EditResult, String(hit.symbol || ''), tidAtCall);
    if (S.currentClass){
      try { await loadSymbol(S.currentClass); }
      catch (e){ notice('applied and recorded (HISTORY) — the view could not be refreshed: ' + String((e as Error).message || e) + '; reopen the class', { err: true }); }
    }
  }
  ok.onclick = apply;
  cancel.onclick = close;
  input.onkeydown = function(e){
    if (e.key === 'Enter'){ e.preventDefault(); apply(); }
  };
}

/* explicit-symbol entry to the dialog for the context menu (P0.2): the
   tree's Rename names the CLASS the row was opened on, not whatever token
   the parked caret happens to sit on once the class is open. Same guard as
   openRename — no symbol, no dialog. */
export function editSymbol(kind: 'rename' | 'comment', hit: CaretHit | null | undefined): void {
  if (hit && hit.symbol) editDialog(kind, hit);
}

/* ==================================================================
   DIRTY STATE + SAVE (⌘S)
================================================================== */
export function markDirty(on: boolean, state?: WorkspaceState): void {
  const b = document.getElementById('save-btn');
  if (!b) return;
  b.classList.toggle('dirty', on);
  const led = b.querySelector('.led');
  if (led) led.className = 'led ' + (on ? 'o' : 'g');
  b.setAttribute('data-tip', on ? 'Save active target — ⌘S · unsaved edits' : 'Save active target — ⌘S');
  /* the single client-side dirty choke: every edit and every save comes through
     here, so the ribbon's UNSAVED count re-reads the backend from one place. It
     re-COUNTS rather than trusting `on` — the count is of targets, and this call
     only knows about the active one. (A caller holding a fresh workspace.state
     passes it in rather than paying for a second fetch.) */
  refreshUnsaved(state);
}

function fmtBytes(n: number): string {
  if (!(n > 0)) return '0 B';
  if (n >= 1e9) return (n / 1e9).toFixed(2) + ' GB';
  if (n >= 1e6) return (n / 1e6).toFixed(1) + ' MB';
  if (n >= 1e3) return (n / 1e3).toFixed(0) + ' KB';
  return n + ' B';
}

let saving = false;
/* Save = a modal SAVING box for the whole write (the 1.27 GB WhatsApp jdb2 takes ~37 s and the
   button gave no sign at all — user, 2026-09-13): the workbench is covered, Esc is swallowed, the
   bar is real (target.saveStatus: JEB rewrites the file in place, bytes written vs the size before
   the write), the box reports bytes + seconds on success and stays up with the reason on failure.
   A save also clears the tabs' dot (renamed, not saved yet). */
export async function saveTarget(): Promise<void>{
  if (saving) return;
  saving = true;
  const b = document.getElementById('save-btn');
  const modal = document.getElementById('savemodal')!;
  const bar = document.getElementById('sv-bar')!, fill = document.getElementById('sv-fill')!;
  const msg = document.getElementById('sv-msg')!, bytesEl = document.getElementById('sv-bytes')!, timeEl = document.getElementById('sv-time')!;
  const row = document.getElementById('sv-row')!, closeBtn = document.getElementById('sv-close') as HTMLButtonElement;
  document.getElementById('sv-title')!.textContent = 'SAVING ' + activeTargetName();
  msg.textContent = 'writing the project database — the workbench waits for the write';
  bar.className = 'sv-bar indet'; fill.style.width = ''; bytesEl.textContent = ''; timeEl.textContent = '0.0 s'; row.hidden = true;   // no inline width in the indeterminate mode: it would override the sliding segment (the box looked frozen, 2026-09-13)
  modal.hidden = false;
  let closable = false;
  let resolveClose: (() => void) | null = null;
  const esc = pushEscape(function(){ if (closable && resolveClose) resolveClose(); return true; }, ESC.modal);   // swallowed while the write runs
  const t0 = Date.now();
  let polling = true;
  (async function(){
    while (polling){
      const st = await tryOp('target.saveStatus', {});
      if (!polling) break;
      timeEl.textContent = ((Date.now() - t0) / 1000).toFixed(1) + ' s';
      if (st.error){
        /* a server without the status op (an older build still running): elapsed time only */
        msg.textContent = 'writing the project database — this server gives no progress figures, only the elapsed time';
        polling = false;
        break;
      }
      const d = (st.data || {}) as { saving?: boolean; bytesNow?: number; bytesExpected?: number };
      if (d.saving && d.bytesExpected && d.bytesExpected > 0){
        const pct = Math.min(99, Math.round(100 * (d.bytesNow || 0) / d.bytesExpected));
        bar.className = 'sv-bar'; fill.style.width = pct + '%';
        bytesEl.textContent = fmtBytes(d.bytesNow || 0) + ' of ~' + fmtBytes(d.bytesExpected) + ' · ' + pct + '%';
      }
      await new Promise(function(r){ setTimeout(r, 150); });
    }
  })();
  const ticker = window.setInterval(function(){ timeEl.textContent = ((Date.now() - t0) / 1000).toFixed(1) + ' s'; }, 200);   // the clock runs even when the status poll stopped
  const r = await tryOp('target.save', {});   // the VIEWED target, like every other write (review 2026-09-09 MED-17)
  polling = false;
  window.clearInterval(ticker);
  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  timeEl.textContent = secs + ' s';
  if (r.error){
    bar.className = 'sv-bar err';
    msg.textContent = 'save FAILED — ' + r.error.message;
    bytesEl.textContent = '';
    row.hidden = false;
    closable = true;
    await new Promise<void>(function(res){ resolveClose = res; closeBtn.onclick = function(){ res(); }; });
    popEscape(esc);
    modal.hidden = true;
    saving = false;
    if (b){ b.style.outline = '1px solid var(--verm)'; setTimeout(function(){ b.style.outline = ''; }, 600); }
    console.warn('target.save failed', r.error);
    return;
  }
  const bytes = Number(((r.data || {}) as { bytes?: number }).bytes || 0);
  bar.className = 'sv-bar ok';
  msg.textContent = 'saved';
  bytesEl.textContent = bytes ? fmtBytes(bytes) + ' written' : '';
  markDirty(false);
  let dots = false;
  for (const t of S.openTabs) if (t.renamed){ t.renamed = false; dots = true; }
  if (dots) hook('renderTabs');
  await new Promise(function(res){ setTimeout(res, 700); });
  popEscape(esc);
  modal.hidden = true;
  saving = false;
  if (b){ b.style.outline = '1px solid var(--grn)'; setTimeout(function(){ b.style.outline = ''; }, 600); }
}

/* OUTLINE dock panel (spec backlog, WORKBENCH_SPEC §5): the open class's methods and fields,
   each a jump. Follows the class (called from the render path) and carries a dock-tab count. */
let outlineSeq = 0;
async function renderOutline(): Promise<void> {
  const host = document.getElementById('outline-rows');
  const head = document.getElementById('outline-head');
  if (!host) return;
  const my = ++outlineSeq;   // two fast navigations must not paint out of order (b2 trio review M1); bump BEFORE any return
  const setHead = function(name: string, meta: string){ if (head) head.innerHTML = esc(name) + ' <span class="meta">' + esc(meta) + '</span>'; };
  if (!S.currentClass){ host.innerHTML = ''; setHead('OUTLINE', 'open a class to see its outline'); return; }
  /* the RESOLVED class, never the raw jump handle: S.currentClass is loadSymbol's argument and is
     often a METHOD sig (a jump via a RELATION row, a search hit, F12/⌘-click on a member). code.outline
     wants the class — classinfo already resolves it this way (GUI-review finding 14). */
  const cls = (S.currentSym && (S.currentSym.fqcn || S.currentSym.owner || S.currentSym.addr)) || S.currentClass;
  const r = await tryOpT('code.outline', { class: cls });
  if (my !== outlineSeq) return;   // a newer navigation superseded this fetch
  if (r.error){ host.innerHTML = ''; setHead('OUTLINE', 'unavailable — ' + r.error.message); return; }
  const d = (r.data || {}) as { symbol?: { name?: string }; methods?: { name: string; addr: string; native?: boolean }[]; fields?: { name: string; addr: string }[] };
  const methods = d.methods || [], fields = d.fields || [];
  setHead(String(d.symbol?.name || S.currentClass), methods.length + ' methods · ' + fields.length + ' fields');
  const frag = document.createDocumentFragment();
  const mkRow = function(name: string, addr: string, cls: string, tag: string){
    const el = document.createElement('div');
    el.className = 'olrow ' + cls;
    el.innerHTML = '<span class="oln">' + esc(name) + '</span>' + (tag ? '<span class="oltag">' + tag + '</span>' : '');
    el.title = addr;
    el.addEventListener('click', function(){ loadSymbol(addr).catch(function(){}); });
    return el;
  };
  methods.forEach(m => frag.appendChild(mkRow(m.name, m.addr, 'olm', m.native ? 'native' : '')));
  fields.forEach(f => frag.appendChild(mkRow(f.name, f.addr, 'olf', '')));
  host.innerHTML = '';
  host.appendChild(frag);
  document.querySelector('.panel[data-panel="outline"]')?.setAttribute('data-count', String(methods.length + fields.length));
  syncTabs();                                     // reflect the new count on the dock tab
}

/* wire navbar save + global hooks */
export function initCode(): void {
  S.hooks.showOutline = async function(){
    showDock();
    activatePanel('outline');   // fires panelShown → renderOutline (no double render)
  };
  /* a tab-click that reveals the OUTLINE panel refreshes it to the current class (trio review M1:
     activatePanel toggles .active without rendering, so re-activation showed a stale class) */
  S.hooks.panelShown = function(id: string){ if (id === 'outline') renderOutline(); };
  document.getElementById('save-btn')!.addEventListener('click', saveTarget);
  S.hooks.openRename = function(){
    const hit = symbolAtCaret();
    /* no symbol → no dialog; a local comes with its enclosing method as the symbol (symbolAtCaret)
       and opens the RENAME LOCAL form of the same dialog */
    if (hit && hit.symbol) editDialog('rename', hit);
  };
  S.hooks.openComment = function(){
    const hit = symbolAtCaret();
    if (hit && hit.symbol) editDialog('comment', hit);
  };
  /* right-click on a local → "Name all locals after their types": the generated locals of the
     method under the caret take their declared types' names (DownloadHelper v5 → downloadHelper;
     a second one downloadHelper_2). locals.auto records one History entry per local (⌘Z undoes
     them one by one) and a binding, so a later rename of the class renames the local again. No
     shortcut (user 2026-09-19). */
  S.hooks.autoNameLocals = async function(){
    const m = methodAt(S.javaMethods, S.caret.line + 1);
    const hit = symbolAtCaret();
    const method = m && (m.addr || m.signature) ? String(m.addr || m.signature)
        : hit && hit.symbol && String(hit.symbol).includes('->') ? String(hit.symbol) : null;
    if (!method){ notice('right-click a local inside a method to name its locals after their types'); return; }
    const r = await tryOpT('locals.auto', { symbol: method });
    if (r.error){ notice('locals not named — ' + explainError(r.error), { err: true }); return; }
    const renames: { local: string; name: string }[] = ((r.data || {}) as { renames?: { local: string; name: string }[] }).renames || [];
    if (!renames.length){ notice('no generated locals with a class type in ' + (m ? m.name : method) + ' — nothing to name'); return; }
    notice('named ' + renames.length + ' local' + (renames.length > 1 ? 's' : '') + ': ' + renames.slice(0, 6).map(function(x){ return x.local + ' → ' + x.name; }).join(', ') + (renames.length > 6 ? ', …' : '') + ' · ⌘Z undoes one at a time');
    markDirty(true);
    await loadHistory();
    const keepLine = S.caret.line;
    try { await loadSymbol(S.currentClass!); } catch (e) { /* the re-render is best effort */ }
    if (keepLine >= 0) gotoLine(keepLine + 1);
  };
  S.hooks.saveTarget = saveTarget;
  /* go-to-definition (F12): resolve the symbol under the caret and delegate to navigation's
     gotoDef (the resolve + owner-class fallback the dbl-click already uses) — WORKBENCH_SPEC §5/§7.
     ⌘-click is the mouse twin, handled in the #code click listener below. */
  S.hooks.gotoDefAtCaret = async function(){
    const hit = symbolAtCaret();
    if (!hit || !hit.symbol){ notice('no symbol at the caret to jump to'); return; }
    if (S.hooks.gotoDef) await S.hooks.gotoDef(String(hit.symbol));
    else try { await loadSymbol(String(hit.symbol)); } catch { /* jump card showed why */ }
  };
  /* implementations (I): the concrete overrides of the method/type under the caret, listed in
     the dock like RELATION's "open all" (the `impls` engine op) */
  S.hooks.implementations = async function(){
    const hit = symbolAtCaret();
    if (!hit || !hit.symbol){ notice('no symbol at the caret'); return; }
    const r = await tryOpT('impls', { symbol: String(hit.symbol) });
    if (r.error){ notice('implementations: ' + r.error.message, { err: true }); return; }
    const rows = (r.data || []) as { addr?: string; method?: string; sig?: string; label?: string }[];
    /* the jump handle is addr OR method OR sig — the same widening RELATION's impl rows use;
       a name-only row (some engines) is not navigable (trio review L1) */
    const jumps = rows.map(x => x.addr || x.method || x.sig).filter(Boolean) as string[];
    if (!jumps.length){ notice('no implementations of ' + (hit.name || hit.symbol)); return; }
    await loadSymbol(jumps[0]);
    if (jumps.length > 1) notice(jumps.length + ' implementations — landed on the first');
  };
  // QA 13: F9 — toggle a breakpoint on the symbol under the caret
  S.hooks.toggleBp = async function(){
    const hit = bpSymbolAtCaret();
    if (!hit || !hit.symbol) return;
    const atCaret = symbolAtCaret();
    const fellBack = !atCaret || atCaret.symbol !== hit.symbol;   // the class's first method, not the caret's line
    await toggleBpFor(String(hit.symbol), fellBack ? (hit.name || String(hit.symbol)) : undefined);
  };
  /* the gutter is the universal breakpoint gesture: a click on a line's
     margin toggles the BP of the method declared around it (UI audit
     2026-09-09: the gutter was cursor:pointer with no handler, and F9's only
     ack was a pulse on the SAVE button — a breakpoint is not a save) */
  document.getElementById('code')!.addEventListener('click', function(e){
    /* ⌘/ctrl-click a nav token = go to its definition (WORKBENCH_SPEC §7), the mouse twin of F12 */
    if (e.metaKey || e.ctrlKey){
      const nav = (e.target as HTMLElement).closest('.nav') as HTMLElement | null;
      const sym = nav && nav.getAttribute('data-sym');
      /* delegate to navigation's gotoDef (owner-class fallback) so ⌘-click is the true twin of
         F12 — raw loadSymbol failed silently on a member handle that only its class decompiles
         (trio review L3) */
      if (sym){ e.preventDefault(); if (S.hooks.gotoDef) S.hooks.gotoDef(sym).catch(function(){}); else loadSymbol(sym).catch(function(){}); return; }
    }
    const gm = (e.target as HTMLElement).closest('.gm');
    if (!gm) return;
    const row = gm.closest<HTMLElement>('.cl');
    if (!row) return;
    const idx = $all('#code .cl').indexOf(row);
    const m = idx >= 0 ? methodAt(S.javaMethods, idx + 1) : null;
    if (m && m.addr) toggleBpFor(m.addr);
  });
  S.hooks.reloadCode = async function(){
    if (S.currentClass) await loadSymbol(S.currentClass);
    refreshRelationTree();                     // HISTORY undo/redo: the RELATION tree follows too
  };
  S.hooks.gotoLine = gotoLine;          // nav history + search open-hit land on lines
  S.hooks.marksChanged = loadMarks;
  // COV: cycle the recorded runs (runs.coverage for the open class follows) — 'c' key in the code view
  S.hooks.covCycle = async function(){
    const r = await tryOp('runs.runs', S.currentTid ? { target: S.currentTid } : {});   // cycle the viewed target's runs (r4 M2)
    const ids = (r.data || []).map(x => x.execId);
    if (!ids.length) return;
    const i = ids.indexOf(S.covRun!);
    S.covRun = ids[(i + 1) % ids.length];
    updateDockScope();      // dock scope is the covRun readout (the COV chip is gone)
    loadMarks();
  };
  initCaretFollow();
}
