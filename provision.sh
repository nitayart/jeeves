#!/usr/bin/env bash
# provision.sh — the workbench's prerequisite check, per OS, with the fix already resolved.
#
#   ./provision.sh                  check; print a table; exit 1 when a required item is missing
#   ./provision.sh --json           the same rows as JSON (for an assistant that executes the fixes)
#   ./provision.sh --apply          run the fixes that need no human (npm ci, web build, frida venv +
#                                   server binary), re-check
#
# The check installs nothing and never needs sudo. Rows marked "human" need a person: a JDK,
# gradle, node, adb, the JEB license, `claude auth login`, API keys, device root. The workbench
# server never provisions itself — the operator, or their assistant following PROVISIONING.md,
# runs this. A running server's `system.doctor` op answers the subset it can see (minus the
# build-time rows gradle/node/web-deps/server, with one combined `frida` row), same row shape.
#
# Row shape (JSON): {id, status: ok|warn|missing, required, found, want, fix, auto}
#   required + missing  → exit 1        warn → optional, or a human step still open
#   auto: true          → --apply can run `fix` unattended (no sudo, no secrets)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

MODE=check; JSON=0
while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE=check ;;
    --apply) MODE=apply ;;
    --json) JSON=1 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

OS="$(uname -s)"; ARCH="$(uname -m)"
DISTRO=""
if [ "$OS" = Linux ] && [ -r /etc/os-release ]; then DISTRO="$(. /etc/os-release; printf '%s' "${ID:-linux}")"; fi
case "$OS" in
  Darwin) PKG="brew install"; PKG_CASK="brew install --cask" ;;
  Linux)  case "$DISTRO" in
            debian|ubuntu|linuxmint|pop) PKG="sudo apt install -y" ;;
            fedora|rhel|centos|rocky|alma) PKG="sudo dnf install -y" ;;
            arch|manjaro) PKG="sudo pacman -S --needed" ;;
            *) PKG="<your package manager> install" ;;
          esac; PKG_CASK="$PKG" ;;
  *) PKG="<package manager> install"; PKG_CASK="$PKG" ;;
esac
HOME_DIR="${HOME:-/root}"

# ---------------------------------------------------------------- rows
ROW_ID=(); ROW_ST=(); ROW_REQ=(); ROW_FOUND=(); ROW_WANT=(); ROW_FIX=(); ROW_AUTO=()
row() {  # id status required found want fix auto
  ROW_ID+=("$1"); ROW_ST+=("$2"); ROW_REQ+=("$3"); ROW_FOUND+=("$4"); ROW_WANT+=("$5"); ROW_FIX+=("$6"); ROW_AUTO+=("$7")
}
status_of() { local i; for i in "${!ROW_ID[@]}"; do [ "${ROW_ID[$i]}" = "$1" ] && { printf '%s' "${ROW_ST[$i]}"; return; }; done; printf 'unknown'; }
reset_rows() { ROW_ID=(); ROW_ST=(); ROW_REQ=(); ROW_FOUND=(); ROW_WANT=(); ROW_FIX=(); ROW_AUTO=(); }

# run a command with a wall-clock cap; output passes through. coreutils timeout when present
# (Linux, or brew coreutils' gtimeout), else a watchdog whose stdio is detached — a watchdog
# holding the caller's stdout makes every "$(run_to …)" capture wait the full cap (review H2).
run_to() {
  local t="$1"; shift
  if command -v timeout >/dev/null 2>&1; then timeout "$t" "$@"; return $?; fi
  if command -v gtimeout >/dev/null 2>&1; then gtimeout "$t" "$@"; return $?; fi
  "$@" & local p=$!
  ( sleep "$t"; kill "$p" 2>/dev/null ) >/dev/null 2>&1 & local w=$!
  wait "$p" 2>/dev/null; local rc=$?
  pkill -P "$w" 2>/dev/null; kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  return $rc
}
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
have() { command -v "$1" >/dev/null 2>&1; }
vermajor() { printf '%s' "$1" | sed -E 's/^[^0-9]*([0-9]+).*/\1/'; }
verminor() { printf '%s' "$1" | sed -E 's/^[^0-9]*[0-9]+\.([0-9]+).*/\1/'; }

