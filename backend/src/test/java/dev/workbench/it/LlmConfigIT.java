package dev.workbench.it;

import dev.workbench.llm.AgentLoop;
import dev.workbench.llm.KeyStore;
import dev.workbench.llm.LlmComposites;
import dev.workbench.llm.LlmRegistry;
import dev.workbench.llm.TaskSettings;
import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.registry.SidecarIO;
import dev.workbench.server.WorkbenchServer;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM provider/model configuration IT (llm-config design §9; plain main-class,
 * no JUnit). HERMETIC: gradle gives it its own WORKBENCH_HOME (the sidecar dir —
 * llm.json / llm-keys.json / tasks.json live there) and port; it refuses to run
 * without one so it can never touch the user's keys. Every id it creates is
 * prefixed llmcfgit- (no developer env var can collide); the env-precedence
 * cases use WORKBENCH_LLM_LLMCFGIT_ENV_KEY, set in build.gradle.
 *
 * Covers: the create gate + slug precedence, the explicit-default invariant,
 * built-in label persistence, write-only keys, the UNLISTED pin (never re-pointed),
 * rename (config/key/pins, position, refusals incl. the key-only residue, custom,
 * env on both ids), remove ordering, persistence through fresh stores + the
 * on-read repair, the persist-failure paths (E_IO, nothing changed; the rename
 * undo via the composite on two dirs), and the configure/rename and setkey/rename
 * races. Prints PASS/FAIL; exit 1 on failure. Run: gradle llmConfigIT
 */
public final class LlmConfigIT {

    private static final String SIDECAR = System.getenv("WORKBENCH_HOME");
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static int failures = 0, checks = 0;

    private static final String P = "llmcfgit-p", Q = "llmcfgit-q", NEW = "llmcfgit-new";

