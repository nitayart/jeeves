package dev.workbench.llm;

import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Json;
import dev.workbench.server.AgentManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP agent loop (multi-LLM): an OpenAI-compatible chat-completions tool-use
 * loop over the workbench's own ops. The model gets a curated SAFE tool subset —
 * read ops + proposal-generating mutations (rename/comment/tag.add/map.setverdict
 * as llm actors, so scope enforcement + the human review queue work unchanged).
 * Tool calls execute through the internal Dispatcher as actor llm:&lt;agentId&gt;.
 * Token usage accumulates into the mission budget; hitting the budget PAUSES the
 * mission (agent.resume continues it). The chat client is injectable — the
 * `fixture` provider plays canned tool calls with no network and no key.
 */
public final class AgentLoop {

    /** One chat-completions round trip. Request/response are plain JSON maps. */
    public interface ChatClient {
        Map<String, Object> chat(Map<String, Object> request) throws Exception;
    }

    /** The provider's wire contract beyond OpenAI's (LlmRegistry.Provider.reasoningField …): which
     *  assistant-message field carries a thinking model's trace and what the provider wants done
     *  with it on the way back (all | task | never), whether the endpoint streams only, its cap on
     *  max_tokens. PLAIN is an OpenAI-compatible endpoint that knows no such field. */
    public record Wire(String reasoningField, String reasoningReplay, boolean streamOnly, int maxOutputTokens) {
        public static final Wire PLAIN = new Wire("", "never", false, 0);
        public Wire {
            reasoningField = reasoningField == null ? "" : reasoningField.trim();
            reasoningReplay = reasoningReplay == null || reasoningReplay.isBlank() ? "never" : reasoningReplay.trim();
        }
        /** The field to read a trace from: the provider's own, else the common one — a trace that
         *  arrives unannounced is still recorded (never sent back unless the policy keeps it). */
        public String field() { return reasoningField.isEmpty() ? "reasoning_content" : reasoningField; }
        public boolean keeps() { return !"never".equals(reasoningReplay); }
    }

    /** The private capture hook: the exact request about to be sent and the
     *  exact answer received, per attempt, each tool result as the model consumed it, and how the
     *  run ended. The loop calls it in order; the implementation decides where it goes. request()
     *  may refuse — a strict capture: no durable admission, no dispatch. Default: nothing
     *  (the owner's own workbench). */
    public interface Capture {
        /** The logical request id — one per attempt, stable across restarts of the same mission. */
        default String logicalId(String missionId, int turn, int attempt) { return missionId + "-t" + turn + "-a" + attempt; }
        /** Before dispatch, with the request exactly as it goes out (minus credentials, which the
         *  client adds as headers). Throwing refuses the dispatch with that reason. */
        default void request(String logicalId, int turn, int attempt, Map<String, Object> request) throws Exception {}
        /** The answer as received: the parsed message the loop uses and the raw body / stream
         *  segments, status and the provider's request id where it sent one. */
        default void response(String logicalId, int turn, int attempt, Map<String, Object> response, Map<String, Object> raw, long ms) {}
        default void failure(String logicalId, int turn, int attempt, String error, long ms) {}
        /** The same, with what the wire did return (status, body, the segments received before a
         *  stream broke, the provider's request id — WireFailure.raw): a paid, ambiguous attempt is
         *  recorded whole, never as a sentence. */
        default void failure(String logicalId, int turn, int attempt, String error, long ms, Map<String, Object> raw) { failure(logicalId, turn, attempt, error, ms); }
        /** One tool result exactly as it was handed to the model (after the loop's own cap). */
        default void toolResult(String logicalId, int turn, String callId, String tool, Map<String, Object> args, String exactContent, boolean ok) {}
        /** The same, with the result's size BEFORE the loop's cap (MAX_TOOL_RESULT_CHARS) — so a
         *  record says whether the model saw the whole answer and how much the cap removed. */
        default void toolResult(String logicalId, int turn, String callId, String tool, Map<String, Object> args, String exactContent, boolean ok, int fullChars) {
            toolResult(logicalId, turn, callId, tool, args, exactContent, ok);
        }
        /** How the run ended: completed | cancelled | failed | blocked, with the reason the mission shows. */
        default void terminal(String outcome, String reason) {}
    }