# ---------------------------------------------------------------- checks
check_jdk() {
  local c found=""
  for c in "${JAVA_HOME:-}" \
           "$( [ "$OS" = Darwin ] && /usr/libexec/java_home -v 21 2>/dev/null || true )" \
           /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
           "$HOME_DIR"/.gradle/jdks/*21* \
           /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-21* /usr/lib/jvm/zulu21*; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    if "$c/bin/java" -version 2>&1 | grep -q 'version "21[."]'; then found="$c"; break; fi   # not head -1: JDK_JAVA_OPTIONS prints a NOTE line first
  done
  local fix
  case "$OS" in
    Darwin) fix="brew install --cask temurin@21" ;;
    Linux) case "$DISTRO" in
             debian|ubuntu|linuxmint|pop) fix="$PKG openjdk-21-jdk   # or Adoptium: temurin-21-jdk from packages.adoptium.net" ;;
             fedora|rhel|centos|rocky|alma) fix="$PKG java-21-openjdk-devel" ;;
             arch|manjaro) fix="$PKG jdk21-openjdk" ;;
             *) fix="install a JDK 21 and export JAVA_HOME" ;;
           esac ;;
    *) fix="install a JDK 21 and export JAVA_HOME" ;;
  esac
  if [ -n "$found" ]; then
    local v; v="$("$found/bin/java" -version 2>&1 | grep -m1 'version "' | sed -E 's/.*"([^"]+)".*/\1/')"
    row jdk21 ok yes "$v at $found" "JDK 21" "" no
  else
    row jdk21 missing yes "no JDK 21 (JAVA_HOME=${JAVA_HOME:-unset})" "JDK 21" "$fix" no
  fi
}

check_gradle() {
  local fix
  case "$OS" in
    Darwin) fix="brew install gradle" ;;
    *) fix="curl -s https://get.sdkman.io | bash && sdk install gradle   # distro gradle packages are too old" ;;
  esac
  local gbin=""
  if have gradle; then
    gbin="gradle"
  elif [ -x "./gradlew" ]; then
    gbin="./gradlew"
  elif compgen -G "$HOME_DIR/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle" >/dev/null; then
    gbin="$(ls -d "$HOME_DIR"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle | sort -V | tail -n 1)"
  fi
  if [ -n "$gbin" ]; then
    local v; v="$(run_to 40 "$gbin" --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')"
    if [ -n "$v" ] && [ "$(vermajor "$v")" -ge 8 ]; then row gradle ok yes "gradle $v ($gbin)" "gradle 8+" "" no
    else row gradle missing yes "gradle ${v:-?} (need 8+)" "gradle 8+" "$fix" no; fi
  else
    row gradle missing yes "not on PATH" "gradle 8+" "$fix" no
  fi
}

check_node() {
  local fix
  case "$OS" in
    Darwin) fix="brew install node" ;;
    *) fix="install node 18+ (nodesource or nvm: https://github.com/nvm-sh/nvm) — distro nodejs is often too old" ;;
  esac
  if have node && have npm; then
    local v; v="$(node --version 2>/dev/null)"
    if [ "$(vermajor "$v")" -ge 18 ]; then row node ok yes "node $v · npm $(npm --version 2>/dev/null)" "node 18+" "" no
    else row node missing yes "node $v (need 18+)" "node 18+" "$fix" no; fi
  else
    row node missing yes "node/npm not on PATH" "node 18+" "$fix" no
  fi
}

