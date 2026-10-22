#!/bin/bash
# Build and run the workbench backend (the developer's launcher).
# JAVA_HOME resolved to a JDK 21 (see find_jdk21); server binds 127.0.0.1:7800 (reserved range 7800-7809), serves web/.
# With a JEB install the server runs with JEB embedded (SecurityManager opt-in, add-opens, the
# install's jars on the classpath); without one it runs mock-only.
# The install is $JEB_HOME when that is set — any licensed JEB, used in place, nothing copied or
# changed — else a copy of an install under backend/engine when that exists.
# WORKBENCH_RUN_ONLY=1 starts what is already built (no npm, no gradle).
# WORKBENCH_HEADLESS=1 runs HTTP/CLI agents without turn limits (including saved task caps).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# The caller's cwd is the fs.list/browse default (Ops.fsList) — the script's own
# cd to backend/ is an implementation detail, not where the user works.
export WORKBENCH_CWD="${WORKBENCH_CWD:-$PWD}"

# The sidecar root is ALWAYS explicit and absolute. The JVM's cwd is backend/ (relative
# classpath below) and the server's cwd heuristic once resolved "backend/.workbench" to
# backend/backend/.workbench — a fresh, empty home — and migrated the WhatsApp database
# into it (2026-09-15). A relative WORKBENCH_HOME resolves against the CALLER's directory,
# before the cd. The JVM's temp dir lives under the home too: nothing of the workbench's
# goes to /tmp (2026-09-15).
if [ -z "${WORKBENCH_HOME:-}" ]; then
  WORKBENCH_HOME="$REPO_ROOT/backend/.workbench"
elif [ "${WORKBENCH_HOME#/}" = "$WORKBENCH_HOME" ]; then
  WORKBENCH_HOME="$PWD/$WORKBENCH_HOME"
fi
export WORKBENCH_HOME
# Optional isolation for JEB native caches (notably ~/.jeb/z3). Different JEB
# versions can overwrite that shared cache while another JVM maps it; macOS may
# kill the process for a changed code signature. OS HOME is NEVER changed.
# This Java-only home also changes other Java home-relative defaults: keep JEB_HOME,
# provider/tool paths and WORKBENCH_HOME explicit. See docs/local-jeb.md.
JVM_HOME_ARGS=()
if [ -n "${WORKBENCH_JVM_USER_HOME:-}" ]; then
  if [ "${WORKBENCH_JVM_USER_HOME#/}" = "$WORKBENCH_JVM_USER_HOME" ]; then
    WORKBENCH_JVM_USER_HOME="$PWD/$WORKBENCH_JVM_USER_HOME"
  fi
  mkdir -p "$WORKBENCH_JVM_USER_HOME"
  JVM_HOME_ARGS=("-Duser.home=$WORKBENCH_JVM_USER_HOME")
fi
mkdir -p "$WORKBENCH_HOME/tmp"
echo "[workbench] home: $WORKBENCH_HOME (tmp: $WORKBENCH_HOME/tmp)" >&2

