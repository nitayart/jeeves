// views/agents.ts — AGENTS VIEW live (AGENTS_SPEC §3): mission cards from
// agent.missions (spawn/pause/resume/stop), activity stream from agent.activity
// + SSE, review queue from agent.proposals, rail inspector with agent.review.
import { $all, S, pushEscape, popEscape, ESC, escHtml as esc, isHexColor, methodAt } from '../ui/hub.js';
import { subjectAtCaret, dottedClassOf } from '../ui/reverse.js';
import { tryOp, onEvent } from '../api.js';
import { showCenter, notice } from '../ui/chrome.js';
import { refreshRelationTree } from '../ui/relation.js';
import { loadSymbol } from './code.js';
import type { ActivityRow, DecompileData, Mission, OpParams, Proposal, Provider, TagList, TurnCall, TurnRecord, TypeEntry, TypeRegistry } from '../types.js';
import type { TryResult } from '../api.js';

let wired = false;
let propSel = -1;
let propCache: Proposal[] = [];
let feedFilterMode = 'all';
const AGENT_COLORS = ['#CC79A7', '#0072B2', '#D55E00', '#009E73', '#E69F00'];

function agentColor(agentId: string | undefined){
  let h = 0;
  for (const c of String(agentId)) h = (h * 31 + c.charCodeAt(0)) & 0xFFFF;
  return AGENT_COLORS[h % AGENT_COLORS.length];
}
function fmtTs(ts: string | number | undefined){
  const d = new Date(ts!);
  if (isNaN(d.getTime())) return ts || '';
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  return p(d.getUTCHours()) + ':' + p(d.getUTCMinutes()) + ':' + p(d.getUTCSeconds()) + 'Z';
}

/* ==================================================================
   MISSIONS column
================================================================== */
/* rail badge + tooltip + footer AGT: real counts. Live from boot and over
   SSE even while the Agents view is closed (the badge used to be a mockup
   "2" fixture that only cleared once the view was opened).

   ONE fetch path per endpoint with ONE request counter — the only scheme
   that can't race itself: every agent.missions fetch goes through
   fetchMissions(); newest request wins; an errored newest request releases
   its claim so an older good response may still paint. */
let missionsSeq = 0;
/** fetchMissions' answer: {list} fresh · {error} failed · null stale */
type MissionsRes = { list: Mission[]; error?: undefined } | { error: true; list?: undefined } | null;
/* The ONLY badge/column agent.missions fetch (read-only consumers use
   fetchMissionsPassive — they must never hold this claim). Result:
   {list} fresh · {error} failed (claim released so an older good response
   may still paint) · null (stale). */
let missionsLast: Mission[] = [];      // the last list fetched — the EDITS header names the mission writing now
async function fetchMissions(): Promise<MissionsRes>{
  const my = ++missionsSeq;
  const r = await tryOp('agent.missions', {});
  if (!r.error && Array.isArray(r.data)) missionsLast = r.data as Mission[];
  if (r.error){ if (my === missionsSeq) missionsSeq--; return { error: true }; }
  if (my !== missionsSeq) return null;
  return { list: r.data || [] };
}

/* No claim, no ordering — for read-only consumers with their own seq guard
   (the summary panel). Holding missionsSeq here would let a panel read
   starve the badge/column writers. */
async function fetchMissionsPassive(): Promise<MissionsRes>{
  const r = await tryOp('agent.missions', {});
  if (r.error) return { error: true };
  return { list: r.data || [] };
}

let agtPainted = false, propsPainted = false;   /* set only by REAL painted values — drive the hello skip */
let missionsPainted = false, propsViewPainted = false;   /* the COLUMNS have shown truth at least once */
let inspPropId: string | null = null;           /* the proposal the rail inspector currently shows */
let railMode = 'summary';                       /* 'proposal' | 'mission' | 'summary' — background reloads may only touch the rail in proposal mode */
let bootPending = 0, bootFailed = 0;            /* boot fetch progress — see the hello gate */
let helloSeen = false;

/* badge = non-terminal missions ("agents up"): a denylist (not done/stopped)
   so a future backend state fails VISIBLE rather than hiding silently.
   Tooltip keeps the per-state breakdown. */
function paintBadge(missions: Mission[]){
  const by: Record<string, number> = {};
  missions.forEach(function(m){ by[m.state] = (by[m.state] || 0) + 1; });
  /* the badge counts what is ACTUALLY running (running + queued) — a mission parked at its cap,
     blocked, or interrupted by a restart is not "2 running" (user 2026-09-20); the tip lists those */
  const up = (by.running || 0) + (by.queued || 0);
  const badge = document.querySelector<HTMLElement>('#rail-agents .rbadge');
  if (badge){
    badge.textContent = String(up);
    badge.style.display = up ? '' : 'none';
  }
  let tip = 'Agents — ⌃5 · ' + (by.running || 0) + ' running';
  if (by.queued)  tip += ' · ' + by.queued + ' queued';
  if (by.paused)  tip += ' · ' + by.paused + ' paused at a cap';
  if (by.blocked) tip += ' · ' + by.blocked + ' blocked';
  if (by.interrupted) tip += ' · ' + by.interrupted + ' interrupted by a restart';
  const railBtn = document.getElementById('rail-agents');
  if (railBtn) railBtn.setAttribute('data-tip', tip + ' · project');
  // D14: the footer AGT readout is live (was a dead fixture)
  const agt = document.getElementById('agt-n');
  if (agt) agt.textContent = String(missions.length);
  agtPainted = true;
}

/* return: true painted · false failed · null stale (superseded — NOT a failure) */
let recovering = false;
function withTimeout<T>(p: Promise<T>, ms: number): Promise<T | false>{   /* recovery fetches must not hang the ladder on a hung endpoint */
  let t = 0;
  const timeout = new Promise<false>(function(r){ t = setTimeout(function(){ r(false); }, ms); });
  return Promise.race([p, timeout]).finally(function(){ clearTimeout(t); });
}
/* Bounded backoff repaints until truth is on screen (3s/10s then every 30s):
   a one-shot retry is not a recovery path for outages with duration. While
   the view is OPEN the rung drives the column loaders (they own the surface).
   'Truth on screen' includes the open view's columns — a '— retrying…' hint
   with a dead ladder is its own lie (review finding 4). */
function allPainted(){
  return agtPainted && propsPainted && (S.center !== 'agents' || (missionsPainted && propsViewPainted));
}
async function bootRecovery(){
  if (recovering) return;
  recovering = true;
  try{
    const delays = [3000, 10000, 30000];
    for (let i = 0; !allPainted(); i++){
      await new Promise(function(r){ setTimeout(r, delays[Math.min(i, delays.length - 1)]); });
      if (allPainted()) return;
      if (S.center === 'agents'){
        loadMissions(); loadActivity(); loadProposals();
        if (feedFilterMode !== 'all') updatePropsCount();
        continue;
      }
      const res = await Promise.all([withTimeout(updateAgentBadge(), 8000), withTimeout(updatePropsCount(), 8000)]);
      if (res[0] === true && res[1] === true) return;
      if (res[0] !== false && res[1] !== false){   /* stale (superseded) — a newer paint is coming; give it a beat */
        await new Promise(function(r){ setTimeout(r, 150); });
        if (allPainted()) return;
      }
    }
  } finally { recovering = false; }
}

/* return: true painted · false failed · null stale (superseded — NOT a failure) */
async function updateAgentBadge(): Promise<boolean | null>{
  const res = await fetchMissions();
  if (res && res.list){ paintBadge(res.list); return true; }
  return res ? false : null;
}

let propsSeq = 0;
/* props-n is PROJECT-scope — its only fetch writer (loadProposals is
   mission-filter-scoped and must not touch it; it owns prop-pending). */
async function updatePropsCount(): Promise<boolean | null>{
  const my = ++propsSeq;
  const r = await tryOp('agent.proposals', {});
  if (r.error){
    if ((r.error as { code?: string }).code === 'E_NOTFOUND'){   // no project open: a legal state (b2-r1 M5)
      const pn0 = document.getElementById('props-n');
      if (pn0 && my === propsSeq) pn0.textContent = '—';
      propsPainted = true;
      return true;
    }
    if (my === propsSeq) propsSeq--; return false;
  }
  if (my !== propsSeq) return null;
  const pn = document.getElementById('props-n');
  if (pn) pn.textContent = String((r.data || []).length);
  propsPainted = true;
  return true;
}

async function loadMissions(){
  const res = await fetchMissions();
  const host = document.querySelector('.ag-missions');
  if (!host) return;                 /* panel popped out by a layout — same rule as initDevice's guard */
  if (!res || res.error){              /* keep the last good column — never blank it on a hiccup */
    /* …unless there IS no last good: a never-painted column says so via an
       APPENDED hint — an innerHTML here would delete the NEW MISSION spawn
       card and its listeners for the page's lifetime (review F1), and firing
       on a settled-empty state would lie about reachability (review F7). */
    if (res && res.error && !missionsPainted){
      if (!host.querySelector('.unreach-hint')){
        const hint = document.createElement('div');
        hint.className = 'dockhint unreach-hint';
        hint.textContent = 'backend unreachable — retrying…';
        host.appendChild(hint);
      }
      bootRecovery();   /* the hint says retrying — make it true (self-latching) */
    }
    return;
  }
  const missions = res.list;
  missionsPainted = true;
  const staleHint = host.querySelector('.unreach-hint');
  if (staleHint) staleHint.remove();
  host.querySelectorAll('.mcard[data-live]').forEach(c => c.remove());
  paintBadge(missions);
  const spawn = document.getElementById('ag-spawncard');
  /* dismissed runs leave the view (and the feed) unless the header's "N hidden" is on */
  dismissedAgents = new Set(missions.filter(m => m.dismissed && m.agentId).map(m => m.agentId));
  const nHidden = missions.filter(m => m.dismissed).length;
  const tog = document.getElementById('ag-dismissed-toggle');
  if (tog){ tog.hidden = nHidden === 0; tog.textContent = showDismissed ? 'hide ' + nHidden + ' dismissed' : nHidden + ' hidden'; tog.classList.toggle('on', showDismissed); }
  $all<HTMLElement>('#ag-feed .arow').forEach(function(r){ r.hidden = !showDismissed && dismissedAgents.has(r.getAttribute('data-agent') || ''); });
  missions.forEach(function(m){
    if (m.dismissed && !showDismissed) return;
    const card = document.createElement('div');
    card.className = 'mcard' + (m.dismissed ? ' dismissed' : '');   /* state shows on the .mstatus pill; .mcard.run has no rule */
    card.setAttribute('data-live', '1');
    card.setAttribute('data-agent', m.agentId);
    const budgetPct = Math.min(100, Math.round((m.tokensUsed || 0) / (m.budget || 1) * 100));
    card.innerHTML =
      '<div class="mc-top"><span class="mstatus' + (({ running:' run', paused:' paused', done:' done', blocked:' blocked', queued:' queued', stopped:' stopped', interrupted:' interrupted' } as Record<string, string>)[m.state] || '') + '">'
        /* STOPPING while a stopped run's last request may still answer */
        + esc(m.displayState === 'draining' ? 'stopping' : m.state).toUpperCase() + '</span>' +   /* unmapped state = unstyled chip, fails visible (never calm-blue 'done') */
      '<span class="mmodel">' + esc(m.providerLabel || m.model) + (m.effort ? ' · ' + esc(m.effort) : '') +
      (m.local ? ' <span class="vchip pv">LOCAL</span>' : '') +
      (m.depth ? ' <span class="vchip depth" title="reversing depth — quick: the subject alone · deep: its call tree">' + esc(m.depth).toUpperCase() + '</span>' : '') +
      '</span>' +
      '<span class="mctrl">' +
      (m.state === 'running' ? '<button data-act="pause">⏸</button>' : '') +
      (m.state === 'paused' ? '<button data-act="resume">▶</button>' : '') +
      (m.state === 'running' || m.state === 'paused' ? '<button data-act="stop">⏹</button>' : '') +
      (m.state !== 'running' && m.state !== 'queued' && (m.writes || 0) > 0 ? '<button data-act="revert" class="revert" data-tip="undo the ' + m.writes + ' rename(s) and comment(s) this mission wrote, newest first — each stays redoable from History">↶ ' + m.writes + '</button>' : '') +
      (isTerminal(m) && !m.dismissed ? '<button data-act="dismiss" class="dismiss" data-tip="remove this run from the view — its record stays in the project (the header\'s hidden count brings it back)">×</button>' : '') +
      (m.dismissed ? '<button data-act="undismiss" data-tip="bring this run back into the view">↶</button>' : '') +
      '</span></div>' +
      '<div class="mc-goal">' + esc(m.goal) + '</div>' +
      /* the meter is for a run in flight; a finished run states what it spent (a bar parked at 78% reads as "still going") */
      (m.budgetUnlimited
        ? '<div class="mc-line mc-spent">' + (m.inputTokens || 0).toLocaleString('en-US') + ' input · ' + (m.outputTokens || 0).toLocaleString('en-US') + ' output tokens · no mission budget limit</div>'
        : isTerminal(m)
        ? '<div class="mc-line mc-spent">' + (m.tokensUsed || 0).toLocaleString('en-US') + ' of ' + (m.budget || 0).toLocaleString('en-US') + ' tokens</div>'
        : '<div class="mc-line"><span class="mbar' + (budgetPct >= 90 ? ' hot' : '') + '"><i style="width:' + budgetPct + '%"></i></span><span>' + budgetPct + '%</span></div>') +
      '<div><span class="mc-scope">' + esc(m.scope || 'no write-scope') + '</span></div>' +
      (pausedAtBudget(m) ? '<div class="mc-need">paused at its token budget (the mission\'s own ceiling — not provider credit) · next turn needs ≥ ' + esc(String(m.budgetNeeded)) + '</div>' +
        '<div class="mc-raise"><input type="number" class="mc-raise-in" min="1000" step="1000" value="' + esc(String(m.budgetNeeded)) + '" spellcheck="false" data-tip="new token ceiling — this mission only">' +
        '<button data-act="raise" data-tip="raise THIS mission\'s ceiling to the amount and resume — future missions unchanged">↑ RAISE THIS MISSION &amp; RESUME</button>' +
        (m.depth ? '<button data-act="raise-default" data-tip="make the amount the default token budget for future ' + esc(m.depth) + ' missions (⚡ REVERSING AGENT settings) — this mission is not changed">SET AS ' + esc(m.depth).toUpperCase() + ' DEFAULT</button>' : '') + '</div>' : '') +
      (pausedAtTurns(m) ? '<div class="mc-need">paused at its turn limit (' + esc(String(m.turnsDone || 0)) + '/' + esc(String(m.turnCap || 0)) + ' turns — the mission\'s own cap) · suggested ' + esc(String(m.turnsNeeded)) + '</div>' +
        '<div class="mc-raise"><input type="number" class="mc-raise-turns" min="1" step="1" value="' + esc(String(m.turnsNeeded)) + '" spellcheck="false" data-tip="new turn cap — this mission only">' +
        '<button data-act="raise-turns" data-tip="raise THIS mission\'s turn cap to the amount and resume — future missions unchanged">↑ RAISE THIS MISSION &amp; RESUME</button>' +
        (m.depth ? '<button data-act="raise-turns-default" data-tip="make the amount the default turn cap for future ' + esc(m.depth) + ' missions (⚡ REVERSING AGENT settings) — this mission is not changed">SET AS ' + esc(m.depth).toUpperCase() + ' DEFAULT</button>' : '') + '</div>' : '');
    /* P0.3: a BLOCKED card says WHY. Mission.reason is the blocked event's
       text (AgentManager.act captures it at the one choke point); the
       activity row alone scrolls off the feed and hides under another
       mission's filter. One vermillion line under the goal, ellipsis, the
       full text in title= (the QA 25 ellipsis-tooltip idiom). Gated on the
       STATE, not the field: a write-scope violation emits a blocked event on
       a mission that keeps running — that reason belongs to the stream, not
       to a RUNNING card. DOM, not the innerHTML string: the text also lands
       in an attribute (title=), and a textContent/title write needs no
       escaping at all. */
    if (m.state === 'blocked' && m.reason){
      const why = document.createElement('div');
      why.className = 'mc-reason';
      why.textContent = m.reason;
      why.title = m.reason;
      card.querySelector('.mc-goal')!.after(why);
    }
    card.addEventListener('click', async function(e){
      const act = (e.target as HTMLElement).closest('[data-act]');
      if (act){
        const op = act.getAttribute('data-act');
        if (op === 'revert'){
          /* the human's control over an agent that applies directly: one click undoes the run */
          const r = await tryOp('agent.revert', { id: m.id });
          if (r.error) notice('revert not applied — ' + r.error.message, { err: true });
          else {
            const d = (r.data || {}) as { reverted?: number; failed?: unknown[] };
            notice('mission ' + m.id + ': ' + (d.reverted || 0) + ' write(s) undone' + (d.failed && d.failed.length ? ' · ' + d.failed.length + ' could not be undone (see History)' : ''), { err: !!(d.failed && d.failed.length) });
          }
          if (S.hooks.historyChanged) S.hooks.historyChanged();
          if (S.hooks.marksChanged) S.hooks.marksChanged();
          await rerenderOpenClass();
          loadMissions();
          return;
        }
        if (op === 'dismiss' || op === 'undismiss'){
          const r = await tryOp('agent.dismiss', { id: m.id, undo: op === 'undismiss' });
          if (r.error) notice('not applied — ' + r.error.message, { err: true });
          if (inspMission && inspMission.id === m.id && op === 'dismiss'){ inspPropId = null; showAgSummary(); }
          loadMissions();
          return;
        }
        if (op === 'raise-turns' || op === 'raise-turns-default'){
          const inp = card.querySelector<HTMLInputElement>('.mc-raise-turns');
          const v = inp ? parseInt(inp.value, 10) : NaN;
          if (op === 'raise-turns'){
            if (!(v > (m.turnsDone || 0))){ notice('enter a turn cap above the ' + (m.turnsDone || 0) + ' turns already run', { err: true }); return; }
            const r = await tryOp(m.state === 'paused' ? 'agent.resume' : 'agent.setturns', { id: m.id, turns: v });
            if (r.error) notice('raise not applied — ' + r.error.message, { err: true });
          } else {
            if (!(v >= 1) || !m.depth){ notice('enter a turn cap (≥ 1)', { err: true }); return; }
            const r = await tryOp('task.configure', { task: 'reverse', depth: m.depth, turns: v });
            notice(r.error ? 'default not changed — ' + r.error.message
              : 'future ' + m.depth + ' missions get ' + v + ' turns — this mission is unchanged', { err: !!r.error });
          }
          loadMissions();
          return;
        }
        if (op === 'raise' || op === 'raise-default'){
          /* the card's raise form: THIS mission (agent.resume {budget} / agent.setbudget) or the
             depth's default for future missions (task.configure) — never both at once */
          const inp = card.querySelector<HTMLInputElement>('.mc-raise-in');
          const v = inp ? parseInt(inp.value, 10) : NaN;
          if (op === 'raise'){
            if (!(v > (m.tokensUsed || 0))){ notice('enter a ceiling above the ' + (m.tokensUsed || 0) + ' tokens already spent', { err: true }); return; }
            const r = await tryOp(m.state === 'paused' ? 'agent.resume' : 'agent.setbudget', { id: m.id, budget: v });
            if (r.error) notice('raise not applied — ' + r.error.message, { err: true });
          } else {
            if (!(v >= 1000) || !m.depth){ notice('enter a token budget (≥ 1000)', { err: true }); return; }
            const r = await tryOp('task.configure', { task: 'reverse', depth: m.depth, budget: v });
            notice(r.error ? 'default not changed — ' + r.error.message
              : 'future ' + m.depth + ' missions start with ' + v.toLocaleString('en-US') + ' tokens — this mission is unchanged', { err: !!r.error });
          }
          loadMissions();
          return;
        }
        const params: OpParams = { id: m.id };
        /* ▶ on a card paused AT the budget raises to the quoted need and resumes in one call —
           a bare resume would only re-park it (the form on the card lets the user pick the amount) */
        if (op === 'resume' && pausedAtBudget(m)){ const inp = card.querySelector<HTMLInputElement>('.mc-raise-in'); const v = inp ? parseInt(inp.value, 10) : NaN; params.budget = v > (m.tokensUsed || 0) ? v : m.budgetNeeded; }
        if (op === 'resume' && pausedAtTurns(m)){ const inp = card.querySelector<HTMLInputElement>('.mc-raise-turns'); const v = inp ? parseInt(inp.value, 10) : NaN; params.turns = v > (m.turnsDone || 0) ? v : m.turnsNeeded; }
        const r = await tryOp('agent.' + op, params);
        if (r.error) notice('agent.' + op + ' — ' + r.error.message, { err: true });
        loadMissions();
        return;
      }
      if ((e.target as HTMLElement).closest('.mc-raise')) return;   /* typing in the form is not a card selection */
      const was = card.classList.contains('sel');
      $all('.mcard').forEach(x => x.classList.remove('sel'));
      if (!was){
        card.classList.add('sel');
        feedFilterMode = m.agentId;
        showMissionInspector(m);   /* rail FIRST — an awaited-then-shown rail reverts any newer user action */
        await loadActivity();
        await loadProposals();     /* loaders self-order via their own claims; rail writes are mode/identity-guarded */
      } else {
        feedFilterMode = 'all';
        showAgSummary();           /* rail first here too — sets railMode before the loaders run */
        await loadActivity();
        await loadProposals();
      }
    });
    host.insertBefore(card, spawn);
  });
  /* the rebuild dropped every .sel — re-apply from feedFilterMode, or the
     highlight desyncs from the rail/filter on every background repaint */
  if (feedFilterMode !== 'all'){
    const selCard = host.querySelector('.mcard[data-agent="' + CSS.escape(feedFilterMode) + '"]');
    if (selCard) selCard.classList.add('sel');
  }
  /* and the mission rail repaints from the FRESH object — its click-time
     snapshot goes stale as tokens burn (the rail is live, not a snapshot) */
  /* INVARIANT (holds via the claim scheme, not construction — review note):
     this vanish check reads feedFilterMode at RESPONSE time. Safe today
     because missionsSeq guarantees only the newest response reaches here and
     the backend list is monotonic outside restarts (where the reset is right). */
  if (feedFilterMode !== 'all'){
    const cur = missions.find(function(x){ return x.agentId === feedFilterMode; });
    if (cur && !(cur.dismissed && !showDismissed)){
      if (railMode === 'mission') showMissionInspector(cur);
    } else {
      /* the filtered mission VANISHED (backend restart — missions are
         in-memory) or was DISMISSED (its rows leave with its card — user
         2026-09-13): reset the filter and reload everything scoped to it,
         or the queue/feed stay filtered to a dead agent with no card left
         to click (review NEW-1/NEW-2) */
      feedFilterMode = 'all';
      paintFeedFilter();
      loadActivity();
      if (railMode === 'mission'){
        /* transition the mode without a fetch — the refired loadProposals'
           tail paints the summary once with fresh data (no double render) */
        railMode = 'summary'; inspPropId = null; inspReq++;
      }
      loadProposals(S.center === 'agents');   /* refire under the restored filter; user-driven when the view is open */
      loadActivity();
    }
  }
}