check_web() {
  local nodeok; nodeok="$(status_of node)"
  if [ -d web/node_modules ]; then row web-deps ok yes "web/node_modules present" "npm ci done" "" yes
  else row web-deps missing yes "web/node_modules absent" "npm ci done" "cd web && npm ci --no-audit --no-fund" "$( [ "$nodeok" = ok ] && echo yes || echo no )"; fi
  if [ -f web/js/app.js ]; then
    local stale=""
    if [ -n "$(find web/src -newer web/js/app.js -name '*.ts' 2>/dev/null | head -1)" ]; then stale=" (older than web/src — rebuild)"; fi
    if [ -n "$stale" ]; then row web-build warn yes "web/js/app.js$stale" "web/js current" "cd web && npm run build" "$( [ "$nodeok" = ok ] && echo yes || echo no )"
    else row web-build ok yes "web/js/app.js present" "web/js current" "" yes; fi
  else
    row web-build missing yes "web/js not built" "web/js current" "cd web && npm run build" "$( [ "$nodeok" = ok ] && echo yes || echo no )"
  fi
}

check_python() {
  local fix
  case "$OS" in
    Darwin) fix="brew install python" ;;
    Linux) case "$DISTRO" in
             debian|ubuntu|linuxmint|pop) fix="$PKG python3 python3-venv" ;;
             *) fix="$PKG python3   # with the venv module" ;;
           esac ;;
    *) fix="install python 3.10+" ;;
  esac
  if have python3; then
    local v; v="$(python3 -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])' 2>/dev/null)"
    local venv; venv="$(python3 -c 'import venv, ensurepip; print("ok")' 2>/dev/null)"
    if [ "$(vermajor "$v")" -ge 3 ] && [ "$(verminor "$v")" -ge 10 ] && [ "$venv" = ok ]; then
      row python3 ok yes "python $v · venv ok" "python 3.10+ with venv" "" no
    elif [ "$venv" != ok ]; then
      row python3 missing yes "python $v without the venv module" "python 3.10+ with venv" "$fix" no
    else
      row python3 missing yes "python $v (need 3.10+)" "python 3.10+ with venv" "$fix" no
    fi
  else
    row python3 missing yes "python3 not on PATH" "python 3.10+ with venv" "$fix" no
  fi
}

check_engine() {
  local home="${JEB_HOME:-backend/engine}"
  local jadx=""
  local c
  for c in "${JADX_HOME:-}" "backend/jadx/lib" "backend/jadx"; do
    [ -n "$c" ] || continue
    if [ -d "$c/lib" ] && { compgen -G "$c/lib/jadx-*-all.jar" >/dev/null || compgen -G "$c/lib/jadx-core*.jar" >/dev/null; }; then jadx="$c/lib"; break; fi
    if [ -d "$c" ] && { compgen -G "$c/jadx-*-all.jar" >/dev/null || compgen -G "$c/jadx-core*.jar" >/dev/null; }; then jadx="$c"; break; fi
  done
  if [ -f "$home/bin/app/jeb.jar" ]; then
    local n; n="$(ls "$home"/bin/app/*.jar 2>/dev/null | wc -l | tr -d ' ')"
    row jeb-engine ok yes "JEB install at $home ($n jars)" "an activated JEB install (JEB_HOME)" "" no
  elif [ -n "$jadx" ]; then
    row jeb-engine warn no "no JEB install (JADX available at $jadx)" "an activated JEB install (JEB_HOME) or JADX" "optional when JADX is present: export JEB_HOME=/path/to/your/activated/jeb" no
  else
    row jeb-engine missing yes "no JEB install (JEB_HOME unset, backend/engine absent; the server would run mock-only)" "an activated JEB install (JEB_HOME) or JADX" "export JEB_HOME=/path/to/your/activated/jeb   # or set JADX_HOME" no
  fi
  if [ -n "$jadx" ]; then
    local jn; jn="$(ls "$jadx"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
    row jadx-engine ok no "JADX jars at $jadx ($jn jars)" "JADX release (JADX_HOME or backend/jadx)" "" no
  else
    row jadx-engine warn no "no JADX jars found" "JADX release (JADX_HOME or backend/jadx)" "export JADX_HOME=/path/to/jadx-1.5.6   # unpacked upstream JADX release" no
  fi
}