    /** Activity sink — AgentManager wraps its activity stream. */
    public interface Activity {
        void row(String kind, String text);
        /** The turn the following rows belong to (0 = outside a turn) — the feed groups by it. */
        default void turn(int n) {}
        /** One turn's transcript: reasoning, assistant text, every tool call's args+result+ms,
         *  tokens, outcome — what the feed's one-line rows summarize (agent.turn). */
        default void record(Map<String, Object> rec) {}
    }
    /** Transcript caps: a turn keeps what a reviewer needs, not the 30k-char decompile a
     *  model was fed — the feed row's 80/120-char digests stay for skimming. */
    private static final int REC_TEXT_CAP = 24_000, REC_JSON_CAP = 8_000;
    private static String cap(String v, int n) {
        return v.length() <= n ? v : v.substring(0, n) + "…[+" + (v.length() - n) + " chars]";
    }
    /** The first JVM signature in a goal — a method ("Lx/y;->m(...)ret") if present, else a class ("Lx/y;"). */
    public static String subjectOf(String goal) {
        if (goal == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("L[\\w/$]+;->[\\w<>$]+\\([^)]*\\)[^\\s]*").matcher(goal);
        if (m.find()) return m.group();
        m = java.util.regex.Pattern.compile("L[\\w/$]+;").matcher(goal);
        return m.find() ? m.group() : null;
    }

    /** What a prefix-cache HIT costs relative to a miss (DeepSeek, Anthropic: 0.1) — the budget
     *  counts cost-weighted tokens, not raw ones. */
    private static final double CACHE_HIT_WEIGHT = 0.10;

    private String usageSummary() {
        return mission.budgetUnlimited
                ? count(mission.inputTokens) + " input / " + count(mission.outputTokens) + " output tokens (no mission ceiling)"
                : mission.tokensUsed + "/" + mission.budget + " tokens";
    }

    /** Two-space pretty JSON for the transcript: the cap then truncates lines, not a one-line blob
     *  (the TURN pane showed a decompile result as one escaped line). */
    private static String prettyJson(Object v) { StringBuilder sb = new StringBuilder(); prettyJson(v, sb, 0); return sb.toString(); }
    private static void prettyJson(Object v, StringBuilder sb, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n"); int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) sb.append(",\n");
                sb.append("  ".repeat(depth + 1)).append(Json.write(String.valueOf(e.getKey()))).append(": ");
                prettyJson(e.getValue(), sb, depth + 1);
            }
            sb.append('\n').append("  ".repeat(depth)).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n"); int i = 0;
            for (Object o : l) { if (i++ > 0) sb.append(",\n"); sb.append("  ".repeat(depth + 1)); prettyJson(o, sb, depth + 1); }
            sb.append('\n').append("  ".repeat(depth)).append(']');
        } else sb.append(Json.write(v));
    }

    private final Dispatcher dispatcher;
    private final AgentManager.Mission mission;
    private final ChatClient client;
    private final String model;
    private final Activity activity;
    private int maxTurns;   // 0 = unlimited; positive caps can be raised by agent.resume {turns}
    /** The symbol the mission is about (first JVM signature in the goal, e.g. "reverse method
     *  LX/0dP;->A01(...)"): its decompile results are never elided from the history. */
    private final String subject;
    private final Map<String, Object> extraParams;   // per-provider extras (e.g. DeepSeek thinking)
    private final Wire wire;                         // the provider's wire contract (reasoning replay, stream, output cap)
    private Capture capture = new Capture() {};      // the private capture; AgentManager may set one
    public void capture(Capture c) { this.capture = c == null ? new Capture() {} : c; }
    private int contextTokens;                       // the model's window (Provider.contextTokens) — the loop's only ceiling; a refusal can lower it
    private java.util.function.IntConsumer onWindowLearned;   // AgentManager saves a learned window for the provider
    public void onWindowLearned(java.util.function.IntConsumer c) { this.onWindowLearned = c; }

    public AgentLoop(Dispatcher dispatcher, AgentManager.Mission mission,
            ChatClient client, String model, Activity activity, int maxTurns) {
        this(dispatcher, mission, client, model, activity, maxTurns, Map.of(), 128_000);
    }

    public AgentLoop(Dispatcher dispatcher, AgentManager.Mission mission,
            ChatClient client, String model, Activity activity, int maxTurns,
            Map<String, Object> extraParams, int contextTokens) {
        this(dispatcher, mission, client, model, activity, maxTurns, extraParams, contextTokens, Wire.PLAIN);
    }

    public AgentLoop(Dispatcher dispatcher, AgentManager.Mission mission,
            ChatClient client, String model, Activity activity, int maxTurns,
            Map<String, Object> extraParams, int contextTokens, Wire wire) {
        this.wire = wire == null ? Wire.PLAIN : wire;
        this.dispatcher = dispatcher;
        this.mission = mission;
        this.client = client;
        this.model = model;
        this.activity = activity;
        this.maxTurns = maxTurns;
        this.subject = subjectOf(mission.goal);
        this.extraParams = extraParams == null ? Map.of() : extraParams;
        this.contextTokens = Math.max(8_192, contextTokens);
    }

    /** Where eviction starts: the model's window minus a 10% estimate margin (chars/4 undercounts
     *  code), the completion reserve and the tool schemas. Below it NOTHING is rewritten. */
    private int contextLimit() {
        return Math.max(4_096, (int) (contextTokens * 0.9) - completionLimit() - TOOL_SCHEMA_TOKENS);
    }

    /** A configured output allowance replaces the historical 4096 default. The provider's
     *  documented maximum still applies; the caller can request less for a particular mission. */
    private int completionLimit() {
        int limit = mission.maxOutputTokens > 0 ? mission.maxOutputTokens
                : wire.maxOutputTokens() > 0 ? wire.maxOutputTokens() : MAX_COMPLETION_TOKENS;
        return wire.maxOutputTokens() > 0 ? Math.min(limit, wire.maxOutputTokens()) : limit;
    }

    private static boolean looksLikeContextOverflow(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof WireFailure failure && failure.raw.get("status") instanceof Number status
                && status.intValue()!=400 && status.intValue()!=413) return false; // never replay an accepted partial stream
        return java.util.regex.Pattern.compile("(?i)context.length|context_length|maximum context|too many tokens|exceeds the (model|maximum)|prompt is too long|prompt exceeds max length|request too large|input is too long").matcher(m).find();
    }
    /** The maximum a provider's refusal states ("maximum context length is 131072 tokens"), or 0. */
    static int windowFromError(String msg) {
        if (msg == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:maximum |max )?context (?:length|window)(?: is| of)?[^0-9]{0,12}(\\d[\\d,]{3,9})").matcher(msg);
        boolean found = m.find();   // one find() per matcher: a second call on a matched one walks PAST the match (m10aIT caught it: every OpenAI-style refusal parsed as 0)
        if (!found) {
            m = java.util.regex.Pattern.compile("(?i)>\\s*(\\d[\\d,]{3,9})\\s*maximum").matcher(msg);   // Anthropic: "prompt is too long: 250000 tokens > 200000 maximum"
            found = m.find();
        }
        if (!found) return 0;
        try { return Integer.parseInt(m.group(1).replace(",", "")); } catch (NumberFormatException e) { return 0; }
    }
    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.split("\\r?\\n")[0];
        return t.length() > 160 ? t.substring(0, 157) + "…" : t;
    }

    // ------------------------------------------------------------ the tool facade

    /* The tools an LLM sees live in ONE table, dev.workbench.llm.ToolFacade: the loop-visible
       subset here (symbols, code, xrefs, strings, search, annotate, map, map_verdict, presence),
       every tool for MCP clients (through the `tool.call` op). The old per-op TOOL_SPECS /
       MCP_ONLY_SPECS tables were folded into it on 2026-09-18. */

    static List<Object> toolDefs() {
        return ToolFacade.functionDefs();
    }

    /** The internal loop's callable tool names — exposed for tests asserting the
     *  boundary (mission, review, llm … must never appear here). */
    public static java.util.Set<String> loopToolNames() {
        return ToolFacade.loopToolNames();
    }

    /** The claude-CLI `--allowedTools` value for claude-mode missions: the loop-visible
     *  tools via the MCP server — explicitly NOT a registry-wide wildcard (a wildcard
     *  hands missions `mission` / `review` → self-review). */
    public static String curatedMcpTools() {
        StringBuilder sb = new StringBuilder();
        for (String t : ToolFacade.loopToolNames()) {
            if (sb.length() > 0) sb.append(',');
            sb.append("mcp__jeeves__").append(t);
        }
        return sb.toString();
    }

    /** ops.tools meta op: the whole facade catalogue for EXTERNAL tool clients
     *  (MCP per-tool inputSchema) — [{tool, op, ops, loop, description, parameters}]. */
    public static List<Object> curatedTools() {
        return ToolFacade.catalogue();
    }

    // ------------------------------------------------------------ context economy

    /* Token cost per turn used to grow linearly with mission length: `messages`
     * accumulates forever and every turn re-sends all of it, so multi-KB
     * decompile results and dead reasoning traces from turn 2 were still being
     * paid for at turn 12 (measured: 30-45k/turn early, 118-141k/turn late).
     * Three deterministic mechanisms keep the prompt on a plateau. The OpenAI
     * tool protocol is preserved throughout: no message is ever deleted or
     * re-ordered, so every assistant tool_calls id keeps its paired tool reply —
     * only message CONTENT is capped or stubbed. */

    /** R1 — hard cap on one tool payload entering the history (~1.5k tokens).
     *  Anything bigger is stored truncated with a marker naming the full size. */
    private static final int MAX_TOOL_RESULT_CHARS = 6_000;

    /** The history is sent WHOLE and UNCHANGED every turn: the provider caches by prompt prefix
     *  (DeepSeek: a hit costs ~1/10 of a miss), so re-sending an untouched history is cheap, while
     *  rewriting old messages — what the sliding window did every turn — breaks the prefix and
     *  forfeits the cache. The only thing that must be bounded is the model's context window:
     *  above this estimate the OLDEST unprotected tool results become stubs first. If the recent
     *  working set alone exceeds the window, read-only results receive explicit reread notices. */

    /** The last N turns' tool results are kept in full while they fit — the working set of an agent
     *  reading a method is what it fetched a turn or two ago (m1: the count-based window threw
     *  the previous turn's windows away every turn). */
    private static final int KEEP_TURNS = 3;

    /** R2 — stub marker; also the sentinel that stops an elided result from
     *  being re-elided (a real payload is JSON, so it can never collide). */
    private static final String ELIDED_PREFIX = "[result elided — ";

    /** Rough token cost of the tool schemas, which ride along on every request
     *  but are not part of `messages`. Deliberately coarse — it only feeds the
     *  budget guard, never the provider's own accounting. */
    private static final int TOOL_SCHEMA_TOKENS = 600;

    /** Fallback when neither mission nor provider declares its output allowance. */
    private static final int MAX_COMPLETION_TOKENS = 4096;

    /** What one context-maintenance pass did, for the per-turn activity row. */
    private record ContextStats(int messages, int elided, int stripped, int estTokens) {}

    // R5 — plateau measurement: prompt estimate at turn 1 vs the peak over the run.

    /** R1: a single tool payload never enters the history above the cap. */
    /** Decompile results: the 6 KB cap cut a 120-line window at ~line 100 with a marker that
     *  said "decompile a single method" (it WAS one). A larger budget, cut on a LINE boundary, and
     *  the marker names the exact next window so the model continues instead of guessing. */
    private static final int DECOMPILE_RESULT_CHARS = 16_000;
    @SuppressWarnings("unchecked")
    private static String capDecompile(Object payload, String content) {
        if (content.length() <= DECOMPILE_RESULT_CHARS || !(payload instanceof Map<?, ?> pm) || !(pm.get("text") instanceof String text))
            return capResult(content);
        int overhead = content.length() - text.length();
        int budget = Math.max(1_000, DECOMPILE_RESULT_CHARS - overhead);
        if (text.length() <= budget) return content;
        int cut = text.lastIndexOf('\n', budget);
        if (cut < budget / 2) cut = budget;
        String kept = text.substring(0, cut);
        int shown = kept.isEmpty() ? 0 : kept.split("\n", -1).length;
        int from = 1, to = -1;
        if (pm.get("window") instanceof Map<?, ?> w) {
            if (w.get("from") instanceof Number f) from = f.intValue();
            if (w.get("to") instanceof Number t) to = t.intValue();
        }
        int totalLines = pm.get("lineCount") instanceof Number lc ? lc.intValue() : -1;
        int last = from + shown - 1;
        int next = last + 1, end = to > 0 ? to : totalLines;
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) pm);
        out.put("text", kept + "\n… [cut here: lines " + from + "–" + last + " shown of this window"
                + (end > 0 ? " — continue with window \"" + next + "," + end + "\"" : " — continue with window \"" + next + ",\" + (next + 100)")
                + " (the result cap is " + count(DECOMPILE_RESULT_CHARS) + " chars)]");
        out.put("truncated", true);
        out.put("shownLines", Map.of("from", from, "to", last));
        return Json.write(out);
    }

    /** A row-shaped result over the cap loses whole rows, never half a row: the JSON stays valid, the
     *  agent sees total/shown and how to choose its own cut (limit, a narrower filter). m3's callees
     *  answer was sliced mid-JSON at 6,000 chars and the retry was sliced again (2026-09-18). */
    @SuppressWarnings("unchecked")
    private static String capRows(Object payload, String content) {
        if (content.length() <= MAX_TOOL_RESULT_CHARS) return content;
        List<Object> rows = null; Map<String, Object> shell = null; String key = null;
        if (payload instanceof List<?> l) rows = (List<Object>) l;
        else if (payload instanceof Map<?, ?> m) {
            for (String k : List.of("rows", "items", "hits")) if (m.get(k) instanceof List<?> rl) { rows = (List<Object>) rl; key = k; break; }
            if (rows != null) shell = new LinkedHashMap<>((Map<String, Object>) m);
        }
        if (rows == null || rows.isEmpty()) return capResult(content);
        int lo = 0, hi = rows.size();
        while (lo < hi) {                                            // the largest prefix that fits
            int mid = (lo + hi + 1) / 2;
            Map<String, Object> probe = shell == null ? new LinkedHashMap<>() : new LinkedHashMap<>(shell);
            probe.put(key == null ? "rows" : key, new ArrayList<>(rows.subList(0, mid)));
            if (Json.write(probe).length() + 220 <= MAX_TOOL_RESULT_CHARS) lo = mid; else hi = mid - 1;
        }
        Map<String, Object> out = shell == null ? new LinkedHashMap<>() : new LinkedHashMap<>(shell);
        out.put(key == null ? "rows" : key, new ArrayList<>(rows.subList(0, Math.max(0, lo))));
        out.put("shown", Math.max(0, lo));
        if (out.get("total") == null) out.put("total", rows.size());
        out.put("truncated", true);
        /* the engine's own hint describes ITS cut ("the first 500 classes … pass a larger limit"); beside
           shown:N of this smaller one it misleads — a larger limit changes nothing under this cap */
        out.remove("hint");
        out.put("note", Math.max(0, lo) + " of " + out.get("total") + " rows shown (result cap " + count(MAX_TOOL_RESULT_CHARS)
                + " chars) — narrow with a filter" + (out.get("beginnings") != null ? " or a name beginning (beginnings counts what exists)" : "")
                + ", or pass limit to choose your own cut");
        return Json.write(out);
    }

    private static String capResult(String content) {
        if (content.length() <= MAX_TOOL_RESULT_CHARS) return content;
        return content.substring(0, MAX_TOOL_RESULT_CHARS)
                + " … [truncated — " + count(MAX_TOOL_RESULT_CHARS) + " of "
                + count(content.length())
                + " chars; narrow the query or decompile a single method]";
    }

    /**
     * R2 + R3, run once before every request. Walks the history newest-first and
     * (a) replaces the content of tool results that fell out of the window with a
     * stub naming what they held, (b) applies the provider's reasoning policy
     * (Wire): under `task` the trace is dropped from assistant messages before the
     * newest user message — a new task began there; under `all` nothing is dropped
     * (DeepSeek thinking with tools, Kimi K3 answer 400 to a history missing it);
     * under `never` it was never kept. Entries are REPLACED with copies, never
     * mutated in place, so provider-owned maps stay untouched. Assistant messages
     * keep their tool_calls and every tool reply keeps its tool_call_id: the
     * protocol pairing is what tool-loop validity depends on.
     */
    private ContextStats compact(List<Object> history, Map<String, Integer> rawChars, Map<String, String> argsById, String subject, int limit) {
        int stripped = 0;
        if ("task".equals(wire.reasoningReplay())) {
            int newestUser = -1;
            for (int i = history.size() - 1; i >= 0; i--)
                if ("user".equals(roleOf(history.get(i)))) { newestUser = i; break; }
            for (int i = 0; i < newestUser; i++) {
                Map<String, Object> m = asMap(history.get(i));
                if (m == null || !"assistant".equals(String.valueOf(m.get("role"))) || !m.containsKey(wire.field())) continue;
                Map<String, Object> copy = new LinkedHashMap<>(m);
                copy.remove(wire.field());
                history.set(i, copy);
                stripped++;
            }
        }
        int newestAssistant = -1;
        for (int i = history.size() - 1; i >= 0; i--)
            if ("assistant".equals(roleOf(history.get(i)))) { newestAssistant = i; break; }
        int elided = 0;
        for (Object o : history) { Map<String, Object> m = asMap(o); if (m != null && "tool".equals(String.valueOf(m.get("role"))) && String.valueOf(m.get("content")).startsWith(ELIDED_PREFIX)) elided++; }
        int est = estimateTokens(history);
        if (est <= limit)
            return new ContextStats(history.size(), elided, stripped, est);   // nothing else rewritten: the prefix stays cacheable

        /* over the limit: the turn each result belongs to, counted from the newest (0 = in flight) */
        int[] turnAge = new int[history.size()];
        int age = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(roleOf(history.get(i)))) age++;
            turnAge[i] = i > newestAssistant ? 0 : age;
        }
        for (int i = 0; i < history.size() && est > limit; i++) {   // OLDEST first
            Map<String, Object> m = asMap(history.get(i));
            if (m == null || !"tool".equals(String.valueOf(m.get("role")))) continue;
            String content = String.valueOf(m.getOrDefault("content", ""));
            if (content.startsWith(ELIDED_PREFIX)) continue;
            if (turnAge[i] < KEEP_TURNS) continue;                                          // the working set
            String callId = String.valueOf(m.get("tool_call_id"));
            String callArgs = argsById.getOrDefault(callId, "");
            if (subject != null && "code".equals(String.valueOf(m.get("name"))) && callArgs.contains(subject)
                    && !callArgs.contains("\"smali\"") && !callArgs.contains("\"outline\"")) continue;   // pinned
            Integer raw = rawChars.get(callId);
            Map<String, Object> stub = new LinkedHashMap<>(m);
            stub.put("content", ELIDED_PREFIX + m.get("name") + " " + callArgs + " returned "
                    + count(raw == null ? content.length() : raw) + " chars; call again if needed]");
            history.set(i, stub);
            elided++;
            est -= Math.max(0, (content.length() - String.valueOf(stub.get("content")).length()) / 4);
        }
        // A recent class listing can itself exceed the provider's whole window.
        // Pinning it forever makes every request impossible. Keep the ordinary
        // working-set policy above; only an over-window remainder permits this
        // emergency reread notice, and only for unambiguously read-only tools.
        est=estimateTokens(history);
        while (est>limit) {
            int candidate=-1,bestGain=0;String notice=null;
            for(int i=0;i<history.size();i++) {
                var m=asMap(history.get(i));
                if(m==null || !"tool".equals(m.get("role")) || !Set.of("code","symbols","xrefs","strings","search").contains(m.get("name")))continue;
                String content=String.valueOf(m.getOrDefault("content",""));if(content.startsWith(ELIDED_PREFIX))continue;
                String id=String.valueOf(m.get("tool_call_id"));
                String replacement=ELIDED_PREFIX+m.get("name")+" "+argsById.getOrDefault(id,"")+" returned "
                        +count(rawChars.getOrDefault(id,content.length()))+" chars; this read cannot stay in the provider context window. "
                        +"Read the needed sections again: code with an exact method and window \"from,to\" for Java lines or smali rows; "
                        +"use outline for class structure and narrow symbol/search/xref queries. Do not repeat an oversized whole-class read. "
                        +"The original tool call remains above; this notice is not complete source evidence.]";
                int gain=content.length()-replacement.length();
                if(gain>bestGain){candidate=i;bestGain=gain;notice=replacement;}
            }
            if(candidate<0)break; // no safely reducible read: never discard recent writes to force a request
            var stub=new LinkedHashMap<>(asMap(history.get(candidate)));stub.put("content",notice);
            history.set(candidate,stub);elided++;est=estimateTokens(history);
        }
        return new ContextStats(history.size(), elided, stripped, estimateTokens(history));
    }

    /** R4: upper bound on what a response ADDS to the next turn's prompt — the
     *  assistant message itself plus one capped result per tool call. */
    private static int pendingReserve(Map<String, Object> resp) {
        Map<String, Object> msg = responseMessage(resp);
        if (msg == null) return 0;
        int calls = msg.get("tool_calls") instanceof List<?> l ? l.size() : 0;
        return (Json.write(msg).length() + calls * MAX_TOOL_RESULT_CHARS) / 4;
    }

    private static Map<String, Object> responseMessage(Map<String, Object> resp) {
        if (!(resp.get("choices") instanceof List<?> choices) || choices.isEmpty()) return null;
        if (!(choices.get(0) instanceof Map<?, ?> choice)) return null;
        return asMap(choice.get("message"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String roleOf(Object o) {
        Map<String, Object> m = asMap(o);
        return m == null ? "" : String.valueOf(m.get("role"));
    }

    /** 31008 → "31,008" (locale-independent: the markers must be stable). */
    private static String count(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    // ------------------------------------------------------------ the loop

    /** The write protocol, shared by the http loop's system prompt and the CLI missions' prompt.
     *  Text accepted by John on 2026-09-18 (rename EARLY: the agent must reap its own names). */
    public static final String RENAME_AS_YOU_GO =
            "RENAME AS YOU GO — one understanding, one rename, in the SAME turn. The moment you can say "
            + "what a method, class or field is, call annotate for it in that turn, before any further read. "
            + "Do not wait to understand its siblings or its fields, and do not keep an old-to-new list in "
            + "your head: file it. Renaming a widely used class is wanted — it improves every later read. "
            + "Your names apply at once and appear in every later decompile, outline and xref. A name is a "
            + "working hypothesis: give a confidence, and rename again when you learn more. A final answer "
            + "that describes names you never filed is a failed mission. The human can revert any write. "
            + "A name in agentNames or a row's nameProvenance/namedBy has recorded authorship, including local variables and arguments. "
            + "An agent-assigned name is that agent's hypothesis with its confidence; 'was' gives the original name when known. When what you read "
            + "contradicts it (a \"download\" loop called from an upload path), rename it to what holds for "
            + "every use, class and method alike, and give that evidence as your rationale; never bend your "
            + "own names to fit a wrong one. Keep it only when every use you can see agrees with it. A name in humanNames is "
            + "final: names a human assigned are not yours to change. During a completion pass, audit arguments and locals "
            + "with naming_coverage and record evidence for suitable existing names. A fully audited class can legitimately "
            + "need zero new renames; never replace a supported name just to produce a write. Coverage reads default to your own actor; "
            + "an explicitly selected prior actor's receipt is evidence to check, not work you have recorded. Before claiming a whole-class "
            + "audit, record your assessment of every method, including items:[] with evidence for methods without variables. "
            + "Reading a complete prior receipt never records your own assessment.";

    private String systemPrompt() {
        return "You are a reverse-engineering agent inside a workbench for Android/Java binaries. "
                + "Work the mission goal with the provided tools: read code, follow xrefs, and write what "
                + "you learn back into the target (renames, comments, tags, map verdicts) with confidence + rationale. "
                + RENAME_AS_YOU_GO + " Be terse and evidence-driven."
                + (mission.budgetUnlimited ? " This mission has no cumulative token budget. Do not stop because of an assumed remaining token budget." : "")
                + (maxTurns == 0 ? " This mission has no turn-count limit. Continue until the requested work is finished or a concrete blocker is documented." : "")
                + (mission.budgetUnlimited || maxTurns == 0 ? " Provider context and per-response output constraints still apply." : "")
                + (mission.scope == null || mission.scope.isBlank() ? ""
                        : " Write-scope: " + mission.scope + " — mutations outside it are BLOCKED.");
    }

    /** Budget ceiling hit: pause and WAIT (don't kill the thread) — after
     *  agent.setbudget + agent.resume the loop continues where it stopped.
     *  Returns true when the loop should abort (stop requested).
     *  R4: the message names the MINIMUM raise the next turn needs. An operator
     *  who resumes with the quoted budget clears both guards by construction
     *  (promptEst is stable across the wait, and completionEst is the cap), so
     *  the raise can no longer be swallowed whole by one turn — the resume race. */
    /** Turn cap hit: pause and WAIT (the thread and its context stay alive) — after
     *  agent.resume {turns: N} with N above the cap the loop continues with the next turn.
     *  Returns true when the loop should abort (stop requested). Mirrors pauseAtCeiling. */
    private boolean pauseAtTurnCap() throws InterruptedException {
        int cap = maxTurns;
        mission.turnCap = cap;
        mission.turnsNeeded = cap + Math.max(5, cap / 2);
        String advice = "turn cap " + cap + " reached — paused at the mission's TURN limit (its own cap, set at spawn — raise it like the token budget)"
                + " · resume with agent.resume {id: " + mission.id + ", turns: " + mission.turnsNeeded + "}";
        activity.row("note", advice);
        progress(advice);
        mission.pauseRequested = true;
        mission.state = "paused";
        while (true) {
            while (mission.pauseRequested && !mission.stopRequested) Thread.sleep(400);
            if (mission.stopRequested) return true;
            if (mission.maxTurns > cap) { maxTurns = mission.maxTurns; break; }
            // resumed without a raise: re-park (agent.resume refuses this, but a raw flag flip lands here)
            activity.row("note", "resumed without a higher turn cap (" + mission.maxTurns + " ≤ " + cap + ") — still paused at the turn limit");
            mission.pauseRequested = true;
            mission.state = "paused";
        }
        mission.turnsNeeded = 0;
        mission.turnCap = maxTurns;
        mission.state = "running";
        activity.row("note", "resumed with turn cap " + maxTurns);
        return false;
    }

    private boolean pauseAtCeiling(String why, int promptEst, int completionEst)
            throws InterruptedException {
        String advice = why + " — paused at the mission's TOKEN budget (its own ceiling, set at spawn — NOT your provider's credit) · next turn needs ≈" + count(promptEst)
                + " prompt + " + count(completionEst) + " completion vs "
                + count(Math.max(0, mission.budget - mission.tokensUsed)) + " remaining"
                // the budget value stays unformatted: it is pasted into the op call
                + " — resume with agent.resume {id: " + mission.id + ", budget: "
                + (mission.tokensUsed + promptEst + completionEst) + "}";
        activity.row("note", advice);
        progress(advice);
        /* structured, not only prose: the inspector's RAISE & RESUME prefills this, and
           agent.resume refuses a no-raise resume against it instead of silently re-pausing
           (the user's "topping up and clicking resume does nothing" — there was no top-up
           control at all, and resume{id} alone just re-parked here) */
        mission.budgetNeeded = mission.tokensUsed + promptEst + completionEst;
        mission.budgetFloor = mission.tokensUsed + promptEst + 1;   // below this the guards re-park at once (agent.resume refuses)
        mission.pauseRequested = true;
        mission.state = "paused";
        while (mission.pauseRequested && !mission.stopRequested) Thread.sleep(400);
        if (mission.stopRequested) return true;
        // resumed without a raise: re-pause, re-quoting against the new budget
        if (mission.tokensUsed >= mission.budget)
            return pauseAtCeiling(why, promptEst, completionEst);
        mission.budgetNeeded = 0;
        mission.budgetFloor = 0;
        mission.state = "running";
        activity.row("note", "resumed with budget " + mission.tokensUsed + "/" + mission.budget);
        return false;
    }

    /** Run the loop. Drives the mission record (state/tokensUsed/progress). */
    public void run() {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        messages.add(Map.of("role", "user", "content", "Mission: " + mission.goal
                + Depth.brief(mission.depth, mission.goal)));   // the same brief the claude CLI prompt carries; a class subject gets the class brief
        Map<String, Integer> rawResultChars = new LinkedHashMap<>();   // tool_call_id → pre-cap size
        Map<String, String> callArgsSummary = new java.util.HashMap<>();   // call id → summarized args, for the elision stub
        int proposalsMade = 0;
        boolean coverageRecorded = false;
        boolean nudged = false;
        int readOnlyStreak = 0;      // consecutive turns with reads but no successful write, since the first decompile
        boolean turnHadCalls = false;
        boolean decompiledOnce = false;
        try {
            mission.turnCap = maxTurns;
            int turn = 1;
            capLoop:
            while (true) {
            for (; maxTurns == 0 || turn <= maxTurns; turn++) {
                activity.turn(turn);
                Map<String, Object> rec = new LinkedHashMap<>();   // the turn transcript (agent.turn) — what the rows below summarize
                List<Map<String, Object>> calls = new ArrayList<>();
                rec.put("turn", turn); rec.put("ts", java.time.Instant.now().toString()); rec.put("calls", calls);
                try {
                    if (mission.stopRequested) return;
                    while (mission.pauseRequested && !mission.stopRequested) Thread.sleep(300);
                    if (mission.stopRequested) return;

                    // (0) context economy (R2/R3) BEFORE the budget math, so the guards
                    // and the pause advice below quote the prompt we actually send
                    ContextStats ctx = compact(messages, rawResultChars, callArgsSummary, subject, contextLimit());
                    int promptEst = ctx.estTokens() + TOOL_SCHEMA_TOKENS;
                    activity.row("note", "turn " + turn + " · context: " + ctx.messages()
                            + " messages · " + ctx.elided() + " elided · " + ctx.stripped()
                            + " reasoning stripped · ~" + count(promptEst) + " / " + count(contextTokens) + " tok");
                    mission.turnsDone = turn;
                    rec.put("context", Map.of("messages", ctx.messages(), "elided", ctx.elided(),
                            "stripped", ctx.stripped(), "promptEst", promptEst, "limit", contextTokens));

                    // (a) budget is a HARD ceiling (QA M2): no new request once spent
                    if (!mission.budgetUnlimited && mission.tokensUsed >= mission.budget) {
                        rec.put("outcome", "paused"); activity.record(rec);   // partial transcript while parked — replaced when the turn completes
                        if (pauseAtCeiling("turn " + turn + " · budget hard ceiling "
                                + mission.tokensUsed + "/" + mission.budget,
                                promptEst, completionLimit())) return;
                        rec.remove("outcome");                     // the retry records afresh under the same turn number
                        turn--;                                    // retry this turn with the raised budget
                        continue;
                    }

                    /* the rename-early nudge (2026-09-18, John's iteration): m3 read for 25 turns while its
                       reasoning kept deciding names it did not file. After 4 read-only turns since the first
                       decompile, one user message asks for the decided names now; the streak restarts on a write. */
                    if (decompiledOnce && readOnlyStreak >= 4) {
                        activity.row("note", "turn " + turn + " · " + readOnlyStreak + " read-only turns — asking for the names decided so far");
                        messages.add(Map.of("role", "user", "content",
                                "You have read for " + readOnlyStreak + " turns without filing a name. File every name you "
                                + "have already decided now with annotate (one items[] call, a name per item), then continue. "
                                + "Preserve supported existing names; a completion pass can record retained-name evidence with naming_coverage instead of inventing replacements."));
                        rec.put("nudge", "rename-early");
                        readOnlyStreak = 0;
                    }
                    Map<String, Object> req = new LinkedHashMap<>(extraParams);   // provider extras first
                    req.put("model", model);                                     // ours always win on conflict
                    req.put("messages", messages);
                    req.put("tools", toolDefs());
                    req.put("tool_choice", "auto");
                    // (b) cap completion so estimated prompt + completion fit the remaining budget
                    long remaining = (long) mission.budget - mission.tokensUsed;
                    if (!mission.budgetUnlimited && remaining - promptEst <= 0) {
                        rec.put("outcome", "paused"); activity.record(rec);
                        if (pauseAtCeiling("turn " + turn + " · prompt estimate " + promptEst
                                + " exceeds remaining budget " + remaining,
                                promptEst, completionLimit())) return;
                        rec.remove("outcome");
                        turn--;
                        continue;
                    }
                    int maxOut = completionLimit();
                    if (!mission.budgetUnlimited) maxOut = (int) Math.min(maxOut, remaining - promptEst);
                    req.put("max_tokens", Math.max(1, maxOut));
                    if (wire.streamOnly()) req.put("stream", true);   // the client collects the deltas; the loop sees one message
                    long chatT0 = System.nanoTime();
                    Map<String, Object> resp;
                    int attempt = 1;
                    while (true) {
                    try {
                        resp = send(req, turn, attempt);
                        break;
                    } catch (CaptureRefused e) {
                        /* a strict capture: the request was not admitted durably — nothing is
                           dispatched uncaptured, and the mission says why. A stop that interrupted
                           the admission is a CANCELLATION with the stop's own reason — not a capture failure. */
                        if (mission.stopRequested) {
                            mission.state = "stopped";
                            rec.put("outcome", "cancelled"); rec.put("error", e.getMessage());
                            activity.row("note", "turn " + turn + " · stopped during the request's admission — not dispatched");
                            return;
                        }
                        mission.state = "blocked";
                        mission.stopReason = "capture refused the request: " + e.getMessage();
                        rec.put("outcome", "blocked"); rec.put("error", e.getMessage());
                        activity.row("blocked", "turn " + turn + " · not dispatched — " + e.getMessage());
                        return;
                    } catch (RuntimeException e) {
                        if (!looksLikeContextOverflow(e)) throw e;
                        /* Only a known refusal is replayable, and only with a changed request.
                           A partial stream or an identical rejected payload is never retried. */
                        String before=Json.write(messages);
                        int learned = windowFromError(e.getMessage());
                        if (learned >= 8_192 && learned < contextTokens) {
                            contextTokens = learned;
                            if (onWindowLearned != null) onWindowLearned.accept(learned);
                            activity.row("note", "turn " + turn + " · the provider says the model's window is " + count(learned) + " tokens — adopted and saved for this provider");
                        }
                        ContextStats again = compact(messages, rawResultChars, callArgsSummary, subject, (int) (contextLimit() * 0.7));
                        if(before.equals(Json.write(messages))) throw e;
                        activity.row("note", "turn " + turn + " · the provider refused the context (" + firstLine(e.getMessage())
                                + ") — evicted the oldest results (" + again.elided() + " stubs, ~" + count(again.estTokens())
                                + " tok) and retrying the changed request");
                        attempt++;
                    }
                    }
                    rec.put("ms", (System.nanoTime() - chatT0) / 1_000_000);
                    if (attempt > 1) rec.put("attempts", attempt);
                    rec.put("requestId", capture.logicalId(mission.id, turn, attempt));
                    /* the receipt: what was asked for and what answered — requested model, returned model
                       identity, the provider's request id, the gateway's replay marker (P0.2) */
                    Map<String, Object> wireRec = new LinkedHashMap<>();
                    wireRec.put("modelRequested", model);
                    if (resp.get("model") != null) wireRec.put("modelReturned", resp.get("model"));
                    if (resp.get("id") != null) wireRec.put("responseId", resp.get("id"));
                    if (resp.get("_capture") instanceof Map<?, ?> rawMeta) {
                        for (String k : List.of("status", "providerRequestId", "replay", "stream")) if (rawMeta.get(k) != null) wireRec.put(k, rawMeta.get(k));
                        resp.remove("_capture");
                    }
                    rec.put("wire", wireRec);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
                    int used = 0;
                    if (usage != null) {
                        int promptTok = num(usage.get("prompt_tokens")), completionTok = num(usage.get("completion_tokens"));
                        int hitTok = num(usage.get("prompt_cache_hit_tokens")), missTok = num(usage.get("prompt_cache_miss_tokens"));
                        if (!usage.containsKey("prompt_cache_hit_tokens") && usage.get("prompt_tokens_details") instanceof Map<?, ?> details)
                            hitTok = num(details.get("cached_tokens"));
                        hitTok = Math.max(0, Math.min(promptTok, hitTok));
                        if (!usage.containsKey("prompt_cache_miss_tokens")) missTok = Math.max(0, promptTok - hitTok);
                        /* the budget counts COST: a prefix-cache hit is billed at ~1/10 of a miss
                           (DeepSeek, Anthropic) — m2 showed 2.19M of 2.29M "used" tokens were hits,
                           and the meter read them at full weight. No cache fields → raw counts. */
                        int promptWeighted = hitTok + missTok > 0 ? (int) Math.round(missTok + hitTok * CACHE_HIT_WEIGHT) : promptTok;
                        used = promptWeighted + completionTok;
                        mission.tokensUsed += used;
                        mission.tokensRaw += promptTok + completionTok;
                        mission.cacheHitTokens += hitTok;
                        mission.inputTokens += promptTok;
                        mission.outputTokens += completionTok;
                        rec.put("usage", new LinkedHashMap<>(usage)); // exact provider usage, including reasoning/cache fields
                        rec.put("tokens", used);
                        rec.put("tokensRaw", promptTok + completionTok);
                        if (usage.get("prompt_cache_hit_tokens") != null || usage.get("prompt_cache_miss_tokens") != null
                                || usage.get("prompt_tokens_details") instanceof Map<?, ?>) {
                            rec.put("cacheHit", hitTok);
                            rec.put("cacheMiss", missTok);
                        }
                    }
                    if (!mission.budgetUnlimited && mission.tokensUsed >= mission.budget) {
                        // this turn's results are still to come; they are capped, so
                        // reserving one cap each bounds what turn+1 must re-send
                        if (pauseAtCeiling("turn " + turn + " · +" + used + " tokens → budget "
                                + mission.tokensUsed + "/" + mission.budget + " reached",
                                estimateTokens(messages) + TOOL_SCHEMA_TOKENS + pendingReserve(resp),
                                completionLimit())) return;
                        // fall through: the response is already paid for — use it
                    }

                    /* a stop that landed while the request was in flight: the answer is paid for
                       (counted above) and set aside — its tool calls are never run, its text is not
                       a result. The mission reads stopped, and says what happened to that turn. */
                    if (mission.stopRequested) {
                        mission.stopReason = "stopped while turn " + turn + "'s request was in flight — the answer was paid for and set aside";
                        rec.put("outcome", "cancelled");
                        activity.row("note", "turn " + turn + " · " + mission.stopReason);
                        return;
                    }
                    /* the shape before anything is read from it: a body that is not a chat
                       completion (an error object with a 200, a truncated stream, a gateway page)
                       ends the mission with the reason, not a ClassCastException in the log */
                    String malformed = malformed(resp);
                    if (malformed != null) {
                        mission.state = "blocked";
                        mission.stopReason = "malformed provider response: " + malformed;
                        activity.row("blocked", "turn " + turn + " · " + mission.stopReason);
                        rec.put("outcome", "blocked"); rec.put("error", mission.stopReason);
                        return;
                    }
                    Map<String, Object> msg = new LinkedHashMap<>(responseMessage(resp));
                    /* a thinking model's trace: recorded (capped) for the transcript — the capture
                       holds it whole — and kept on the message or not per the provider's own rule
                       (Wire.reasoningReplay): DeepSeek thinking and Kimi K3 need it back in every
                       later request of a tool conversation, an endpoint that does not know the
                       field must never see it. Never rewritten later, so the prefix stays cacheable. */
                    Object rc = msg.get(wire.field());
                    if (rc != null) {
                        activity.row("note", "turn " + turn + " · reasoned "
                                + String.valueOf(rc).length() + " chars" + (wire.keeps() ? " (kept for the provider)" : ""));
                        rec.put("reasoning", cap(String.valueOf(rc), REC_TEXT_CAP));
                        if (!wire.keeps()) msg.remove(wire.field());
                    }
                    List<Object> toolCalls = msg.get("tool_calls") instanceof List<?> tl ? new ArrayList<>(tl) : null;
                    if (toolCalls != null && toolCalls.isEmpty()) { toolCalls = null; msg.remove("tool_calls"); }
                    messages.add(msg);
                    Object said = msg.get("content");
                    if (said != null && !String.valueOf(said).isBlank()) rec.put("text", cap(String.valueOf(said), REC_TEXT_CAP));

                    if (toolCalls == null) {
                        String text = msg.get("content") == null ? "" : String.valueOf(msg.get("content"));   // an explicit null is no text (a thinking model's reasoning-only answer carries one)
                        /* reasoning with no answer and no tool call is not a completion — a thinking
                           model that ran out of output, or stopped mid-thought. Once: ask it to go on;
                           twice in a row: end the mission and say so, never "done" (P0.1). */
                        if (text.isBlank() && rc != null) {
                            reasoningOnly++;
                            rec.put("outcome", "reasoning-only");
                            if (reasoningOnly == 1) {
                                activity.row("note", "turn " + turn + " · the answer carried reasoning only (" + String.valueOf(rc).length() + " chars), no text and no tool call — asking it to go on");
                                messages.add(Map.of("role", "user", "content",
                                        "Your last message carried reasoning but no answer and no tool call. Go on: call a tool, or give the answer."));
                                continue;
                            }
                            mission.state = "blocked";
                            mission.stopReason = "two reasoning-only answers in a row (no text, no tool call) — not a result";
                            activity.row("blocked", "turn " + turn + " · " + mission.stopReason);
                            return;
                        }
                        reasoningOnly = 0;
                        // (4) proposal protocol: a mission that only CHATTED gets one nudge
                        if (proposalsMade == 0 && !coverageRecorded && !nudged && (maxTurns == 0 || turn < maxTurns)) {
                            nudged = true;
                            rec.put("outcome", "nudged");
                            activity.row("note", "turn " + turn + " · final text with 0 proposals — nudging");
                            messages.add(Map.of("role", "user", "content",
                                    "Record your findings in the target: use annotate for evidence-supported name improvements. "
                                    + "Preserve suitable existing names and use naming_coverage to record your argument/local audit "
                                    + "and retained-name evidence. Do not invent replacements merely to produce a rename."));
                            continue;
                        }
                        activity.row("note", "turn " + turn + " · final: "
                                + text.substring(0, Math.min(160, text.length())) + " · "
                                + usageSummary());
                        rec.put("outcome", "final");
                        mission.state = "done";
                        mission.stopReason = "final answer after " + turn + " turn" + (turn == 1 ? "" : "s");
                        return;
                    }
                    reasoningOnly = 0;

                    int callIx = 0;
                    for (Object tco : toolCalls) {
                        callIx++;
                        /* a stop that landed mid-turn: the remaining
                           calls — writes included — are not run; the mission reads stopped and the turn
                           record says where it stopped (fix-review 2026-10-09 M1) */
                        if (mission.stopRequested) {
                            rec.put("outcome", "cancelled");
                            rec.put("stoppedBeforeCall", callIx);
                            activity.row("note", "turn " + turn + " · stopped before call " + callIx + "/" + toolCalls.size() + " — the remaining calls were not run");
                            activity.record(rec);
                            return;
                        }
                        /* a call that is not a function call (no map, no function, no name) gets an
                           error reply under a synthesised id, so the pairing the protocol demands
                           holds and the model learns what was wrong */
                        Map<String, Object> tc = asMap(tco) != null ? new LinkedHashMap<>(asMap(tco)) : new LinkedHashMap<>();
                        Map<String, Object> fn = asMap(tc.get("function"));
                        if (tc.get("id") == null || String.valueOf(tc.get("id")).isBlank()) tc.put("id", "call_" + turn + "_" + callIx);
                        String toolName = fn == null || fn.get("name") == null ? "" : String.valueOf(fn.get("name"));
                        String argJson = fn == null ? "{}" : fn.get("arguments") instanceof String a0 ? a0 : fn.get("arguments") == null ? "{}" : Json.write(fn.get("arguments"));
                        Map<String, Object> args;
                        boolean argsBroken = false;
                        try {
                            Object parsed = Json.parse(argJson);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> a = parsed instanceof Map ? (Map<String, Object>) parsed : null;
                            args = a != null ? a : new LinkedHashMap<>();
                        } catch (Exception e) {
                            args = new LinkedHashMap<>();
                            argsBroken = !argJson.isBlank() && !"{}".equals(argJson.trim());
                        }
                        boolean known = ToolFacade.loopToolNames().contains(toolName);
                        activity.row("tool", "turn " + turn + " · call " + callIx + "/" + toolCalls.size() + " · " + model + " → " + toolName
                                + " " + summarizeArgs(args));
                        Map<String, Object> callRec = new LinkedHashMap<>();
                        callRec.put("tool", toolName);
                    callRec.put("n", callIx);
                    callRec.put("of", toolCalls.size());
                        callRec.put("args", cap(prettyJson(args), REC_JSON_CAP));
                        calls.add(callRec);
                        long callT0 = System.nanoTime();
                        Map<String, Object> result;
                        // args pre-check: reject empty required params BEFORE the dispatcher
                        // with a corrective hint the model can self-correct from (glm52 fix)
                        String missing = ToolFacade.missingRequired(toolName, args);
                        if (!known) {
                            result = toolError("E_UNSUPPORTED", "tool not in the mission's set: " + toolName,
                                    "call one of: " + String.join(", ", ToolFacade.loopToolNames()));
                        } else if (argsBroken) {
                            /* the model cut its own JSON (m5 turn 5: a 39-item annotate arrived truncated and
                               read as "missing items") — say what happened and how to recover */
                            result = toolError("E_SYNTAX", "the arguments of " + toolName + " were not valid JSON ("
                                    + argJson.length() + " chars; probably cut off)",
                                    "send the same call in smaller pieces — for annotate, fewer items per call");
                        } else if (missing != null) {
                            result = toolError("E_SYNTAX",
                                    "missing required param '" + missing + "' for " + toolName,
                                    "call " + toolName + " with " + exampleFor(toolName));
                        } else {
                            /* pin every op to the mission's target (fix-review reverse r1
                               M2): the curated schemas carry no target param, so each call
                               used to resolve the server's ACTIVE target — a chip click
                               mid-mission re-aimed the agent at another apk. Dispatch-only:
                               the activity row above shows the model's own arguments */
                            Map<String, Object> callArgs = args;
                            if (mission.target != null && !args.containsKey("target")) {
                                callArgs = new LinkedHashMap<>(args);
                                callArgs.put("target", mission.target);
                            }
                            result = ToolFacade.call(dispatcher, toolName, callArgs, "llm:" + mission.agentId);
                            if (!Boolean.TRUE.equals(result.get("ok")) && result.get("error") instanceof Map<?, ?> err) {
                                // feedback the model can act on: code + message + corrective hint
                                String code = String.valueOf(err.get("code"));
                                /* the pinned target is gone (closed mid-mission): every
                                   further call fails the same way — park the mission with
                                   the reason instead of hinting the model into a retry
                                   loop (r2 R2-5) */
                                if ("E_NOTFOUND".equals(code) && mission.target != null
                                        && String.valueOf(err.get("message")).endsWith(mission.target)) {
                                    mission.state = "blocked";
                                    activity.row("blocked", "mission target " + mission.target
                                            + " was closed — respawn from the target you want");
                                    return;
                                }
                                /* a write outside the mission's scope is not a spelling
                                   problem: the spelling hint made the model re-resolve
                                   and retry a symbol it can never write (fix-review
                                   reverse r1 H1) */
                                String hint = "E_SCOPE".equals(code)
                                        ? "that symbol is outside the mission's write-scope (" + mission.scope
                                                + ") — name it in your summary instead of retrying"
                                        : "E_UNSUPPORTED".equals(code)
                                        ? "this call is not supported as made (" + err.get("detail") + ") — do not retry it the same way"
                                        : err.get("hint") != null ? String.valueOf(err.get("hint"))   // the facade's own hint (candidates …)
                                        : "the arguments were invalid or the target does not resolve — "
                                                + "check the symbol spelling (symbols {symbol} resolves it), then retry with "
                                                + exampleFor(toolName);
                                // Preserve the operation's diagnostics (identity, item index,
                                // collision owner, etc.). A generic hint must not erase the
                                // information the model needs to correct its next request.
                                Map<String, Object> e2 = new LinkedHashMap<>();
                                for (var entry : err.entrySet()) e2.put(String.valueOf(entry.getKey()), entry.getValue());
                                e2.put("hint", hint);
                                result = Map.of("ok", false, "error", e2);
                            }
                        }
                        if (ToolFacade.isWrite(toolName) && wrote(result)) {
                            proposalsMade++;
                            readOnlyStreak = -1;                     // this turn wrote: the streak restarts after it
                        }
                        if ("naming_coverage".equals(toolName) && "record".equals(args.get("action")) && Boolean.TRUE.equals(result.get("ok"))) {
                            coverageRecorded = true;
                            readOnlyStreak = -1; // durable research progress, but not a rename/proposal count
                        }
                        boolean decompileCall = isDecompile(toolName, args);
                        if (decompileCall) decompiledOnce = true;
                        turnHadCalls = true;
                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role", "tool");
                        String callId = String.valueOf(tc.getOrDefault("id", "call_" + turn));
                        toolMsg.put("tool_call_id", callId);
                        toolMsg.put("name", toolName);
                        Object payload = Boolean.TRUE.equals(result.get("ok")) ? result.get("data") : result.get("error");
                        String content = Json.write(payload == null ? Map.of() : payload);
                        rawResultChars.put(callId, content.length());
                        callArgsSummary.put(callId, summarizeArgs(args));   // pre-cap size, for the R2 stub
                        // Unattended exhaustive naming needs complete outlines, locals and argument inventories.
                        // Keep the requested payload intact; context-window maintenance still applies between turns.
                        toolMsg.put("content", mission.turnsUnlimited ? content
                                : decompileCall ? capDecompile(payload, content) : capRows(payload, content));
                        messages.add(toolMsg);
                        capture.toolResult(capture.logicalId(mission.id, turn, attempt), turn, callId, toolName, args,
                                String.valueOf(toolMsg.get("content")), Boolean.TRUE.equals(result.get("ok")), content.length());
                        activity.row("result", "turn " + turn + " · call " + callIx + "/" + toolCalls.size() + " · " + toolName + " → "
                                + summarizeResult(result));
                        callRec.put("ok", Boolean.TRUE.equals(result.get("ok")));
                        /* a large text payload (a decompile, smali, a file) is its own block in the pane,
                           with real newlines — not "\\n" escapes inside a JSON string */
                        Object shown = payload == null ? Map.of() : payload;
                        if (shown instanceof Map<?, ?> pm && pm.get("text") instanceof String t && t.length() > 160) {
                            Map<String, Object> rest = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : pm.entrySet()) rest.put(String.valueOf(e.getKey()), e.getValue());
                            rest.put("text", "(TEXT block below · " + t.length() + " chars)");
                            callRec.put("resultText", cap(t, REC_TEXT_CAP));
                            shown = rest;
                        }
                        callRec.put("result", cap(prettyJson(shown), REC_JSON_CAP));
                        callRec.put("ms", (System.nanoTime() - callT0) / 1_000_000);
                    }
                    progress("turn " + turn + " · " + toolCalls.size() + " tool call(s) · "
                            + usageSummary());
                } catch (Exception e) {
                    rec.put("outcome", "error"); rec.put("error", String.valueOf(e));
                    if (e instanceof WireFailure failure) {
                        Map<String, Object> metadata = failureMetadata(failure.raw);
                        if (!metadata.isEmpty()) rec.put("failure", metadata);
                    }
                    throw e;
                } finally {
                    /* the read-only streak: a turn with calls and no write counts one; a write reset it to -1 above */
                    if (turnHadCalls) readOnlyStreak = readOnlyStreak < 0 ? 0 : readOnlyStreak + 1;
                    turnHadCalls = false;
                    if (!rec.containsKey("outcome")) rec.put("outcome", calls.isEmpty() ? "empty" : "tools");
                    rec.put("tokensUsed", mission.tokensUsed);
                    activity.record(rec);
                    activity.turn(0);
                }
            }
            /* the turn cap parks the mission exactly like the budget ceiling (user rule: "same
               behaviour as the budget limit — the user should be able to raise it"): the loop waits
               for agent.resume {turns} and carries on with the same context; stop ends it */
            if (pauseAtTurnCap()) return;
            continue capLoop;
            }
        } catch (Exception e) {
            if (mission.stopRequested) {
                /* a stop — the user's, interrupting the request in flight — is a
                   cancellation, not a provider failure: the attempt's own failure record is in the
                   capture, the stop reason is the one the stop gave */
                mission.state = "stopped";
                activity.row("note", "stopped mid-request: " + firstLine(e.getMessage()));
            } else {
                mission.state = "blocked";
                mission.stopReason = "provider failure: " + firstLine(e.getMessage());
                dev.workbench.server.Log.error("agent loop " + mission.id + " (" + mission.agentId + ")", e,
                        Map.of("model", String.valueOf(mission.model), "turnsDone", mission.turnsDone, "goal", String.valueOf(mission.goal)));
                activity.row("blocked", "http loop failed: " + e.getMessage());
            }
        } finally {
            if ("stopped".equals(mission.state) && mission.stopReason == null) mission.stopReason = "stopped by the user";
            String outcome = switch (String.valueOf(mission.state)) {
                case "done" -> "completed"; case "stopped" -> "cancelled"; case "blocked", "interrupted" -> "failed"; default -> null;
            };
            if (outcome != null) try { capture.terminal(outcome, mission.stopReason); } catch (Throwable ignored) {}
        }
    }

    /** One request on the wire: admitted by the capture first (strict mode may refuse), sent, and
     *  the answer or the failure recorded exactly. The client strips the `_logicalId` it sends as
     *  X-Request-ID and returns what it saw under `_capture`. */
    private Map<String, Object> send(Map<String, Object> req, int turn, int attempt) throws CaptureRefused {
        String lid = capture.logicalId(mission.id, turn, attempt);
        Map<String, Object> wireReq = new LinkedHashMap<>(req);
        wireReq.put("_logicalId", lid);
        try { capture.request(lid, turn, attempt, req); }
        catch (Exception e) { throw new CaptureRefused(e.getMessage()); }
        long t0 = System.nanoTime();
        try {
            Map<String, Object> resp = client.chat(wireReq);
            Map<String, Object> raw = resp.get("_capture") instanceof Map<?, ?> rm ? asMap(rm) : Map.of();
            Map<String, Object> clean = new LinkedHashMap<>(resp);
            clean.remove("_capture");
            capture.response(lid, turn, attempt, clean, raw, (System.nanoTime() - t0) / 1_000_000);
            return resp;
        } catch (Exception e) {
            capture.failure(lid, turn, attempt, String.valueOf(e.getMessage()), (System.nanoTime() - t0) / 1_000_000, e instanceof WireFailure wf ? wf.raw : Map.of());
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        }
    }

    /** The capture would not admit a request (a strict capture). */
    public static final class CaptureRefused extends Exception {
        public CaptureRefused(String why) { super(why); }
    }

    /** A request that got no usable answer, carrying what the wire DID return — the status, the
     *  body (an error body whole, not its first 200 chars), the segments received before a stream
     *  broke, the provider's request id and replay marker (wireRecord) — for the capture: the
     *  message stays the loop's one-line reason. */
    public static final class WireFailure extends IllegalStateException {
        public final transient Map<String, Object> raw;
        public WireFailure(String message, Map<String, Object> raw) { super(message); this.raw = raw == null ? Map.of() : raw; }
        public WireFailure(String message, Map<String, Object> raw, Throwable cause) {
            super(message, cause); this.raw = raw == null ? Map.of() : raw;
        }
    }

    /** Safe failure receipt for the public turn archive. Wire content stays at the private capture hook. */
    private static Map<String, Object> failureMetadata(Map<String, Object> raw) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : List.of("status", "providerRequestId", "replay", "transportError"))
            if (raw.containsKey(key)) metadata.put(key, raw.get(key));
        if (raw.get("segments") instanceof List<?> segments) metadata.put("receivedSegments", segments.size());
        if (raw.get("partialEventData") instanceof String partial) metadata.put("partialEventChars", partial.length());
        return metadata;
    }

    /** Why a response body is not a chat completion the loop can read, or null when it is. */
    static String malformed(Map<String, Object> resp) {
        if (resp == null) return "no body";
        if (resp.get("error") != null && resp.get("choices") == null) return "an error object: " + firstLine(Json.write(resp.get("error")));
        if (!(resp.get("choices") instanceof List<?> choices)) return "no choices[] (" + (resp.get("choices") == null ? "absent" : resp.get("choices").getClass().getSimpleName()) + ")";
        if (choices.isEmpty()) return "choices[] is empty";
        if (!(choices.get(0) instanceof Map<?, ?> choice)) return "choices[0] is not an object";
        if (!(choice.get("message") instanceof Map<?, ?>)) return "choices[0] has no message object";
        return null;
    }

    private int reasoningOnly;   // consecutive answers with a trace and nothing else (see the loop)

    private void progress(String text) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", System.currentTimeMillis());
        row.put("text", text);
        mission.progress.add(row);
    }

    /** A write tool's result counts as a write when something applied or queued: annotate
     *  answers ok:true even when every item failed (each row carries its reason). */
    @SuppressWarnings("unchecked")
    private static boolean wrote(Map<String, Object> result) {
        if (!Boolean.TRUE.equals(result.get("ok"))) return false;
        if (!(result.get("data") instanceof Map<?, ?> dm)) return true;
        Map<String, Object> d = (Map<String, Object>) dm;
        if (!d.containsKey("applied") && !d.containsKey("pending")) return true;   // map_verdict
        return num(d.get("applied")) + num(d.get("pending")) > 0;
    }

    /** code {view: java} (the default view) is the decompile — the call the subject pin,
     *  the 16 KB cap and the rename-early nudge key on. */
    private static boolean isDecompile(String toolName, Map<String, Object> args) {
        if (!"code".equals(toolName)) return false;
        Object v = args.get("view");
        return v == null || "java".equals(String.valueOf(v));
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static String exampleFor(String toolName) {
        return ToolFacade.exampleFor(toolName);
    }

    private static Map<String, Object> toolError(String code, String message, String hint) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message, "hint", hint));
    }

    /** Result summary for the activity stream (~120 chars). */
    private static String summarizeResult(Map<String, Object> result) {
        String s;
        if (Boolean.TRUE.equals(result.get("ok"))) {
            s = Json.write(result.get("data"));
        } else {
            Object err = result.get("error");
            s = err == null ? "error" : Json.write(err);
        }
        s = String.valueOf(s).replaceAll("\\s+", " ");
        return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    /** Rough token estimate for a message list (~4 chars/token). */
    private static int estimateTokens(List<Object> messages) {
        return Json.write(messages).length() / 4;
    }

    private static String summarizeArgs(Map<String, Object> args) {
        String s = Json.write(args);
        return s.length() > 80 ? s.substring(0, 77) + "…" : s;
    }

    // ------------------------------------------------------------ clients

    /** Endpoint for a provider baseUrl (QA M2 — glm52 404 fix):
     *  - already ends in /chat/completions → use as-is;
     *  - path ends in a version segment (/v1, /v2, /v4…) → append only /chat/completions
     *    (https://api.z.ai/api/paas/v4 → …/v4/chat/completions);
     *  - otherwise → append /v1/chat/completions (https://api.deepseek.com). */
    public static String endpointFor(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        String path = URI.create(url).getPath();
        if (path != null && path.matches(".*(^|/)v\\d+$")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    /** OpenAI-compatible chat-completions client: POST endpointFor(baseUrl),
     *  Authorization: Bearer &lt;key&gt;. */
    public static ChatClient openAiClient(String baseUrl, String key) {
        final String endpoint = endpointFor(baseUrl);
        final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
        return req -> {
            Map<String, Object> body = new LinkedHashMap<>(req);
            Object lid = body.remove("_logicalId");   // the loop's logical request id → X-Request-ID
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (lid != null) b.header("X-Request-ID", String.valueOf(lid));
            if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new WireFailure("provider HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())), wireRecord(resp.statusCode(), resp.headers().map(), resp.body(), null));
            Object parsedObj = Json.parse(resp.body());
            Map<String, Object> parsed = parsedObj instanceof Map<?, ?> pm ? asMap(pm) : new LinkedHashMap<>();
            if (!(parsedObj instanceof Map)) parsed.put("choices", parsedObj);   // not an object: the loop names the malformed shape
            parsed.put("_capture", wireRecord(resp.statusCode(), resp.headers().map(), resp.body(), null));
            return parsed;
        };
    }

    /** What the client saw on the wire, for the capture and the transcript: status, the provider's
     *  own request id (several header spellings), the gateway's replay marker, and the body — whole
     *  for a plain answer, every SSE data segment for a stream. Never a header that carries a
     *  credential. */
    private static Map<String, Object> wireRecord(int status, Map<String, List<String>> headers, String body, List<String> segments) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("status", status);
        for (String h : List.of("x-request-id", "x-wb-request-id", "request-id", "x-amzn-requestid", "cf-ray")) {
            List<String> v = headers.get(h);
            if (v == null) for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(h)) v = e.getValue();
            if (v != null && !v.isEmpty()) { w.put("providerRequestId", v.get(0)); break; }
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("x-wb-replay") && !e.getValue().isEmpty()) w.put("replay", "true".equalsIgnoreCase(e.getValue().get(0)));
        if (segments != null) { w.put("stream", true); w.put("segments", segments); }
        else w.put("body", body);
        return w;
    }

    /** The streaming variant for an endpoint that answers SSE only (some gateways require
     *  stream:true): sends `stream: true` + `stream_options.include_usage`, collects the deltas into
     *  the one message the loop reads — content and the reasoning field by concatenation, tool calls
     *  by index with their id, name and argument fragments — keeps the final chunk's usage, and
     *  returns every data segment exactly under `_capture.segments`. A stream that ends without
     *  `[DONE]` is reported as interrupted: the partial message is NOT handed to the loop as an
     *  answer (an upstream-paid, undelivered result is a failure receipt, P0.2). */
    public static ChatClient openAiStreamingClient(String baseUrl, String key) {
        final String endpoint = endpointFor(baseUrl);
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return req -> {
            Map<String, Object> body = new LinkedHashMap<>(req);
            Object lid = body.remove("_logicalId");
            body.put("stream", true);
            if (!(body.get("stream_options") instanceof Map)) body.put("stream_options", Map.of("include_usage", true));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(600))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (lid != null) b.header("X-Request-ID", String.valueOf(lid));
            if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
            HttpResponse<java.io.InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                if (resp.statusCode() / 100 != 2) {
                    StringBuilder err = new StringBuilder();
                    for (String line; (line = in.readLine()) != null && err.length() < 64_000; ) err.append(line).append('\n');
                    throw new WireFailure("provider HTTP " + resp.statusCode() + ": " + err.toString().substring(0, Math.min(200, err.length())),
                            wireRecord(resp.statusCode(), resp.headers().map(), err.toString(), null));
                }
                return parseSse(in, resp.statusCode(), resp.headers().map());
            }
        };
    }

    /** The SSE body of a chat completion → the one response map the loop reads (see
     *  openAiStreamingClient); the data segments ride along exactly under `_capture.segments`. */
    static Map<String, Object> parseSse(java.io.BufferedReader in, int status, Map<String, List<String>> headers) throws java.io.IOException {
        List<String> segments = new ArrayList<>();
        StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
        String reasoningField = null;
        java.util.TreeMap<Integer, Map<String, Object>> calls = new java.util.TreeMap<>();
        Map<String, Object> usage = null, head = new LinkedHashMap<>();
        String finish = null;
        boolean done = false;
        StringBuilder data = new StringBuilder();
        for (String line; (line = readSseLine(in, status, headers, segments, data)) != null; ) {
            if (line.isEmpty()) {   // the event ends
                if (data.length() == 0) continue;
                String seg = data.toString(); data.setLength(0);
                segments.add(seg);
                if ("[DONE]".equals(seg.trim())) { done = true; break; }
                Object parsed;
                try { parsed = Json.parse(seg); } catch (Exception e) { continue; }   // a non-JSON segment is kept in the capture, not in the message
                Map<String, Object> chunk = asMap(parsed);
                if (chunk == null) continue;
                for (String k : List.of("id", "model", "created", "system_fingerprint")) if (chunk.get(k) != null) head.putIfAbsent(k, chunk.get(k));
                if (chunk.get("usage") instanceof Map<?, ?> u) usage = asMap(u);
                if (!(chunk.get("choices") instanceof List<?> cl) || cl.isEmpty() || !(cl.get(0) instanceof Map<?, ?> c0)) continue;
                Map<String, Object> choice = asMap(c0);
                if (choice.get("finish_reason") != null) finish = String.valueOf(choice.get("finish_reason"));
                Map<String, Object> delta = asMap(choice.get("delta"));
                if (delta == null) continue;
                if (delta.get("content") != null) content.append(delta.get("content"));
                for (String rf : List.of("reasoning_content", "reasoning")) if (delta.get(rf) != null) { reasoning.append(delta.get(rf)); reasoningField = rf; }
                if (delta.get("tool_calls") instanceof List<?> tcs) {
                    for (Object o : tcs) {
                        Map<String, Object> tc = asMap(o);
                        if (tc == null) continue;
                        int ix = tc.get("index") instanceof Number n ? n.intValue() : calls.size();
                        Map<String, Object> acc = calls.computeIfAbsent(ix, x -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", "function"); m.put("function", new LinkedHashMap<>(Map.of("name", "", "arguments", ""))); return m; });
                        if (tc.get("id") != null) acc.put("id", tc.get("id"));
                        Map<String, Object> fn = asMap(tc.get("function"));
                        if (fn != null) {
                            Map<String, Object> af = asMap(acc.get("function"));
                            if (fn.get("name") != null) af.put("name", String.valueOf(af.get("name")) + fn.get("name"));
                            if (fn.get("arguments") != null) af.put("arguments", String.valueOf(af.get("arguments")) + fn.get("arguments"));
                        }
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            }   // other SSE fields (event:, id:, comments) are not part of the payload
        }
        if (!done && finish == null)
            throw new WireFailure("provider stream interrupted after " + segments.size() + " segment(s) — no finish and no [DONE]; the partial answer was not used",
                    wireRecord(status, headers, null, segments));   // the segments that did arrive go to the capture, whole
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content.length() == 0 ? null : content.toString());
        if (reasoning.length() > 0) msg.put(reasoningField == null ? "reasoning_content" : reasoningField, reasoning.toString());
        if (!calls.isEmpty()) msg.put("tool_calls", new ArrayList<>(calls.values()));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0); choice.put("message", msg); choice.put("finish_reason", finish == null ? "stop" : finish);
        Map<String, Object> out = new LinkedHashMap<>(head);
        out.put("object", "chat.completion");
        out.put("choices", List.of(choice));
        if (usage != null) out.put("usage", usage);
        out.put("_capture", wireRecord(status, headers, null, segments));
        return out;
    }

    private static String readSseLine(java.io.BufferedReader in, int status,
            Map<String, List<String>> headers, List<String> segments, StringBuilder data) throws java.io.IOException {
        try { return in.readLine(); }
        catch (java.io.IOException failure) {
            Map<String, Object> raw = wireRecord(status, headers, null, segments);
            if (data.length() > 0) raw.put("partialEventData", data.toString());
            Throwable cause = failure;
            Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while (cause.getCause() != null && seen.add(cause)) cause = cause.getCause();
            raw.put("transportError", Map.of("type", failure.getClass().getName(),
                    "message", String.valueOf(failure.getMessage()), "causeType", cause.getClass().getName(),
                    "cause", String.valueOf(cause.getMessage())));
            throw new WireFailure("provider stream interrupted after " + segments.size()
                    + " segment(s): " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + " — the partial answer was not used", raw, failure);
        }
    }

    /** Models-list endpoint for a provider baseUrl, mirroring {@link #endpointFor}'s
     *  path logic: a version-tailed base gets /models appended (https://api.z.ai/api/paas/v4
     *  → …/v4/models), everything else gets /v1/models (https://api.deepseek.com →
     *  …/v1/models). A base already pointing at /chat/completions is rewound first. */
    public static String modelsEndpointFor(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - "/chat/completions".length());
        if (url.endsWith("/models")) url = url.substring(0, url.length() - "/models".length());   // rewind a base mistakenly set to .../models (no double-append; review nit)
        String path = URI.create(url).getPath();
        if (path != null && path.matches(".*(^|/)v\\d+$")) return url + "/models";
        return url + "/v1/models";
    }

    private static final int MODELS_BODY_CAP = 2 * 1024 * 1024;   // refuse to buffer a /models body larger than this (review L2: unbounded ofString OOMs on a hostile endpoint)

    /** Read up to {@code cap} bytes; throw if the stream carries more (bounded, no OOM). */
    private static byte[] readCapped(java.io.InputStream in, int cap) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) throw new IllegalStateException("the /models response exceeds " + (cap / 1024 / 1024) + " MB - refusing to buffer it");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Parse a /models response body into model ids. OpenAI {data:[{id}]}, tolerating
     *  {models:[...]} and a bare [{id}] / ["id"] list; preserves the endpoint's order,
     *  dedupes, drops blank/idless entries. Throws IllegalStateException on non-JSON or
     *  an unrecognised shape. PURE - unit-tested without a network (review M2). */
    public static List<String> parseModels(String body) {
        Object parsed;
        // Throwable, not Exception: a deeply-nested body can StackOverflow the recursive
        // parser — an Error that would bypass every catch(Exception) and the Dispatcher
        // (review r-853c5f9 #1). The Json depth-guard makes this a catchable throw; this
        // is the belt to that guard's suspenders.
        try { parsed = Json.parse(body); }
        catch (Throwable e) { throw new IllegalStateException("the /models response was not valid JSON (or too deeply nested)"); }
        List<?> data;
        if (parsed instanceof Map<?, ?> m && m.get("data") instanceof List<?> l) data = l;
        else if (parsed instanceof Map<?, ?> m && m.get("models") instanceof List<?> l) data = l;
        else if (parsed instanceof List<?> l) data = l;
        else throw new IllegalStateException("no data[]/models[] array in the /models response");
        List<String> ids = new ArrayList<>();
        for (Object o : data) {
            String s2 = null;
            if (o instanceof Map<?, ?> mm && mm.get("id") != null) s2 = String.valueOf(mm.get("id")).trim();
            else if (o instanceof String str) s2 = str.trim();
            if (s2 != null && !s2.isEmpty() && !ids.contains(s2)) ids.add(s2);
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        return ids;
    }

    /** Discover a provider's model ids: GET {@link #modelsEndpointFor}, Bearer key, the
     *  body read {@link #readCapped bounded} and handed to the pure {@link #parseModels}.
     *  Throws on a non-2xx or an unrecognisable body - the caller maps it to a clear
     *  message; the KEY authenticates and is never returned. */
    public static List<String> discoverModels(String baseUrl, String key) throws Exception {
        String endpoint = modelsEndpointFor(baseUrl);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
        HttpResponse<java.io.InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] raw;
        try (java.io.InputStream in = resp.body()) { raw = readCapped(in, MODELS_BODY_CAP); }
        String body = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        if (resp.statusCode() / 100 != 2)
            throw new IllegalStateException("HTTP " + resp.statusCode() + " from " + endpoint + " - "
                    + body.substring(0, Math.min(180, body.length())));
        return parseModels(body);
    }

    /** The `fixture` provider's scenarios, by model id (LlmRegistry lists them). Recorded-shape
     *  answers, no network, no key, no secret — what the provider-loop tests drive the
     *  loop with (P0.1):
     *    fixture-1       the original script: symbols → code → annotate → final text
     *    ds-tools        a thinking provider with tools (DeepSeek/Kimi shape): reasoning_content on
     *                    every answer, two tool calls in one turn, two consecutive tool turns, then
     *                    content + reasoning; it VERIFIES each request the way the provider would —
     *                    every earlier assistant message must still carry its reasoning_content and
     *                    every tool_call id must have its tool reply — and answers "provider HTTP 400"
     *                    otherwise
     *    plain-strips    an endpoint that returns a reasoning field but rejects it on the way back
     *                    (400 when any message carries it): the loop must strip under replay=never
     *    reasoning-only  a trace and nothing else, every turn
     *    malformed       {"choices": "nope"}
     *    upstream-500    the provider fails
     *    slow-tools      1.5 s per answer, a tool call every turn — for cancellation
     *    big-results     tool calls whose results exceed the transcript caps (strings, a whole class)
     *    stream-tools    ds-tools, answered as SSE segments through the streaming client's parser */
    public static ChatClient fixtureClient(String model) {
        String m = model == null ? "fixture-1" : model;
        switch (m) {
            case "ds-tools": return thinkingFixture(true, false, 3);
            case "ds-long": return thinkingFixture(true, false, 7);   // six tool turns: the oldest results fall out of a small window, the pairing and the traces must not
            case "stream-tools": return streamedFixture(thinkingFixture(true, false, 3));
            case "plain-strips": return thinkingFixture(false, true, 3);
            case "reasoning-only": return reasoningOnlyFixture();
            case "malformed": return req -> { Map<String, Object> out = new LinkedHashMap<>(); out.put("choices", "nope"); out.put("usage", Map.of("prompt_tokens", 10, "completion_tokens", 1)); return out; };
            case "upstream-500": return req -> { throw new IllegalStateException("provider HTTP 500: {\"error\":{\"message\":\"upstream unavailable\",\"type\":\"server_error\"}}"); };
            case "slow-tools": return slowFixture();
            case "big-results": return bigResultsFixture();
            default: return fixtureClient();
        }
    }

    /** The thinking-provider shape. requireReplay: every earlier assistant message must still carry
     *  reasoning_content (DeepSeek: "the reasoning_content of all previous turns should be passed
     *  back … else a 400"); rejectReplay: the opposite endpoint, which 400s when it sees the field. */
    private static ChatClient thinkingFixture(boolean requireReplay, boolean rejectReplay, int finalTurn) {
        return new ChatClient() {
            private int turn;
            private final java.util.Set<String> traces = new java.util.LinkedHashSet<>();   // the traces this endpoint emitted
            @Override
            public Map<String, Object> chat(Map<String, Object> req) {
                turn++;
                List<?> msgs = req.get("messages") instanceof List<?> l ? l : List.of();
                /* the provider's own checks on the history */
                java.util.Set<String> pendingCalls = new java.util.LinkedHashSet<>();
                int seenTraces = 0;
                for (Object o : msgs) {
                    Map<String, Object> mm = asMap(o);
                    if (mm == null) continue;
                    String role = String.valueOf(mm.get("role"));
                    if ("assistant".equals(role)) {
                        if (mm.get("reasoning_content") != null) {
                            seenTraces++;
                            if (rejectReplay) throw new IllegalStateException("provider HTTP 400: {\"error\":{\"message\":\"Unrecognized request argument supplied: messages[].reasoning_content\"}}");
                        }
                        if (mm.get("tool_calls") instanceof List<?> tcs) for (Object t : tcs) { Map<String, Object> tc = asMap(t); if (tc != null && tc.get("id") != null) pendingCalls.add(String.valueOf(tc.get("id"))); }
                    } else if ("tool".equals(role)) {
                        if (!pendingCalls.remove(String.valueOf(mm.get("tool_call_id"))))
                            throw new IllegalStateException("provider HTTP 400: {\"error\":{\"message\":\"tool message with unknown or duplicate tool_call_id " + mm.get("tool_call_id") + "\"}}");
                    }
                }
                if (!pendingCalls.isEmpty())
                    throw new IllegalStateException("provider HTTP 400: {\"error\":{\"message\":\"tool_calls without a tool response: " + pendingCalls + "\"}}");
                if (requireReplay && seenTraces < traces.size())
                    throw new IllegalStateException("provider HTTP 400: {\"error\":{\"message\":\"The reasoning_content of previous assistant messages is missing (" + seenTraces + " of " + traces.size() + "); in thinking mode with tools it must be passed back\"}}");
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                String trace = "turn " + turn + ": I will " + (turn == 1 ? "look at the package tree and the class at once" : turn == 2 ? "name what I understood" : "sum up") + ".";
                traces.add(trace);
                msg.put("reasoning_content", trace);
                if (turn == 1) {
                    msg.put("content", null);
                    msg.put("tool_calls", List.of(call("call_a1", "symbols", Map.of()), call("call_a2", "code", Map.of("symbol", "MessageSyncHandler"))));
                } else if (turn == 2) {
                    msg.put("content", null);
                    msg.put("tool_calls", List.of(call("call_b1", "annotate", Map.of("items", List.of(Map.of("symbol", "onMessage", "name", "onEnvelope", "confidence", 0.7))))));
                } else if (turn < finalTurn) {
                    msg.put("content", null);   // a long run: one read per turn, each result ~a few KB
                    msg.put("tool_calls", List.of(call("call_c" + turn, "code", Map.of("symbol", "MessageSyncHandler"))));
                } else {
                    msg.put("content", "Done: the handler receives an envelope and dispatches it; onMessage renamed to onEnvelope (confidence 0.7). The history held " + seenTraces + " trace(s) and every tool call was answered.");
                }
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0); choice.put("message", msg); choice.put("finish_reason", turn >= finalTurn ? "stop" : "tool_calls");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", "fx-" + turn); out.put("model", "fixture-thinking-2026-10");
                out.put("choices", List.of(choice));
                out.put("usage", Map.of("prompt_tokens", 200 + 50 * turn, "completion_tokens", 40, "total_tokens", 240 + 50 * turn,
                        "prompt_cache_hit_tokens", 100 * (turn - 1), "prompt_cache_miss_tokens", 200 + 50 * turn - 100 * (turn - 1)));
                return out;
            }
        };
    }

    private static ChatClient reasoningOnlyFixture() {
        return req -> {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant"); msg.put("content", null);
            msg.put("reasoning_content", "Thinking about the method… the loop reads bytes… I should check the caller… (cut)");
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0); choice.put("message", msg); choice.put("finish_reason", "length");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("choices", List.of(choice)); out.put("usage", Map.of("prompt_tokens", 300, "completion_tokens", 64, "total_tokens", 364));
            return out;
        };
    }

    private static ChatClient slowFixture() {
        return new ChatClient() {
            private int turn;
            @Override public Map<String, Object> chat(Map<String, Object> req) throws Exception {
                turn++;
                Thread.sleep(1500);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant"); msg.put("content", null);
                msg.put("tool_calls", List.of(call("slow_" + turn, "symbols", Map.of("query", "Sync"))));
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0); choice.put("message", msg); choice.put("finish_reason", "tool_calls");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("choices", List.of(choice)); out.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120));
                return out;
            }
        };
    }

    /** Results past the transcript caps: a long strings listing (> 24,000 chars of text) and a whole
     *  class decompile, then a final. The capture must hold them whole while the transcript shows
     *  its capped projection. */
    private static ChatClient bigResultsFixture() {
        return new ChatClient() {
            private int turn;
            @Override public Map<String, Object> chat(Map<String, Object> req) {
                turn++;
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                if (turn == 1) { msg.put("content", null); msg.put("tool_calls", List.of(call("big_1", "strings", Map.of("limit", 3000)), call("big_2", "code", Map.of("symbol", "MessageSyncHandler")))); }   // the mock scene's class, by the name it resolves
                else msg.put("content", "read it all");
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0); choice.put("message", msg); choice.put("finish_reason", turn == 1 ? "tool_calls" : "stop");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("choices", List.of(choice)); out.put("usage", Map.of("prompt_tokens", 500, "completion_tokens", 30, "total_tokens", 530));
                return out;
            }
        };
    }

    /** Wraps a canned client as an SSE stream and feeds it through the streaming parser the real
     *  client uses — one message becomes head/delta/usage/[DONE] segments, tool-call arguments cut
     *  in two fragments — so the parser is exercised without a network. */
    private static ChatClient streamedFixture(ChatClient inner) {
        return req -> {
            Map<String, Object> full = inner.chat(req);
            Map<String, Object> msg = responseMessage(full);
            List<String> segs = new ArrayList<>();
            String id = String.valueOf(full.getOrDefault("id", "fx")), mdl = String.valueOf(full.getOrDefault("model", "fixture"));
            java.util.function.Function<Map<String, Object>, String> chunk = delta -> Json.write(Map.of("id", id, "object", "chat.completion.chunk", "model", mdl,
                    "choices", List.of(Map.of("index", 0, "delta", delta))));
            segs.add(chunk.apply(Map.of("role", "assistant")));
            if (msg.get("reasoning_content") != null) { String r = String.valueOf(msg.get("reasoning_content")); int h = r.length() / 2; segs.add(chunk.apply(Map.of("reasoning_content", r.substring(0, h)))); segs.add(chunk.apply(Map.of("reasoning_content", r.substring(h)))); }
            if (msg.get("content") != null) segs.add(chunk.apply(Map.of("content", msg.get("content"))));
            if (msg.get("tool_calls") instanceof List<?> tcs) {
                int ix = 0;
                for (Object o : tcs) {
                    Map<String, Object> tc = asMap(o); Map<String, Object> fn = asMap(tc.get("function"));
                    String args = String.valueOf(fn.get("arguments")); int h = Math.max(1, args.length() / 2);
                    segs.add(chunk.apply(Map.of("tool_calls", List.of(Map.of("index", ix, "id", tc.get("id"), "type", "function", "function", Map.of("name", fn.get("name"), "arguments", args.substring(0, h)))))));
                    segs.add(chunk.apply(Map.of("tool_calls", List.of(Map.of("index", ix, "function", Map.of("arguments", args.substring(h)))))));
                    ix++;
                }
            }
            segs.add(Json.write(Map.of("id", id, "object", "chat.completion.chunk", "model", mdl, "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", msg.get("tool_calls") != null ? "tool_calls" : "stop")))));
            segs.add(Json.write(Map.of("id", id, "object", "chat.completion.chunk", "model", mdl, "choices", List.of(), "usage", full.get("usage"))));
            segs.add("[DONE]");
            StringBuilder sse = new StringBuilder();
            for (String sg : segs) sse.append("data: ").append(sg).append("\n\n");
            return parseSse(new java.io.BufferedReader(new java.io.StringReader(sse.toString())), 200, Map.of("x-request-id", List.of("fx-stream-" + id)));
        };
    }

    /** The original script: symbol.tree → code.decompile → rename (proposal) → final text.
     *  Usage numbers are fixed so budget accounting is exercised end-to-end. */
    public static ChatClient fixtureClient() {
        return new ChatClient() {
            private int turn;
            @Override
            public Map<String, Object> chat(Map<String, Object> req) {
                turn++;
                Map<String, Object> msg = new LinkedHashMap<>();
                if (turn == 1) msg.put("tool_calls", List.of(call("c1", "symbols", Map.of())));
                else if (turn == 2) msg.put("tool_calls", List.of(call("c2", "code",
                        Map.of("symbol", "MessageSyncHandler"))));
                else if (turn == 3) msg.put("tool_calls", List.of(call("c3", "annotate",
                        Map.of("items", List.of(Map.of("symbol", "onMessage", "name", "onEnvelope"))))));
                else msg.put("content", "fixture mission complete: tree walked, class decompiled, "
                        + "rename proposal filed");
                msg.put("role", "assistant");
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0);
                choice.put("message", msg);
                choice.put("finish_reason", turn >= 4 ? "stop" : "tool_calls");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("choices", List.of(choice));
                out.put("usage", Map.of("prompt_tokens", 120, "completion_tokens", 30,
                        "total_tokens", 150));
                return out;
            }
        };
    }

    private static Map<String, Object> call(String id, String name, Map<String, Object> args) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", Json.write(args));
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("id", id);
        tc.put("type", "function");
        tc.put("function", fn);
        return tc;
    }
}