/* mission inspector ⏸/⏹ (P0.6): they were decorative ("Pause mission — visual").
   They act on the INSPECTED mission through agent.pause/resume/stop — the ops
   the card's own ⏸ ▶ ⏹ use — and paint from the mission's state: disabled
   unless it is running/paused, PAUSE ↔ RESUME by state. loadMissions' tail
   repaints the rail from the fresh object, so the outcome shows honestly. */
let inspMission: Mission | null = null;
function paintMissionCtrl(m: Mission){
  inspMission = m;
  const pause = document.getElementById('agm-pause') as HTMLButtonElement | null;
  const stop = document.getElementById('agm-stop') as HTMLButtonElement | null;
  if (!pause || !stop) return;
  const live = m.state === 'running' || m.state === 'paused';
  const atBudget = pausedAtBudget(m), atTurns = pausedAtTurns(m);
  pause.disabled = !live;
  pause.textContent = m.state === 'paused' ? (atBudget ? '▶ RAISE & RESUME' : atTurns ? '▶ RAISE TURNS & RESUME' : '▶ RESUME') : '⏸ PAUSE';
  pause.setAttribute('data-tip', !live ? 'mission is ' + m.state + ' — nothing to pause'
    : atBudget ? 'Paused at the budget ceiling — raises the budget to the amount in the box below and resumes (agent.resume {budget})'
    : atTurns ? 'Paused at the turn cap — raises the cap to the amount in the TURNS box and resumes (agent.resume {turns})'
    : m.state === 'paused' ? 'Resume mission (agent.resume)' : 'Pause mission (agent.pause)');
  const turnsEl = document.getElementById('agm-turns');
  if (turnsEl) turnsEl.textContent = m.turnsUnlimited
    ? (m.turnsDone || 0) + ' turns · no turn limit (headless)'
    : (m.turnsDone || 0) + ' / ' + (m.turnCap || m.maxTurns || '—') + ' turns · the mission\'s own cap';
  const trow = document.getElementById('agm-turnup-row'), tinp = document.getElementById('agm-turnup') as HTMLInputElement | null;
  const tbtn = document.getElementById('agm-turnup-btn'), thint = document.getElementById('agm-turnup-hint'), tdflt = document.getElementById('agm-turnup-default');
  if (trow && tinp && tbtn && thint){
    trow.hidden = !live || !!m.turnsUnlimited;
    const tkey = m.id + ':' + (m.turnsNeeded || 0);
    if (live && tinp.dataset.for !== tkey){
      tinp.dataset.for = tkey;
      tinp.value = String(m.turnsNeeded || ((m.turnCap || m.maxTurns || 10) + 10));
      thint.textContent = atTurns ? 'paused at the mission\'s own turn cap (' + (m.turnsDone || 0) + '/' + (m.turnCap || 0) + ') — suggested ' + m.turnsNeeded + ' turns' : '';
    }
    tbtn.textContent = atTurns ? '↑ RAISE THIS MISSION & RESUME' : '↑ RAISE THIS MISSION';
    if (tdflt){ tdflt.hidden = !m.depth; tdflt.textContent = 'SET AS ' + String(m.depth || '').toUpperCase() + ' DEFAULT'; }
  }
  stop.disabled = !live;
  stop.setAttribute('data-tip', live ? 'Stop mission (agent.stop) — terminal' : 'mission is ' + m.state + ' — already stopped');
  /* the top-up row: live missions only. Prefill once per mission+quote — never clobber the
     user's typing on every SSE repaint */
  const row = document.getElementById('agm-topup-row'), inp = document.getElementById('agm-topup') as HTMLInputElement | null;
  const btn = document.getElementById('agm-topup-btn'), hint = document.getElementById('agm-topup-hint');
  if (row && inp && btn && hint){
    row.hidden = !live || !!m.budgetUnlimited;
    const key = m.id + ':' + (m.budgetNeeded || 0);
    if (live && inp.dataset.for !== key){
      inp.dataset.for = key;
      inp.value = String(m.budgetNeeded || Math.max(1000, Math.ceil((m.budget || 0) * 1.5 / 1000) * 1000));
      hint.textContent = atBudget ? 'paused at the mission\'s own token ceiling (not your provider balance) — the next turn needs ≥ ' + m.budgetNeeded + ' tokens' : '';
    }
    btn.textContent = atBudget ? '↑ RAISE THIS MISSION & RESUME' : '↑ RAISE THIS MISSION';
    const dflt = document.getElementById('agm-topup-default');
    if (dflt){ dflt.hidden = !m.depth; dflt.textContent = 'SET AS ' + String(m.depth || '').toUpperCase() + ' DEFAULT'; }
  }
}

let dismissedAgents = new Set<string>();
let showDismissed = false;
function isTerminal(m: Mission): boolean { return m.state === 'done' || m.state === 'blocked' || m.state === 'stopped' || m.state === 'interrupted'; }

/** Paused AT the turn cap with the cap not raised — a plain resume would only re-park it. */
function pausedAtTurns(m: Mission): boolean {
  return !m.turnsUnlimited && m.state === 'paused' && !!m.turnsNeeded && (m.maxTurns || 0) <= (m.turnCap || 0);
}
/** Paused AT the budget ceiling with the budget still spent — a plain resume would only re-park it. */
function pausedAtBudget(m: Mission): boolean {
  return !m.budgetUnlimited && m.state === 'paused' && !!m.budgetNeeded && (m.budget || 0) < (m.budgetFloor || m.budgetNeeded || 0);
}
/* act: pause | stop | raise — 'pause' on a paused mission resumes; a mission paused AT the
   budget resumes WITH the raise in the box (agent.resume {budget}); 'raise' on a running
   mission is agent.setbudget. Errors land in the hint under the box, not only the console —
   the backend's refusal names the raise the next turn needs. */
async function missionCtrl(act: string){
  const m = inspMission;
  if (!m) return;
  const hint = document.getElementById('agm-topup-hint');
  const inp = document.getElementById('agm-topup') as HTMLInputElement | null;
  const tinp = document.getElementById('agm-turnup') as HTMLInputElement | null;
  const thint = document.getElementById('agm-turnup-hint');
  if (act === 'raise-turns' || act === 'raise-turns-default'){
    const v = tinp ? parseInt(tinp.value, 10) : NaN;
    if (act === 'raise-turns-default'){
      if (!(v >= 1) || !m.depth){ if (thint) thint.textContent = 'enter a turn cap (≥ 1) — the default applies to future ' + (m.depth || '') + ' missions'; return; }
      const r = await tryOp('task.configure', { task: 'reverse', depth: m.depth, turns: v });
      if (thint) thint.textContent = r.error ? 'default not changed — ' + r.error.message : 'future ' + m.depth + ' missions get ' + v + ' turns — this mission is unchanged (RAISE THIS MISSION for it)';
      return;
    }
    if (!(v > (m.turnsDone || 0))){ if (thint) thint.textContent = 'enter a turn cap above the ' + (m.turnsDone || 0) + ' turns already run'; return; }
    const r = await tryOp(m.state === 'paused' ? 'agent.resume' : 'agent.setturns', { id: m.id, turns: v });
    if (r.error){ if (thint) thint.textContent = r.error.message; } else if (thint) thint.textContent = '';
    await loadMissions();
    return;
  }
  if (act === 'raise-default'){
    const v = inp ? parseInt(inp.value, 10) : NaN;
    if (!(v >= 1000) || !m.depth){ if (hint) hint.textContent = 'enter a token budget (≥ 1000) — the default applies to future ' + (m.depth || '') + ' missions'; return; }
    const r = await tryOp('task.configure', { task: 'reverse', depth: m.depth, budget: v });
    if (hint) hint.textContent = r.error ? 'default not changed — ' + r.error.message
      : 'future ' + m.depth + ' missions start with ' + v.toLocaleString('en-US') + ' tokens — this mission is unchanged (RAISE THIS MISSION for it)';
    return;
  }
  let opName = (act === 'pause' && m.state === 'paused') ? 'resume' : act;
  const params: OpParams = { id: m.id };
  if (opName === 'resume' && pausedAtTurns(m)){   /* ▶ on a turn-capped mission raises to the box's value and resumes */
    const v = tinp ? parseInt(tinp.value, 10) : NaN;
    if (!(v > (m.turnsDone || 0))){ if (thint) thint.textContent = 'enter a turn cap above the ' + (m.turnsDone || 0) + ' turns already run'; return; }
    params.turns = v;
  }
  if (act === 'raise' || (opName === 'resume' && pausedAtBudget(m))){
    const v = inp ? parseInt(inp.value, 10) : NaN;
    if (!(v > (m.tokensUsed || 0))){
      if (hint) hint.textContent = 'enter a ceiling above the ' + (m.tokensUsed || 0) + ' tokens already spent';
      return;
    }
    params.budget = v;
    if (act === 'raise') opName = m.state === 'paused' ? 'resume' : 'setbudget';
  }
  const r = await tryOp('agent.' + opName, params);
  if (r.error){ console.warn('agent.' + opName, r.error.message); if (hint) hint.textContent = r.error.message; }
  else if (hint) hint.textContent = '';
  await loadMissions();          /* the card AND the rail repaint from the fresh state */
}

function showMissionInspector(m: Mission){
  toggleAgi('agi-mission');
  railMode = 'mission';
  paintMissionCtrl(m);
  inspPropId = null;   /* the rail is on a mission, not a proposal */
  inspReq++;           /* and any in-flight proposal inspector is superseded */
  /* the live refresh fires per SSE tick — writing unchanged textContent kills
     the user's text selection in the panel, so only write what changed */
  const setText = function(id: string, v: string | undefined){ const el = document.getElementById(id)!; if (el.textContent !== v) el.textContent = v as string; };
  setText('agm-goal', m.goal);
  setText('agm-model', m.model + ' · ' + ((m.mode === 'cli' || m.mode === 'claude') ? 'Claude Code CLI' : m.mode === 'http' ? 'API' : m.mode) + (m.depth ? ' · ' + m.depth : '') + (m.effort ? ' · effort ' + m.effort : '')
    + (m.maxTurns ? ' · ≤' + m.maxTurns + ' turns' : '') + (m.target ? ' · ' + m.target : ''));
  setText('agm-budget', m.budgetUnlimited
    ? (m.inputTokens || 0).toLocaleString('en-US') + ' input · ' + (m.outputTokens || 0).toLocaleString('en-US') + ' output · ' + (m.cacheHitTokens || 0).toLocaleString('en-US') + ' cached input tokens · no mission budget limit'
    : (m.tokensUsed || 0).toLocaleString('en-US') + ' / ' + (m.budget || 0).toLocaleString('en-US') + ' tokens (cost-weighted: cache hits count 1/10) · the mission\'s own ceiling'
    + (m.tokensRaw ? ' · raw ' + m.tokensRaw.toLocaleString('en-US') + ', ' + Math.round(100 * (m.cacheHitTokens || 0) / Math.max(1, m.tokensRaw)) + '% cached' : ''));
  setText('agm-scope', m.scope || '(no write-scope)');
  /* P0.3: REASON section — the card's gate (blocked state + reason). One
     wrapper toggles title + row (a hidden .mini-title would lose to its
     display:flex rule), so a running/done mission shows no empty REASON.
     The rail is the full-detail surface: the row wraps (shell.css) where
     the card ellipses. */
  const why = (m.state === 'blocked' && m.reason) ? String(m.reason) : '';
  const whySec = document.getElementById('agm-reason-sec');
  if (whySec){ whySec.hidden = !why; setText('agm-reason', why); }
  const p = document.getElementById('agm-prog')!;
  /* rebuild only on change — an unconditional innerHTML wipes the user's text
     selection in the step log on every SSE tick (review NEW-4) */
  const sig = JSON.stringify(m.progress || []);   /* unambiguous (join('\n') collides on embedded newlines) */
  if (p.dataset.sig !== sig){
    p.dataset.sig = sig;
    p.innerHTML = '';
    (m.progress || []).forEach(function(step){
      const r = document.createElement('div');
      r.className = 'ckr';
      r.innerHTML = '<span class="s-done">✓</span><span>' + esc(step.text) + '</span>';
      p.appendChild(r);
    });
  }
}

/* ==================================================================
   ACTIVITY stream (backfill + live SSE)
================================================================== */
function activityRow(a: ActivityRow){
  const r = document.createElement('div');
  const inTurn = !!(a.turn && a.mission);
  r.className = 'arow' + (a.kind === 'blocked' ? ' alert blocked' : '') + (inTurn ? ' turn' : '');
  r.setAttribute('data-agent', a.agent || '');
  if (!showDismissed && a.agent && dismissedAgents.has(a.agent)) r.hidden = true;
  if (inTurn){
    r.setAttribute('data-mission', a.mission!); r.setAttribute('data-turn', String(a.turn));
    if (selTurn && selTurn.mission === a.mission && selTurn.turn === a.turn) r.classList.add('sel');
    r.title = 'open turn ' + a.turn + ' — reasoning + tool calls';
  }
  r.innerHTML = '<span class="at">' + esc(fmtTs(a.ts)) + '</span>' +
    '<span class="aid" style="color:' + agentColor(a.agent) + '">' + esc(a.agent || '') + '</span>' +
    '<span class="akind">' + esc(String(a.kind || '').toUpperCase()) + '</span>' +
    '<span class="atext">' + esc(a.text || '') + '</span>';
  return r;
}

/* ==================================================================
   The log's bottom pane (PROPOSALS · TURN): opens on demand — a proposal arriving, a turn
   click, ▴ PROPOSALS — and × / Esc closes it. Bottom half of the stream by default, draggable.
================================================================== */
let bottomEsc: ReturnType<typeof pushEscape> | null = null;
function openBottom(tab: 'props' | 'turn'){
  const pane = document.getElementById('ag-bottom'); if (!pane) return;
  if (pane.hidden){
    pane.hidden = false;
    bottomEsc = pushEscape(function(){ if (pane.hidden) return false; closeBottom(); return true; }, ESC.view);
  }
  setBottomTab(tab);
}
function closeBottom(){
  const pane = document.getElementById('ag-bottom'); if (!pane || pane.hidden) return;
  pane.hidden = true;
  if (bottomEsc){ popEscape(bottomEsc); bottomEsc = null; }
  selTurn = null;
  $all<HTMLElement>('#ag-feed .arow.sel').forEach(r => r.classList.remove('sel'));
}
function setBottomTab(tab: 'props' | 'turn'){
  $all<HTMLElement>('#ag-bottom .ag-bottom-tabs [data-agtab]').forEach(b => b.classList.toggle('on', b.getAttribute('data-agtab') === tab));
  const p = document.getElementById('ag-bpanel-props'), t = document.getElementById('ag-bpanel-turn');
  if (p) p.hidden = tab !== 'props';
  if (t) t.hidden = tab !== 'turn';
  if (tab === 'props'){ const pc = document.getElementById('propcol'); if (pc) pc.focus(); }
}

/* ==================================================================
   TURN transcript (agent.turn) — the pane's TURN tab
================================================================== */
let selTurn: { mission: string; turn: number } | null = null;
let turnReq = 0;
function pretty(json: string | undefined): string {
  if (!json) return '';
  try { return JSON.stringify(JSON.parse(json), null, 2); } catch (_e) { return json; }
}
/* one labelled text block with a hover copy button (the whole block, real newlines) */
function blk(label: string, text: string, cls?: string){
  return '<div class="agt-blk"><div class="lbl">' + esc(label) + '</div><button class="agt-copy" data-tip="copy this block to the clipboard">⧉ copy</button><pre class="' + (cls || '') + '">' + esc(text) + '</pre></div>';
}
function callBlock(c: TurnCall){
  const st = c.ok === undefined ? '' : c.ok ? '<span class="ok">ok</span>' : '<span class="err">error</span>';
  return '<div class="agt-call"><div class="ct">' + (c.n ? '<span class="cn">call ' + c.n + '/' + (c.of || '?') + '</span>' : '') + '<span class="tool">' + esc(c.tool) + '</span>' + st +
    (c.ms != null ? '<span class="ms">' + c.ms + ' ms</span>' : '') + '</div>' +
    blk('ARGS', pretty(c.args)) +
    (c.result != null ? blk('RESULT', pretty(c.result)) : '') +
    (c.resultText ? blk('TEXT · ' + c.resultText.length.toLocaleString('en-US') + ' chars', c.resultText, 'agt-code') : '') + '</div>';
}
async function selectTurn(mission: string, turn: number){
  selTurn = { mission, turn };
  $all<HTMLElement>('#ag-feed .arow').forEach(function(r){
    r.classList.toggle('sel', r.getAttribute('data-mission') === mission && r.getAttribute('data-turn') === String(turn));
  });
  const my = ++turnReq;
  const empty = document.getElementById('agt-empty'), wrap = document.getElementById('agt-wrap');
  const head = document.getElementById('agt-head'), left = document.getElementById('agt-left'), calls = document.getElementById('agt-calls');
  if (!empty || !wrap || !head || !left || !calls) return;
  openBottom('turn');
  const tb = document.getElementById('ag-tab-turn'); if (tb) tb.textContent = 'TURN ' + turn;
  empty.hidden = true; wrap.hidden = false;
  head.innerHTML = '<b>TURN ' + turn + '</b><span class="dim">' + esc(mission) + ' · loading…</span>';
  const r = await tryOp('agent.turn', { mission, turn });
  if (my !== turnReq) return;
  if (r.error){
    /* a claude-mode mission records no transcript: its rows ARE its MCP calls — show them */
    const rows = $all<HTMLElement>('#ag-feed .arow.sel').map(x => x.querySelector('.atext')!.textContent || '');
    head.innerHTML = '<b>TURN ' + turn + '</b><span class="dim">' + esc(mission) + '</span>';
    left.innerHTML = '<div class="dockhint">' + esc(r.error.message) + '</div>';
    calls.innerHTML = rows.length ? '<div class="lbl">ROWS</div><pre class="agt-pre">' + rows.map(esc).join('\n') + '</pre>' : '';
    return;
  }
  const t = r.data as TurnRecord;
  const ctx = t.context || {};
  head.innerHTML = '<b>TURN ' + t.turn + '</b>' +
    '<span class="oc ' + esc(t.outcome || '') + '">' + esc(t.outcome || '') + '</span>' +
    (t.tokens != null ? '<span>' + t.tokens.toLocaleString('en-US') + ' tok this turn</span>' : '') +
    (t.tokensUsed != null ? '<span>' + t.tokensUsed.toLocaleString('en-US') + ' used</span>' : '') +
    (t.ms != null ? '<span>' + (t.ms / 1000).toFixed(1) + ' s</span>' : '') +
    (t.cacheHit != null ? '<span data-tip="the provider\'s prompt-prefix cache: hit tokens cost ~1/10 of a miss">cache ' + Math.round(100 * (t.cacheHit || 0) / Math.max(1, (t.cacheHit || 0) + (t.cacheMiss || 0))) + '% hit (' + (t.cacheHit || 0).toLocaleString('en-US') + ' / ' + (t.cacheMiss || 0).toLocaleString('en-US') + ' miss)</span>' : '') +
    (ctx.messages != null ? '<span class="dim">context ' + ctx.messages + ' msgs · ' + (ctx.elided || 0) + ' elided · ~' + (ctx.promptEst || 0).toLocaleString('en-US') + (ctx.limit ? ' / ' + ctx.limit.toLocaleString('en-US') : '') + ' prompt tok</span>' : '') +
    '<span class="dim">' + esc(fmtTs(t.ts)) + ' · ' + esc(mission) + '</span>';
  let lh = '';
  if (t.reasoning) lh += blk('REASONING · ' + t.reasoning.length.toLocaleString('en-US') + ' chars', t.reasoning, 'agt-pre');
  if (t.text) lh += blk(t.calls && t.calls.length ? 'SAID' : 'FINAL ANSWER', t.text, 'agt-pre');
  if (t.error) lh += blk('ERROR', t.error, 'agt-pre err');
  if (!lh) lh = '<div class="dockhint">no reasoning trace and no text this turn' + (t.calls && t.calls.length ? ' — the model went straight to tool calls' : '') + ' (a provider returns reasoning only in thinking mode)</div>';
  left.innerHTML = lh;
  calls.innerHTML = (t.calls && t.calls.length)
    ? '<div class="lbl">' + t.calls.length + ' TOOL CALL' + (t.calls.length === 1 ? '' : 'S') + '</div>' + t.calls.map(callBlock).join('')
    : '<div class="dockhint">no tool calls this turn</div>';
}