FRIDA_VER="$(sed -n 's/^VER="\(.*\)"/\1/p' backend/runs/pull-frida.sh 2>/dev/null | head -1)"
check_frida() {
  local bin="backend/runs/server/frida-server-${FRIDA_VER}-android-arm64"
  local pyok; pyok="$(status_of python3)"
  if [ -f "$bin" ]; then row frida-server ok yes "$(basename "$bin")" "frida-server $FRIDA_VER (android-arm64)" "" yes
  else
    local need_xz=""; have xz || need_xz=" — needs xz: $( [ "$OS" = Darwin ] && echo 'brew install xz' || echo "$PKG xz-utils" )"
    row frida-server missing yes "not vendored$need_xz" "frida-server $FRIDA_VER (android-arm64)" "backend/runs/pull-frida.sh" "$( [ "$pyok" = ok ] && [ -z "$need_xz" ] && echo yes || echo no )"
  fi
  if [ -x backend/runs/venv/bin/python ]; then
    local v; v="$(backend/runs/venv/bin/python -c 'import frida; print(frida.__version__)' 2>/dev/null)"
    if [ "$v" = "$FRIDA_VER" ]; then row frida-venv ok yes "venv frida $v" "frida==$FRIDA_VER in backend/runs/venv" "" yes
    else row frida-venv missing yes "venv frida ${v:-broken} ≠ $FRIDA_VER" "frida==$FRIDA_VER in backend/runs/venv" "rm -rf backend/runs/venv && backend/runs/pull-frida.sh" "$( [ "$pyok" = ok ] && echo yes || echo no )"; fi
  else
    row frida-venv missing yes "backend/runs/venv absent" "frida==$FRIDA_VER in backend/runs/venv" "backend/runs/pull-frida.sh" "$( [ "$pyok" = ok ] && echo yes || echo no )"
  fi
}

ADB=""
check_adb() {
  local c
  for c in "${WORKBENCH_ADB:-}" "$HOME_DIR/Library/Android/sdk/platform-tools/adb" "$HOME_DIR/Android/Sdk/platform-tools/adb" \
           "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "$(command -v adb 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { ADB="$c"; break; }
  done
  local fix
  case "$OS" in
    Darwin) fix="brew install --cask android-platform-tools" ;;
    Linux) case "$DISTRO" in
             debian|ubuntu|linuxmint|pop) fix="$PKG adb android-sdk-platform-tools-common && sudo usermod -aG plugdev \$USER   # udev rules + USB permission; re-login" ;;
             fedora|rhel|centos|rocky|alma) fix="$PKG android-tools   # plus a udev rule for the device's vendor id" ;;
             arch|manjaro) fix="$PKG android-tools android-udev" ;;
             *) fix="install android platform-tools and a udev rule for USB devices" ;;
           esac ;;
    *) fix="install android platform-tools" ;;
  esac
  if [ -n "$ADB" ]; then
    local v; v="$(run_to 15 "$ADB" version 2>/dev/null | head -1)"
    row adb ok yes "${v:-adb} at $ADB" "adb on PATH or in the SDK" "" no
  else
    row adb missing yes "no adb (WORKBENCH_ADB=${WORKBENCH_ADB:-unset})" "adb on PATH or in the SDK" "$fix" no
  fi
}

