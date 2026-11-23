#!/usr/bin/env bash
# Local environment self-check (P0-T01). Prints OK / WARN / FAIL per item; exits 1 if any FAIL.
# The JDK is not checked: Gradle Toolchain downloads Temurin 25 automatically.
set -u

REQUIRED_DOCKER_MEM_MIB=10240   # 10 GiB
REQUIRED_NODE="22.12.0"
REQUIRED_DISK_GB=30
RECOMMENDED_CPUS=6
DOCKER_SETTINGS="$HOME/Library/Group Containers/group.com.docker/settings-store.json"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ -t 1 ]; then
  GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  GREEN=""; YELLOW=""; RED=""; DIM=""; RESET=""
fi

FAILS=0
WARNS=0
ok()   { printf '%s[ OK ]%s %s\n' "$GREEN" "$RESET" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$RESET" "$1"; [ $# -gt 1 ] && printf '       %s%s%s\n' "$DIM" "$2" "$RESET"; WARNS=$((WARNS + 1)); }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$RESET" "$1"; [ $# -gt 1 ] && printf '       %s%s%s\n' "$DIM" "$2" "$RESET"; FAILS=$((FAILS + 1)); }
has()  { command -v "$1" >/dev/null 2>&1; }

# Version comparison: returns 0 when $1 >= $2 (numeric x.y.z part only)
version_ge() {
  local IFS=.
  local -a a b
  read -r -a a <<<"${1%%[-+]*}"
  read -r -a b <<<"${2%%[-+]*}"
  local i
  for i in 0 1 2; do
    local x="${a[$i]:-0}" y="${b[$i]:-0}"
    if [ "$x" -gt "$y" ]; then return 0; fi
    if [ "$x" -lt "$y" ]; then return 1; fi
  done
  return 0
}

# Read a Docker Desktop setting (tries both the new and the old key name)
docker_setting() {
  [ -f "$DOCKER_SETTINGS" ] && has jq || return 0
  jq -r --arg a "$1" --arg b "$2" '.[$a] // .[$b] // empty' "$DOCKER_SETTINGS" 2>/dev/null
}

echo "crypto-trading environment check"
echo

# ---- Basic CLI tools ----
for tool in git jq curl; do
  if has "$tool"; then
    ok "$tool $("$tool" --version 2>&1 | head -1)"
  else
    fail "$tool is not installed" "Install: brew install $tool"
  fi
done

# ---- Docker ----
if ! has docker; then
  fail "docker is not installed" "Install Docker Desktop: https://docs.docker.com/desktop/setup/install/mac-install/"
elif ! docker info >/dev/null 2>&1; then
  fail "Cannot connect to the Docker engine" "Start Docker Desktop, then re-run this script"
else
  ok "Docker engine reachable (Server $(docker version --format '{{.Server.Version}}' 2>/dev/null))"

  arch="$(docker info --format '{{.Architecture}}' 2>/dev/null)"
  case "$arch" in
    aarch64|arm64) ok "Docker architecture $arch" ;;
    *) fail "Docker architecture is ${arch:-unknown}, arm64 required" "Make sure the Apple silicon build of Docker Desktop is installed" ;;
  esac

  # Memory: prefer the Docker Desktop setting; fall back to the engine-reported value (slightly lower)
  mem_mib="$(docker_setting MemoryMiB memoryMiB)"
  mem_src="Docker Desktop settings"
  if [ -z "$mem_mib" ]; then
    mem_bytes="$(docker info --format '{{.MemTotal}}' 2>/dev/null)"
    mem_mib=$(( ${mem_bytes:-0} / 1024 / 1024 ))
    mem_src="docker info"
  fi
  mem_gib="$(awk -v m="$mem_mib" 'BEGIN { printf "%.2f", m / 1024 }')"
  if [ "$mem_mib" -ge "$REQUIRED_DOCKER_MEM_MIB" ]; then
    ok "Docker Desktop memory ${mem_gib} GiB (${mem_src})"
  else
    fail "Docker Desktop memory ${mem_gib} GiB (${mem_src}), >= 10 GiB required" \
      "Docker Desktop -> Settings -> Resources -> Memory: set to 10 GB, then Apply & restart"
  fi

  # CPU: recommendation only, never a failure
  cpus="$(docker info --format '{{.NCPU}}' 2>/dev/null)"
  if [ "${cpus:-0}" -eq "$RECOMMENDED_CPUS" ]; then ok "Docker Desktop CPUs: ${cpus}"
  else warn "Docker Desktop CPUs: ${cpus:-unknown}, 6 recommended" "Settings -> Resources -> CPU limit"; fi
fi

# ---- Node.js ----
if ! has node && has fnm && [ -x "$HOME/.local/share/fnm/aliases/default/bin/node" ]; then
  fail "Node.js is installed via fnm but is not on this shell's PATH" \
    "Add eval \"\$(fnm env --shell zsh)\" to ~/.zprofile (so non-interactive shells and the IDE find node), or run it in this shell and re-run"
elif ! has node; then
  fail "Node.js is not installed, >= ${REQUIRED_NODE} (current LTS) required" "Install LTS: brew install fnm && fnm install --lts && fnm default lts-latest (see docs/setup/macos.md)"
else
  node_ver="$(node -v | sed 's/^v//')"
  if version_ge "${node_ver}" "${REQUIRED_NODE}"; then ok "Node.js ${node_ver}"
  else fail "Node.js ${node_ver}, >= ${REQUIRED_NODE} required" "Upgrade to the current LTS"; fi
fi

# ---- pnpm (corepack) ----
if ! has corepack; then
  fail "corepack is not available" "Node.js >= 25 no longer bundles corepack: npm install -g corepack, then corepack enable pnpm"
elif ! has pnpm; then
  fail "pnpm is not enabled" "corepack enable pnpm"
else
  pnpm_path="$(command -v pnpm)"
  pnpm_ver="$(COREPACK_ENABLE_DOWNLOAD_PROMPT=0 pnpm -v 2>/dev/null)"
  if [ -z "${pnpm_ver}" ]; then
    fail "pnpm does not run (${pnpm_path})" "Run corepack enable pnpm and try again"
  elif [ -L "${pnpm_path}" ] && readlink "${pnpm_path}" | grep -q corepack; then
    ok "pnpm ${pnpm_ver} (corepack)"
  else
    warn "pnpm ${pnpm_ver} is not provided by corepack (${pnpm_path})" "Uninstall the standalone pnpm, then run corepack enable pnpm"
  fi
fi

# ---- Disk ----
avail_kb="$(df -k "$ROOT_DIR" | awk 'NR==2 { print $4 }')"
avail_gb=$(( ${avail_kb:-0} / 1024 / 1024 ))
if [ "$avail_gb" -ge "$REQUIRED_DISK_GB" ]; then ok "Free disk space ${avail_gb} GB"
else fail "Free disk space ${avail_gb} GB, >= ${REQUIRED_DISK_GB} GB required" "Free up disk space, or run docker system prune"; fi

echo
if [ "$FAILS" -gt 0 ]; then
  printf '%s%d check(s) failed%s, %d warning(s). Fix them and re-run scripts/doctor.sh\n' "$RED" "$FAILS" "$RESET" "$WARNS"
  exit 1
fi
printf '%sAll required checks OK%s (%d warning(s))\n' "$GREEN" "$RESET" "$WARNS"