let actReq = 0;
let feedKinds = 'all';   /* the kind chips (agent.activity {kinds}) */
function paintFeedFilter(){
  $all<HTMLElement>('#ag-filter .fchip[data-kinds]').forEach(function(b){ b.classList.toggle('on', b.getAttribute('data-kinds') === feedKinds); });
  const mf = document.getElementById('ag-filter-mission'), ma = document.getElementById('ag-filter-agent');
  if (mf && ma){ mf.hidden = feedFilterMode === 'all'; ma.textContent = feedFilterMode; }
}
async function loadActivity(){
  const my = ++actReq;
  const params: OpParams = { count: 100 };
  if (feedFilterMode !== 'all') params.agent = feedFilterMode;
  if (feedKinds !== 'all') params.kinds = feedKinds;
  const r = await tryOp('agent.activity', params);
  if (r.error) return;                 /* keep the last good feed */
  if (my !== actReq) return;
  paintFeedFilter();
  const host = document.getElementById('ag-feed')!;
  host.innerHTML = '';
  const rows = r.data || [];
  if (!rows.length)
    host.innerHTML = '<div class="dockhint">no activity yet — spawn a mission (NEW MISSION above)</div>';
  rows.forEach(a => host.appendChild(activityRow(a)));
  host.scrollTop = host.scrollHeight;
}

/* ==================================================================
   PROPOSALS queue + review inspector
================================================================== */
function propRows(){
  return $all('.prow').filter(r => r.style.display !== 'none');
}

/* One row per agent write, whatever its state (the EDITS pane, 2026-09-19): pending rows take
   ✓ / ✗ and a name edit; applied rows (a ✓ or the APPLY DIRECTLY policy) take ↶ revert and ✎
   rename-again; reverted rows take ↷ redo; refused / rejected / purged rows are dim records. */
const PENDING = 'pending', APPLIED = 'accepted';
function rowState(p: Proposal): 'pending' | 'applied' | 'reverted' | 'refused' | 'rejected' | 'purged' | 'unchanged' {
  if (p.state === PENDING) return 'pending';
  if (p.state === APPLIED) return p.reverted ? 'reverted' : 'applied';
  if (p.state === 'refused') return 'refused';
  if (p.state === 'purged') return 'purged';
  if (p.state === 'unchanged') return 'unchanged';
  return 'rejected';
}
function proposalRow(p: Proposal){
  const r = document.createElement('div');
  const st = rowState(p);
  r.className = 'prow' + (st === 'applied' ? ' done' : st === 'pending' ? '' : ' settled');
  r.setAttribute('data-pid', p.id);
  r.setAttribute('data-agent', p.agentId || '');
  r.setAttribute('data-state', st);
  let edit = '';
  if (p.kind === 'rename')
    /* the name is click-to-edit (a collision, a better name — any reason): on a pending row Enter
       accepts with the edit, Esc restores, leaving the field keeps the edit for ✓ / ACCEPT ALL; on
       an applied row Enter renames again, as the human (the agent cannot take it back) */
    edit = st === 'unchanged' ? '<b>' + esc(p.newName) + '</b>' : '<s>' + esc(p.oldName || '?') + '</s> → <b class="pnew" title="' + (st === 'applied' ? 'click to rename again' : 'click to edit the name before accepting') + '">' + esc(p.editedName || p.newName) + '</b>'
      + (st === 'pending' || st === 'applied' ? '<button class="pencil" title="' + (st === 'applied' ? 'rename again' : 'edit the proposed name') + '">✎</button>' : '')
      + (p.editedName && p.editedName !== p.newName ? '<span class="pchip edited" title="the agent proposed ' + esc(p.newName) + '">edited</span>' : '')
      + (p.refusal ? '<div class="prefused">' + esc(p.refusal) + '</div>' : '');
  else if (p.kind === 'comment')
    edit = '<b>✎</b> ' + esc(String(p.newText || '').slice(0, 160)) + (String(p.newText || '').length > 160 ? '…' : '') + ' (comment)';
  else if (p.kind === 'tag')
    edit = '<span class="vchip vc-mixed">' + esc(p.name) + '</span>' + (p.note ? '<span class="pnote">' + esc(String(p.note).slice(0, 140)) + (String(p.note).length > 140 ? '…' : '') + '</span>' : '');
  else if (p.kind === 'verdict')
    edit = '<span class="vchip vc-authored">' + esc(p.verdict) + '</span> ' + esc(p.node);
  const conf = p.confidence != null && p.confidence !== '' ? ' · conf ' + p.confidence : ' · conf —';
  const nr = p.rationale ? '' : '<span class="pchip nr" title="the agent gave no rationale">no rationale</span>';
  const kindLabel = ({ rename: 'RENAME', comment: 'COMMENT', tag: 'TAG', verdict: 'VERDICT' } as Record<string, string>)[p.kind || ''] || String(p.kind || '?').toUpperCase();
  const stChip = st === 'pending' ? '' :
    '<span class="pchip st-' + st + '" title="' + (st === 'applied' ? (p.auto ? 'applied at once by the APPLY DIRECTLY policy' : 'accepted by a human') : st === 'reverted' ? 'undone — ↷ applies it again' : st === 'refused' ? 'the engine refused it' : st) + '">'
      + (st === 'applied' ? (p.auto ? 'applied' : 'accepted') : st) + '</span>';
  const refused = st === 'refused' && p.refusal ? '<div class="prefused">' + esc(String(p.refusal)) + '</div>' : '';
  let act = '';
  if (st === 'pending') act = '<button class="pok" title="accept — ⌘A">✓</button><button class="pno" title="reject — ⌘X">✗</button>';
  else if (st === 'applied' && p.historyId) act = '<button class="prv" title="revert this edit (History keeps it for redo)">↶</button>';
  else if (st === 'reverted' && p.historyId) act = '<button class="prd" title="apply this edit again">↷</button>';
  /* when: filed (ts) and, for a settled row, settled (settledTs) — HH:MM:SS with the full instant
     on hover (user 2026-09-20: "so we can understand when changes were made") */
  const hhmmss = function(v: unknown){ const t = v == null ? NaN : (typeof v === 'number' ? v : Date.parse(String(v))); if (!isFinite(t)) return ''; const d = new Date(t); const z = function(n: number){ return (n < 10 ? '0' : '') + n; }; return z(d.getHours()) + ':' + z(d.getMinutes()) + ':' + z(d.getSeconds()); };
  const filed = hhmmss(p.ts), settled = hhmmss(p.settledTs);
  const when = filed ? '<span class="pwhen" title="filed ' + esc(String(p.ts)) + (p.settledTs ? ' · ' + esc(String(p.state)) + ' ' + esc(String(p.settledTs)) : '') + '">' + esc(filed) + (settled && settled !== filed ? ' → ' + esc(settled) : '') + '</span>' : '';
  r.innerHTML = '<span class="pkindb pk-' + esc(String(p.kind || 'other')) + '" title="the kind of edit">' + esc(kindLabel) + '</span>' +
      '<div class="pbody">' +
      '<div class="pedit">' + edit + refused + '</div>' +
      '<div class="pmeta">' + stChip + (p.targetName ? '<span class="pchip" title="target">' + esc(p.targetName) + '</span>' : '') + nr +
        esc(p.kind) + conf + ' · ' + esc(p.agentId || '') + when + '</div>' +
      '<div class="ploc" title="jump to symbol">' + esc(p.symbol || (p.map ? p.map + '/' + p.node : '')) + '</div>' +
    '</div>' +
    '<div class="pact">' + act + '</div>';
  return r;
}

/* ↶ on an applied row: History undo of its entry; ↷ on a reverted row: History redo. The open
   class re-renders (a reverted rename leaves its text) and the row repaints from the server. */