check_devices() {
  [ -n "$ADB" ] || { row devices warn no "skipped — no adb" "a rooted device or emulator" "" no; return; }
  local lines; lines="$(run_to 20 "$ADB" devices -l 2>/dev/null | sed '1d' | sed '/^$/d')"
  if [ -z "$lines" ]; then
    row devices warn no "none attached" "a rooted device or emulator" "plug a device (USB debugging on) or start an emulator; then: adb devices" no; return
  fi
  local summary="" bad=0 good=0 probed=0 total; total="$(printf '%s\n' "$lines" | grep -c .)"
  while IFS= read -r line <&3; do
    probed=$((probed+1))
    if [ "$probed" -gt 4 ]; then summary="$summary+$((total-4)) more not probed (4 per run); "; break; fi   # same fan-out cap as system.doctor   # fd 3: the adb shell probes below would otherwise eat the loop's stdin (review r2 M4)
    local serial state; serial="$(printf '%s' "$line" | awk '{print $1}')"; state="$(printf '%s' "$line" | awk '{print $2}')"
    case "$line" in
      *"no permissions"*) summary="$summary$serial: no permissions ($( [ "$OS" = Linux ] && printf 'udev rule + plugdev group, re-plug' || printf 'another adb server or a stale daemon: adb kill-server' )); "; bad=$((bad+1)); continue ;;
    esac
    case "$state" in
      device) ;;
      unauthorized) summary="$summary$serial: unauthorized (accept the USB-debugging prompt on the device); "; bad=$((bad+1)); continue ;;
      *) summary="$summary$serial: $state — reconnect it (adb kill-server && adb devices); "; bad=$((bad+1)); continue ;;
    esac
    local uid; uid="$(run_to 8 "$ADB" -s "$serial" shell id -u 2>/dev/null | tr -d '\r' | tr -d ' ')"
    local root="none"
    if [ "$uid" = 0 ]; then root="adbd-root"
    else
      local suid; suid="$(run_to 8 "$ADB" -s "$serial" shell su root id -u 2>/dev/null | tr -d '\r' | tr -d ' ')"
      [ "$suid" = 0 ] && root="su"
    fi
    local se="?"
    if [ "$root" = adbd-root ]; then se="$(run_to 8 "$ADB" -s "$serial" shell getenforce 2>/dev/null | tr -d '\r')"
    elif [ "$root" = su ]; then se="$(run_to 8 "$ADB" -s "$serial" shell su root getenforce 2>/dev/null | tr -d '\r')"; fi
    local fs; fs="$(run_to 8 "$ADB" -s "$serial" shell ps -A -o NAME 2>/dev/null | tr -d '\r' | grep -c '^frida-server' || true)"
    local props; props="$(run_to 8 "$ADB" -s "$serial" shell 'getprop ro.product.model; getprop ro.product.cpu.abi' 2>/dev/null | tr -d '\r')"
    local model abi; model="$(printf '%s\n' "$props" | sed -n '1p')"; abi="$(printf '%s\n' "$props" | sed -n '2p')"
    local abinote=""; if [ -n "$abi" ] && [ "$abi" != arm64-v8a ]; then abinote=" ABI=$abi (the vendored frida-server is android-arm64 only)"; fi
    summary="$summary$serial ($model): root=$root selinux=${se:-?} frida-server=$( [ "${fs:-0}" -gt 0 ] && echo running || echo down )$abinote; "
    if [ "$root" = none ] || [ -n "$abinote" ]; then bad=$((bad+1)); else good=$((good+1)); fi
  done 3<<< "$lines"
  if [ "$good" -gt 0 ] && [ "$bad" -eq 0 ]; then row devices ok no "$summary" "root via adbd or su; SELinux permissive is set at attach" "" no
  elif [ "$good" -gt 0 ]; then row devices warn no "$summary" "root via adbd or su" "unrooted/unauthorized devices cannot be attached: adb root (emulator/userdebug) or root the device" no
  else row devices warn no "$summary" "root via adbd or su" "adb root (emulator/userdebug builds) or a rooted device; frida-server is pushed at attach" no; fi
}

