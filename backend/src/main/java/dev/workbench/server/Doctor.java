package dev.workbench.server;

import dev.workbench.engine.Engines;
import dev.workbench.llm.LlmRegistry;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * system.doctor (MCP_SPEC §4.16) — the prerequisite rows a running server can answer,
 * in the same shape as {@code ./provision.sh --json}:
 * {@code {id, status: ok|warn|missing, required, found, want, fix, auto}}.
 *
 * Read-only. Nothing is installed from here: the fixes are per-OS command lines for
 * the operator or their assistant (PROVISIONING.md). The only side effect is starting
 * the frida sidecar for its ping, which runs.devices does on every Runs view open anyway.
 * Keys are never read — providers report {@code configured} only (LlmRegistry.list).
 */
public final class Doctor {

    private final Workspace ws;
    private final boolean mac;
    /** set by claude(): the CLI provider counts as credentialed only when logged in */
    private boolean claudeLoggedIn;

    public Doctor(Workspace ws) {
        this.ws = ws;
        this.mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    public Map<String, Object> run() throws OpException { return run(null); }

    static final java.util.Set<String> ROW_IDS = java.util.Set.of("os", "jdk21", "jeb-engine", "jadx-engine", "web-build", "python3", "frida",
            "adb", "devices", "claude-cli", "llm-providers", "reversing-agent");

    /** rowsCsv: only these row ids (null = all) — the Runs preflight asks for adb,devices,frida,
        jeb-engine and pays for nothing else (r2 M2: the full set spawns the claude CLI twice). */
    public Map<String, Object> run(String rowsCsv) throws OpException {
        java.util.Set<String> only = null;
        if (rowsCsv != null && !rowsCsv.isBlank()) {
            only = new java.util.HashSet<>();
            for (String id : rowsCsv.split(",")) {
                if (id.isBlank()) continue;
                if (!ROW_IDS.contains(id.trim()))   // a typo must not paint an empty "all clear" (r3 L1)
                    throw OpException.syntax("unknown doctor row: " + id.trim(), "known: " + String.join(", ", ROW_IDS));
                only.add(id.trim());
            }
        }
        final java.util.Set<String> want = only;
        java.util.function.Predicate<String> on = id -> want == null || want.contains(id);
        List<Object> rows = new ArrayList<>();
        if (on.test("os")) rows.add(row("os", "ok", false, System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "macOS or Linux", "", false));
        if (on.test("jdk21")) rows.add(jdk());
        if (on.test("jeb-engine")) rows.add(engine());
        if (on.test("jadx-engine")) rows.add(jadxEngine());
        if (on.test("web-build")) rows.add(web());
        if (on.test("python3")) rows.add(python());
        if (on.test("frida")) rows.add(frida());
        if (on.test("adb") || on.test("devices")) {
            Map<String, Object> adb = adb();
            if (on.test("adb")) rows.add(adb);
            if (on.test("devices"))
                rows.add("ok".equals(adb.get("status")) ? devices()
                        : row("devices", "warn", false, "skipped — no adb", "a device rooted via adbd or su", "", false));
        }
        if (on.test("claude-cli") || on.test("llm-providers") || on.test("reversing-agent")) {
            Map<String, Object> c = claude();   // sets claudeLoggedIn for the two rows below
            if (on.test("claude-cli")) rows.add(c);
        }
        if (on.test("llm-providers")) rows.add(providers());
        if (on.test("reversing-agent")) rows.add(reversing());

        boolean ok = true;
        for (Object o : rows) {
            Map<?, ?> r = (Map<?, ?>) o;
            if (Boolean.TRUE.equals(r.get("required")) && "missing".equals(r.get("status"))) ok = false;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("os", System.getProperty("os.name"));
        out.put("ok", ok);
        out.put("rows", rows);
        return out;
    }

    // ------------------------------------------------------------ rows

    private Map<String, Object> jdk() {
        int feature = Runtime.version().feature();
        String found = "java " + System.getProperty("java.version") + " at " + System.getProperty("java.home");
        if (feature == 21) return row("jdk21", "ok", true, found, "JDK 21", "", false);
        return row("jdk21", "missing", true, found + " (the server is running on a " + feature + ")", "JDK 21",
                mac ? "brew install --cask temurin@21" : "sudo apt install openjdk-21-jdk   # then restart via backend/workbench.sh", false);
    }

    private Map<String, Object> engine() {
        String home = System.getProperty("workbench.jebHome");
        boolean hasJadx = Engines.jadxAvailable();
        if (!Engines.jebAvailable()) {
            if (hasJadx) {
                String jv = Engines.jadxVersion();
                return row("jeb-engine", "warn", false, "JEB not on classpath (JADX " + (jv == null ? "embedded" : jv) + " is active)" + (home == null ? "" : " · jebHome=" + home),
                        "an analysis engine (JEB or JADX)",
                        "Optional when JADX is present: set JEB_HOME to an activated JEB install for JEB targets",
                        false);
            }
            return row("jeb-engine", "missing", true, "not on the classpath (mock-only server)" + (home == null ? "" : " · jebHome=" + home),
                    "an explicitly selected, already licensed JEB installation (or JADX)",
                    "Set JEB_HOME to your personal installation before starting an isolated local workbench; no license activation is performed",
                    false);
        }
        String version = Engines.jebVersion();
        return row("jeb-engine", "ok", true, "external engine" + (home == null ? "" : " at " + home)
                + " · " + (version == null ? "not booted yet (opens with the first target)" : "JEB " + version),
                "an explicitly selected, already licensed JEB installation", "", false);
    }

    private Map<String, Object> jadxEngine() {
        if (!Engines.jadxAvailable()) {
            boolean req = !Engines.jebAvailable();
            boolean runtimeOnly = Engines.jadxRuntimeAvailable() && !Engines.jadxAdapterAvailable();
            return row("jadx-engine", req ? "missing" : "warn", req,
                    runtimeOnly ? "JADX jars on classpath, but JadxEngine adapter was not compiled" : "not on the classpath",
                    "JADX release jars on classpath (JADX_HOME or backend/jadx)",
                    runtimeOnly ? "Rebuild backend classes with JADX_HOME set (gradle -p backend classes)"
                            : "Set JADX_HOME to an unpacked JADX release (e.g. jadx-1.5.6) or place its lib/ in backend/jadx/lib",
                    false);
        }
        String version = Engines.jadxVersion();
        return row("jadx-engine", "ok", false, "JADX " + (version == null ? "embedded" : version),
                "JADX release jars on classpath", "", false);
    }

    private Map<String, Object> web() {
        File js = locate("web/js/app.js");
        if (js == null) return row("web-build", "missing", true, "web/js not built", "web/js current", "cd web && npm ci && npm run build", true);
        String path = js.getAbsoluteFile().toPath().normalize().toString();
        boolean stale = false;   // the script warns on a .ts newer than the bundle; same here (review r4 L3)
        File src = locate("web/src");
        if (src != null && src.isDirectory()) {
            long built = js.lastModified();
            try (var walk = java.nio.file.Files.walk(src.toPath())) {
                stale = walk.anyMatch(p -> p.toString().endsWith(".ts") && p.toFile().lastModified() > built);
            } catch (java.io.IOException e) {
                stale = false;
            }
        }
        if (stale) return row("web-build", "warn", true, path + " (older than web/src — rebuild)", "web/js current", "cd web && npm run build", true);
        return row("web-build", "ok", true, path, "web/js current", "", true);
    }

    private Map<String, Object> python() {
        String want = "python 3.10+ with venv (frida sidecar, MCP bridge)";
        String fix = mac ? "brew install python" : "sudo apt install python3 python3-venv";
        String[] r = exec(List.of("python3", "-c", "import sys; print('%d.%d.%d' % sys.version_info[:3])"), 10, false);
        File mcp = locate("mcp/workbench_mcp.py");
        if (r == null || !"0".equals(r[0]))
            return row("python3", "missing", true, "python3 not on the server's PATH", want, fix, false);
        String v = r[1].trim();
        boolean newEnough = v.matches("3\\.(1\\d|[2-9]\\d)(\\..*)?");
        String[] venv = exec(List.of("python3", "-c", "import venv, ensurepip"), 10, false);
        boolean venvOk = venv != null && "0".equals(venv[0]);   // Debian ships it as python3-venv (review r2 L5)
        String found = "python " + v + (venvOk ? " · venv ok" : " · venv module MISSING")
                + (mcp == null ? " · mcp/workbench_mcp.py NOT found" : " · MCP bridge " + mcp.getAbsoluteFile().toPath().normalize());
        if (newEnough && venvOk && mcp != null) return row("python3", "ok", true, found, want, "", false);
        if (!newEnough || !venvOk) return row("python3", "missing", true, found, want, fix, false);
        return row("python3", "warn", true, found, want, "run the server from the repo (mcp/ must be beside backend/)", false);
    }

    private Map<String, Object> frida() {
        Map<String, Object> ping;
        try { ping = ws.runs().ping(); }
        catch (Throwable t) { ping = new LinkedHashMap<>(); ping.put("error", t.getMessage()); }
        String want = "an explicit runner Python and a matching operator-installed device server";
        String fix = "Set WORKBENCH_RUNS_PYTHON to an absolute Python path with backend/runs/requirements-runner.txt installed; select the device and installed server in Runs";
        if (ping.get("error") != null)
            return row("frida", "missing", false, "optional recorder sidecar unavailable: " + ping.get("error"), want, fix, true);
        return row("frida", "ok", false, "client " + ping.get("frida")
                + " · device server version and ownership are checked only after explicit selection", want, "", true);
    }

    private Map<String, Object> adb() {
        String bin = dev.workbench.device.DeviceManager.adbBinary();
        String fix = mac ? "brew install --cask android-platform-tools"
                : "sudo apt install adb android-sdk-platform-tools-common && sudo usermod -aG plugdev $USER   # udev rules; re-login";
        if (bin == null)
            return row("adb", "missing", true, "no adb (WORKBENCH_ADB, ~/Library/Android/sdk, ~/Android/Sdk, $ANDROID_HOME, PATH)",
                    "adb on PATH or in the SDK", fix, false);
        String[] r = exec(List.of(bin, "version"), 15, false);
        if (r == null || !"0".equals(r[0]))   // found but does not run: wrong arch, broken install, hung (r5 L1)
            return row("adb", "missing", true, bin + " does not run" + (r == null ? " (timeout or spawn failure)" : ": " + r[1].trim()),
                    "adb on PATH or in the SDK", fix, false);
        String v = r[1].isBlank() ? "adb" : r[1].lines().findFirst().orElse("adb").trim();
        return row("adb", "ok", true, v + " at " + bin, "adb on PATH or in the SDK", "", false);
    }

    private Map<String, Object> devices() {
        String want = "an explicitly selected device with existing root; SELinux Enforcing is supported";
        dev.workbench.device.DeviceManager dm;
        List<Map<String, Object>> devs;
        try {
            dm = ws.devices();   // throws when unwired (a test server): a row, not a 500
            devs = dm.listFast();   // raw states, no per-device shelling (review r2 H1/M2)
        } catch (IllegalStateException e) {
            return row("devices", "warn", false, "device manager not wired: " + e.getMessage(), want, "", false);
        } catch (OpException e) {
            return row("devices", "warn", false, "adb devices failed: " + e.getMessage(), want,
                    "Inspect the existing ADB connection without restarting another controller" + (mac ? "" : "   # Linux: udev rule / plugdev group when 'no permissions'"), false);
        }
        if (devs.isEmpty())
            return row("devices", "warn", false, "none attached", want,
                    "Connect an already prepared device, or import an external run without a device session", false);
        StringBuilder sb = new StringBuilder();
        List<String> fixes = new ArrayList<>();
        List<Object> detail = new ArrayList<>();
        int good = 0, probed = 0, skipped = 0;
        /* one budget for the row, not per probe: a device costs up to four 8 s probes, so the
           deadline is checked before each device and at most one device runs past it (r4 M2) */
        final long deadline = System.nanoTime() + 20_000_000_000L;
        for (Map<String, Object> m : devs) {
            if (System.nanoTime() > deadline) { skipped++; continue; }
            probed++;
            String serial = String.valueOf(m.get("serial"));
            String state = String.valueOf(m.get("state"));
            Map<String, Object> d = new LinkedHashMap<>(m);
            if (sb.length() > 0) sb.append("; ");
            switch (state) {
                case "unauthorized" -> {
                    sb.append(serial).append(": unauthorized");
                    fixes.add("accept the USB-debugging prompt on " + serial);
                }
                case "no permissions" -> {
                    sb.append(serial).append(": no permissions");
                    fixes.add(mac ? "Check USB authorization and the existing controller for " + serial
                            : "udev rule + plugdev group for " + serial + ", then re-plug");
                }
                case "device" -> {
                    Map<String, Object> cap = dm.rootCapability(serial);   // 8 s probes only
                    d.putAll(cap);
                    String root = String.valueOf(cap.get("root"));
                    String abi = String.valueOf(cap.get("abi"));
                    Object fs = cap.get("fridaServer");
                    boolean up = fs instanceof Map<?, ?> fm && Boolean.TRUE.equals(fm.get("running"));
                    sb.append(serial).append(" (").append(cap.get("model")).append(", Android ").append(cap.get("android"))
                            .append(abi.isEmpty() ? "" : ", " + abi).append("): root=").append(root)
                            .append(cap.get("selinux") == null ? "" : " selinux=" + cap.get("selinux"))
                            .append(" named-server=").append(up ? "observed (ownership unverified)" : "not observed");
                    if ("none".equals(root)) fixes.add(serial + ": no existing root discovered; use an external controller or a prepared device");
                    if ("?".equals(root)) fixes.add(serial + ": root probe failed (" + cap.get("error") + ") — the device shell may be wedged; reconnect and retry");
                    if (!"none".equals(root) && !"?".equals(root)) good++;
                }
                default -> {
                    sb.append(serial).append(": ").append(state);
                    fixes.add(serial + " is " + state + ": inspect its connection with the owning controller; do not restart shared ADB");
                }
            }
            detail.add(d);
        }
        if (skipped > 0) sb.append("; +").append(skipped).append(" more not probed (20 s row budget)");
        Map<String, Object> r = good == probed
                ? row("devices", "ok", false, sb.toString(), want, "", false)
                : row("devices", "warn", false, sb.toString(), want, String.join("; ", fixes), false);
        r.put("devices", detail);   // per-device detail beyond the common row shape (MCP_SPEC §4.16)
        return r;
    }

    private Map<String, Object> claude() {
        String bin = LlmRegistry.claudeBinary();
        String want = "claude CLI on PATH and signed in (only for the Anthropic provider's 'Claude Code login' connection — its 'API key' connection needs no CLI)";
        LlmRegistry.Provider an = ws.agents().llm().get("anthropic");
        if (an != null && "api".equals(an.auth))   // an operator on the API-key connection must not see a permanent amber row for a CLI they don't need (review B15)
            return row("claude-cli", "ok", false, "not needed — Anthropic connects with an API key", want, "", false);
        if (bin == null)
            return row("claude-cli", "warn", false, "not installed", want,
                    "curl -fsSL https://claude.ai/install.sh | bash && claude auth login", false);
        String[] ver = exec(List.of(bin, "--version"), 20, true);
        String v = ver == null || ver[1].isBlank() ? "?" : ver[1].lines().findFirst().orElse("?").trim();
        String[] st = exec(List.of(bin, "auth", "status", "--json"), 20, true);
        boolean loggedIn = st != null && st[1].matches("(?s).*\"loggedIn\"\\s*:\\s*true.*");
        String how = null;
        if (st != null) {
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"authMethod\"\\s*:\\s*\"([^\"]*)\"").matcher(st[1]);
            if (mm.find()) how = mm.group(1);
        }
        String found = "claude " + v + " at " + bin + (loggedIn ? " · logged in" + (how == null ? "" : " (" + how + ")") : " · NOT logged in");
        claudeLoggedIn = loggedIn;
        LlmRegistry.noteSignedIn(loggedIn);   // one cache: the doctor's fresh probe is the card's answer too (review A4)
        if (loggedIn) return row("claude-cli", "ok", false, found, want, "", false);
        return row("claude-cli", "warn", false, found, want, "claude auth login", false);
    }

    private Map<String, Object> providers() {
        String want = "at least one provider with a key, or a logged-in claude CLI";
        /* credentialed = a key-bearing provider with its key, or the claude CLI when logged in.
           LlmRegistry.configured() is true for local/no-key providers (ollama, the QA fixture)
           and for the CLI on mere binary presence — neither satisfies this row (review M1) */
        List<String> on = new ArrayList<>(), off = new ArrayList<>(), local = new ArrayList<>();
        try {
            for (Object o : ws.agents().llm().list()) {
                Map<?, ?> p = (Map<?, ?>) o;
                String id = String.valueOf(p.get("id"));
                boolean needsKey = Boolean.TRUE.equals(p.get("needsKey"));
                boolean configured = Boolean.TRUE.equals(p.get("configured"));
                if ("builtin".equals(p.get("driver"))) continue;
                if ("cli".equals(p.get("driver"))) { (claudeLoggedIn ? on : off).add(id); continue; }
                if (!needsKey) { local.add(id); continue; }
                (configured ? on : off).add(id);
            }
        } catch (Throwable t) {
            return row("llm-providers", "warn", false, "unreadable: " + t.getMessage(), want, "", false);
        }
        String found = on.isEmpty() ? "none with credentials" : on.size() + " with credentials: " + String.join(", ", on);
        if (!off.isEmpty()) found += " · no key: " + String.join(", ", off);
        if (!local.isEmpty()) found += " · local, no key needed: " + String.join(", ", local);
        if (!on.isEmpty()) return row("llm-providers", "ok", false, found, want, "", false);
        return row("llm-providers", "warn", false, found, want,
                "PROVIDERS panel in the workbench (or the MCP tool llm: action setkey, or configure with create:true to add a provider) — keys are write-only", false);
    }

    private Map<String, Object> reversing() {
        String want = "the reverse task's quick and deep depths resolve to a configured provider";
        List<String> parts = new ArrayList<>();
        boolean allOk = true;
        try {
            for (Object t : ws.agents().tasks().list(ws.agents().llm())) {
                Map<?, ?> task = (Map<?, ?>) t;
                Object depths = task.get("depths");
                if (!(depths instanceof List<?> dl)) continue;
                for (Object d : dl) {
                    Map<?, ?> dm = (Map<?, ?>) d;
                    Object res = dm.get("resolved");
                    String s;
                    if (!(res instanceof Map<?, ?> rm) || Boolean.TRUE.equals(rm.get("unconfigured"))) { s = "unset"; allOk = false; }
                    else if (Boolean.TRUE.equals(rm.get("unresolved"))) { s = "unresolved (" + rm.get("model") + ")"; allOk = false; }
                    else {
                        s = rm.get("provider") + ":" + rm.get("model");
                        /* the pinned model was dropped from its provider's list — the pin is
                           kept (never silently re-pointed) and this row goes WARN (llm-config §6.4) */
                        if (Boolean.TRUE.equals(rm.get("unlisted"))) { s += " (model no longer listed under the provider)"; allOk = false; }
                        /* resolved.configured is binary presence for the CLI driver — the
                           login is what a spawn needs (review r2 M1) */
                        if ("cli".equals(rm.get("driver")) && !claudeLoggedIn) { s += " (claude CLI not logged in)"; allOk = false; }
                        else if (!Boolean.TRUE.equals(rm.get("configured"))) { s += " (provider has no key)"; allOk = false; }
                    }
                    parts.add(task.get("task") + "/" + dm.get("depth") + "=" + s);
                }
            }
        } catch (Throwable t) {
            return row("reversing-agent", "warn", false, "unreadable: " + t.getMessage(), want, "", false);
        }
        String found = String.join(" · ", parts);
        if (allOk && !parts.isEmpty()) return row("reversing-agent", "ok", false, found, want, "", false);
        return row("reversing-agent", "warn", false, found.isEmpty() ? "no tasks" : found, want,
                "REVERSING AGENT settings in the workbench (or the MCP tool reversing_agent: action configure, depth quick|deep, model provider:model)", false);
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> row(String id, String status, boolean required, String found,
                                           String want, String fix, boolean auto) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("status", status);
        r.put("required", required);
        r.put("found", found);
        r.put("want", want);
        r.put("fix", fix);
        r.put("auto", auto);
        return r;
    }

    /** Find a repo file whether the server was launched from the repo root or backend/. */
    private static File locate(String rel) {
        String bare = rel.startsWith("backend/") ? rel.substring("backend/".length()) : null;
        for (String base : new String[] { ".", "..", System.getProperty("user.dir") }) {
            File f = new File(base, rel);
            if (f.exists()) return f;
            if (bare != null) {
                File g = new File(base, bare);
                if (g.exists()) return g;
            }
        }
        return null;
    }

    /** {rc, output} of a short command (stderr merged), or null when it could not run / timed out.
     *  Output goes to a temp file so the cap bounds the wait even when a grandchild keeps the
     *  pipe open (review M7). spawnEnv gives the child exactly the credential env a mission
     *  spawn gets (AgentManager.applyBillingEnv), so `claude auth status` answers for the
     *  spawn: a headless box logged in through CLAUDE_CODE_OAUTH_TOKEN stays logged in. */
    private static String[] exec(List<String> cmd, int timeoutSec, boolean spawnEnv) {
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("wb-doctor-", ".out");   // 0600 on POSIX (review r2 L3)
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tmp.toFile()));
            if (spawnEnv) AgentManager.applyBillingEnv(pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            byte[] out = java.nio.file.Files.readAllBytes(tmp);
            if (out.length > 64 * 1024) out = java.util.Arrays.copyOf(out, 64 * 1024);
            return new String[] { String.valueOf(p.exitValue()), new String(out, StandardCharsets.UTF_8) };
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignore) { }
        }
    }
}
