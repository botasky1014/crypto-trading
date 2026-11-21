#!/usr/bin/env bash
# 本机环境自检（P0-T01）。逐项打印 OK / WARN / FAIL，存在 FAIL 时退出码为 1。
# JDK 不在检查范围内：由 Gradle Toolchain 自动下载 Temurin 25。
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

# 版本比较：$1 >= $2 时返回 0（只比较数字部分 x.y.z）
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

# 读取 Docker Desktop 设置项（新旧两种键名都试）
docker_setting() {
  [ -f "$DOCKER_SETTINGS" ] && has jq || return 0
  jq -r --arg a "$1" --arg b "$2" '.[$a] // .[$b] // empty' "$DOCKER_SETTINGS" 2>/dev/null
}

echo "crypto-trading 环境自检"
echo

# ---- 基础 CLI ----
for tool in git jq curl; do
  if has "$tool"; then
    ok "$tool $("$tool" --version 2>&1 | head -1)"
  else
    fail "$tool 未安装" "安装：brew install $tool"
  fi
done

# ---- Docker ----
if ! has docker; then
  fail "docker 未安装" "安装 Docker Desktop：https://docs.docker.com/desktop/setup/install/mac-install/"
elif ! docker info >/dev/null 2>&1; then
  fail "Docker 引擎无法连接" "启动 Docker Desktop 后重跑本脚本"
else
  ok "Docker 引擎可连接（Server $(docker version --format '{{.Server.Version}}' 2>/dev/null)）"

  arch="$(docker info --format '{{.Architecture}}' 2>/dev/null)"
  case "$arch" in
    aarch64|arm64) ok "Docker 架构 $arch" ;;
    *) fail "Docker 架构为 ${arch:-未知}，需要 arm64" "确认使用 Apple 芯片版 Docker Desktop" ;;
  esac

  # 内存：优先读 Docker Desktop 设置值；读不到时用引擎上报值（会略小于设置值）
  mem_mib="$(docker_setting MemoryMiB memoryMiB)"
  mem_src="Docker Desktop 设置"
  if [ -z "$mem_mib" ]; then
    mem_bytes="$(docker info --format '{{.MemTotal}}' 2>/dev/null)"
    mem_mib=$(( ${mem_bytes:-0} / 1024 / 1024 ))
    mem_src="docker info"
  fi
  mem_gib="$(awk -v m="$mem_mib" 'BEGIN { printf "%.2f", m / 1024 }')"
  if [ "$mem_mib" -ge "$REQUIRED_DOCKER_MEM_MIB" ]; then
    ok "Docker Desktop 内存 ${mem_gib} GiB（${mem_src}）"
  else
    fail "Docker Desktop 内存 ${mem_gib} GiB（${mem_src}），需要 ≥ 10 GiB" \
      "Docker Desktop → Settings → Resources → Memory 调到 10 GB，Apply & restart"
  fi

  # CPU：只给建议，不算失败
  cpus="$(docker info --format '{{.NCPU}}' 2>/dev/null)"
  if [ "${cpus:-0}" -eq "$RECOMMENDED_CPUS" ]; then ok "Docker Desktop CPU ${cpus} 核"
  else warn "Docker Desktop CPU ${cpus:-未知} 核，建议 6 核" "Settings → Resources → CPU limit"; fi
fi

# ---- Node.js ----
if ! has node && has fnm && [ -x "$HOME/.local/share/fnm/aliases/default/bin/node" ]; then
  fail "Node.js 已通过 fnm 安装，但当前 shell 的 PATH 中没有" \
    "把 eval \"\$(fnm env --shell zsh)\" 加到 ~/.zprofile（非交互 shell 和 IDE 也能找到 node），或在当前 shell 执行后重跑"
elif ! has node; then
  fail "Node.js 未安装，需要 ≥ ${REQUIRED_NODE}（当前 LTS）" "安装 LTS：brew install fnm && fnm install --lts && fnm default lts-latest（见 docs/setup/macos.md）"
else
  node_ver="$(node -v | sed 's/^v//')"
  if version_ge "${node_ver}" "${REQUIRED_NODE}"; then ok "Node.js ${node_ver}"
  else fail "Node.js ${node_ver}，需要 ≥ ${REQUIRED_NODE}" "升级到当前 LTS"; fi
fi

# ---- pnpm（corepack）----
if ! has corepack; then
  fail "corepack 不可用" "Node.js ≥ 25 不再自带 corepack：npm install -g corepack，然后 corepack enable pnpm"
elif ! has pnpm; then
  fail "pnpm 未启用" "corepack enable pnpm"
else
  pnpm_path="$(command -v pnpm)"
  pnpm_ver="$(COREPACK_ENABLE_DOWNLOAD_PROMPT=0 pnpm -v 2>/dev/null)"
  if [ -z "${pnpm_ver}" ]; then
    fail "pnpm 无法运行（${pnpm_path}）" "corepack enable pnpm 后重试"
  elif [ -L "${pnpm_path}" ] && readlink "${pnpm_path}" | grep -q corepack; then
    ok "pnpm ${pnpm_ver}（corepack）"
  else
    warn "pnpm ${pnpm_ver} 不是由 corepack 提供（${pnpm_path}）" "建议卸载独立安装的 pnpm 后执行 corepack enable pnpm"
  fi
fi

# ---- 磁盘 ----
avail_kb="$(df -k "$ROOT_DIR" | awk 'NR==2 { print $4 }')"
avail_gb=$(( ${avail_kb:-0} / 1024 / 1024 ))
if [ "$avail_gb" -ge "$REQUIRED_DISK_GB" ]; then ok "可用磁盘 ${avail_gb} GB"
else fail "可用磁盘 ${avail_gb} GB，需要 ≥ ${REQUIRED_DISK_GB} GB" "清理磁盘，或执行 docker system prune"; fi

echo
if [ "$FAILS" -gt 0 ]; then
  printf '%s%d 项失败%s，%d 项建议。修复后重跑 scripts/doctor.sh\n' "$RED" "$FAILS" "$RESET" "$WARNS"
  exit 1
fi
printf '%s全部必需项 OK%s（%d 项建议）\n' "$GREEN" "$RESET" "$WARNS"