CLAUDE_BIN=""; CLAUDE_OK=0
check_claude() {
  local c
  for c in "$HOME_DIR/.local/bin/claude" /usr/local/bin/claude /opt/homebrew/bin/claude "$(command -v claude 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { CLAUDE_BIN="$c"; break; }
  done
  if [ -z "$CLAUDE_BIN" ]; then
    row claude-cli warn no "not installed (only needed for Anthropic's 'Claude Code login' connection — its 'API key' connection needs no CLI)" "claude on PATH, logged in" "curl -fsSL https://claude.ai/install.sh | bash && claude auth login" no; return
  fi
  local v; v="$(run_to 20 "$CLAUDE_BIN" --version 2>/dev/null | head -1)"
  # the login probe runs in the env a spawn gets (AgentManager.applyBillingEnv): the API key and
  # auth token are stripped unless ANTHROPIC_BASE_URL names a gateway; CLAUDE_CODE_OAUTH_TOKEN is a
  # login (`claude setup-token` on a headless box) and stays. bash 3.2 + set -u: guard the empty array.
  local unset_args=()
  [ -n "${ANTHROPIC_BASE_URL:-}" ] || unset_args=(-u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN)
  local st; st="$(run_to 20 env ${unset_args[@]+"${unset_args[@]}"} "$CLAUDE_BIN" auth status --json 2>/dev/null)"
  if printf '%s' "$st" | grep -q '"loggedIn": *true'; then
    local how; how="$(printf '%s' "$st" | sed -n 's/.*"authMethod": *"\([^"]*\)".*/\1/p' | head -1)"
    CLAUDE_OK=1
    row claude-cli ok no "claude ${v:-?} at $CLAUDE_BIN · logged in${how:+ ($how)}" "claude on PATH, logged in" "" no
  else
    row claude-cli warn no "claude ${v:-?} at $CLAUDE_BIN · NOT logged in" "claude on PATH, logged in" "claude auth login" no
  fi
}

SERVER_UP=0
api() {  # op [json-body] → data json (empty on failure)
  have curl || return 1
  curl -sf -m 20 -X POST "http://127.0.0.1:7800/api/$1" -H 'Content-Type: application/json' -d "${2:-{\}}" 2>/dev/null
}
check_server_and_llm() {
  if port_open 7800; then SERVER_UP=1; row server ok no "listening on 127.0.0.1:7800" "backend up (backend/workbench.sh)" "" no
  else row server warn no "not running" "backend up (backend/workbench.sh)" "backend/workbench.sh   # then re-run this check for the provider rows" no; fi
  if [ "$SERVER_UP" = 1 ] && have python3 && have curl; then
    local prov; prov="$(api llm.providers)"
    # credentialed = a key-bearing provider with its key, or the claude CLI when logged in;
    # local/no-key providers (ollama, the QA fixture) are listed but never satisfy the row
    local summ; summ="$(printf '%s' "$prov" | CLAUDE_OK="$CLAUDE_OK" python3 -c '
import json,sys,os
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
rows=d.get("data") or []
cli_ok=os.environ.get("CLAUDE_OK")=="1"
on=[r["id"] for r in rows if r.get("configured") and (r.get("needsKey") or (r.get("driver")=="cli" and cli_ok))]
off=[r["id"] for r in rows if (r.get("needsKey") and not r.get("configured")) or (r.get("driver")=="cli" and not cli_ok)]
loc=[r["id"] for r in rows if r.get("driver") not in ("builtin","cli") and not r.get("needsKey")]
print("%d|%s|%s|%s" % (len(on), ",".join(on), ",".join(off), ",".join(loc)))' 2>/dev/null)"
    local n="${summ%%|*}"; local rest="${summ#*|}"; local on="${rest%%|*}"; rest="${rest#*|}"; local off="${rest%%|*}"; local loc="${rest#*|}"
    if [ -z "$prov" ]; then row llm-providers warn no "server answered an error for llm.providers" "at least one provider with a key or a logged-in claude CLI" "check /tmp/wb_main.log; re-run" no
    elif [ -n "$summ" ] && [ "${n:-0}" -gt 0 ]; then row llm-providers ok no "$n with credentials: $on${off:+ · no key: $off}${loc:+ · local, no key needed: $loc}" "at least one provider with a key or a logged-in claude CLI" "" no
    else row llm-providers warn no "none with credentials${off:+ (no key: $off)}${loc:+ · local only: $loc}" "at least one provider with a key or a logged-in claude CLI" "PROVIDERS panel in the workbench (or MCP wb_llm_setkey / wb_llm_configure with create:true to add one) — keys are write-only" no; fi
    local task; task="$(api task.list)"
    # a depth is satisfied only by a credentialed provider: the CLI driver counts when logged in
    local ts; ts="$(printf '%s' "$task" | CLAUDE_OK="$CLAUDE_OK" python3 -c '
import json,sys,os
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
data=d.get("data") or []
cli_ok=os.environ.get("CLAUDE_OK")=="1"
# task.list: [{task, depths:[{depth, resolved:{unconfigured|unresolved|provider,model,driver,configured}}]}]
out=[]; bad=0
for t in data if isinstance(data,list) else []:
    for dv in (t.get("depths") or []):
        r=dv.get("resolved") or {}
        if r.get("unconfigured") or not r: s="unset"; bad+=1
        elif r.get("unresolved"): s="unresolved (%s)" % r.get("model"); bad+=1
        else:
            s="%s:%s" % (r.get("provider"), r.get("model"))
            if r.get("driver")=="cli" and not cli_ok: s+=" (claude CLI not logged in)"; bad+=1
            elif not r.get("configured"): s+=" (no key)"; bad+=1
        out.append("%s/%s=%s" % (t.get("task"), dv.get("depth"), s))
print(("OK " if not bad and out else "GAP ") + " ".join(out))' 2>/dev/null)"
    local tsok=0
    case "$ts" in "OK "*) tsok=1; ts="${ts#OK }" ;; "GAP "*) ts="${ts#GAP }" ;; esac
    if [ "$tsok" = 1 ]; then row reversing-agent ok no "$ts" "quick and deep depths resolve to a provider" "" no
    else row reversing-agent warn no "${ts:-unknown}" "quick and deep depths resolve to a provider" "REVERSING AGENT settings in the workbench (or MCP wb_task_configure)" no; fi
  else
    # server down: providers from the sidecars, key ids only (values are never read)
    if have python3 && [ -f backend/.workbench/llm.json ]; then
      local summ; summ="$(python3 - <<'PY' 2>/dev/null
import json,os
try: prov=json.load(open("backend/.workbench/llm.json"))
except Exception: prov={}
try: keys=json.load(open("backend/.workbench/llm-keys.json"))
except Exception: keys={}
ids = list(prov.get("providers", prov).keys()) if isinstance(prov, dict) else []
kid = [k for k,v in (keys.items() if isinstance(keys, dict) else []) if v]
print("providers on disk: %s · keys for: %s" % (",".join(ids) or "none", ",".join(kid) or "none"))
PY
)"
      row llm-providers warn no "${summ:-unreadable} (server down — start it for the resolved view)" "at least one provider with a key or a logged-in claude CLI" "" no
    elif ! have python3; then
      row llm-providers warn no "unknown — server down and no python3 to read backend/.workbench/llm.json" "at least one provider with a key or a logged-in claude CLI" "start the server, then PROVIDERS panel (or MCP wb_llm_setkey)" no
    else
      row llm-providers warn no "none yet — backend/.workbench/llm.json does not exist (server never started here)" "at least one provider with a key or a logged-in claude CLI" "start the server, then PROVIDERS panel (or MCP wb_llm_setkey)" no
    fi
    row reversing-agent warn no "unknown — server down" "quick and deep depths resolve to a provider" "start the server; REVERSING AGENT settings (or MCP wb_task_configure)" no
  fi
}