# JDK 21, wherever this box keeps it: $JAVA_HOME when it is a 21, else the OS's answer
# (macOS java_home, Linux /usr/lib/jvm), else the Temurin default paths. Fail with the
# fix line rather than run on the wrong JDK — ./provision.sh --check names the rest.
find_jdk21() {
  local c
  for c in "${JAVA_HOME:-}" \
           "$( [ "$(uname -s)" = Darwin ] && /usr/libexec/java_home -v 21 2>/dev/null || true )" \
           /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
           "$HOME"/.gradle/jdks/*21* \
           /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-21* /usr/lib/jvm/zulu21*; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    # match the version line itself: JDK_JAVA_OPTIONS / _JAVA_OPTIONS print a NOTE line first
    if "$c/bin/java" -version 2>&1 | grep -q 'version "21[."]'; then printf '%s' "$c"; return 0; fi
  done
  return 1
}
if ! JAVA_HOME="$(find_jdk21)"; then
  echo "[workbench] ERROR: no JDK 21 found — set JAVA_HOME, or run ./provision.sh --check for the install line" >&2
  exit 1
fi
export JAVA_HOME

# The JEB install: an explicit $JEB_HOME wins (and must be one — a typo is an error, not a
# silent fall back to another engine); else the copy under backend/engine.
if [ -n "${JEB_HOME:-}" ]; then
  if [ ! -f "$JEB_HOME/bin/app/jeb.jar" ]; then
    echo "[workbench] ERROR: JEB_HOME=$JEB_HOME is not a JEB install (no bin/app/jeb.jar)" >&2
    exit 1
  fi
  ENGINE="$(cd "$JEB_HOME" && pwd)"
  export JEB_HOME="$ENGINE"   # gradle (build.gradle) compiles against the same install
else
  ENGINE="$SCRIPT_DIR/engine"
fi

# The JADX install: an explicit $JADX_HOME wins, else backend/jadx.
# Accepts either a stock release zip (lib/jadx-*-all.jar) or split jars (lib/jadx-core*.jar).
has_jadx_jars() {
  [ -d "$1" ] && { compgen -G "$1/jadx-*-all.jar" >/dev/null || compgen -G "$1/jadx-core*.jar" >/dev/null; }
}
JADX_CP=""
if [ -n "${JADX_HOME:-}" ]; then
  if has_jadx_jars "$JADX_HOME/lib"; then
    JADX_DIR="$(cd "$JADX_HOME/lib" && pwd)"
  elif has_jadx_jars "$JADX_HOME"; then
    JADX_DIR="$(cd "$JADX_HOME" && pwd)"
  else
    echo "[workbench] ERROR: JADX_HOME=$JADX_HOME has no jadx-*-all.jar or jadx-core*.jar (in lib/ or root)" >&2
    exit 1
  fi
  JADX_HOME="$(cd "$JADX_HOME" && pwd)"
  export JADX_HOME
  JADX_CP=":$JADX_DIR/*"
else
  for cand in "$SCRIPT_DIR/jadx/lib" "$SCRIPT_DIR/jadx"; do
    if has_jadx_jars "$cand"; then
      JADX_DIR="$(cd "$cand" && pwd)"
      JADX_CP=":$JADX_DIR/*"
      break
    fi
  done
fi

# WORKBENCH_RUN_ONLY=1: start what is already built and build nothing — no npm, no gradle. For
# a tree that was built once and is started many times (a rehearsal of several instances from one
# checkout); the deployed package has its own entrypoint and never builds.
RUN_ONLY="${WORKBENCH_RUN_ONLY:-0}"
if [ "$RUN_ONLY" = 1 ]; then
  [ -d "$SCRIPT_DIR/build/classes/java/main/dev/workbench" ] && [ -f "$REPO_ROOT/web/js/app.js" ] || {
    echo "[workbench] ERROR: WORKBENCH_RUN_ONLY=1 but nothing is built here (backend/build/classes, web/js)" >&2
    exit 1
  }
fi

# Frontend (web/): TypeScript sources in web/src compile to the served web/js
# tree (tsc, pinned in web/package.json). A fresh checkout has no web/js at all,
# so build here before the server starts serving it. Type errors do not block
# the launch — tsc still emits — but they are reported loudly.
if [ "$RUN_ONLY" != 1 ] && [ -f "$REPO_ROOT/web/package.json" ]; then
  if command -v npm >/dev/null 2>&1; then
    ( cd "$REPO_ROOT/web" \
      && { [ -d node_modules ] || npm ci --no-audit --no-fund; } \
      && npm run --silent build ) \
      || echo "[workbench] WARN: frontend build reported errors — web/js may be stale (cd web && npm run build)" >&2
  else
    echo "[workbench] WARN: npm not on PATH — web/js is NOT rebuilt from web/src" >&2
  fi
fi

cd "$SCRIPT_DIR"
if [ "$RUN_ONLY" != 1 ]; then
  if command -v gradle >/dev/null 2>&1; then
    gradle -q classes
  elif [ -x "$REPO_ROOT/gradlew" ]; then
    "$REPO_ROOT/gradlew" -p "$SCRIPT_DIR" -q classes
  elif compgen -G "$HOME/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle" >/dev/null; then
    GRADLE_BIN="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle | sort -V | tail -n 1)"
    "$GRADLE_BIN" -p "$SCRIPT_DIR" -q classes
  elif [ -d "$SCRIPT_DIR/build/classes/java/main/dev/workbench" ]; then
    echo "[workbench] NOTE: gradle not on PATH — using existing backend/build/classes" >&2
  else
    echo "[workbench] ERROR: gradle not found on PATH (and no gradlew found)" >&2
    exit 1
  fi
fi

# Mock fixtures (dev/test demo targets) stay OUT of the interactive server by
# default; ITs run their own servers without this and keep them.
# Override with WORKBENCH_MOCK=1 to get the demo targets back.
export WORKBENCH_MOCK="${WORKBENCH_MOCK:-0}"

if [ -f "$ENGINE/bin/app/jeb.jar" ]; then
  # Heap ceiling: browsing GMS-class targets from a cached jdb2 fits in ~8g
  # (5.5g live, measured post-GC 2026-08-04); the one-time FRESH analysis +
  # first save needs 16g (14.1g peak) — and an OOM mid-analysis can strand a
  # .jdb2.lock (app-review-opus P1-2), so 16g is the default. WORKBENCH_XMX
  # overrides (e.g. 8g when only reopening cached jdb2s on a small machine).
  XMX="${WORKBENCH_XMX:-16g}"
  XMS="${WORKBENCH_XMS:-1g}"   # the initial heap: an idle instance holds this much (lower it where several instances share a machine)
  echo "[workbench] JEB install: $ENGINE" >&2
  # WORKBENCH_JAVA_OPTS: extra JVM flags, word-split (a JFR recording, a GC log, a heap dump on OOM)
  # shellcheck disable=SC2086
  source "$SCRIPT_DIR/jeb-jvm-options.sh"
  load_jeb_jvm_options "$ENGINE"
  exec "$JAVA_HOME/bin/java" \
    ${JEB_JVM_OPTIONS[@]+"${JEB_JVM_OPTIONS[@]}"} \
    -Xms"$XMS" -Xmx"$XMX" -XX:+UseG1GC -Djava.awt.headless=true ${WORKBENCH_JAVA_OPTS:-} \
    -Djava.security.manager=allow \
    -Djava.io.tmpdir="$WORKBENCH_HOME/tmp" \
    ${JVM_HOME_ARGS[@]+"${JVM_HOME_ARGS[@]}"} \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    -Dworkbench.jebHome="$ENGINE" \
    -cp "build/classes/java/main:$ENGINE/bin/app/*${JADX_CP}" \
    dev.workbench.engine.jeb.LocalJebLauncher "$REPO_ROOT/web"
else
  if [ -n "$JADX_CP" ]; then
    echo "[workbench] JADX install: $JADX_DIR (no JEB install)" >&2
    XMX="${WORKBENCH_XMX:-8g}"
    XMS="${WORKBENCH_XMS:-512m}"
  else
    echo "[workbench] NOTE: no JEB/JADX install (JEB_HOME/JADX_HOME unset) — mock-only mode" >&2
    XMX="${WORKBENCH_XMX:-2g}"
    XMS="${WORKBENCH_XMS:-256m}"
  fi
  # shellcheck disable=SC2086
  exec "$JAVA_HOME/bin/java" -Xms"$XMS" -Xmx"$XMX" -Djava.awt.headless=true ${WORKBENCH_JAVA_OPTS:-} \
      -Djava.io.tmpdir="$WORKBENCH_HOME/tmp" ${JVM_HOME_ARGS[@]+"${JVM_HOME_ARGS[@]}"} -cp "build/classes/java/main${JADX_CP}" \
      dev.workbench.server.WorkbenchServer "$REPO_ROOT/web"
fi