    public static void main(String[] args) throws Exception {
        if (SIDECAR == null || SIDECAR.isBlank()) {
            System.out.println("LlmConfig IT REFUSED: WORKBENCH_HOME is not set — this IT only runs against a hermetic home (gradle llmConfigIT)");
            System.exit(1);
        }
        try {
            run();
        } catch (Throwable t) {
            System.out.println("LlmConfig IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · sidecar " + SIDECAR);
        try {
            parseModelsChecks();
            configureChecks();
            anthropicChecks();
            keyChecks();
            unlistedChecks();
            renameChecks();
            removeChecks();
            persistenceChecks();
            failureChecks();
            raceChecks();
        } finally {
            cleanup();
        }
        System.out.println();
        if (failures > 0) {
            System.out.println("LlmConfig IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("LlmConfig IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 0. parseModels (pure, hermetic — review M2)
    private static void parseModelsChecks() {
        check(List.of("a", "b").equals(AgentLoop.parseModels("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")),
                "parseModels: OpenAI {data:[{id}]}");
        check(List.of("a-model", "B-model", "z-model").equals(AgentLoop.parseModels("{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"B-model\"}]}")),
                "parseModels: sorts model ids alphabetically (case-insensitive)");
        check(List.of("x", "y").equals(AgentLoop.parseModels("{\"models\":[\"x\",\"y\"]}")),
                "parseModels: tolerates {models:[strings]}");
        check(List.of("m1", "m2").equals(AgentLoop.parseModels("[{\"id\":\"m1\"},{\"id\":\"m2\"}]")),
                "parseModels: bare [{id}] list");
        check(List.of("s1", "s2").equals(AgentLoop.parseModels("[\"s1\",\"s2\"]")),
                "parseModels: bare [string] list");
        check(List.of("a", "b").equals(AgentLoop.parseModels("{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"},{\"id\":\"a\"}]}")),
                "parseModels: dedupes and sorts");
        check(List.of("123").equals(AgentLoop.parseModels("{\"data\":[{\"id\":123}]}")),
                "parseModels: numeric id stringified to \"123\"");
        check(List.of("ok").equals(AgentLoop.parseModels("{\"data\":[{\"id\":\"\"},{\"foo\":1},{\"id\":\"ok\"}]}")),
                "parseModels: blank/idless entries dropped");
        check(AgentLoop.parseModels("{\"data\":[]}").isEmpty(), "parseModels: empty data -> empty list");
        boolean nonJson = false;
        try { AgentLoop.parseModels("<html>nope</html>"); } catch (IllegalStateException e) { nonJson = true; }
        check(nonJson, "parseModels: non-JSON throws");
        boolean badShape = false;
        try { AgentLoop.parseModels("{\"foo\":\"bar\"}"); } catch (IllegalStateException e) { badShape = true; }
        check(badShape, "parseModels: unrecognised shape throws");
        check(AgentLoop.parseModels("[[1],[2]]").isEmpty(), "parseModels: array-of-arrays -> no ids (elements are neither maps nor strings)");
        boolean scalar = false;
        try { AgentLoop.parseModels("123"); } catch (IllegalStateException e) { scalar = true; }
        check(scalar, "parseModels: a bare scalar body throws");
        // a deeply-nested body must throw a CATCHABLE IllegalStateException, NOT StackOverflowError (review r-853c5f9 #1)
        StringBuilder deep = new StringBuilder(); for (int i = 0; i < 5000; i++) deep.append('[');
        boolean deepThrew = false;
        try { AgentLoop.parseModels(deep.toString()); } catch (IllegalStateException e) { deepThrew = true; }
        check(deepThrew, "parseModels: a 5000-deep body throws IllegalStateException (Json depth-guard, no StackOverflow)");
        // endpoint construction (pure): version-tailed vs plain, and the /models + /chat/completions rewinds
        check("https://api.z.ai/api/paas/v4/models".equals(AgentLoop.modelsEndpointFor("https://api.z.ai/api/paas/v4")),
                "modelsEndpointFor: version-tailed base -> /vN/models");
        check("https://api.deepseek.com/v1/models".equals(AgentLoop.modelsEndpointFor("https://api.deepseek.com")),
                "modelsEndpointFor: plain base -> /v1/models");
        check("https://api.z.ai/api/paas/v4/models".equals(AgentLoop.modelsEndpointFor("https://api.z.ai/api/paas/v4/models")),
                "modelsEndpointFor: a base already ending /models does not double-append");
    }

    // ---------------------------------------------------------------- 0b. Anthropic = vendor, auth = connection (llm-anthropic-vendor design)
    private static void anthropicChecks() throws Exception {
        Map<String, Object> an = row("anthropic");
        check(!an.isEmpty() && "cli".equals(an.get("auth")) && "cli".equals(an.get("driver")) && Boolean.FALSE.equals(an.get("needsKey"))
                        && List.of("cli", "api").equals(an.get("authOptions"))
                        && ((List<?>) an.get("models")).containsAll(List.of("claude-code", "fable", "opus", "sonnet", "haiku")),
                "anthropic: shipped as the Claude Code login — cli driver, no key, the CLI aliases incl. haiku, authOptions [cli, api]");
        check(row("deepseek").get("auth") == null && row("deepseek").get("authOptions") == null,
                "anthropic: a single-option provider carries no auth/authOptions (#6)");
        check(row("claude").isEmpty(), "anthropic: the legacy id claude is not a provider");
        Map<String, Object> r = post("llm.configure", Map.of("id", "anthropic", "auth", "api", "models", "x"));
        check("E_SYNTAX".equals(err(r)), "anthropic: auth combined with models is E_SYNTAX — auth travels alone (A1)");
        r = post("llm.configure", Map.of("id", "deepseek", "auth", "api"));
        check("E_UNSUPPORTED".equals(err(r)), "anthropic: auth on a provider with no choice is E_UNSUPPORTED");
        r = post("llm.configure", Map.of("id", "anthropic", "local", true));
        check("E_UNSUPPORTED".equals(err(r)), "anthropic: LOCAL does not apply to a choosing provider (A2)");
        r = post("llm.configure", Map.of("id", "anthropic", "baseUrl", "http://127.0.0.1:1/v1"));
        check("E_UNSUPPORTED".equals(err(r)) && "".equals(row("anthropic").get("baseUrl")),
                "anthropic: the endpoint follows the connection — baseUrl is refused, memory untouched (review A2, §5)");
        Map<String, Object> api = data(post("llm.configure", Map.of("id", "anthropic", "auth", "api")));
        check("api".equals(api.get("auth")) && "http".equals(api.get("driver")) && "https://api.anthropic.com/v1".equals(api.get("baseUrl"))
                        && Boolean.TRUE.equals(api.get("needsKey")) && Boolean.FALSE.equals(api.get("configured"))
                        && List.of("claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5").equals(api.get("models"))
                        && "claude-opus-5".equals(api.get("model")),
                "anthropic: auth=api derives http + the Anthropic endpoint + needsKey, re-seeds the API list (default opus-5), unconfigured without a key");
        LlmRegistry fresh = new LlmRegistry(new File(SIDECAR), new KeyStore(new File(SIDECAR)));
        check(fresh.get("anthropic") != null && "api".equals(fresh.get("anthropic").auth) && "http".equals(fresh.get("anthropic").driver)
                        && "https://api.anthropic.com/v1".equals(fresh.get("anthropic").baseUrl),
                "anthropic: auth persists and re-derives the endpoint on a fresh registry (A3)");
        data(post("llm.setkey", Map.of("id", "anthropic", "key", "sk-ant-it")));
        check(Boolean.TRUE.equals(row("anthropic").get("configured")), "anthropic: a stored key configures the API connection");
        data(post("llm.setkey", Map.of("id", "anthropic", "key", "")));
        Map<String, Object> back = data(post("llm.configure", Map.of("id", "anthropic", "auth", "cli")));
        check("cli".equals(back.get("driver")) && Boolean.FALSE.equals(back.get("needsKey")) && "".equals(back.get("baseUrl"))
                        && ((List<?>) back.get("models")).contains("haiku") && "claude-code".equals(back.get("model")),
                "anthropic: back to cli re-seeds the aliases (incl. haiku), default claude-code, no key needed");
        r = post("llm.configure", Map.of("id", "claude", "create", true, "baseUrl", "http://x"));
        check("E_SYNTAX".equals(err(r)), "anthropic: creating a provider named claude is refused — reserved (A4)");
        r = post("llm.rename", Map.of("id", P, "newId", "claude"));
        check("E_SYNTAX".equals(err(r)), "anthropic: renaming to claude is refused — reserved (A4)");
        String[] s1 = fresh.resolve("claude-code"), s2 = fresh.resolve("claude:opus"), s3 = fresh.resolve("claude");
        check(s1 != null && "anthropic".equals(s1[0]) && s2 != null && "anthropic".equals(s2[0]) && "opus".equals(s2[1]) && s3 != null && "anthropic".equals(s3[0]),
                "anthropic: claude-code, claude:opus and bare claude all resolve to anthropic (the legacy alias)");
    }

    // ---------------------------------------------------------------- 1. configure
    private static void configureChecks() throws Exception {
        Map<String, Object> r = post("llm.configure", Map.of("id", "llmcfgit-nope", "baseUrl", "http://x"));
        check("E_NOTFOUND".equals(err(r)), "configure: unknown id without create → E_NOTFOUND (the create gate)");
        r = post("llm.configure", Map.of("id", "Bad Id!", "create", true));
        check("E_SYNTAX".equals(err(r)), "configure: an invalid slug is E_SYNTAX even with create (slug precedes the gate)");
        r = post("llm.configure", Map.of("id", "Bad Id!"));
        check("E_SYNTAX".equals(err(r)), "configure: an invalid unknown id is E_SYNTAX, not E_NOTFOUND (M10aIT:569 stays green)");

        Map<String, Object> p = data(post("llm.configure", Map.of("id", P, "create", true, "label", "IT P",
                "baseUrl", "http://127.0.0.1:1/v1", "models", "m1, m2", "model", "m2", "local", true)));
        check(List.of("m1", "m2").equals(p.get("models")) && "m2".equals(p.get("model"))
                        && Boolean.TRUE.equals(p.get("local")) && Boolean.TRUE.equals(p.get("configured"))
                        && Boolean.FALSE.equals(p.get("builtin")),
                "configure: create:true → list [m1,m2], explicit default m2 (not first), LOCAL needs no key");

        r = post("llm.configure", Map.of("id", P, "models", "m1, m2", "model", "zz"));
        check("E_SYNTAX".equals(err(r)) && "m2".equals(row(P).get("model")),
                "configure: models given + out-of-list default → E_SYNTAX, nothing mutated");

        p = data(post("llm.configure", Map.of("id", P, "model", "m3")));
        check(List.of("m1", "m2", "m3").equals(p.get("models")) && "m3".equals(p.get("model")),
                "configure: models absent + new model → appended (not prepended) and made the default");

        p = data(post("llm.configure", Map.of("id", P, "models", "m1, m2")));
        check(List.of("m1", "m2").equals(p.get("models")) && "m1".equals(p.get("model")),
                "configure: the default dropped from the list → the first listed is promoted");

        p = data(post("llm.configure", Map.of("id", P, "models", "")));
        check(((List<?>) p.get("models")).isEmpty() && "".equals(p.get("model")),
                "configure: an explicit empty list clears list and default");
        data(post("llm.configure", Map.of("id", P, "models", "m1, m2", "model", "m2")));   // restore

        data(post("llm.configure", Map.of("id", "deepseek", "label", "DS IT")));
        LlmRegistry fresh = new LlmRegistry(new File(SIDECAR), new KeyStore(new File(SIDECAR)));
        check(fresh.get("deepseek") != null && "DS IT".equals(fresh.get("deepseek").label),
                "configure: a BUILT-IN label edit is persisted and survives a registry reload (review M3)");
        data(post("llm.configure", Map.of("id", "deepseek", "label", "DeepSeek")));   // restore the shipped label

        data(post("llm.configure", Map.of("id", "llmcfgit-a-b", "create", true, "local", true)));
        r = post("llm.configure", Map.of("id", "llmcfgit-a_b", "create", true, "local", true));
        check("E_SYNTAX".equals(err(r)) && row("llmcfgit-a_b").isEmpty(),
                "configure: create refused when the id's env-variable name is another provider's (a-b vs a_b, L3)");
        data(post("llm.remove", Map.of("id", "llmcfgit-a-b")));
    }

    // ---------------------------------------------------------------- 2. keys
    private static void keyChecks() throws Exception {
        Map<String, Object> q = data(post("llm.configure", Map.of("id", Q, "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "q1")));
        check(Boolean.FALSE.equals(q.get("configured")) && Boolean.TRUE.equals(q.get("needsKey")), "setkey: a non-local provider starts unconfigured");
        Map<String, Object> k = data(post("llm.setkey", Map.of("id", Q, "key", "sk-it-secret-001")));
        check(Boolean.TRUE.equals(k.get("configured")) && Boolean.TRUE.equals(row(Q).get("configured")), "setkey: stores → configured:true");
        String all = Json.write(post("llm.providers", Map.of()));
        check(!all.contains("sk-it-secret-001"), "setkey: the key never appears in llm.providers (write-only)");
        k = data(post("llm.setkey", Map.of("id", Q, "key", "")));
        check(Boolean.FALSE.equals(k.get("configured")), "setkey: \"\" clears the stored key");
        Map<String, Object> r = post("llm.setkey", Map.of("id", Q));
        check("E_SYNTAX".equals(err(r)), "setkey: an absent key param is E_SYNTAX, never a wipe");
        data(post("llm.setkey", Map.of("id", Q, "key", "sk-it-secret-002")));   // keep a key on Q for the rename cases
    }

    // ---------------------------------------------------------------- 3. the UNLISTED pin (§6.4)
    private static void unlistedChecks() throws Exception {
        data(post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", P + ":m2",
                "effort", "high", "turns", 7, "budget", 1234)));
        data(post("llm.configure", Map.of("id", P, "models", "m1")));                      // drops the pinned m2
        Map<String, Object> quick = depth("quick");
        Map<?, ?> res = (Map<?, ?>) quick.get("resolved");
        check((P + ":m2").equals(quick.get("model")) && Boolean.TRUE.equals(res.get("unlisted"))
                        && "high".equals(quick.get("effort")) && Integer.valueOf(7).equals(((Number) quick.get("turns")).intValue())
                        && Integer.valueOf(1234).equals(((Number) quick.get("budget")).intValue()),
                "unlisted: dropping a pinned model keeps the pin + effort/turns/budget and marks resolved.unlisted");
        String doctor = Json.write(post("system.doctor", Map.of("rows", "reversing-agent")));
        check(doctor.contains("no longer listed") && doctor.contains("warn"),
                "unlisted: system.doctor's reversing-agent row goes WARN and says why");
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "anthropic:opus")));
        data(post("llm.configure", Map.of("id", "anthropic", "models", "claude-code, fable, sonnet")));   // drops the pinned cli alias
        Map<?, ?> deepRes = (Map<?, ?>) depth("deep").get("resolved");
        check(Boolean.TRUE.equals(deepRes.get("unlisted")), "unlisted: applies to the claude CLI alias list too (any listed provider, N8)");
        data(post("llm.configure", Map.of("id", "anthropic", "models", "claude-code, fable, opus, sonnet, haiku")));   // restore the shipped list
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "")));
        data(post("llm.configure", Map.of("id", P, "models", "m1, m2", "model", "m2")));     // m2 listed again
        check(!Boolean.TRUE.equals(((Map<?, ?>) depth("quick").get("resolved")).get("unlisted")), "unlisted: clears once the model is listed again");
    }

    // ---------------------------------------------------------------- 4. rename (§6.2)
    private static void renameChecks() throws Exception {
        int before = ids().size();
        data(post("llm.setkey", Map.of("id", "llmcfgit-keyonly", "key", "k-only")));       // a key with no provider
        data(post("llm.configure", Map.of("id", "llmcfgit-c-d", "create", true, "local", true)));

        refused("deepseek", "llmcfgit-x", "E_UNSUPPORTED", "a built-in cannot be renamed");
        refused(P, Q, "E_SYNTAX", "newId already registered");
        Map<String, Object> r = post("llm.rename", Map.of("id", P, "newId", "llmcfgit-keyonly"));
        check("E_SYNTAX".equals(err(r)) && String.valueOf(r.get("error")).contains("residue"),
                "rename: refused when a KEY is stored under newId with no provider — names the residue recovery (H2/N6)");
        refused(P, "Bad Id!", "E_SYNTAX", "invalid newId slug");
        refused("llmcfgit-nope", "llmcfgit-x", "E_NOTFOUND", "unknown id");
        refused(P, P, "E_SYNTAX", "newId equals id");
        refused(P, "llmcfgit-c_d", "E_SYNTAX", "newId's env-variable name is another provider's (L3)");
        check(ids().size() == before + 1 && !row(P).isEmpty(), "rename: every refusal left the registry untouched");
        data(post("llm.remove", Map.of("id", "llmcfgit-c-d")));
        data(post("llm.setkey", Map.of("id", "llmcfgit-keyonly", "key", "")));

        int pos = ids().indexOf(P);
        Map<String, Object> renamed = data(post("llm.rename", Map.of("id", P, "newId", NEW)));
        List<String> after = ids();
        @SuppressWarnings("unchecked") List<Map<String, Object>> rewritten = (List<Map<String, Object>>) renamed.get("tasksRewritten");
        check(NEW.equals(renamed.get("id")) && P.equals(renamed.get("renamedFrom")) && after.indexOf(NEW) == pos && !after.contains(P)
                        && List.of("m1", "m2").equals(renamed.get("models")) && "m2".equals(renamed.get("model")) && "IT P".equals(renamed.get("label"))
                        && "http://127.0.0.1:1/v1".equals(renamed.get("baseUrl")) && Boolean.TRUE.equals(renamed.get("local")),
                "rename: config moved to " + NEW + " at the SAME position with models/default/label/baseUrl/local intact");
        check(rewritten != null && rewritten.size() == 1 && (NEW + ":m2").equals(rewritten.get(0).get("model")),
                "rename: tasksRewritten names the quick pin as " + NEW + ":m2");
        Map<String, Object> quick = depth("quick");
        check((NEW + ":m2").equals(quick.get("model")) && "high".equals(quick.get("effort"))
                        && ((Number) quick.get("turns")).intValue() == 7 && ((Number) quick.get("budget")).intValue() == 1234
                        && NEW.equals(((Map<?, ?>) quick.get("resolved")).get("provider")),
                "rename: the pin resolves to the new id with effort/turns/budget KEPT");
        check("E_SYNTAX".equals(err(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", P + ":m1")))),
                "rename: the old id no longer resolves");
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", NEW + ":m1")));
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "")));
        check(Boolean.FALSE.equals(renamed.get("effectiveKeyChanged")), "rename: no env variable involved → effectiveKeyChanged:false");

        // the key moves with a keyed provider; the old id's key is GONE (the only seam: re-create it)
        Map<String, Object> q2 = data(post("llm.rename", Map.of("id", Q, "newId", "llmcfgit-q2")));
        check(Boolean.TRUE.equals(q2.get("configured")), "rename: a keyed provider is still configured under its new id");
        Map<String, Object> qAgain = data(post("llm.configure", Map.of("id", Q, "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "q1")));
        check(Boolean.FALSE.equals(qAgain.get("configured")) && Boolean.TRUE.equals(qAgain.get("needsKey")),
                "rename: the old id re-created has NO key — the key was moved, not copied");
        data(post("llm.remove", Map.of("id", Q)));

        // custom: the one renameable built-in; an empty custom is re-seeded at once
        Map<String, Object> vend = data(post("llm.rename", Map.of("id", "custom", "newId", "llmcfgit-vend")));
        Map<String, Object> custom = row("custom");
        check("llmcfgit-vend".equals(vend.get("label")) && !custom.isEmpty() && Boolean.TRUE.equals(custom.get("builtin"))
                        && ((List<?>) custom.get("models")).isEmpty() && Boolean.FALSE.equals(row("llmcfgit-vend").get("builtin")),
                "rename: custom → vend (label follows the id) and an empty built-in custom is present immediately");
        data(post("llm.remove", Map.of("id", "llmcfgit-vend")));

        // env under the OLD id (build.gradle sets WORKBENCH_LLM_LLMCFGIT_ENV_KEY)
        Map<String, Object> envP = data(post("llm.configure", Map.of("id", "llmcfgit-env", "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "e1")));
        check(Boolean.TRUE.equals(envP.get("configured")), "env: WORKBENCH_LLM_LLMCFGIT_ENV_KEY makes llmcfgit-env configured with no stored key");
        Map<String, Object> env2 = data(post("llm.rename", Map.of("id", "llmcfgit-env", "newId", "llmcfgit-env2")));
        check(Boolean.TRUE.equals(env2.get("effectiveKeyChanged")) && String.valueOf(env2.get("hint")).contains("WORKBENCH_LLM_LLMCFGIT_ENV_KEY")
                        && Boolean.FALSE.equals(env2.get("configured")),
                "rename: an env-only key under the OLD id → effectiveKeyChanged + hint; the new id is unconfigured (H3)");
        data(post("llm.remove", Map.of("id", "llmcfgit-env2")));
        // env under the NEW id: a stored key moves under an id whose env var shadows it
        data(post("llm.configure", Map.of("id", "llmcfgit-tmp", "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "t1")));
        data(post("llm.setkey", Map.of("id", "llmcfgit-tmp", "key", "file-key")));
        Map<String, Object> shadow = data(post("llm.rename", Map.of("id", "llmcfgit-tmp", "newId", "llmcfgit-env")));
        check(Boolean.TRUE.equals(shadow.get("effectiveKeyChanged")) && String.valueOf(shadow.get("hint")).contains("shadows"),
                "rename: an env var under the NEW id → effectiveKeyChanged + 'shadows' hint (the H3 gap)");
        data(post("llm.remove", Map.of("id", "llmcfgit-env")));
    }

    private static void refused(String id, String newId, String code, String why) throws Exception {
        Map<String, Object> r = post("llm.rename", Map.of("id", id, "newId", newId));
        check(code.equals(err(r)), "rename: refused (" + code + ") — " + why);
    }

    // ---------------------------------------------------------------- 5. remove (§5 ordering)
    private static void removeChecks() throws Exception {
        data(post("llm.configure", Map.of("id", "llmcfgit-r", "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "r1")));
        data(post("llm.setkey", Map.of("id", "llmcfgit-r", "key", "r-key")));
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "llmcfgit-r:r1")));
        Map<String, Object> rm = data(post("llm.remove", Map.of("id", "llmcfgit-r")));
        check(Boolean.TRUE.equals(rm.get("removed")) && String.valueOf(rm.get("tasksReset")).contains("reverse/deep")
                        && Boolean.TRUE.equals(((Map<?, ?>) depth("deep").get("resolved")).get("unconfigured")),
                "remove: the provider goes, its pin is reset");
        Map<String, Object> again = data(post("llm.configure", Map.of("id", "llmcfgit-r", "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "r1")));
        check(Boolean.FALSE.equals(again.get("configured")), "remove: its key went with it (re-created id has none)");
        data(post("llm.remove", Map.of("id", "llmcfgit-r")));
    }

    // ---------------------------------------------------------------- 6. persistence through fresh stores
    private static void persistenceChecks() throws Exception {
        data(post("llm.configure", Map.of("id", "deepseek", "label", "DS IT2")));
        File dir = new File(SIDECAR);
        LlmRegistry reg = new LlmRegistry(dir, new KeyStore(dir));
        TaskSettings ts = new TaskSettings(dir);
        LlmRegistry.Provider n = reg.get(NEW);
        check(n != null && List.of("m1", "m2").equals(n.models) && "m2".equals(n.defaultModel) && "IT P".equals(n.label) && reg.get(P) == null,
                "persistence: the renamed provider, its list and default survive a fresh registry");
        check(ts.get("reverse", "quick") != null && (NEW + ":m2").equals(ts.get("reverse", "quick").model),
                "persistence: the REWRITTEN pin survives a fresh TaskSettings");
        check(reg.get("deepseek") != null && "DS IT2".equals(reg.get("deepseek").label), "persistence: an edited built-in label survives");
        data(post("llm.configure", Map.of("id", "deepseek", "label", "DeepSeek")));
        // an old file whose default is not in its list loads REPAIRED (appended), never dropped (§4 on read)
        Path tmp = Files.createTempDirectory("llmcfgit-old");
        Files.writeString(tmp.resolve("llm.json"),
                "{\"providers\":{\"llmcfgit-old\":{\"baseUrl\":\"http://x\",\"models\":[\"a\",\"b\"],\"model\":\"z\"}}}");
        LlmRegistry old = new LlmRegistry(tmp.toFile(), new KeyStore(tmp.toFile()));
        check(old.get("llmcfgit-old") != null && List.of("a", "b", "z").equals(old.get("llmcfgit-old").models) && "z".equals(old.get("llmcfgit-old").defaultModel),
                "persistence: an out-of-list default in an old llm.json is appended on read, not dropped");
        // review r-f31a574 M2: emptying a BUILT-IN's model list must SURVIVE a reload —
        // the shipped list must NOT silently return (the twin of the M3 label bug this
        // suite already guards). Capture the shipped list, clear via the op, reload from
        // disk, assert it stayed empty, then restore so no later check sees a bare deepseek.
        LlmRegistry.Provider ds0 = new LlmRegistry(dir, new KeyStore(dir)).get("deepseek");
        List<String> dsModels = List.copyOf(ds0.models);
        String dsDefault = ds0.defaultModel;
        data(post("llm.configure", Map.of("id", "deepseek", "models", List.of())));   // explicit [] CLEARS
        LlmRegistry emptied = new LlmRegistry(dir, new KeyStore(dir));
        check(emptied.get("deepseek") != null && emptied.get("deepseek").models.isEmpty() && emptied.get("deepseek").defaultModel.isEmpty(),
                "persistence: an emptied built-in list stays empty after a reload — the shipped list does NOT return (review r-f31a574 M2)");
        data(post("llm.configure", Map.of("id", "deepseek", "models", dsModels, "model", dsDefault)));   // restore the shipped list
    }

    // ---------------------------------------------------------------- 7. persist failures (§5 / §9.7)
    private static void failureChecks() throws Exception {
        Path dir = Path.of(SIDECAR);
        // a NON-local, key-requiring provider (created while the dir is writable) — the
        // setkey/task reverts below must be proven on a provider whose configured flag
        // actually depends on the key (review r-f31a574 M4: the old case used a LOCAL
        // provider, so configured==local==true was a tautology).
        data(post("llm.configure", Map.of("id", "llmcfgit-nl", "create", true, "baseUrl", "http://127.0.0.1:1/v1", "models", "n1")));
        var rw = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        boolean enforced;
        try { Files.createTempFile(dir, "probe", ".tmp").toFile().delete(); enforced = false; }
        catch (Exception e) { enforced = true; }
        try {
            if (!enforced) { System.out.println("SKIP failure path: the directory mode is not enforced here (root / ACL / fs)"); }
            else {
                long fails = SidecarIO.writeFailures.get();
                Map<String, Object> r = post("llm.configure", Map.of("id", NEW, "label", "IT P changed"));
                check("E_IO".equals(err(r)) && String.valueOf(r.get("error")).contains("llm.json") && "IT P".equals(row(NEW).get("label"))
                                && SidecarIO.writeFailures.get() > fails,
                        "failure: an unwritable sidecar dir → E_IO naming llm.json, memory reverted, writeFailures counted");
                r = post("llm.setkey", Map.of("id", "llmcfgit-nl", "key", "never"));
                check("E_IO".equals(err(r)) && Boolean.FALSE.equals(row("llmcfgit-nl").get("configured")),
                        "failure: a key that cannot be written is NOT held in memory — the non-local provider stays configured:false (setkey → E_IO, review r-f31a574 M4)");
                // the THIRD store: task.configure's revert through the op layer (§9.7 named it uncovered — only llm.json and the keyring were exercised)
                r = post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "llmcfgit-nl:n1"));
                check("E_IO".equals(err(r)) && !String.valueOf(post("task.list", Map.of())).contains("llmcfgit-nl:n1"),
                        "failure: a task pin that cannot be written is reverted — no llmcfgit-nl:n1 survives in task.list (task.configure → E_IO)");
            }
        } finally {
            Files.setPosixFilePermissions(dir, rw);
        }
        data(post("llm.remove", Map.of("id", "llmcfgit-nl")));   // clean up the fixture, whether or not the mode was enforced
        if (!enforced) return;
        // the rename undo, driven through the composite with the stores on two directories
        Path dirW = Files.createTempDirectory("llmcfgit-w"), dirRO = Files.createTempDirectory("llmcfgit-ro");
        KeyStore ks = new KeyStore(dirW.toFile());
        ks.set("llmcfgit-h", "h-key");
        LlmRegistry reg = new LlmRegistry(dirRO.toFile(), ks);
        reg.configure("llmcfgit-h", "http://127.0.0.1:1/v1", null, "h", List.of("m"), false, null, true, null, null);   // contextTokens: the model window (unchanged here)
        TaskSettings ts = new TaskSettings(dirW.toFile());
        var roPerms = Files.getPosixFilePermissions(dirRO);
        Files.setPosixFilePermissions(dirRO, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            String code = null, msg = "";
            try { LlmComposites.rename(reg, ks, ts, "llmcfgit-h", "llmcfgit-h2"); }
            catch (OpException e) { code = e.code; msg = e.getMessage(); }
            KeyStore disk = new KeyStore(dirW.toFile());
            check("E_IO".equals(code) && msg.contains("llm.json was not written") && msg.contains("nothing changed"),
                    "failure: a rename whose registry write fails is E_IO 'nothing changed'");
            check(disk.hasStored("llmcfgit-h") && !disk.hasStored("llmcfgit-h2") && reg.get("llmcfgit-h") != null && reg.get("llmcfgit-h2") == null,
                    "failure: the key move was undone ON DISK (fresh KeyStore) and the registry is unchanged");
        } finally {
            Files.setPosixFilePermissions(dirRO, roPerms);
        }
    }

    // ---------------------------------------------------------------- 8. races (the Dispatcher does not serialize)
    private static void raceChecks() throws Exception {
        final Map<String, Object>[] res = new Map[2];
        Thread t1 = new Thread(() -> { try { res[0] = post("llm.configure", Map.of("id", NEW, "label", "C1")); } catch (Exception e) { res[0] = Map.of("error", e.toString()); } });
        Thread t2 = new Thread(() -> { try { res[1] = post("llm.rename", Map.of("id", NEW, "newId", "llmcfgit-cc")); } catch (Exception e) { res[1] = Map.of("error", e.toString()); } });
        t1.start(); t2.start(); t1.join(); t2.join();
        Map<String, Object> cc = row("llmcfgit-cc");
        boolean confOk = Boolean.TRUE.equals(res[0].get("ok"));
        boolean serial = !cc.isEmpty() && row(NEW).isEmpty()
                && (confOk ? "C1".equals(cc.get("label")) : ("E_NOTFOUND".equals(err(res[0])) && "IT P".equals(cc.get("label"))));
        check(serial, "race: configure vs rename on one provider ends in one of the two serial outcomes (configure-then-rename → C1; rename-then-configure → E_NOTFOUND, label kept)");
        Json.parseObject(Files.readString(Path.of(SIDECAR, "llm.json")));
        Json.parseObject(Files.readString(Path.of(SIDECAR, "tasks.json")));
        check(true, "race: llm.json and tasks.json re-parse (no torn write)");

        // setkey racing a rename into the same newId: a key is never lost
        final Map<String, Object>[] r2 = new Map[2];
        Thread s1 = new Thread(() -> { try { r2[0] = post("llm.setkey", Map.of("id", "llmcfgit-q3", "key", "race-key")); } catch (Exception e) { r2[0] = Map.of("error", e.toString()); } });
        Thread s2 = new Thread(() -> { try { r2[1] = post("llm.rename", Map.of("id", "llmcfgit-q2", "newId", "llmcfgit-q3")); } catch (Exception e) { r2[1] = Map.of("error", e.toString()); } });
        s1.start(); s2.start(); s1.join(); s2.join();
        boolean renamed = Boolean.TRUE.equals(r2[1].get("ok"));
        Map<String, Object> q3 = row("llmcfgit-q3"), q2 = row("llmcfgit-q2");
        boolean neverLost = renamed ? (!q3.isEmpty() && Boolean.TRUE.equals(q3.get("configured")) && q2.isEmpty())
                : ("E_SYNTAX".equals(err(r2[1])) && !q2.isEmpty() && Boolean.TRUE.equals(q2.get("configured")));
        check(neverLost, "race: setkey vs rename into the same newId — either the rename won and q3 is keyed, or it was refused (COLLISION) and q2 kept its key");
        if (!renamed) data(post("llm.setkey", Map.of("id", "llmcfgit-q3", "key", "")));   // the key-only leftover
    }

    // ---------------------------------------------------------------- cleanup (the home is throwaway; best-effort)
    private static void cleanup() {
        try { post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "")); } catch (Exception ignored) {}
        try { post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "")); } catch (Exception ignored) {}
        for (String id : List.of(P, Q, NEW, "llmcfgit-cc", "llmcfgit-q2", "llmcfgit-q3", "llmcfgit-r", "llmcfgit-env", "llmcfgit-env2",
                "llmcfgit-tmp", "llmcfgit-vend", "llmcfgit-a-b", "llmcfgit-c-d")) {
            try { post("llm.remove", Map.of("id", id)); } catch (Exception ignored) {}
            try { post("llm.setkey", Map.of("id", id, "key", "")); } catch (Exception ignored) {}
        }
        try { post("llm.configure", Map.of("id", "deepseek", "label", "DeepSeek")); } catch (Exception ignored) {}
        try { post("llm.configure", Map.of("id", "anthropic", "auth", "cli")); } catch (Exception ignored) {}   // auth alone (A1): a failure mid-anthropicChecks must not leave api mode behind (review A12)
        try { post("llm.setkey", Map.of("id", "anthropic", "key", "")); } catch (Exception ignored) {}
        try { post("llm.configure", Map.of("id", "anthropic", "models", "claude-code, fable, opus, sonnet, haiku")); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- helpers
    private static Map<String, Object> row(String id) throws Exception {
        for (Object o : dataList(post("llm.providers", Map.of())))
            if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) {
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
                return mm;
            }
        return new LinkedHashMap<>();
    }

    private static List<String> ids() throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : dataList(post("llm.providers", Map.of()))) out.add(String.valueOf(((Map<?, ?>) o).get("id")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> depth(String d) throws Exception {
        for (Object t : dataList(post("task.list", Map.of())))
            for (Object row : (List<Object>) ((Map<?, ?>) t).get("depths"))
                if (d.equals(((Map<?, ?>) row).get("depth"))) return (Map<String, Object>) row;
        throw new IllegalStateException("no depth " + d);
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static String err(Map<String, Object> resp) {
        Object e = resp.get("error");
        return e instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : String.valueOf(e);
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", "human");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error"));
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dataList(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error"));
        return (List<Object>) resp.get("data");
    }
}