run_checks() {
  reset_rows
  row os ok no "$OS $ARCH${DISTRO:+ ($DISTRO)}" "macOS or Linux" "" no
  check_jdk; check_gradle; check_node; check_web; check_python; check_engine; check_frida; check_adb; check_devices; check_claude; check_server_and_llm
}

# ---------------------------------------------------------------- output
jesc() { local s="$1"; s="${s//\\/\\\\}"; s="${s//\"/\\\"}"; s="${s//$'\n'/\\n}"; s="${s//$'\r'/\\r}"; s="${s//$'\t'/ }"; s="$(printf '%s' "$s" | tr -d '\000-\010\013\014\016-\037')"; printf '%s' "$s"; }
print_json() {
  local i first=1
  printf '{"os":"%s","arch":"%s","distro":"%s","fridaVersion":"%s","rows":[' "$(jesc "$OS")" "$(jesc "$ARCH")" "$(jesc "$DISTRO")" "$(jesc "$FRIDA_VER")"
  for i in "${!ROW_ID[@]}"; do
    [ $first = 1 ] || printf ','; first=0
    printf '{"id":"%s","status":"%s","required":%s,"found":"%s","want":"%s","fix":"%s","auto":%s}' \
      "$(jesc "${ROW_ID[$i]}")" "${ROW_ST[$i]}" "$( [ "${ROW_REQ[$i]}" = yes ] && echo true || echo false )" \
      "$(jesc "${ROW_FOUND[$i]}")" "$(jesc "${ROW_WANT[$i]}")" "$(jesc "${ROW_FIX[$i]}")" "$( [ "${ROW_AUTO[$i]}" = yes ] && echo true || echo false )"
  done
  printf '],"ok":%s}\n' "$( exit_code >/dev/null; [ $? -eq 0 ] && echo true || echo false )"
}
print_table() {
  local i g
  printf '%s\n' "workbench prerequisites — $OS $ARCH${DISTRO:+ ($DISTRO)} — $(date '+%Y-%m-%d %H:%M')"
  for i in "${!ROW_ID[@]}"; do
    case "${ROW_ST[$i]}" in ok) g='✓';; warn) g='!';; missing) g='✗';; *) g='?';; esac
    printf ' %s %-16s %s\n' "$g" "${ROW_ID[$i]}" "${ROW_FOUND[$i]}"
    if [ -n "${ROW_FIX[$i]}" ] && [ "${ROW_ST[$i]}" != ok ]; then
      printf '   %-16s fix%s: %s\n' "" "$( [ "${ROW_AUTO[$i]}" = yes ] && printf ' (auto)' || printf ' (human)' )" "${ROW_FIX[$i]}"
    fi
  done
}
exit_code() {
  local i rc=0
  for i in "${!ROW_ID[@]}"; do
    [ "${ROW_REQ[$i]}" = yes ] && [ "${ROW_ST[$i]}" = missing ] && rc=1
  done
  return $rc
}