const reverting = new Set<string>();
async function revertRow(id: string | null, redo: boolean){
  const p = propCache.find(x => x.id === id);
  if (!p || !p.historyId || reverting.has(p.id)) return;
  reverting.add(p.id);
  try {
    const r = await tryOp(redo ? 'history.redo' : 'history.undo', { target: p.target, id: p.historyId });
    if (r.error){ notice((redo ? 'redo' : 'revert') + ' failed — ' + r.error.message, { err: true }); return; }
    if (p.kind === 'rename' && p.symbol && !p.symbol.includes('->') && S.hooks.classRenamed){
      /* a class rename undone (or redone): its tab and tree row follow at once */
      const oldSym = p.symbol, isAddr = /^L.*;$/.test(oldSym);
      const oldDotted = isAddr ? oldSym.slice(1, -1).replace(/\//g, '.').replace(/\$/g, '.') : oldSym;
      const newDotted = oldDotted.replace(/[^.]+$/, p.newName || '');
      if (redo) S.hooks.classRenamed(oldDotted, isAddr ? oldSym : undefined, newDotted, p.newName || '');
      else S.hooks.classRenamed(newDotted, p.newAddr, oldDotted, p.oldName || '');
    }
    notice(redo ? 'applied again' : 'reverted — ↷ on the row applies it again');
    await loadProposals();
    if (S.hooks.historyChanged) S.hooks.historyChanged();
    if (S.hooks.marksChanged) S.hooks.marksChanged();
    await rerenderOpenClass();
  } finally { reverting.delete(p.id); }
}

/* ✎ on an applied rename: a HUMAN rename at the address the symbol moved to — the agent's
   later reads see it and cannot overwrite it */
async function renameApplied(p: Proposal, name: string){
  const r = await tryOp('rename', { target: p.target, symbol: p.newAddr || p.symbol, name });
  if (r.error){ notice('rename not applied — ' + r.error.message, { err: true }); return; }
  notice('renamed to ' + name + ' (yours now — agents cannot change it)');
  await loadProposals();
  if (S.hooks.historyChanged) S.hooks.historyChanged();
  await rerenderOpenClass();
}

/* the reviewer's pending edits and the last refusal per proposal — kept across the pane's
   reloads (propCache is rebuilt from the server on every settle) */
const rowEdits = new Map<string, { editedName?: string; refusal?: string }>();
function mergeRowEdits(list: Proposal[]){
  for (const p of list){ const e = rowEdits.get(p.id); if (e){ p.editedName = e.editedName; p.refusal = e.refusal; } }
}
function repaintRow(id: string | null){
  if (!id) return;
  const p = propCache.find(x => x.id === id);
  const old = document.querySelector<HTMLElement>('.prow[data-pid="' + id + '"]');
  if (!p || !old) return;
  const fresh = proposalRow(p);
  if (old.classList.contains('psel')) fresh.classList.add('psel');
  old.replaceWith(fresh);
}
function openNameEditor(row: HTMLElement, p: Proposal){
  const b = row.querySelector<HTMLElement>('.pnew');
  if (!b || row.querySelector('.pname-in')) return;
  const input = document.createElement('input');
  input.className = 'pname-in';
  input.value = (rowEdits.get(p.id) || {}).editedName || p.newName || '';
  input.spellcheck = false;
  input.setAttribute('aria-label', 'new name');
  b.replaceWith(input);
  input.focus();
  input.select();
  let done = false;
  const finish = function(){ done = true; window.removeEventListener('keydown', onKey, true); popEscape(escH); };
  const commit = function(andAccept: boolean){
    const v = input.value.trim();
    if (rowState(p) === 'applied'){
      /* an applied rename: Enter renames again as the human; leaving the field changes nothing */
      if (andAccept && v && v !== p.newName) renameApplied(p, v); else repaintRow(p.id);
      return;
    }
    const e = rowEdits.get(p.id) || {};
    if (v && v !== p.newName) e.editedName = v; else delete e.editedName;
    delete e.refusal;                                   // a fresh attempt clears the old reason
    rowEdits.set(p.id, e);
    p.editedName = e.editedName;
    p.refusal = undefined;
    repaintRow(p.id);
    if (andAccept) settle(p.id, true, document.querySelector('.prow[data-pid="' + p.id + '"]'));
  };
  /* Esc goes through the shell's Esc stack at menu priority: the agents view's own Esc owner
     (ESC.view) would otherwise close the view, the hidden field would blur, and the blur would
     commit — an Esc meant "restore" kept the typed text (QA 2026-09-13). Enter is taken at the
     window in the capture phase, ahead of the column's shortcuts. */
  const escH = pushEscape(function(){ if (done) return false; finish(); repaintRow(p.id); return true; }, ESC.menu);
  const onKey = function(ev: KeyboardEvent){
    if (ev.target !== input || done) return;
    if (ev.key === 'Enter'){ ev.preventDefault(); ev.stopPropagation(); finish(); commit(true); }
  };
  window.addEventListener('keydown', onKey, true);
  input.addEventListener('blur', function(){ if (!done){ finish(); commit(false); } });
  input.addEventListener('click', function(ev){ ev.stopPropagation(); });
}

let propsReq = 0;
let lastSettledId: string | null = null;   /* settle() records it — the ONLY vanish that earns auto-advance */
async function loadProposals(userDriven?: boolean){
  const my = ++propsReq;
  let summaryShown = false;             /* set when this call already repainted the rail's summary */
  const mine = feedFilterMode;                       /* capture at REQUEST time — the filter can move mid-flight */
  const myProps = (mine === 'all') ? ++propsSeq : 0; /* request-time claim for the props-n write */
  const params: OpParams = { state: 'all' };   /* the EDITS pane: applied and pending alike */
  if (mine !== 'all') params.mission = mine;
  const r = await tryOp('agent.proposals', params);
  /* every non-painting exit must RELEASE the propsSeq claim (a discarded
     loader must not invalidate an in-flight updatePropsCount) and, when
     this was the unfiltered loader, hand the project chip to its fallback */
  const bail = function(){
    if (myProps && myProps === propsSeq) propsSeq--;
    if (mine === 'all' && my === propsReq) updatePropsCount();
  };
  if (r.error){
    /* no project open is a LEGAL state (M11), not an outage: say so, no recovery ladder (b2-r1 M5) */
    if ((r.error as { code?: string }).code === 'E_NOTFOUND'){
      if (my === propsReq){
        propCache = [];
        const host0 = document.getElementById('proplist');
        if (host0) host0.innerHTML = '<div class="dockhint">no project open — edits are project-scoped (⌘⇧O opens a project)</div>';
        const pp = document.getElementById('prop-pending');
        if (pp) pp.textContent = '';
        propsViewPainted = true;
      }
      bail(); return;
    }
    /* a never-painted queue says it is unreachable; a settled-empty queue is a
       real state — never banner over it. bail() owns the updatePropsCount
       fallback (exactly once — no direct call here, that double-fires and
       leaks the claim, review F3). */
    if (!propsViewPainted){
      const host0 = document.getElementById('proplist');
      if (host0 && !host0.querySelector('.unreach-hint')){
        const hint = document.createElement('div');
        hint.className = 'dockhint unreach-hint';
        hint.textContent = 'backend unreachable — retrying…';
        host0.appendChild(hint);
      }
      bootRecovery();   /* the hint says retrying — make it true. OUTSIDE the
                           creation guard: an existing hint must not disarm it */
    }
    bail(); return;
  }
  if (my !== propsReq){ bail(); return; }
  if (mine !== feedFilterMode){ bail(); return; }   /* the filter moved — wrong-scope response */
  /* consume the settle flag ONLY in a call that paints: a superseded/errored
     load eating it breaks settle-advance (review F1). Poisoning is inert by
     construction — the advance also requires inspPropId === the flag, which
     only holds while the rail still shows the settled proposal. */
  const settledThisRound = lastSettledId;
  lastSettledId = null;
  propCache = r.data || [];
  mergeRowEdits(propCache);   // the reviewer's edits and refusals survive the reload
  propsViewPainted = true;
  const host = document.getElementById('proplist')!;
  host.innerHTML = '';
  propCache.forEach(p => host.appendChild(proposalRow(p)));
  // D15: honest empty states (every other view has one)
  if (!propCache.length)
    host.innerHTML = '<div class="dockhint">no agent edits yet — renames, comments and tags list here as they are applied (APPLY DIRECTLY) or wait here for a ✓ (REVIEW FIRST)</div>';
  const pendingN = propCache.filter(function(p){ return rowState(p) === 'pending'; }).length;
  const appliedN = propCache.filter(function(p){ return rowState(p) === 'applied'; }).length;
  const revertedN = propCache.filter(function(p){ return rowState(p) === 'reverted'; }).length;
  const unchangedN = propCache.filter(function(p){ return rowState(p) === 'unchanged'; }).length;
  /* prop-pending is the VIEW-scope (possibly mission-filtered) summary — this loader is its
     only writer. props-n is project-scope (pending only: what needs a hand); this loader writes
     it only when unfiltered (its list IS the project list) and only if its request-time claim
     is still the newest. */
  /* the header says whose edits these are: a card click filters the pane to ONE mission, and a
     newer mission writing elsewhere is then invisible here (user 2026-09-20: "I don't see its
     renames in the Edits view") — name the filter and the running mission that is not in it */
  const running = missionsLast.filter(function(m){ return m.state === 'running' && m.agentId !== mine; });
  const scope = mine === 'all' ? '' : ' · showing ' + mine + ' only'
    + (running.length ? ' — ' + running.map(function(m){ return m.id + ' (' + m.agentId + ') is writing now' ; }).join(', ') + ' — × in the log bar shows every mission' : ' — × in the log bar shows every mission');
  document.getElementById('prop-pending')!.textContent = (propCache.length
    ? appliedN + ' applied' + (pendingN ? ' · ' + pendingN + ' pending' : '') + (revertedN ? ' · ' + revertedN + ' reverted' : '') + (unchangedN ? ' · ' + unchangedN + ' unchanged' : '')
    : 'no edits' + (mine === 'all' ? '' : ' from ' + mine)) + scope;
  const pn = propCache.length ? String(propCache.length) : '';
  ['ag-tab-props-n', 'ag-open-n'].forEach(function(id){ const el = document.getElementById(id); if (el) el.textContent = pn; });
  /* the pane opens itself once, on the first edits of the session — never at view-open with
     nothing in it (the empty column was the complaint); after that the count on ▴ EDITS says
     there is something to look at and the user opens it */
  if (propCache.length && !propsAutoOpened && S.center === 'agents'){ propsAutoOpened = true; openBottom('props'); }
  if (mine === 'all' && myProps === propsSeq){
    const pn = document.getElementById('props-n');
    if (pn) pn.textContent = String(pendingN);
    propsPainted = true;
  }
  if (propCache.length && propSel < 0 && railMode !== 'mission' && userDriven){
    /* auto-select the first row only when the USER opened the view — a
       background tick with nothing selected must not arm an ACCEPT button
       nobody picked (rounds 10/11: the steal relocates, the flag doesn't) */
    propSel = 0;
    paintPropSel();
    showProposalInspector(propCache[0].id);
  } else if (propCache.length){
    /* re-anchor on IDENTITY, not index. When the rail's proposal vanished,
       WHY it vanished decides what happens: settled-by-the-user advances the
       review flow to the next row; filtered-out-from-under-them falls to the
       summary — it never arms an unselected proposal (round 13, F1) */
    const keep = inspPropId != null ? propCache.findIndex(function(x){ return x.id === inspPropId; }) : -1;
    if (keep >= 0){
      propSel = keep;
      paintPropSel();
      /* NOTE (latent, no backend path today): an in-place REVISED proposal under
         a stable id would leave the rail's evidence stale — ProposalStore is
         append-only now; if that changes, re-fire the inspector on object diff. */
    } else if (railMode === 'proposal' && settledThisRound != null && inspPropId === settledThisRound){
      propSel = Math.min(propSel, propCache.length - 1);
      paintPropSel();
      if (propSel >= 0) showProposalInspector(propCache[propSel].id);
    } else if (railMode === 'proposal'){
      inspPropId = null;
      propSel = -1;
      paintPropSel();
      showAgSummary();
      summaryShown = true;
    } else {
      propSel = -1;   /* rail is mission/summary — don't move the keyboard target */
      paintPropSel();
    }
  } else {
    propSel = -1;
    if (railMode === 'proposal'){ inspPropId = null; showAgSummary(); summaryShown = true; }   /* the rail's proposal is gone */
  }
  /* the summary reads propCache — repaint it whenever a fresh list lands while
     it's up (the deselect hoist shows it BEFORE this loader completes), unless
     this call already showed it */
  if (railMode === 'summary' && !summaryShown) showAgSummary();
}

function paintPropSel(){
  $all('.prow').forEach(r => r.classList.remove('psel'));
  const rows = propRows();
  if (propSel >= 0 && propSel < rows.length) rows[propSel].classList.add('psel');
}

const settling = new Set<string | null>();   /* a proposal mid-review must not double-fire (⌘X repeat / slow-click) */
/* One click accepts (user rule 2026-09-12: "it's not our job to block the user"). A missing
   rationale or confidence is shown on the row as information, never as a gate; accepted renames
   and comments stay undoable through History.
   returns null on success, else the reason (r4 L4: a shared slot mixed two settles' reasons);
   quiet = the bulk loop, which reports the reasons itself instead of one toast per row */
/* The class open in the Explorer re-renders after an accepted write — ANY accepted write: a
   rename lands in its own class's text and in every class that references the symbol (the
   2026-09-13 batch renamed Mtt/OFW/160 while 15Q was open and 15Q's text is where they show).
   The re-render follows a rename of the open class itself: the tab is re-keyed (classOpened
   with the previous fqcn) and the tree row retitled (classRenamed). One decompile per accept,
   one per bulk. */
async function rerenderOpenClass(){
  if (!S.currentClass) return;
  const oldFqcn = S.currentFqcn, oldAddr = S.currentSym && S.currentSym.addr;
  try { await loadSymbol(S.currentClass); } catch (e) { console.warn('re-render after accept', e); return; }
  if (oldFqcn && S.currentFqcn && oldFqcn !== S.currentFqcn && S.hooks.classRenamed)
    S.hooks.classRenamed(oldFqcn, oldAddr, S.currentFqcn, S.currentFqcn.split('.').pop() || S.currentFqcn);
  refreshRelationTree();                       // the RELATION tree shows the new names too, levels kept open
}

async function settle(id: string | null, accept: boolean, rowEl: Element | null, quiet?: boolean, deferRender?: boolean): Promise<string | null> {
  if (settling.has(id)) return 'already settling';
  const pp = propCache.find(x => x.id === id);
  if (pp && rowState(pp) !== 'pending') return 'not pending';   /* an applied row reverts (↶), it is not accepted twice */
  settling.add(id);
  try{
    const edit = rowEdits.get(id || '') || {};
    const params: OpParams = { id, action: accept ? 'accept' : 'reject' };
    if (accept && edit.editedName) params.newName = edit.editedName;   // the reviewer's edit rides on the accept
    const r = await tryOp('agent.review', params);
    /* the server refuses with a reason (E_BUSY: the proposal's target is not open, JEB kept the
       old name — a collision) — a console line was a dead click (b2-r2 H1); say it where the click
       happened AND on the row, and open the name editor there: the fix is one edit away */
    if (r.error){
      const msg = r.error.message;
      if (accept){
        edit.refusal = msg;
        rowEdits.set(id || '', edit);
        const p = propCache.find(x => x.id === id);
        if (p){ p.refusal = msg; p.editedName = edit.editedName; }
        repaintRow(id);
        if (!quiet && p && p.kind === 'rename'){
          const row = document.querySelector<HTMLElement>('.prow[data-pid="' + id + '"]');
          if (row) openNameEditor(row, p);
        }
      }
      if (!quiet) notice('review not applied — ' + msg, { err: true });
      return msg;
    }
    const unchanged = (r.data as Proposal | undefined)?.state === 'unchanged';
    const editedName = edit.editedName;
    rowEdits.delete(id || '');
    const p0 = propCache.find(x => x.id === id);
    if (accept && !unchanged && p0 && p0.kind === 'rename' && p0.symbol && !p0.symbol.includes('->') && S.hooks.classRenamed){
      /* a CLASS rename: its tab (active or not) and tree row follow at once — the open class's
         own re-render below would only re-key the active tab (user 2026-09-13) */
      const finalName = editedName || p0.newName || '';
      const oldSym = p0.symbol;
      const isAddr = /^L.*;$/.test(oldSym);
      const oldDotted = isAddr ? oldSym.slice(1, -1).replace(/\//g, '.').replace(/\$/g, '.') : oldSym;
      const newDotted = oldDotted.replace(/[^.]+$/, finalName);
      if (finalName) S.hooks.classRenamed(oldDotted, isAddr ? oldSym : undefined, newDotted, finalName);
    }
    lastSettledId = id;   /* the only vanish that earns auto-advance in loadProposals */
    if (rowEl){
      rowEl.classList.add('settled');
      const act = rowEl.querySelector('.pact')!;
      act.innerHTML = '<span class="pstamp ' + (accept ? 'ok' : 'no') + '">' +
        (accept ? (unchanged ? 'UNCHANGED' : '✓ ACCEPTED') : '✗ REJECTED') + '</span>';
      /* let the stamp actually render before the reload wipes the row */
      await new Promise(function(r){ setTimeout(r, 400); });
    }
    await Promise.all([loadProposals(), loadMissions()]);
    // accepted renames/comments land in History (agent badge) — refresh the dock
    if (S.hooks.historyChanged) S.hooks.historyChanged();
    if (S.hooks.marksChanged) S.hooks.marksChanged();
    if (accept && !unchanged && !deferRender) await rerenderOpenClass();
    return null;
  } finally { settling.delete(id); }
}

let inspReq = 0;
async function showProposalInspector(pid: string | null){
  const p = propCache.find(x => x.id === pid);
  if (!p) return;
  const my = ++inspReq;          /* post-await writes belong to the NEWEST selection only */
  inspPropId = pid;
  railMode = 'proposal';
  toggleAgi('agi-proposal');
  /* bind the rail buttons NOW, in the sync prelude — after an await they'd
     carry the PREVIOUS proposal's closure for the whole decompile round-trip
     (a silent wrong-edit window, review finding 1) */
  const accept = document.getElementById('agi-accept')!;
  const reject = document.getElementById('agi-reject')!;
  const revert = document.getElementById('agi-revert')!;
  const redo = document.getElementById('agi-redo')!;
  const st = rowState(p);
  accept.hidden = st !== 'pending';
  reject.hidden = st !== 'pending';
  revert.hidden = !(st === 'applied' && p.historyId);
  redo.hidden = !(st === 'reverted' && p.historyId);
  accept.onclick = function(){ settle(p.id, true, null); };
  reject.onclick = function(){ settle(p.id, false, null); };
  revert.onclick = function(){ revertRow(p.id, false); };
  redo.onclick = function(){ revertRow(p.id, true); };
  /* EVIDENCE is all from p — it belongs in the sync prelude. Written after the
     decompile await it shows the PREVIOUS proposal's symbol under this header
     for the whole round-trip (the identity of the edit under review!) */
  const ev = document.getElementById('agi-ev')!;
  ev.innerHTML = '';
  ['symbol: ' + (p.symbol || '—'), 'target: ' + (p.target || 'active'),
   'map: ' + (p.map || '—'), 'state: ' + st + (p.auto ? ' (policy)' : '')].forEach(function(t){
    const r = document.createElement('div');
    r.className = 'xrow';
    r.innerHTML = '<span class="led b"></span><span>' + esc(t) + '</span>';
    ev.appendChild(r);
  });
  const kc = document.getElementById('agi-kind')!;
  kc.textContent = String(p.kind || '').toUpperCase();
  kc.className = 'vchip vc-identity';
  document.getElementById('agi-conf')!.textContent =
    (p.confidence != null ? 'conf ' + p.confidence : 'conf —') + ' · ' + (p.agentId || '');
  document.getElementById('agi-rat')!.textContent = p.rationale || '(no rationale)';
  const body = document.getElementById('agi-code')!;
  const title = document.getElementById('agi-code-title');
  if (title) title.textContent = p.kind === 'verdict' ? 'MAP NODE' : 'CODE EXCERPT';   /* P0.5: a map proposal's excerpt is the node, not code */
  body.innerHTML = '<div class="cx"><span class="dim">' + (p.kind === 'verdict' ? 'reading node…' : 'decompiling…') + '</span></div>';   /* labels the async window (was an unlabelled hole) */
  // ghost diff: decompile the owning class and mark the proposed edit inline
  let shown = false;
  if (p.kind === 'rename' && p.symbol){
    const dec = await decompileFor(p);   // the proposal's own target, like the other ghosts (fix-review r4)
    if (my !== inspReq) return;   /* a newer selection owns the rail — drop everything below */
    body.innerHTML = '';          /* clear the 'decompiling…' placeholder before any append */
    if (dec.data && dec.data.text){
      const lines = dec.data.text.split('\n');
      const oldName = p.oldName || '';
      const at = lines.findIndex(t => t.includes(oldName + '('));
      const from = Math.max(0, (at < 0 ? 0 : at) - 1);
      lines.slice(from, Math.min(lines.length, from + 6)).forEach(function(t, i){
        const row = document.createElement('div');
        row.className = 'cx';
        const marked = t.replace(oldName + '(', '/* → ' + p.newName + ' */' + oldName + '(');
        row.innerHTML = '<span class="cxn">' + (from + i + 1) + '</span><span>' +
          (i === 1 && at >= 0 ? '<span class="gd-add">' + esc(marked) + '</span>' : esc(t)) + '</span>';
        body.appendChild(row);
      });
      shown = true;
    }
  }
  /* P0.5: comment / tag / verdict — three of the four kinds — fell to a
     JSON.stringify(p).slice(0,200) blob here. Each has its own ghost now. */
  else if (p.kind === 'comment') shown = await ghostComment(p, body, my);
  else if (p.kind === 'tag')     shown = await ghostTag(p, body, my);
  else if (p.kind === 'verdict') shown = await ghostVerdict(p, body, my);
  if (my !== inspReq) return;   /* a renderer answers false when superseded too — never paint the degrade over a newer selection */
  if (!shown) fieldRows(p, body);   /* labelled fields (clears the placeholder) — never a raw JSON blob */
}

/* ---- P0.5: per-kind proposal ghosts ---------------------------------------
   The review inspector is the surface that must let the reviewer judge the
   edit without leaving the queue (AGENTS_SPEC §3.1). Each kind ghosts its
   edit in the rename branch's idiom (.cx rows, .gd-add mark, 6-line window);
   a missing decompile/node degrades to a LABELLED head + ghost, and anything
   the renderers can't key on goes to fieldRows. The rename branch above is
   untouched. Every renderer re-checks inspReq after its await: a newer
   selection owns the rail (the round-trip rule the rename branch follows). */

/* mirrors map.js chipText: dark ink on the two light registry swatches */
function chipText(hex: string){
  return (hex === '#E69F00' || hex === '#F0E442') ? '#1c2430' : '#ffffff';
}
/* a verdict chip in the map's own styling: registry colour (map.type.list —
   the per-project truth map.js paints its inspector chip from) when the name
   is known there, else the static .vc-<name> class; an unknown name reads as
   vc-unknown rather than an invisible white-on-paper chip */
function verdictChip(name: unknown, registry: TypeEntry[] | undefined){
  const n = String(name || 'none');
  const t = (registry || []).find(function(x){ return x.name === n; });
  if (t && isHexColor(t.color))
    return '<span class="vchip" style="background:' + t.color + ';color:' + chipText(t.color) + '">' + esc(n).toUpperCase() + '</span>';
  const known = ['authored', 'mixed', 'identity', 'gap', 'dead'].indexOf(n) >= 0;
  return '<span class="vchip ' + (known ? 'vc-' + n : 'vc-unknown') + '">' + esc(n).toUpperCase() + '</span>';
}
/* method name of a JEB addr (Lpkg/C;->m(I)V → m); '' for a class/field addr —
   the excerpt then anchors at the class top instead of the first '(' */
function methodOf(sym: string | undefined){
  const m = /->([^(]+)\(/.exec(String(sym || ''));
  return m ? m[1] : '';
}
/* decompile bound to the proposal's target: a comment/tag body carries the
   resolved id, and a target-less op follows the server's active target — a
   different apk when another actor moved it (loadSymbol's desync). Proposals
   persist across restarts while target ids remint, so a dead id retries on
   the active target instead of dead-ending the excerpt. */
async function decompileFor(p: Proposal): Promise<TryResult<DecompileData>>{
  const params: OpParams = { symbol: p.symbol };
  if (p.target) params.target = p.target;
  let dec = await tryOp('code.decompile', params);
  if (dec.error && p.target && /target not found/.test(String(dec.error.message))){
    delete params.target;
    dec = await tryOp('code.decompile', params);
  }
  return dec;
}
/* one excerpt row; 'ghost' rows wrap (prose, not code — see shell.css) */
function cxRow(cls: string, n: string | number, html: string){
  const row = document.createElement('div');
  row.className = 'cx' + (cls ? ' ' + cls : '');
  row.innerHTML = '<span class="cxn">' + n + '</span><span>' + html + '</span>';
  return row;
}
/* the excerpt painter comment/tag share: a dim head (where), then the rename
   branch's window — up to 6 source lines from one above `at`, numbered — with
   the `ghost` rows (inner HTML, one per row, '+' in the gutter) inserted
   before the anchor line. `at` < 0 anchors at the excerpt top; no lines at
   all (decompile failed) paints head + ghost — the labelled degrade. */
function paintGhostExcerpt(body: HTMLElement, head: string, lines: string[], at: number, ghost: string[]){
  body.innerHTML = '';
  body.appendChild(cxRow('ghost', '', '<span class="dim">' + head + '</span>'));
  const from = Math.max(0, (at < 0 ? 0 : at) - 1);
  const anchor = at < 0 ? from : at;
  const slice = lines.slice(from, Math.min(lines.length, from + 6));
  const addGhost = function(){ ghost.forEach(function(g){ body.appendChild(cxRow('ghost', '+', g)); }); };
  if (!slice.length) addGhost();
  slice.forEach(function(t, i){
    if (from + i === anchor) addGhost();
    body.appendChild(cxRow('', from + i + 1, esc(t)));
  });
}
function excerptHead(p: Proposal, dec: TryResult<DecompileData>, ok: boolean){
  return esc(p.symbol) + (Number(p.line) > 0 ? ' · L' + Number(p.line) : '') +
    (ok ? '' : ' · excerpt unavailable' + (dec.error ? ': ' + esc(dec.error.message) : ''));
}
/* comment → the comment ghosted at its symbol (spec §3.1 "ghosted comment
   line"): one '+' row per line of newText, marked .gd-add like the rename's
   new name, above the method's own line (class top for a class addr). An
   empty body is what accept APPLIES (it clears the comment) — say so. */
async function ghostComment(p: Proposal, body: HTMLElement, my: number){
  if (!p.symbol) return false;
  const text = String(p.newText ?? '');
  const ghost = text.length
    ? text.split('\n').map(function(l){ return '<span class="gd-add">' + esc('// ' + l) + '</span>'; })
    : ['<span class="gd-ghost">(empty comment — accept clears the existing one)</span>'];
  const dec = await decompileFor(p);
  if (my !== inspReq) return false;
  const ok = !!(dec.data && dec.data.text);
  const lines = ok ? dec.data.text.split('\n') : [];
  const name = methodOf(p.symbol);
  const at = (ok && name) ? lines.findIndex(function(t){ return t.includes(name + '('); }) : -1;
  paintGhostExcerpt(body, excerptHead(p, dec, ok), lines, at, ghost);
  return true;
}
/* tag → the symbol excerpt with the tag ghosted at the tagged line (p.line is
   the resolved line tag.add stamped; a symbol-level tag anchors at the
   method). The chip is the queue row's own vchip, led by the .gtag
   glyph-margin square in the family's registry colour (tag.list families —
   var(--blue) when the family is unknown there, the TAGS dock's fallback),
   then the note. */
async function ghostTag(p: Proposal, body: HTMLElement, my: number){
  if (!p.symbol || !p.name) return false;
  const fam = String(p.name).split(':')[0];
  const tlParams: OpParams = { family: fam };
  if (p.target) tlParams.target = p.target;
  const [dec, tl] = await Promise.all([decompileFor(p), tryOp('tag.list', tlParams)]);
  if (my !== inspReq) return false;
  const f = ((tl.data || {} as TagList).families || []).find(function(x){ return x.name === fam; });
  const color = f && isHexColor(f.color) ? f.color : 'var(--blue)';
  const ghost = ['<span class="gtag" style="background:' + color + '"></span>' +
    '<span class="vchip vc-mixed">' + esc(p.name) + '</span>' +
    (p.note ? ' <span class="gd-ghost">' + esc(p.note) + '</span>' : '')];
  const ok = !!(dec.data && dec.data.text);
  const lines = ok ? dec.data.text.split('\n') : [];
  const line = Number(p.line) > 0 ? Number(p.line) : 0;
  const name = methodOf(p.symbol);
  const at = !ok ? -1
    : (line && line <= lines.length) ? line - 1
    : name ? lines.findIndex(function(t){ return t.includes(name + '('); }) : -1;
  paintGhostExcerpt(body, excerptHead(p, dec, ok), lines, at, ghost);
  return true;
}
/* verdict → "the affected map region" (spec §3.1): the node (map / id ·
   label · kind) and old → new as verdict chips in the map's registry colours
   (map.type.list — the same source map.js's inspector chip uses). A per-field
   proposal (p.field) diffs that field's verdict. The node's pending slot is
   normally THIS proposal (map.setverdict parks it there as it proposes); when
   it holds a different one, accept overrides it — say so. */
async function ghostVerdict(p: Proposal, body: HTMLElement, my: number){
  if (!p.map || !p.node) return false;
  const [nr, tr] = await Promise.all([tryOp('map.node', { id: p.map, node: p.node }), tryOp('map.type.list', {})]);
  if (my !== inspReq) return false;
  const reg = (tr.data || {} as TypeRegistry).verdicts || [];
  const d = nr.data || null;
  body.innerHTML = '';
  const where = esc(p.map) + ' / ' + esc(p.node) +
    (d ? (d.label ? ' · ' + esc(d.label) : '') + (d.kind ? ' · ' + esc(d.kind) : '')
       : ' · node unavailable' + (nr.error ? ': ' + esc(nr.error.message) : ''));
  body.appendChild(cxRow('ghost', '', '<span class="dim">' + where + '</span>'));
  let old = d ? (d.verdict || 'none') : '?';
  let label = '';
  if (p.field){
    const f = d ? (d.fields || []).find(function(x){ return x.field === p.field; }) : null;
    old = f ? (f.verdict || 'none') : (d ? 'none' : '?');
    label = '<span class="dim">field ' + esc(p.field) + ' </span>';
  }
  body.appendChild(cxRow('ghost', '+', label + verdictChip(old, reg) + ' → ' + verdictChip(p.verdict, reg)));
  const pend = d && d.pending;
  if (pend && (pend.verdict !== p.verdict || (pend.field || null) !== (p.field || null)))
    body.appendChild(cxRow('ghost', '', '<span class="gd-ghost">⏳ the node\'s pending slot now holds ' +
      esc(String(pend.verdict || '')).toUpperCase() + (pend.field ? ' on field ' + esc(pend.field) : '') +
      ' (a different proposal) — accept overrides it</span>'));
  return true;
}
/* the labelled degrade for whatever the renderers can't paint (a kind with
   no renderer — catalog — or a body missing the fields they key on): one row
   per payload field the header/evidence don't already show. Never a raw
   JSON.stringify(p) blob (P0.5). */
function fieldRows(p: Proposal, body: HTMLElement){
  body.innerHTML = '';
  const skip: Record<string, number> = { id: 1, kind: 1, state: 1, ts: 1, agentId: 1, confidence: 1, rationale: 1 };
  let n = 0;
  Object.keys(p).forEach(function(k){
    if (skip[k] || p[k] == null || p[k] === '') return;
    const v = typeof p[k] === 'object' ? JSON.stringify(p[k]) : String(p[k]);
    body.appendChild(cxRow('ghost', '', '<span class="dim">' + esc(k) + '</span> ' + esc(v)));
    n++;
  });
  if (!n) body.appendChild(cxRow('ghost', '', '<span class="dim">(no payload beyond the header)</span>'));
}

function toggleAgi(id: string){
  ['agi-proposal', 'agi-mission', 'agi-summary'].forEach(function(s){
    const el = document.getElementById(s); if (el) el.hidden = (s !== id);
  });
}

let propsAutoOpened = false;
let sumReq = 0;
function showAgSummary(){
  toggleAgi('agi-summary');
  railMode = 'summary';
  inspPropId = null;   /* the rail is on the summary, not a proposal */
  inspReq++;           /* supersede any in-flight proposal inspector */
  const my = ++sumReq;
  const el = document.getElementById('agi-sumrows')!;
  el.innerHTML = '';   /* never show the previous render as current while fetching */
  const paint = function(missions: Mission[]){
    /* same non-terminal semantic as the rail badge — they share a screen */
    const up = missions.filter(m => m.state !== 'done' && m.state !== 'stopped').length;
    const running = missions.filter(m => m.state === 'running').length;
    const render = function(blocked: string){
      /* the proposals row is propCache — which may be mission-FILTERED. Say so,
         rather than publishing a scoped count next to unscoped mission numbers */
      const propRow = propCache.filter(function(p){ return rowState(p) === 'applied'; }).length + ' edits applied · '
        + propCache.filter(function(p){ return rowState(p) === 'pending'; }).length + ' pending review' +
        (feedFilterMode !== 'all' ? ' (filtered: ' + feedFilterMode + ')' : '');
      el.innerHTML =
        '<div class="xrow"><span class="led p"></span><span>' + up + ' agent(s) up · ' +
          running + ' running · ' + missions.length + ' missions</span></div>' +
        '<div class="xrow"><span class="led o"></span><span>' + propRow + '</span></div>' +
        '<div class="xrow"><span class="led v"></span><span>' + blocked +
          ' blocked event(s)</span></div>';
    };
    render('—');   /* mission numbers paint NOW — never blank behind a slow/hung blocked fetch */
    tryOp('agent.activity', { kinds: 'blocked', count: 20 }).then(function(ar){
      if (my !== sumReq) return;
      if (!ar.error){   /* patch the one row when it lands; a full page means 'at least' */
        const n = (ar.data || []).length;
        render(n >= 20 ? '20+' : String(n));
      }
    });
  };
  const errRow = function(){
    el.innerHTML = '<div class="xrow"><span class="led o"></span><span>summary unavailable — backend slow or unreachable</span></div>';
  };
  const f = fetchMissionsPassive();   /* passive: no missionsSeq claim — a panel read never starves writers */
  const slowTimer = setTimeout(function(){
    if (my !== sumReq || el.children.length) return;   /* only when nothing painted yet */
    errRow();
  }, 8000);
  f.then(function(res){
    clearTimeout(slowTimer);
    if (my !== sumReq) return;
    /* fast OR late success both paint — a >8s response must not be stranded
       behind a timeout error row it could have replaced */
    if (res && res.list){ paint(res.list); return; }
    if (res && res.error) errRow();
  });
}

/* ==================================================================
   LLM PROVIDERS · MODEL PICKERS · THE REVERSING AGENT'S SETTINGS
   Two configurable areas (user directive 2026-09-09):
   · PROVIDERS (⚙ PROVIDERS, ⌘K "LLM providers…") — the endpoints: add an
     OpenAI-compatible provider, edit label / baseUrl / model list / LOCAL,
     set or clear its API key, delete a user-added one (llm.*).
   · MODEL CHOICE — per mission in the NEW MISSION card (provider → model
     within its family → effort), and per built-in task in the REVERSING
     AGENT window (task.list / task.configure): the right-click / ⚡ REVERSE
     routine is not a custom goal, so it is configured there, not typed.
================================================================== */
let providersCache: Provider[] = [];

/* CLI effort levels and the native Max contract of DeepSeek V4 / GLM 5.3. */
const EFFORTS: { v: string; label: string; cliOnly?: boolean }[] = [
  { v: '', label: 'effort: default' }, { v: 'low', label: 'effort: low' }, { v: 'medium', label: 'effort: medium' },
  { v: 'high', label: 'effort: high' }, { v: 'xhigh', label: 'effort: xhigh', cliOnly: true }, { v: 'max', label: 'effort: max', cliOnly: true },
];

/* the canned test provider stays out of the pickers unless the page runs
   under the QA driver (?qa=) — a user pinning a task to it got canned
   proposals with a green line (settings r1 LAT-2) */
function pickable(p: Provider, forTask: boolean): boolean {
  if (p.id === 'fixture' && !/[?&]qa=/.test(location.search)) return false;
  if (forTask && p.driver === 'builtin') return false;   // the scripted agent runs only triage-package goals
  return true;
}
/* the ⚙ PROVIDERS management window lists what a user CONFIGURES — real LLM vendors.
   The canned test provider and the scripted triage routine are not that: they show
   only behind the ?qa= debug flag. Deliberately separate from `pickable` — the
   routine stays offered in NEW MISSION (llm-anthropic-vendor design #2, B14/B16). */
function manageable(p: Provider): boolean {
  const qa = /[?&]qa=/.test(location.search);
  if (p.id === 'fixture' && !qa) return false;
  if (p.driver === 'builtin' && !qa) return false;
  return true;
}
function driverOf(providerId: string): string {
  const p = providersCache.find(function(x){ return x.id === providerId; });
  return p && p.driver ? p.driver : '';
}

/* the provider select: a placeholder first ("" = nothing picked — there is
   NO implicit default provider), then every registered provider; a key-less
   one is dimmed */
function fillProviderSel(sel: HTMLSelectElement, current: string, placeholder: string, forTask: boolean){
  sel.innerHTML = '';
  const none = document.createElement('option');
  none.value = ''; none.textContent = placeholder;
  sel.appendChild(none);
  providersCache.forEach(function(p){
    if (!pickable(p, forTask)) return;
    const o = document.createElement('option');
    o.value = p.id;
    /* a provider that cannot run right now says why — a missing CLI looked
       exactly as healthy as a keyed provider (settings r2 M1) */
    const bad = !p.configured;
    const name = p.driver === 'builtin' ? 'Scripted routine · triage-package' : p.label;   // the routine is not a vendor (B14)
    o.textContent = name + (p.local && p.driver !== 'builtin' ? ' · LOCAL' : '') + (bad ? (p.driver === 'cli' ? (p.installed === false ? ' · CLI not installed' : ' · not signed in') : ' · no key') : '');   // B2: installed ≠ signed in; the routine's "local" means "no network", not a local model (review B7)
    if (bad) o.className = 'nokey';
    sel.appendChild(o);
  });
  if (current && !providersCache.some(function(p){ return p.id === current && pickable(p, forTask); })){
    /* the stored provider is hidden from this picker (the fixture outside QA)
       or unknown: keep it as its own option so the select never silently
       empties and a SAVE cannot reset the depth by accident (r2 M2) */
    const o = document.createElement('option');
    o.value = current; o.textContent = current + ' · not offered here'; o.className = 'nokey';
    sel.appendChild(o);
  }
  sel.value = current && [...sel.options].some(function(o){ return o.value === current; }) ? current : '';
}

/* the model select follows the provider: its family, the default first;
   no provider = no choice (the server default's own model) */
function fillModelSel(sel: HTMLSelectElement, providerId: string, current: string){
  sel.innerHTML = '';
  const p = providersCache.find(function(x){ return x.id === providerId; });
  if (!p){
    const o = document.createElement('option'); o.value = ''; o.textContent = 'model: — pick a provider first —'; sel.appendChild(o);
    sel.disabled = true;
    return;
  }
  const models = (p.models && p.models.length ? p.models : (p.model ? [p.model] : [])).slice();
  if (p.model && !models.includes(p.model)) models.push(p.model);   // never reorder: order is presentation, not meaning (llm-config §4)
  if (!models.length){
    /* no model to pick — the select stays DISABLED so the spawn gate refuses
       (a "provider:" spec with no model reached the endpoint — settings r1 M7) */
    const o = document.createElement('option'); o.value = ''; o.textContent = 'model: (none listed — add one in ⚙ PROVIDERS)'; sel.appendChild(o);
    sel.disabled = true;
    return;
  }
  sel.disabled = false;
  /* name the source of these models so the Providers↔picker link is explicit:
     "z.ai models", "Claude Code (CLI) models" — answers "where do fable/opus come
     from" (they are the claude CLI provider's). Wrap ONLY the real options. */
  const og = document.createElement('optgroup');
  og.label = p.driver === 'builtin' ? 'Scripted routine'
           : p.auth === 'cli' ? (p.label || p.id) + ' — Claude Code login'
           : p.auth === 'api' ? (p.label || p.id) + ' — API'
           : (p.label || p.id) + ' models';   // B6: the routine is not a vendor here either   // name the connection where the vendor offers a choice (llm-anthropic-vendor B3)
  models.forEach(function(m){
    const o = document.createElement('option'); o.value = m; o.textContent = m + (m === p.model ? ' · default' : ''); og.appendChild(o);
  });
  sel.appendChild(og);
  sel.value = models.includes(current) ? current : (p.model && models.includes(p.model) ? p.model : models[0]);
}

function fillEffortSel(sel: HTMLSelectElement, current: string, driver?: string, model?: string){
  sel.innerHTML = '';
  const cli = driver === 'cli';
  const maxHttp = driver === 'http' && (model === 'deepseek-v4-pro' || model === 'deepseek-flash' || !!model?.startsWith('glm-5.3'));
  const allowed = (e: typeof EFFORTS[number]) => (!e.cliOnly || cli || (maxHttp && e.v === 'max')) && !(maxHttp && e.v === 'medium');
  EFFORTS.forEach(function(e){
    if (!allowed(e)) return;
    const o = document.createElement('option'); o.value = e.v; o.textContent = e.label; sel.appendChild(o);
  });
  sel.value = EFFORTS.some(function(e){ return e.v === current && allowed(e); }) ? current : '';
}

/* a "provider:model" spec → its halves (the pickers' current values) */
function splitSpec(spec: string | null | undefined): { provider: string; model: string } {
  const s = String(spec || '');
  const i = s.indexOf(':');
  return i > 0 ? { provider: s.slice(0, i), model: s.slice(i + 1) } : { provider: s, model: '' };
}

async function loadProviders(){
  const r = await tryOp('llm.providers', {});
  providersCache = r.data || [];
  const prov = document.getElementById('ag-spawn-provider') as HTMLSelectElement | null;
  const model = document.getElementById('ag-spawn-modelsel') as HTMLSelectElement | null;
  if (!prov || !model) return;
  const curP = prov.value, curM = model.value;
  fillProviderSel(prov, curP, '— provider: the depth\'s (⚡ REVERSING AGENT) —', true);   // forTask: the scripted test routine is not a human's choice
  fillModelSel(model, prov.value, curM);
  const effort = document.getElementById('ag-spawn-effort') as HTMLSelectElement | null;
  if (effort) fillEffortSel(effort, effort.value, driverOf(prov.value), model.value);
}

/* ---- the PROVIDERS dialog */
/* the open config dialog's closer: the two dialogs share a z-index, so an
   opener closes its sibling first instead of refusing silently (r1 M6, r2 L7) */
let activeDialogClose: (() => void) | null = null;

function openProvidersDialog(){
  const modal = document.getElementById('llmmodal')!;
  if (!modal.hidden) return;                                   // already up: no second escape handle (r1 M6)
  if (activeDialogClose) activeDialogClose();
  const err = document.getElementById('llm-err')!;
  err.textContent = '';
  const note0 = document.getElementById('llm-note'); if (note0) note0.textContent = '';   // a stale rename hint must not survive close/reopen (review r-f31a574 L11d)
  const escH = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){ modal.hidden = true; popEscape(escH); if (activeDialogClose === close) activeDialogClose = null; }
  activeDialogClose = close;
  document.getElementById('llm-close')!.onclick = close;
  modal.hidden = false;
  renderProviderRows();
  loadProviders().then(renderProviderRows);   /* fresh rows — keys and edits may have landed from another client */
}

/* every provider edit refreshes the registry and the card's pickers; the
   rows repaint only where asked (one row after its SAVE — the others may hold
   unsaved typing, r1 M8), the task window only while nothing in it is dirty */
async function afterProviderEdit(rowId?: string){
  await loadProviders();
  const host = document.getElementById('llm-rows')!;
  if (rowId){
    const fresh = providersCache.find(function(x){ return x.id === rowId; });
    const old = host.querySelector<HTMLElement>('.llmrow[data-id="' + CSS.escape(rowId) + '"]');
    if (fresh && old) old.replaceWith(buildProviderRow(fresh));
  }
  /* an ADD appends its row, a DELETE removes its row — the rows in between
     keep whatever the user typed (r2 L6) */
  $all<HTMLElement>('.llmrow[data-id]', host).forEach(function(row){
    if (!providersCache.some(function(p){ return p.id === row.dataset.id; })) row.remove();
  });
  providersCache.forEach(function(p){
    if (manageable(p) && !host.querySelector('.llmrow[data-id="' + CSS.escape(p.id) + '"]')) host.appendChild(buildProviderRow(p));
  });
  const tm = document.getElementById('taskmodal');
  if (tm && !tm.hidden && !taskDirty.size) await renderTaskGrid();
}

function renderProviderRows(){
  const host = document.getElementById('llm-rows')!;
  host.innerHTML = '';
  providersCache.filter(manageable).forEach(function(p){ host.appendChild(buildProviderRow(p)); });
}

/* one provider's CARD (llm-config design §7): label (an input — built-in labels
   persist now), the endpoint fields an http provider has, the MODEL LIST (rows
   with an explicit default radio, ×, add — never reordered here: order is the
   user's, §4), the key input, LOCAL, a RENAME affordance on the id for user-added
   cards (and `custom`), and SAVE / CLEAR KEY / DELETE. SAVE sends ONE
   llm.configure — never `create` (a card edits an existing id, §6.1). */
function buildProviderRow(p: Provider): HTMLElement {
  const err = document.getElementById('llm-err')!;
  const note = document.getElementById('llm-note');
  const row = document.createElement('div');
  /* a VENDOR that offers more than one way to connect (Anthropic: your Claude Code
     sign-in, or an API key) renders that choice under its name; its endpoint and key
     requirement are derived from the choice, so the baseUrl field and the LOCAL box
     never show on it (llm-anthropic-vendor design A2/B5/B7/B12). */
  const chooses = !!(p.authOptions && p.authOptions.length > 1);
  const template = p.id === 'custom' && !p.baseUrl && !(p.models && p.models.length);   // the blank OpenAI-compatible template, not a broken vendor (B9)
  row.className = 'llmrow' + (p.needsKey && !p.configured && !template ? ' nokey' : '');   // from the SAVED state only, never from an unsaved radio flip (B15)
  row.dataset.id = p.id;
  const editable = p.driver === 'http' && !chooses;          // endpoint fields
  const listable = p.driver !== 'builtin';                   // a model list (the CLI aliases too)
  const renamable = !p.builtin || p.id === 'custom';         // built-in ids are code-referenced; custom is the one exception (§6.2)
  const envVar = 'WORKBENCH_LLM_' + p.id.toUpperCase().replace(/[^A-Z0-9]/g, '_') + '_KEY';
  const cliMode = chooses ? p.auth === 'cli' : p.driver === 'cli';
  const status = p.configured ? '<span class="llmconf ok">configured ✓</span>'
    : template ? '<span class="llmconf tpl">template</span>'
    : cliMode ? '<span class="llmconf">' + (p.installed === false ? 'CLI not installed' : 'installed, not signed in') + '</span>'
    : '<span class="llmconf">no key</span>';
  const how = chooses ? (cliMode ? 'Claude Code login' : 'API key') : p.driver;   // the chip says HOW it connects, not a transport enum (B7)
  const cliNote = !cliMode ? ''
    : p.installed === false
      ? '<div class="llmnote">Claude Code CLI not installed — choose <b>Anthropic API key</b>, or install it: <code>curl -fsSL https://claude.ai/install.sh | bash</code>, then <code>claude auth login</code></div>'   // B10
      : !p.configured
        ? '<div class="llmnote">installed, not signed in — run <code>claude auth login</code></div>'   // B2
        : '<div class="llmnote">uses your Claude Code sign-in (<code>claude auth login</code>) — no API key</div>';   // B8
  const apiNote = chooses && !cliMode
    ? '<div class="llmlimits" data-tip="Anthropic\'s OpenAI-compatible endpoint: no prompt caching (history is re-sent every turn), tool-call JSON is not schema-enforced, unknown request fields are dropped without error. Good for probing; costlier on long missions. A native Messages-API client is the follow-up.">Anthropic\'s OpenAI-compatible endpoint — no prompt caching, no strict tool schemas, unknown fields dropped (hover for why). Key: stored here or <code>' + esc(envVar) + '</code>; a shell ANTHROPIC_API_KEY is not used.</div>'   // #5 + B11, one line (review B8)
    : '';
  row.innerHTML =
    '<div class="llmhead">' +
    '<input class="llmlabel-in" placeholder="label" spellcheck="false" aria-label="provider label" value="' + esc(p.label) + '">' +
    '<span class="llmid' + (renamable ? '' : ' nore') + '"' + (renamable ? ' role="button" tabindex="0" data-tip="rename this provider id"' : '') + '>' + esc(p.id) + '</span>' +
    (p.local ? '<span class="vchip pv">LOCAL</span>' : '') +
    '<span class="llmdriver">' + esc(how) + (p.builtin ? '' : ' · user-added') + '</span>' +
    status + '</div>' +
    (chooses
      ? '<div class="llmauth"><span class="llmauth-cap">how to connect</span>' +
        '<label><input type="radio" class="llmauth-in" name="llmauth-' + esc(p.id) + '" value="cli"' + (cliMode ? ' checked' : '') + '> Claude Code sign-in — no key</label>' +
        '<label><input type="radio" class="llmauth-in" name="llmauth-' + esc(p.id) + '" value="api"' + (!cliMode ? ' checked' : '') + '> Anthropic API key</label>' +
        '</div><div class="llmauth-note" hidden></div>' : '') +
    cliNote + apiNote +
    (template ? '<div class="llmnote">a blank OpenAI-compatible template — fill in a baseUrl and models, or use ＋ ADD PROVIDER below</div>' : '') +
    (editable
      ? '<input class="llmbase" placeholder="baseUrl — https://host/v1" spellcheck="false" aria-label="base URL" value="' + esc(p.baseUrl || '') + '">' : '') +
    (listable ? '<div class="llmlist" role="group" aria-label="models"></div>' : '') +
    (listable && p.model ? '<div class="llmspec">pins as <code>' + esc(p.id + ':' + p.model) + '</code></div>' : '') +
    (p.needsKey
      ? '<input class="llmkey" type="password" placeholder="API key — write-only, never displayed' + (p.configured ? ' (blank keeps the stored one)' : '') + '" spellcheck="false" autocomplete="off" aria-label="API key">' : '') +
    (p.needsKey && !chooses ? '<div class="llmnote">env override: <code>' + esc(envVar) + '</code> (beats the stored key)</div>' : '') +
    (editable ? '<label class="llmlocal"><input type="checkbox" class="llmlocal-in"' + (p.local ? ' checked' : '') + '> LOCAL — loopback, no key needed</label>' : '') +
    (editable || listable || p.needsKey || chooses
      ? '<div class="llmacts"><button class="mbtn llmsave">SAVE</button>' +
        (p.driver !== 'builtin' ? '<button class="mbtn llmtest" data-tip="wet test: one tiny completion — key valid? credit? does it reply?">TEST</button>' : '') +   // the ✓ says "has a key / signed in"; TEST proves a reply (review B1/B2)
        ((p.configured && p.needsKey) || p.keyStored ? '<button class="mbtn llmclearkey" data-tip="forget the stored key">CLEAR KEY</button>' : '') +   // keyStored: reachable on the CLI connection too (review B10)
        (p.builtin ? '' : '<button class="mbtn llmdel" data-tip="remove this provider and its key">DELETE</button>') + '</div>'
      : '');
  /* per-card dirty state (§7): any edit marks the card; SAVE clears it; RENAME is
     refused while dirty so the two ops never race on one card */
  const idEl = row.querySelector<HTMLElement>('.llmid');
  const dirty = function(){ row.dataset.dirty = '1'; };
  if (listable) buildModelList(row.querySelector<HTMLElement>('.llmlist')!, p.models || [], p.model || '', dirty, { providerId: p.id, discoverable: p.driver === 'http', keyed: p.configured, placeholder: p.auth === 'cli' ? 'add a CLI alias, e.g. opusplan, best, opus[1m]' : p.auth === 'api' ? 'add a model id, e.g. claude-opus-5' : undefined });
  $all<HTMLInputElement>('.llmlabel-in, .llmbase, .llmkey, .llmlocal-in', row).forEach(function(i){
    i.addEventListener('input', dirty); i.addEventListener('change', dirty);
  });
  /* the connection radio (llm-anthropic-vendor A1/A6/B15): flipping it on a CLEAN
     card stages an auth-only SAVE and explains, in place, what the switch replaces
     (the list) and which Reversing-Agent pins will read UNLISTED — read from
     task.list; nothing re-pins. On a dirty card the flip is refused (mirrors the
     rename guard). The card is NOT restyled as broken until the saved state says so. */
  const authNote = row.querySelector<HTMLElement>('.llmauth-note');
  $all<HTMLInputElement>('.llmauth-in', row).forEach(function(r){
    r.addEventListener('change', async function(){
      if (r.value === (p.auth || '')){ delete row.dataset.authPending; if (authNote) authNote.hidden = true; return; }
      if (row.dataset.dirty){
        const cur = row.querySelector<HTMLInputElement>('.llmauth-in[value="' + (p.auth || '') + '"]'); if (cur) cur.checked = true;
        err.textContent = 'save or discard this card\'s edits before changing how it connects'; return;
      }
      row.dataset.authPending = r.value;
      const lost = (p.models || []).slice();
      const pins: string[] = [];
      const tl = await tryOp('task.list', {});
      if (!tl.error && tl.data){
        (tl.data as unknown as { task: string; depths?: { depth: string; model?: string }[] }[]).forEach(function(t){
          (t.depths || []).forEach(function(d){
            const m = d.model || '';
            if (m.startsWith(p.id + ':') && lost.includes(m.slice(p.id.length + 1))) pins.push(t.task + '/' + d.depth + ' (' + m + ')');
          });
        });
      }
      if (authNote){
        authNote.hidden = false;
        authNote.textContent = 'switching to ' + (r.value === 'cli' ? 'Claude Code login' : 'an Anthropic API key') + ' replaces the model list (' + lost.join(', ') + ') with that connection\'s models'
          + (pins.length ? ' — these pins will read UNLISTED until you re-pick: ' + pins.join(', ') : '') + '. SAVE to confirm.';
      }
    });
  });
  const save = row.querySelector('.llmsave');
  if (save) save.addEventListener('click', async function(){
    err.textContent = ''; if (note) note.textContent = '';
    /* a staged connection change travels ALONE (A1): {id, auth}, then a repaint from
       the server — the re-seeded list, the key field shown/hidden, the status chip */
    if (row.dataset.authPending){
      if (row.dataset.dirty){   // flip-THEN-edit: the auth-only save would repaint and silently drop the typed key/label (review B9)
        err.textContent = 'a connection change saves alone — set the radio back, SAVE your other edits, then change the connection';
        return;
      }
      const ra = await tryOp('llm.configure', { id: p.id, auth: row.dataset.authPending });
      if (ra.error){ err.textContent = ra.error.message; return; }
      delete row.dataset.authPending;
      await afterProviderEdit(p.id);
      return;
    }
    const params: OpParams = { id: p.id };
    const labelEl = row.querySelector<HTMLInputElement>('.llmlabel-in');
    if (labelEl && labelEl.value.trim()) params.label = labelEl.value.trim();
    const baseEl = row.querySelector<HTMLInputElement>('.llmbase');
    if (baseEl) params.baseUrl = baseEl.value.trim();
    const list = row.querySelector<HTMLElement>('.llmlist');
    if (list){ const ml = readModelList(list); params.models = ml.models; if (ml.model) params.model = ml.model; }   // an ARRAY on the wire — a model id is opaque and may contain a comma (§4); an empty list CLEARS (review r-f31a574 L9)
    const localEl = row.querySelector<HTMLInputElement>('.llmlocal-in');
    if (localEl) params.local = localEl.checked;
    if (Object.keys(params).length > 1){
      const rc = await tryOp('llm.configure', params);
      if (rc.error){ err.textContent = rc.error.message; return; }
    }
    const keyEl = row.querySelector<HTMLInputElement>('.llmkey');
    if (keyEl && keyEl.value.trim()){
      const rk = await tryOp('llm.setkey', { id: p.id, key: keyEl.value.trim() });
      if (rk.error){ err.textContent = rk.error.message; return; }
      keyEl.value = '';
    }
    delete row.dataset.dirty;
    await afterProviderEdit(p.id);
  });
  /* TEST — the wet test (llm.probe): one tiny completion through the provider's default
     model. The ✓ chip only says "has a key / signed in"; this proves a REPLY, and names
     the served model, the latency and a one-line hint on failure (review B1/B2). */
  const test = row.querySelector<HTMLButtonElement>('.llmtest');
  if (test) test.addEventListener('click', async function(){
    err.textContent = ''; if (note) note.textContent = 'testing ' + p.id + '…';
    test.disabled = true;
    const rp = await tryOp('llm.probe', { id: p.id });
    test.disabled = false;
    if (rp.error){ if (note) note.textContent = ''; err.textContent = rp.error.message; return; }
    const d = rp.data || ({} as { ok?: boolean; model?: string; latencyMs?: number; reply?: string; servedModel?: string; error?: string; hint?: string });
    const secs = d.latencyMs !== undefined ? (d.latencyMs / 1000).toFixed(1) + ' s' : '';
    if (note) note.textContent = d.ok
      ? p.id + ' · ' + (d.model || '') + ' · ok · ' + secs + (d.servedModel && d.servedModel !== d.model ? ' · served ' + d.servedModel : '') + (d.reply ? ' · "' + d.reply + '"' : '')
      : p.id + ' · ' + (d.model || '') + ' · FAILED · ' + (d.error || '') + (d.hint ? ' ⇒ ' + d.hint : '');
  });
  const clear = row.querySelector('.llmclearkey');
  if (clear) clear.addEventListener('click', async function(){
    err.textContent = '';
    const rk = await tryOp('llm.setkey', { id: p.id, key: '' });   // an explicit blank key clears the stored one
    if (rk.error){ err.textContent = rk.error.message; return; }
    await afterProviderEdit(p.id);
    /* a key from the environment (WORKBENCH_LLM_<ID>_KEY) survives a clear —
       say so instead of looking like a dead button (r1 L7) */
    const again = providersCache.find(function(x){ return x.id === p.id; });
    if (again && again.configured) err.textContent = p.label + ': the key comes from the environment (' + envVar + ') — unset it there';
  });
  const del = row.querySelector<HTMLButtonElement>('.llmdel');
  if (del) del.addEventListener('click', async function(){
    err.textContent = '';
    /* two-step, no typing: the button becomes the confirm (the projmenu idiom) */
    if (!del.classList.contains('arm')){
      del.classList.add('arm'); del.textContent = 'DELETE — SURE?';
      setTimeout(function(){ del.classList.remove('arm'); del.textContent = 'DELETE'; }, 3000);
      return;
    }
    const rd = await tryOp('llm.remove', { id: p.id });
    if (rd.error){ err.textContent = rd.error.message; return; }
    await afterProviderEdit();
  });
  /* RENAME (§6.2/§7): click the id → an inline newId input → ✓ → llm.rename; the
     card is then replaced IN PLACE by afterProviderRename */
  if (idEl && renamable){
    const open = function(){
      if (row.dataset.dirty){ err.textContent = 'save or discard this card\'s edits before renaming it'; return; }
      if (row.querySelector('.llmren')) return;
      const wrap = document.createElement('span'); wrap.className = 'llmren';
      wrap.innerHTML = '<input placeholder="new id — the vendor, e.g. zai" aria-label="new provider id" spellcheck="false"><button class="mbtn llmren-ok" aria-label="rename">✓</button><button class="mbtn llmren-no" aria-label="cancel rename">✕</button>';
      idEl.replaceWith(wrap);
      const inp = wrap.querySelector('input')!; inp.focus();
      /* Esc must CANCEL the rename, not close the whole PROVIDERS window. The
         escape stack is a capture-phase window listener that fires before the
         event reaches this input (and stopImmediatePropagation()s), so a
         bubble-phase Escape handler here never ran — the modal's handler won and
         closed the dialog, losing every other card's unsaved typing. Push onto
         the stack at modal prio: a later push at the same prio sorts first, so
         this wins over the modal's own handler (review r-f31a574 M1). */
      const modalEl = document.getElementById('llmmodal');
      let escH: ReturnType<typeof pushEscape> | null = null;
      const cancel = function(){ if (escH) popEscape(escH); wrap.replaceWith(idEl); };
      escH = pushEscape(function(){
        /* if the window was closed by button, or this row was rebuilt, while the
           rename input is open, decline the Esc (so the palette/modal handler gets
           it, not this detached one) and self-clean off the stack (review F1). */
        if (!modalEl || modalEl.hidden || !wrap.isConnected){ if (escH) popEscape(escH); return false; }
        cancel(); return true;
      }, ESC.modal);
      wrap.querySelector('.llmren-no')!.addEventListener('click', cancel);
      const go = async function(){
        const newId = inp.value.trim(); if (!newId){ cancel(); return; }
        err.textContent = '';
        const rr = await tryOp('llm.rename', { id: p.id, newId: newId });
        if (rr.error){ err.textContent = rr.error.message; return; }
        if (escH) popEscape(escH);
        const d = rr.data || {};
        if (note) note.textContent = (d.hint ? d.hint + ' ' : '') + (d.tasksRewritten && d.tasksRewritten.length ? d.tasksRewritten.length + ' task pin' + (d.tasksRewritten.length === 1 ? '' : 's') + ' now name ' + newId : '');
        await afterProviderRename(p.id, newId, d.tasksRewritten || []);
      };
      wrap.querySelector('.llmren-ok')!.addEventListener('click', go);
      inp.addEventListener('keydown', function(e){
        if (e.key === 'Enter'){ e.preventDefault(); go(); }   // Escape → the escape stack (pushEscape above), not a bubble handler
      });
    };
    idEl.addEventListener('click', open);
    idEl.addEventListener('keydown', function(e){ if (e.key === 'Enter' || e.key === ' '){ e.preventDefault(); open(); } });
  }
  return row;
}

/* the MODEL LIST control (§7): one row per model — a radio marks the explicit
   default, × removes (the default's × arms first, then the next is promoted),
   an add input appends. Order is the user's; a model id is opaque (a ':' is
   part of it — ollama's qwen3:32b). `onEdit` marks the card dirty. */
type ModelListState = { models: string[]; def: string; discovered?: string[] };
function buildModelList(host: HTMLElement, models: string[], def: string, onEdit: () => void, opts?: { providerId?: string; discoverable?: boolean; keyed?: boolean; placeholder?: string }){
  const state: ModelListState = { models: models.slice(), def: def };
  const name = 'llmdef-' + Math.random().toString(36).slice(2, 8);
  const provId = opts && opts.providerId ? opts.providerId : '';
  const canDiscover = !!(opts && opts.discoverable && provId);
  const keyed = !!(opts && opts.keyed);   // discovery authenticates with the stored key — no key ⇒ fetch is disabled with a hint (review r-853c5f9 #3)
  const addModel = function(m: string){ m = m.trim(); if (!m) return; if (!state.models.includes(m)) state.models.push(m); if (!state.def) state.def = m; onEdit(); paint(); };
  const paint = function(){
    host.innerHTML = '';
    state.models.forEach(function(m){
      const r = document.createElement('div'); r.className = 'llmm';
      const isDef = m === state.def;
      r.innerHTML = '<input type="radio" name="' + name + '" aria-label="default model"' + (isDef ? ' checked' : '') + '>' +
        '<span class="llmmid" title="' + esc(m) + '">' + esc(m) + '</span>' +
        (m === 'claude-code' ? '<span class="llmmsub">the CLI\'s own default</span>' : '') +   // not a peer of fable/opus/sonnet — it means "no --model flag" (B13)
        (isDef ? '<span class="llmdef">default</span>' : '') +
        '<span class="llmmx" role="button" tabindex="0" aria-label="remove ' + esc(m) + '" data-tip="remove this model">×</span>';
      r.querySelector('input')!.addEventListener('change', function(){ state.def = m; onEdit(); paint(); });
      const x = r.querySelector<HTMLElement>('.llmmx')!;
      const rm = function(){
        if (isDef && !x.classList.contains('arm')){   // the default's × always arms first — including the LAST model, whose removal clears the list + default (review r-f31a574 L11a)
          x.classList.add('arm'); x.textContent = 'remove default?';
          setTimeout(function(){ x.classList.remove('arm'); x.textContent = '×'; }, 3000);
          return;
        }
        state.models = state.models.filter(function(y){ return y !== m; });
        if (state.def === m) state.def = state.models[0] || '';           // removing the default promotes the next
        onEdit(); paint();
      };
      x.addEventListener('click', rm);
      x.addEventListener('keydown', function(e){ if (e.key === 'Enter' || e.key === ' '){ e.preventDefault(); rm(); } });
      host.appendChild(r);
    });
    if (canDiscover){
      /* discovery-first (design): ↻ fetch pulls the endpoint's models into the
         dropdown; choosing one adds it. Manual entry stays as a fallback. The
         fetched set lives on state so it survives a repaint (no re-fetch per add). */
      const cmpModel = function(a: string, b: string){ return a.localeCompare(b, undefined, { sensitivity: 'base' }) || (a < b ? -1 : a > b ? 1 : 0); };
      const avail = [...(state.discovered || [])].sort(cmpModel).filter(function(d){ return !state.models.includes(d); });
      const pick = document.createElement('div'); pick.className = 'llmmadd';
      const sel = document.createElement('select'); sel.className = 'llmmadd-sel'; sel.setAttribute('aria-label', 'add a discovered model');
      const ph = document.createElement('option'); ph.value = '';
      ph.textContent = !keyed ? '＋ add a model — add a key to fetch'
        : !state.discovered ? '＋ add a model — ↻ fetch first'
        : state.discovered.length === 0 ? '(the endpoint listed no models — type one below)'   // empty [] is truthy: distinguish "none" from "all added" (review r-853c5f9 #2)
        : avail.length ? '＋ add a model…'
        : '(all discovered models added)';
      sel.appendChild(ph);
      avail.forEach(function(d){ const o = document.createElement('option'); o.value = d; o.textContent = d; sel.appendChild(o); });
      sel.disabled = !avail.length;
      sel.addEventListener('change', function(){ if (sel.value) addModel(sel.value); });
      const fetchBtn = document.createElement('button'); fetchBtn.className = 'mbtn llmmadd-fetch'; fetchBtn.type = 'button';
      fetchBtn.textContent = '↻ fetch';
      fetchBtn.disabled = !keyed;
      fetchBtn.setAttribute('data-tip', keyed ? "fetch this provider's models from its endpoint" : 'add a key and SAVE first, then fetch');
      fetchBtn.addEventListener('click', async function(){
        const err = document.getElementById('llm-err'); if (err) err.textContent = '';
        const rowEl = host.closest('.llmrow') as HTMLElement | null;
        if (rowEl && rowEl.dataset.dirty){ if (err) err.textContent = 'save this card first — fetch queries the saved endpoint, not your unsaved edits'; return; }
        fetchBtn.disabled = true; fetchBtn.textContent = 'fetching…';
        const rr = await tryOp('llm.models', { id: provId });
        fetchBtn.disabled = false; fetchBtn.textContent = '↻ fetch';
        if (rr.error){ if (err) err.textContent = rr.error.message; return; }
        state.discovered = [...(rr.data?.models ?? [])].sort(cmpModel);
        paint();
      });
      pick.appendChild(sel); pick.appendChild(fetchBtn);
      host.appendChild(pick);
      const man = document.createElement('div'); man.className = 'llmmadd llmmadd-manual';
      const minp = document.createElement('input');
      minp.placeholder = 'or type a model id'; minp.setAttribute('aria-label', 'type a model id'); minp.spellcheck = false; minp.autocomplete = 'off';
      const mbtn = document.createElement('button'); mbtn.className = 'mbtn llmmadd-go'; mbtn.type = 'button'; mbtn.textContent = 'ADD';
      man.appendChild(minp); man.appendChild(mbtn);
      if (avail.length){
        const dl = document.createElement('datalist'); dl.id = name + '-dl';
        avail.forEach(function(d){ const o = document.createElement('option'); o.value = d; dl.appendChild(o); });
        minp.setAttribute('list', dl.id);
        man.appendChild(dl);
      }
      const mgo = function(){ addModel(minp.value); const again = host.querySelector<HTMLInputElement>('.llmmadd-manual input'); if (again){ again.value = ''; again.focus(); } };
      mbtn.addEventListener('click', mgo);
      minp.addEventListener('keydown', function(e){ if (e.key === 'Enter'){ e.preventDefault(); mgo(); } });
      host.appendChild(man);
    } else {
      const add = document.createElement('div'); add.className = 'llmmadd';
      const ph = opts && opts.placeholder ? opts.placeholder : 'add a model id, e.g. glm-5.3';   // a vendor's own example, not another vendor's (review B11)
      add.innerHTML = '<input placeholder="' + esc(ph) + '" aria-label="add a model" spellcheck="false"><button class="mbtn llmmadd-go" type="button">ADD</button>';
      const inp = add.querySelector('input')!;
      const go = function(){ addModel(inp.value); const again = host.querySelector<HTMLInputElement>('.llmmadd input'); if (again){ again.value = ''; again.focus(); } };
      add.querySelector('.llmmadd-go')!.addEventListener('click', go);
      inp.addEventListener('keydown', function(e){ if (e.key === 'Enter'){ e.preventDefault(); go(); } });
      host.appendChild(add);
    }
  };
  paint();
  (host as HTMLElement & { _llm?: ModelListState })._llm = state;
}
function readModelList(host: HTMLElement): { models: string[]; model: string } {
  const st = (host as HTMLElement & { _llm?: ModelListState })._llm;
  return st ? { models: st.models.slice(), model: st.def } : { models: [], model: '' };
}

/* a RENAME replaces the old card IN PLACE (same index) with the new provider's —
   the orphan-sweep-then-append of afterProviderEdit would move it to the bottom —
   paints a re-seeded `custom`, and reaches a dirty REVERSING AGENT grid by
   re-mapping its pending picks (§7) */
async function afterProviderRename(oldId: string, newId: string, rewritten: { task: string; depth: string; model: string }[]){
  await loadProviders();
  const host = document.getElementById('llm-rows')!;
  const fresh = providersCache.find(function(x){ return x.id === newId; });
  const old = host.querySelector<HTMLElement>('.llmrow[data-id="' + CSS.escape(oldId) + '"]');
  if (fresh && old) old.replaceWith(buildProviderRow(fresh));
  else if (fresh) host.appendChild(buildProviderRow(fresh));
  providersCache.forEach(function(p){
    if (manageable(p) && !host.querySelector('.llmrow[data-id="' + CSS.escape(p.id) + '"]')) host.appendChild(buildProviderRow(p));   // e.g. the re-seeded `custom`
  });
  $all<HTMLElement>('.llmrow[data-id]', host).forEach(function(row){
    if (!providersCache.some(function(p){ return p.id === row.dataset.id; })) row.remove();
  });
  const tm = document.getElementById('taskmodal');
  if (tm && !tm.hidden) await remapTaskGrid(oldId, newId, rewritten);
}

/* the dirty-grid re-map (§7): harvest each dirty depth's five controls, rewrite a
   pending provider pick oldId → newId, repaint (renderTaskGrid refetches and
   clears taskDirty), re-apply the harvest, re-mark the depths — so a rename
   never leaves a stale spec that SAVE would send as E_SYNTAX */
async function remapTaskGrid(oldId: string, newId: string, rewritten: { task: string; depth: string; model: string }[]){
  const grid = document.getElementById('task-grid')!;
  const note = document.getElementById('task-note');
  const pending: { depth: string; prov: string; model: string; effort: string; turns: string; budget: string }[] = [];
  taskDirty.forEach(function(depth){
    const q = function(cls: string){ return grid.querySelector<HTMLInputElement | HTMLSelectElement>('.' + cls + '[data-depth="' + depth + '"]'); };
    const prov = q('tprov'); if (!prov) return;
    const g = function(cls: string){ const el = q(cls); return el ? el.value : ''; };
    pending.push({ depth: depth, prov: prov.value === oldId ? newId : prov.value, model: g('tmodel'), effort: g('teffort'), turns: g('tturns'), budget: g('tbudget') });
  });
  await renderTaskGrid();
  pending.forEach(function(pd){
    const q = function(cls: string){ return grid.querySelector<HTMLInputElement | HTMLSelectElement>('.' + cls + '[data-depth="' + pd.depth + '"]'); };
    const prov = q('tprov'); if (prov){ prov.value = pd.prov; prov.dispatchEvent(new Event('change')); }   // refills the model picker for the provider
    const model = q('tmodel'); if (model) model.value = pd.model;
    const effort = q('teffort'); if (effort) effort.value = pd.effort;
    const turns = q('tturns'); if (turns) turns.value = pd.turns;
    const budget = q('tbudget'); if (budget) budget.value = pd.budget;
    taskDirty.add(pd.depth);
  });
  if (note) note.textContent = oldId + ' was renamed to ' + newId
    + (pending.length ? ' — your unsaved edits were kept and updated' : '')
    + (rewritten.length ? ' · ' + rewritten.length + ' saved pin' + (rewritten.length === 1 ? '' : 's') + ' rewritten' : '');
}

/* the ADD PROVIDER form (index.html #llm-add): an OpenAI-compatible endpoint.
   Sends llm.configure with create:true — the gate that keeps a stale SAVE or
   MCP client from resurrecting a renamed-away id (§6.1); the models field is
   the same list control as the cards, seeded empty. */
function initProviderAdd(){
  const add = document.getElementById('llm-new-add');
  if (!add) return;
  const field = function(id: string){ return document.getElementById(id) as HTMLInputElement; };
  const listEl = document.getElementById('llm-new-list');
  if (listEl) buildModelList(listEl, [], '', function(){});
  add.addEventListener('click', async function(){
    const err = document.getElementById('llm-err')!;
    err.textContent = '';
    const id = field('llm-new-id').value.trim();
    if (!id){ err.textContent = 'a provider needs an id — the vendor as a lowercase slug (it names the key: WORKBENCH_LLM_<ID>_KEY); put model versions in the list'; return; }
    const params: OpParams = { id: id, create: true, label: field('llm-new-label').value.trim() || id, baseUrl: field('llm-new-base').value.trim(), local: field('llm-new-local').checked };
    if (listEl){ const ml = readModelList(listEl); if (ml.models.length){ params.models = ml.models; params.model = ml.model; } }   // array on the wire, not a comma-join (review r-f31a574 L9)
    const rc = await tryOp('llm.configure', params);
    if (rc.error){ err.textContent = rc.error.message; return; }
    const key = field('llm-new-key').value.trim();
    if (key){
      const rk = await tryOp('llm.setkey', { id: id, key: key });
      if (rk.error){ err.textContent = rk.error.message; return; }
    }
    ['llm-new-id', 'llm-new-label', 'llm-new-base', 'llm-new-key'].forEach(function(x){ field(x).value = ''; });
    field('llm-new-local').checked = false;
    if (listEl) buildModelList(listEl, [], '', function(){});
    await afterProviderEdit();
  });
}

/* ---- the REVERSING AGENT window (task.list / task.configure): per depth a
   provider, a model in its family, an effort, a turn cap and a budget —
   the model has NO default (an unconfigured depth spawns nothing), the caps
   do; the last line says what the next ⚡ Reverse resolves to. Depths the
   user touched are tracked (taskDirty) so SAVE writes only those (r1 M3). */
const taskDirty = new Set<string>();
async function renderTaskGrid(){
  const grid = document.getElementById('task-grid')!;
  const err = document.getElementById('task-err')!;
  const r = await tryOp('task.list', {});
  if (r.error){ err.textContent = r.error.message; return; }
  const task = (r.data || []).find(function(t){ return t.task === 'reverse'; });
  grid.innerHTML = '';
  if (!task){ err.textContent = 'the backend lists no reverse task'; return; }
  const depths = task.depths || [];
  const cell = function(html: string, cls?: string){ const d = document.createElement('div'); d.className = cls || ''; d.innerHTML = html; grid.appendChild(d); return d; };
  cell('', 'th');
  depths.forEach(function(d){ cell(esc(d.depth).toUpperCase() + '<span class="tsub">' + (d.depth === 'quick' ? 'the method alone' : 'the method and its call tree') + '</span>', 'th'); });
  const rowsSpec: { key: string; label: string; tip: string }[] = [
    { key: 'provider', label: 'provider', tip: 'which provider runs this depth — nothing runs until one is picked' },
    { key: 'model', label: 'model', tip: "a model within the provider's family — default: the provider's own" },
    { key: 'effort', label: 'effort', tip: "how hard it thinks — the claude CLI's --effort / an http provider's reasoning_effort" },
    { key: 'turns', label: 'turn cap', tip: 'where it stops: tool calls (CLI) or loop turns (http)' },
    { key: 'budget', label: 'budget', tip: 'token ceiling — metered on http providers only' },
  ];
  taskDirty.clear();
  /* the caps' placeholders and the budget's relevance follow the driver of the
     depth's CURRENT pick: CLI turns are tool calls (2× the http loop's) and the
     budget meters http providers only (r1 M4, LAT-1) */
  const capHints = function(depth: string, driver: string){
    const d = depths.find(function(x){ return x.depth === depth; })!;
    const turns = grid.querySelector<HTMLInputElement>('.tturns[data-depth="' + depth + '"]');
    const budget = grid.querySelector<HTMLInputElement>('.tbudget[data-depth="' + depth + '"]');
    if (turns){
      turns.disabled = !!d.turnsUnlimited;
      if (d.turnsUnlimited) turns.value = '';
      turns.placeholder = d.turnsUnlimited ? 'no turn limit (headless)' : 'default: ' + (driver === 'cli' ? d.defaults.turnsCli + ' (CLI tool calls)' : driver ? d.defaults.turnsLoop + ' (http loop turns)' : d.defaults.turnsCli + ' CLI / ' + d.defaults.turnsLoop + ' http');
    }
    if (budget){
      budget.disabled = !!d.budgetUnlimited && driver === 'http';
      if (budget.disabled) budget.value = '';
      budget.placeholder = budget.disabled ? 'no mission budget limit (headless)' : 'default: ' + d.defaults.budget + (driver === 'cli' ? ' — not metered on the CLI' : '');
      budget.classList.toggle('dim', driver === 'cli');
      budget.title = driver === 'cli'
        ? 'the claude CLI reports no token usage: this budget is advisory text in its prompt' + (d.turnsUnlimited ? '; headless missions have no turn limit' : ', the turn cap is what stops it')
        : '';
    }
  };
  const mark = function(depth: string){ taskDirty.add(depth); };
  rowsSpec.forEach(function(spec){
    cell('<span data-tip="' + esc(spec.tip) + '">' + esc(spec.label) + '</span>', 'tlabel');
    depths.forEach(function(d){
      const cur = splitSpec(d.model);
      const c = cell('', 'tcell');
      if (spec.key === 'provider'){
        const sel = document.createElement('select'); sel.className = 'tprov'; sel.dataset.depth = d.depth;
        fillProviderSel(sel, cur.provider, '— not configured —', true);
        sel.addEventListener('change', function(){
          mark(d.depth);
          const ms = grid.querySelector<HTMLSelectElement>('.tmodel[data-depth="' + d.depth + '"]');
          if (ms) fillModelSel(ms, sel.value, '');
          const es = grid.querySelector<HTMLSelectElement>('.teffort[data-depth="' + d.depth + '"]');
          if (es) fillEffortSel(es, es.value, driverOf(sel.value), ms?.value);
          capHints(d.depth, driverOf(sel.value));
        });
        c.appendChild(sel);
      } else if (spec.key === 'model'){
        const sel = document.createElement('select'); sel.className = 'tmodel'; sel.dataset.depth = d.depth;
        fillModelSel(sel, cur.provider, cur.model);
        sel.addEventListener('change', function(){
          mark(d.depth);
          const es = grid.querySelector<HTMLSelectElement>('.teffort[data-depth="' + d.depth + '"]');
          const ps = grid.querySelector<HTMLSelectElement>('.tprov[data-depth="' + d.depth + '"]');
          if (es && ps) fillEffortSel(es, es.value, driverOf(ps.value), sel.value);
        });
        c.appendChild(sel);
      } else if (spec.key === 'effort'){
        const sel = document.createElement('select'); sel.className = 'teffort'; sel.dataset.depth = d.depth;
        fillEffortSel(sel, d.effort || '', driverOf(cur.provider), cur.model);
        sel.addEventListener('change', function(){ mark(d.depth); });
        c.appendChild(sel);
      } else {
        const inp = document.createElement('input');
        inp.type = 'number'; inp.min = '0'; inp.className = 't' + spec.key; inp.dataset.depth = d.depth;
        const val = spec.key === 'turns' ? d.turns : d.budget;
        inp.value = val > 0 ? String(val) : '';
        inp.addEventListener('input', function(){ mark(d.depth); });
        c.appendChild(inp);
      }
    });
  });
  cell('next spawn', 'tlabel');
  depths.forEach(function(d){
    capHints(d.depth, driverOf(splitSpec(d.model).provider));
    const res = d.resolved || {};
    let txt: string, warn = false;
    if (res.unconfigured){ txt = 'NOT CONFIGURED — pick a provider and a model; ⚡ Reverse ' + d.depth + ' spawns nothing until then'; warn = true; }
    else if (res.unresolved){ txt = 'UNRESOLVED — ' + (res.model || '') + ' (provider removed?) — pick again'; warn = true; }
    else if (res.unlisted){ txt = 'UNLISTED — ' + (res.model || '') + ' is no longer listed under ' + (res.providerLabel || res.provider || '') + ' — pick a listed model (the pin was kept, not re-pointed)'; warn = true; }
    else { txt = (res.providerLabel || '') + ' · ' + (res.model || '') + (res.local ? ' · LOCAL' : '') + (res.configured === false ? ' · NOT CONFIGURED (no key, or Claude Code not signed in)' : ''); warn = res.configured === false; }
    const c = cell(esc(txt), 'tresolved' + (warn ? ' warn' : ''));
    c.dataset.tip = txt;                                       // the cell ellipsises (r3 L5)
  });
}

function openTaskDialog(){
  const modal = document.getElementById('taskmodal')!;
  if (!modal.hidden) return;                                   // already up (r1 M6)
  if (activeDialogClose) activeDialogClose();
  const err = document.getElementById('task-err')!;
  const note = document.getElementById('task-note')!;
  err.textContent = ''; note.textContent = '';
  const escH = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){ modal.hidden = true; popEscape(escH); if (activeDialogClose === close) activeDialogClose = null; }
  activeDialogClose = close;
  document.getElementById('task-close')!.onclick = close;
  document.getElementById('task-save')!.onclick = async function(){
    err.textContent = '';
    const grid = document.getElementById('task-grid')!;
    /* only the depths the user touched are written — an untouched SAVE over a
       stale picker used to reset a depth to nothing (r1 M3) */
    const depths = $all<HTMLSelectElement>('.tprov', grid).map(function(s){ return s.dataset.depth!; }).filter(function(d){ return taskDirty.has(d); });
    if (!depths.length){ note.textContent = 'nothing changed'; return; }
    note.textContent = 'saving…';
    for (const depth of depths){
      const q = function(cls: string){ return grid.querySelector<HTMLInputElement | HTMLSelectElement>('.' + cls + '[data-depth="' + depth + '"]')!; };
      const prov = q('tprov').value, model = q('tmodel').value;
      const params: OpParams = {
        task: 'reverse', depth: depth,
        model: prov ? prov + ':' + model : '',
        effort: q('teffort').value,
        turns: parseInt(q('tturns').value, 10) || 0,
        budget: parseInt(q('tbudget').value, 10) || 0,
      };
      if (q('tturns').disabled) delete params.turns; // preserve the stored value while headless overrides it
      if (q('tbudget').disabled) delete params.budget;
      const rs = await tryOp('task.configure', params);
      if (rs.error){ err.textContent = depth + ': ' + rs.error.message; note.textContent = ''; return; }
    }
    await renderTaskGrid();
    note.textContent = 'saved — the next ⚡ Reverse runs on these';
  };
  modal.hidden = false;
  loadProviders().then(renderTaskGrid);                        // always the live registry (r1 M3)
}

/* ==================================================================
   spawn dialog (scripted triage or claude)
================================================================== */
/* AGENT WRITES — review first | apply directly (backend ReviewPolicy: review.policy / review.configure).
   The gate is a switch on the application side, not a property of the tools (user, 2026-09-18):
   headless and batch runs apply directly; every direct write is a History entry with the agent
   as actor, undoable one by one. Kinds and a confidence floor narrow what applies directly. */
interface ReviewPolicyData { mode?: string; storedMode?: string; envOverride?: string; kinds?: string[]; minConfidence?: number }
async function loadReviewPolicy(){
  const r = await tryOp('review.policy', {});
  const d = (r.data || {}) as ReviewPolicyData;
  const sw = document.getElementById('rv-switch'), opts = document.getElementById('rv-opts');
  if (!sw || !opts) return;
  if (r.error){ sw.hidden = true; opts.hidden = true; return; }   // an older backend: no switch
  sw.hidden = false;
  $all<HTMLButtonElement>('.chip', sw).forEach(function(c){
    c.classList.toggle('on', c.getAttribute('data-mode') === d.mode);
    c.disabled = !!d.envOverride;
  });
  opts.hidden = d.mode !== 'auto';
  $all<HTMLInputElement>('input[data-kind]', opts).forEach(function(c){ c.checked = (d.kinds || []).includes(c.getAttribute('data-kind') || ''); c.disabled = !!d.envOverride && false; });
  const mc = document.getElementById('rv-minconf') as HTMLInputElement | null;
  if (mc && document.activeElement !== mc) mc.value = String(d.minConfidence || 0);
  const note = document.getElementById('rv-note');
  if (note) note.textContent = d.envOverride ? 'set by the server environment: ' + d.envOverride : '';
}
async function configureReview(params: OpParams){
  const r = await tryOp('review.configure', params);
  if (r.error) notice('review setting not applied — ' + r.error.message, { err: true });
  await loadReviewPolicy();
}
function initReviewSwitch(){
  const sw = document.getElementById('rv-switch'), opts = document.getElementById('rv-opts');
  if (!sw || !opts) return;
  $all<HTMLButtonElement>('.chip', sw).forEach(function(c){
    c.addEventListener('click', function(){ configureReview({ mode: c.getAttribute('data-mode') || 'manual' }); });
  });
  $all<HTMLInputElement>('input[data-kind]', opts).forEach(function(c){
    c.addEventListener('change', function(){
      const kinds = $all<HTMLInputElement>('input[data-kind]', opts).filter(function(x){ return x.checked; }).map(function(x){ return x.getAttribute('data-kind'); }).join(',');
      configureReview({ kinds });
    });
  });
  const mc = document.getElementById('rv-minconf') as HTMLInputElement | null;
  if (mc) mc.addEventListener('change', function(){
    const v = parseFloat(mc.value);
    configureReview({ minConfidence: isFinite(v) ? Math.max(0, Math.min(1, v)) : 0 });
  });
  loadReviewPolicy();
}
/* a write applied directly lands in the open class's text (and in History) with nobody clicking
   ✓ — re-render shortly after the last AUTO-APPLIED row, whatever view is up */
let autoAppliedTimer = 0;
function noteAutoApplied(){
  window.clearTimeout(autoAppliedTimer);
  autoAppliedTimer = window.setTimeout(function(){
    if (S.hooks.historyChanged) S.hooks.historyChanged();
    if (S.hooks.marksChanged) S.hooks.marksChanged();
    rerenderOpenClass();
  }, 1200);
}

let prefillSubject: (() => void) | null = null;   /* the view opens with the open symbol as the subject (agentsOpened) */
function initSpawn(){
  const card = document.getElementById('ag-spawncard')!;
  const subjectEl = document.getElementById('ag-spawn-subject') as HTMLInputElement;
  const goalEl = document.getElementById('ag-spawn-goal') as HTMLTextAreaElement;
  const useBtn = document.getElementById('ag-spawn-use') as HTMLButtonElement;
  const scopeKind = document.getElementById('ag-spawn-scopekind') as HTMLSelectElement;
  const scopeEl = document.getElementById('ag-spawn-scope') as HTMLInputElement;
  const btn = document.getElementById('ag-spawn-btn') as HTMLButtonElement;
  const msg = document.getElementById('ag-spawn-msg');
  /* The card (rebuilt 2026-09-13 — it had grown out of the test harness: a "triage-package"
     placeholder, a write-scope grammar in a 30-char field, no way to name a subject):
     SUBJECT (the symbol the mission is about — ◎ takes the caret's or the open class), GOAL
     (free text; blank with a subject = "reverse <subject>", the ⚡ REVERSE gesture's goal),
     DEPTH (quick/deep → the brief, turn cap and token budget of ⚡ REVERSING AGENT, like the
     ⚡ gesture), EDITS ALLOWED IN (the write-scope, spelled out), and the provider/model/
     effort/caps folded away as overrides. An empty subject AND goal is a gate; a failure stays
     under the card with the fields intact. */
  const HINT = 'name a subject (◎ takes the open symbol), a goal, or both — the mission runs in the feed, its edits land in EDITS';
  const say = function(text: string, isErr?: boolean){
    if (!msg) return;
    msg.textContent = text || '';
    msg.classList.toggle('err', !!isErr);
  };
  let busy = false, depth: 'quick' | 'deep' = 'deep', scopeTouched = false;
  const gate = function(){
    const empty = !subjectEl.value.trim() && !goalEl.value.trim();
    btn.disabled = empty || busy;
    if (empty) say(HINT);
    else if (msg && !msg.classList.contains('err')) say('');   /* a failure stays readable while the fields are edited */
  };
  const paintScope = function(){ scopeEl.hidden = scopeKind.value !== 'pkg'; };
  const paintDepth = function(){
    $all<HTMLElement>('.nm-depth .chip', card).forEach(function(c){ c.classList.toggle('on', c.getAttribute('data-depth') === depth); });
    if (!scopeTouched){ scopeKind.value = depth === 'quick' ? 'class' : 'target'; paintScope(); }   // the ⚡ gesture's scopes, until the user picks one
  };
  $all<HTMLElement>('.nm-depth .chip', card).forEach(function(c){
    c.addEventListener('click', function(){ depth = c.getAttribute('data-depth') === 'quick' ? 'quick' : 'deep'; paintDepth(); });
  });
  scopeKind.addEventListener('change', function(){ scopeTouched = true; paintScope(); if (scopeKind.value === 'pkg') scopeEl.focus(); });
  const openSymbol = function(): string {
    const sub = subjectAtCaret();
    if (sub) return sub.symbol;                                                   // the token under the caret names a method/class
    const m = S.currentClass && S.javaMethods ? methodAt(S.javaMethods, S.caret.line + 1) : null;   // else the method the caret sits in
    if (m && m.addr) return String(m.addr);
    return S.currentSym && S.currentSym.addr ? String(S.currentSym.addr) : '';   // else the open class
  };
  useBtn.addEventListener('click', function(){
    const v = openSymbol();
    if (!v){ say('open a class first — the subject is the symbol under the caret, or the open class', true); return; }
    subjectEl.value = v;
    gate();
  });
  prefillSubject = function(){
    if (subjectEl.value.trim() || goalEl.value.trim()) return;
    const v = openSymbol();
    if (v){ subjectEl.value = v; gate(); }
  };
  btn.addEventListener('click', async function(){
    const subject = subjectEl.value.trim(), text = goalEl.value.trim();
    if ((!subject && !text) || busy){ gate(); return; }
    const isMethod = subject.includes('->');
    const cls = subject ? dottedClassOf(subject) : '';
    const member = isMethod ? subject.replace(/^.*->/, '').replace(/\(.*$/, '') : '';
    const dotted = subject ? (isMethod ? cls + '.' + member : cls) : '';
    /* the goal string every op takes: the exact handle plus the dotted name for the human
       reading the card — the ⚡ gesture's spelling (reverse.ts), so the backend pins the same
       subject (AgentLoop.subjectOf: the first JVM signature in the goal) */
    const goal = text
      ? text + (subject ? ' — subject: ' + subject + ' (' + dotted + ')' : '')
      : 'reverse ' + (isMethod ? 'method' : 'class') + ' ' + subject + (isMethod ? ' (' + dotted + ')' : '');
    let scope = '';
    if (scopeKind.value === 'class'){
      if (!cls){ say('"the subject\'s class only" needs a subject — name one, or allow edits in the viewed target', true); return; }
      scope = 'pkg:' + cls;
    } else if (scopeKind.value === 'pkg'){
      const pfx = scopeEl.value.trim().replace(/^pkg:/, '');
      if (!pfx){ say('type the package or class prefix, e.g. com.example.net or X.15Q', true); scopeEl.focus(); return; }
      scope = 'pkg:' + pfx;
    } else if (scopeKind.value === 'project'){
      scope = 'project';
    } else if (S.currentTid){
      scope = 'target:' + S.currentTid;
    }
    const provSel = document.getElementById('ag-spawn-provider') as HTMLSelectElement | null;
    const modelSel = document.getElementById('ag-spawn-modelsel') as HTMLSelectElement | null;
    if (provSel && provSel.value && modelSel && (modelSel.disabled || !modelSel.value)){ say('this provider lists no model — add one in ⚙ PROVIDERS', true); return; }
    const model = provSel && provSel.value ? provSel.value + ':' + (modelSel ? modelSel.value : '') : '';
    const effortSel = document.getElementById('ag-spawn-effort') as HTMLSelectElement | null;
    const budgetEl = document.getElementById('ag-spawn-budget') as HTMLInputElement | null;
    const turnsEl = document.getElementById('ag-spawn-turns') as HTMLInputElement | null;
    const params: OpParams = { goal, depth };
    if (scope) params.scope = scope;
    if (S.currentTid) params.target = S.currentTid;   // pin the mission to the VIEWED target (Mission.target)
    if (model) params.model = model;
    if (effortSel && effortSel.value) params.effort = effortSel.value;
    const budget = budgetEl ? parseInt(budgetEl.value, 10) : 0;
    if (budget > 0) params.budget = budget;
    const turns = turnsEl ? parseInt(turnsEl.value, 10) : 0;
    if (turns > 0) params.turns = turns;
    busy = true; gate(); say('spawning…');
    const r = await tryOp('agent.spawn', params);
    busy = false;
    if (r.error){
      gate();
      const detail = r.error.payload && r.error.payload.error ? String(r.error.payload.error.detail || '') : '';
      if (/^task-unconfigured/.test(detail))
        say('⚡ ' + depth.toUpperCase() + ' has no provider and model yet — set them in ⚡ REVERSING AGENT above, or pick a provider under PROVIDER · MODEL', true);
      else say(r.error.message, true);   /* fields kept for the retry; the error under the card, never in a field */
      return;
    }
    subjectEl.value = ''; goalEl.value = '';
    gate();
    await Promise.all([loadMissions(), loadActivity()]);
    /* NO auto-select of the fresh card: following it would filter the queue to a mission that
       has proposed nothing yet and paint the empty state over every other mission's rows; the
       unfiltered queue lists its proposals as they land and the feed appends its rows over SSE */
  });
  goalEl.addEventListener('keydown', function(e){
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)){ e.preventDefault(); btn.click(); }   // ⌘↵ spawns; ↵ is a newline in the goal
  });
  subjectEl.addEventListener('keydown', function(e){
    if (e.key === 'Enter'){ e.preventDefault(); btn.click(); }
  });
  goalEl.addEventListener('input', gate);
  subjectEl.addEventListener('input', gate);
  paintDepth();
  paintScope();
  gate();   /* the card boots empty: SPAWN disabled, hint up (P0.4) */
  const provSel = document.getElementById('ag-spawn-provider') as HTMLSelectElement | null;
  const modelSel = document.getElementById('ag-spawn-modelsel') as HTMLSelectElement | null;
  const effortSel = document.getElementById('ag-spawn-effort') as HTMLSelectElement | null;
  if (provSel && modelSel) provSel.addEventListener('change', function(){
    fillModelSel(modelSel, provSel.value, '');
    if (effortSel) fillEffortSel(effortSel, effortSel.value, driverOf(provSel.value), modelSel.value);
  });
  if (modelSel && provSel && effortSel) modelSel.addEventListener('change', function(){
    fillEffortSel(effortSel, effortSel.value, driverOf(provSel.value), modelSel.value);
  });
  if (effortSel) fillEffortSel(effortSel, '', '');
  /* the two configurable areas, as buttons above the cards and as hooks for
     the palette / the ⚡ menu (navigation.ts) */
  document.getElementById('ag-prov-btn')!.addEventListener('click', openProvidersDialog);
  document.getElementById('ag-task-btn')!.addEventListener('click', openTaskDialog);
  S.hooks.openProviders = openProvidersDialog;
  S.hooks.openReverseSettings = openTaskDialog;
  initProviderAdd();
  loadProviders();
  initReviewSwitch();
}

/* ==================================================================
   INIT
================================================================== */
/* project switch (app.ts): PROPS and the queue are project-scoped (batch 2) — refetch both */
export function refreshProposalsForProject(): void {
  updatePropsCount();
  if (S.center === 'agents') loadProposals();
}

export function initAgents(): void {
  if (wired) return;
  wired = true;

  initSpawn();
  /* boot pair: real counts from first paint. bootPending/bootFailed tell the
     boot hello whether these are still flying (skip it — no doubled fetches)
     or one already FAILED (let the hello resync — never swallow recovery). */
  bootPending = 2;
  /* ok: true painted · false FAILED · null stale. Only real failures count,
     and a late failure (after the hello was already consumed) triggers the
     bounded backoff recovery instead of dying silently. Updaters never
     throw (tryOp-wrapped), so .then alone suffices. */
  const bootDone = function(ok: boolean | null){
    bootPending--;
    if (ok === false){ bootFailed++; bootRecovery(); }
  };
  updateAgentBadge().then(bootDone);
  updatePropsCount().then(bootDone);

  document.getElementById('proplist')!.addEventListener('click', function(e){
    if ((e.target as HTMLElement).closest('.pname-in')) return;            // typing in the name editor
    const pen = (e.target as HTMLElement).closest<HTMLElement>('.pnew, .pencil');
    if (pen){
      const row = pen.closest<HTMLElement>('.prow')!;
      const p = propCache.find(x => x.id === row.getAttribute('data-pid'));
      if (p && p.kind === 'rename') openNameEditor(row, p);
      return;
    }
    const ok = (e.target as HTMLElement).closest('.pok'), no = (e.target as HTMLElement).closest('.pno');
    if (ok){ settle(ok.closest('.prow')!.getAttribute('data-pid'), true, ok.closest('.prow')); return; }
    if (no){ settle(no.closest('.prow')!.getAttribute('data-pid'), false, no.closest('.prow')); return; }
    const rv = (e.target as HTMLElement).closest('.prv'), rd = (e.target as HTMLElement).closest('.prd');
    if (rv){ revertRow(rv.closest('.prow')!.getAttribute('data-pid'), false); return; }
    if (rd){ revertRow(rd.closest('.prow')!.getAttribute('data-pid'), true); return; }
    const loc = (e.target as HTMLElement).closest('.ploc');
    if (loc){
      const p = propCache.find(x => x.id === loc.closest('.prow')!.getAttribute('data-pid'));
      if (p && p.symbol) loadSymbol(p.symbol);
      return;
    }
    const row = (e.target as HTMLElement).closest<HTMLElement>('.prow');
    if (row){
      propSel = propRows().indexOf(row);
      paintPropSel();
      showProposalInspector(row.getAttribute('data-pid'));
    }
  });

  /* ACCEPT ALL: one click accepts EVERY pending proposal in the view (user rule 2026-09-12:
     "if the user clicks accept all, we accept all, period") — no eligibility filter, no
     confirm step; the toast reports what was applied and what the backend refused. Accepted
     renames and comments stay undoable through History. */
  let bulkRunning = false;
  document.getElementById('review-all')!.addEventListener('click', async function(){
    const btn = this as HTMLButtonElement;
    if (bulkRunning) return;
    const eligible = propCache.filter(function(p){ return rowState(p) === 'pending'; });
    if (!eligible.length){ notice('no edits waiting for review'); return; }
    bulkRunning = true;
    btn.disabled = true;
    btn.textContent = 'ACCEPTING ' + eligible.length + '…';
    let done = 0, failed = 0;
    const reasons: string[] = [];
    try {
    for (const p of eligible) {
      const err = await settle(p.id, true, null, true, true);   // deferRender: one re-render for the batch, below
      if (!err) done++;
      else { failed++; if (!reasons.includes(err)) reasons.push(err); }
    }
    if (done) await rerenderOpenClass();
    } finally { bulkRunning = false; btn.disabled = false; btn.textContent = 'ACCEPT ALL ⌥R'; }
    const why = reasons.length ? ' — ' + reasons[0] + (reasons.length > 1 ? ' (+' + (reasons.length - 1) + ' other reason' + (reasons.length > 2 ? 's' : '') + ')' : '') : '';
    notice('accepted ' + done + (failed ? ' · ' + failed + ' not applied' + why : ''), { err: failed > 0, ms: failed ? 12000 : undefined });
  });

  document.getElementById('propcol')!.addEventListener('keydown', function(e){
    const k = e.key, mod = e.metaKey || e.ctrlKey;
    const rows = propRows();
    if (k === 'ArrowDown'){
      e.preventDefault();
      propSel = Math.min(rows.length - 1, propSel + 1);
      paintPropSel();
      if (rows[propSel]) showProposalInspector(rows[propSel].getAttribute('data-pid'));
    } else if (k === 'ArrowUp'){
      e.preventDefault();
      propSel = Math.max(0, propSel - 1);
      paintPropSel();
      if (rows[propSel]) showProposalInspector(rows[propSel].getAttribute('data-pid'));
    } else if (mod && k === 'a'){
      e.preventDefault();
      if (rows[propSel]) settle(rows[propSel].getAttribute('data-pid'), true, rows[propSel]);
    } else if (mod && k === 'x'){
      e.preventDefault();
      if (rows[propSel]) settle(rows[propSel].getAttribute('data-pid'), false, rows[propSel]);
    } else if (e.altKey && k === 'r'){                       // QA 19: ⌥R = review all (⌘R is browser-reserved)
      e.preventDefault();
      document.getElementById('review-all')!.click();
    }
    /* Esc: the central dispatcher closes the view (registration below) */
  });

  /* agents Esc — close the view, back to code. One owner for the whole surface
     (it used to be duplicated on #propcol and #agentswrap); declines unless
     focus sits inside the agents surface. */
  pushEscape(function(){
    const wrap = document.getElementById('agentswrap')!;
    const a = document.activeElement;
    if (wrap.hidden || !a || !wrap.contains(a)) return false;
    showCenter('code');
  }, ESC.view);

  /* live: activity rows + proposal counts over SSE */
  /* the log's bottom pane: tabs, ×, ▴ PROPOSALS, the drag bar */
  const bottom = document.getElementById('ag-bottom');
  if (bottom) bottom.querySelector('.ag-bottom-tabs')!.addEventListener('click', function(e){
    const t = (e.target as HTMLElement).closest<HTMLElement>('[data-agtab]');
    if (t){ setBottomTab(t.getAttribute('data-agtab') as 'props' | 'turn'); return; }
    if ((e.target as HTMLElement).closest('#ag-bottom-close')) closeBottom();
  });
  if (bottom) bottom.addEventListener('click', function(e){
    const b = (e.target as HTMLElement).closest<HTMLButtonElement>('.agt-copy');
    if (!b) return;
    const pre = b.parentElement ? b.parentElement.querySelector('pre') : null;
    if (!pre) return;
    const txt = pre.textContent || '';
    const done = function(ok: boolean){ b.textContent = ok ? '✓ copied' : 'copy failed'; window.setTimeout(function(){ b.textContent = '⧉ copy'; }, 1400); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(txt).then(function(){ done(true); }, function(){ done(false); });
    else { try { const r = document.createRange(); r.selectNodeContents(pre); const sel = window.getSelection(); if (sel){ sel.removeAllRanges(); sel.addRange(r); } done(document.execCommand('copy')); } catch { done(false); } }
  });
  /* the missions column collapses (× at its top) when the user is done picking a run; ▸ MISSIONS
     in the filter bar brings it back (session state) */
  const dtog = document.getElementById('ag-dismissed-toggle');
  if (dtog) dtog.addEventListener('click', function(){ showDismissed = !showDismissed; loadMissions(); });
  const mcol = document.querySelector<HTMLElement>('.ag-missions'), mclose = document.getElementById('ag-missions-close'), mopen = document.getElementById('ag-missions-open');
  const showMissions = function(on: boolean){ if (mcol) mcol.hidden = !on; if (mopen) mopen.hidden = on; const sp = document.getElementById('ag-msplit'); if (sp) sp.hidden = !on; };
  if (mclose) mclose.addEventListener('click', function(){ showMissions(false); });
  if (mopen) mopen.addEventListener('click', function(){ showMissions(true); });
  const openBtn = document.getElementById('ag-bottom-open');
  if (openBtn) openBtn.addEventListener('click', function(){ openBottom('props'); });
  /* the missions column: draggable width (user 2026-09-19: "non-extensible or adjustable"),
     remembered in this browser; the goal text wraps inside the card (overflow-wrap) */
  const msplit = document.getElementById('ag-msplit');
  if (msplit && mcol){
    try { const w = parseInt(localStorage.getItem('ag.missionsW') || '', 10); if (w >= 200) mcol.style.width = w + 'px'; } catch { /* storage may be unavailable */ }
    msplit.addEventListener('pointerdown', function(e){
      e.preventDefault(); msplit.classList.add('on'); document.body.classList.add('splitting');
      const move = function(ev: PointerEvent){
        const left = mcol.getBoundingClientRect().left;
        const w = Math.max(200, Math.min(Math.round(window.innerWidth * 0.6), Math.round(ev.clientX - left)));
        mcol.style.width = w + 'px';
      };
      const up = function(){
        msplit.classList.remove('on'); document.body.classList.remove('splitting');
        document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up);
        try { localStorage.setItem('ag.missionsW', String(parseInt(mcol.style.width, 10) || 280)); } catch { /* ignore */ }
      };
      document.addEventListener('pointermove', move); document.addEventListener('pointerup', up);
    });
  }
  const split = document.getElementById('ag-bottom-split'), stream = document.querySelector<HTMLElement>('.ag-stream');
  if (split && bottom && stream) split.addEventListener('pointerdown', function(e){
    e.preventDefault(); split.classList.add('on'); document.body.classList.add('splitting');
    const move = function(ev: PointerEvent){ const r = stream.getBoundingClientRect(); bottom.style.flexBasis = Math.max(120, Math.min(r.height * 0.85, r.bottom - ev.clientY)) + 'px'; };
    const up = function(){ split.classList.remove('on'); document.body.classList.remove('splitting'); document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up); };
    document.addEventListener('pointermove', move); document.addEventListener('pointerup', up);
  });
  /* activity stream: kind chips, the mission-filter readout's ×, a turn row → its transcript */
  const filt = document.getElementById('ag-filter');
  if (filt) filt.addEventListener('click', function(e){
    const chip = (e.target as HTMLElement).closest<HTMLElement>('.fchip[data-kinds]');
    if (chip){ feedKinds = chip.getAttribute('data-kinds') || 'all'; loadActivity(); return; }
    if ((e.target as HTMLElement).closest('#ag-filter-clear')){
      feedFilterMode = 'all';
      $all('.mcard').forEach(x => x.classList.remove('sel'));
      loadActivity(); updatePropsCount(); loadProposals();
    }
  });
  const feed = document.getElementById('ag-feed');
  if (feed) feed.addEventListener('click', function(e){
    const row = (e.target as HTMLElement).closest<HTMLElement>('.arow.turn');
    if (!row) return;
    const sel = window.getSelection();
    if (sel && String(sel).length && row.contains(sel.anchorNode)) return;   /* a text selection is not a click */
    selectTurn(row.getAttribute('data-mission')!, parseInt(row.getAttribute('data-turn')!, 10));
  });
  onEvent(function(channel, data){
    if (channel === 'activity' && data && /AUTO-APPLIED/.test(String((data as ActivityRow).text || ''))) noteAutoApplied();
    /* reconnect resync: EventSource reconnects silently and EventBus drops
       events when full — 'hello' is the only "you may have missed things"
       signal, so it repaints from source (full columns if the view is open). */
    if (channel === 'hello'){
      /* Skip the boot-connect hello when the boot fetches are still in
         flight (they'll paint when they land) or already painted truth.
         If they FAILED, this hello is the recovery path — fall through.
         Also correct if subscription order ever changes: any hello with
         nothing pending and nothing painted resyncs. */
      if (!helloSeen){
        helloSeen = true;
        /* skip the boot hello only while recovery is impossible to need:
           nothing failed and fetches are still flying or already painted */
        if (!bootFailed && (bootPending > 0 || (agtPainted && propsPainted))) return;
      }
      if (S.center === 'agents'){ loadMissions(); loadProposals(); loadActivity(); if (feedFilterMode !== 'all') updatePropsCount(); }
      else { updateAgentBadge(); updatePropsCount(); }
      return;
    }
    if (S.center === 'agents'){
      /* view open: column loaders own the counts (they're error-guarded) */
      if (channel === 'activity'){
        const row = data as ActivityRow | null;
        if (row && (feedFilterMode === 'all' || row.agent === feedFilterMode)
            && (feedKinds === 'all' || feedKinds.split(',').includes(String(row.kind || '')))){
          const host = document.getElementById('ag-feed')!;
          host.appendChild(activityRow(data as ActivityRow));
          host.scrollTop = host.scrollHeight;
        }
        loadMissions();
      }
      if (channel === 'proposals'){
        const pn = document.getElementById('props-n');
        const d = data as { pending?: number };
        if (pn && data && d.pending != null){ propsSeq++; pn.textContent = String(d.pending); propsPainted = true; }
        loadProposals();
      }
      return;
    }
    /* view closed: counts only. A proposals event carries the new count —
       write it (claiming propsSeq so an in-flight fetch can't paint older
       over it); it says nothing about missions, so don't refetch those. */
    if (channel === 'activity') updateAgentBadge();
    if (channel === 'proposals'){
      const pn = document.getElementById('props-n');
      const d = data as { pending?: number };
      if (pn && data && d.pending != null){ propsSeq++; pn.textContent = String(d.pending); propsPainted = true; }
      else updatePropsCount();
    }
  });

  const agmPause = document.getElementById('agm-pause');
  const agmStop = document.getElementById('agm-stop');
  if (agmPause) agmPause.addEventListener('click', function(){ missionCtrl('pause'); });
  if (agmStop) agmStop.addEventListener('click', function(){ missionCtrl('stop'); });
  const agmRaise = document.getElementById('agm-topup-btn'), agmDefault = document.getElementById('agm-topup-default');
  if (agmRaise) agmRaise.addEventListener('click', function(){ missionCtrl('raise'); });          /* was never wired — the box's button did nothing */
  const agmTurnRaise = document.getElementById('agm-turnup-btn'), agmTurnDefault = document.getElementById('agm-turnup-default');
  if (agmTurnRaise) agmTurnRaise.addEventListener('click', function(){ missionCtrl('raise-turns'); });
  if (agmTurnDefault) agmTurnDefault.addEventListener('click', function(){ missionCtrl('raise-turns-default'); });
  if (agmDefault) agmDefault.addEventListener('click', function(){ missionCtrl('raise-default'); });

  S.hooks.proposalsChanged = function(){ loadProposals(); };   // a comment deleted from the code view: the EDITS row says reverted


  S.hooks.agentsOpened = function(){
    loadReviewPolicy();   /* another client (MCP, a second tab) may have flipped it */
    if (prefillSubject) prefillSubject();   /* the NEW MISSION subject follows the open symbol until the user types */
    if (railMode === 'summary') showAgSummary();   /* never a blank rail at open */
    loadMissions();
    loadActivity();
    loadProposals(true);       /* user-driven: may auto-select the first row */
    if (feedFilterMode !== 'all') updatePropsCount();   /* filtered: the project chip needs its own fetch */
  };
}