# ---------------------------------------------------------------- apply (the unattended subset only)
apply_fixes() {
  local did=0
  if [ "$(status_of node)" = ok ]; then
    [ "$(status_of web-deps)" = ok ] || { echo "[provision] npm ci"; (cd web && npm ci --no-audit --no-fund); did=1; }
    [ "$(status_of web-build)" = ok ] || { echo "[provision] web build"; (cd web && npm run --silent build); did=1; }
  fi
  if [ "$(status_of python3)" = ok ]; then
    if [ "$(status_of frida-venv)" != ok ]; then
      # the venv needs no xz: build it regardless (the same steps pull-frida.sh takes); a broken
      # one is removed only here, on the path that rebuilds it (review r2 L4, r4 L2)
      echo "[provision] frida venv (frida==$FRIDA_VER)"; rm -rf backend/runs/venv
      python3 -m venv backend/runs/venv && backend/runs/venv/bin/pip install -q "frida==$FRIDA_VER"; did=1
    fi
    if [ "$(status_of frida-server)" != ok ]; then
      if ! have xz; then
        echo "[provision] frida-server download needs xz — human step: $( [ "$OS" = Darwin ] && echo 'brew install xz' || echo "$PKG xz-utils" )"
      else
        echo "[provision] backend/runs/pull-frida.sh"; backend/runs/pull-frida.sh; did=1
      fi
    fi
  fi
  [ $did = 1 ] || echo "[provision] nothing to apply unattended"
}

run_checks
if [ "$MODE" = apply ]; then
  if [ "$JSON" = 1 ]; then apply_fixes >&2; else apply_fixes; fi   # keep stdout parseable under --json
  run_checks
fi
if [ "$JSON" = 1 ]; then print_json; else print_table; fi
exit_code
