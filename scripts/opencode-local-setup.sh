#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────
DISTRO_ALIAS="opencode-debian"
DISTRO_BASE="debian"
LOCAL_PORT="4096"
LOCAL_HOST="127.0.0.1"
INSTALL_DIR="$HOME/opencode-local"
TERMUX_PROPERTIES_DIR="$HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_PROPERTIES_DIR/termux.properties"
SETUP_SCRIPT_PATH="$INSTALL_DIR/setup.sh"
SETUP_SHA_FILE="$INSTALL_DIR/setup.sha256"
SETUP_SHA_SOURCES=(
    "https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sha256"
    "https://github.com/crim50n/oc-remote/raw/master/scripts/opencode-local-setup.sha256"
    "https://cdn.jsdelivr.net/gh/crim50n/oc-remote@master/scripts/opencode-local-setup.sha256"
)
SETUP_SCRIPT_SOURCES=(
    "https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sh"
    "https://github.com/crim50n/oc-remote/raw/master/scripts/opencode-local-setup.sh"
    "https://cdn.jsdelivr.net/gh/crim50n/oc-remote@master/scripts/opencode-local-setup.sh"
)

# Debian rootfs from GitHub Releases CDN (fast).
# proot-distro's default CDN (easycli.sh) is often extremely slow.
DEBIAN_ROOTFS_URL="https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
DEBIAN_ROOTFS_SHA256="3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"

TERMUX_REQUIRED_PACKAGES=(proot-distro curl jq)
WAKE_LOCK_HELD=0
STEP_NUMBER=0
TERMUX_RESTART_REQUIRED=0

# Geographically diverse, high-reliability Termux mirrors.
# The script tests each and picks the fastest for the user.
TERMUX_MIRRORS=(
    "https://packages-cf.termux.dev/apt/termux-main"       # Cloudflare CDN (global)
    "https://mirror.fcix.net/termux/termux-main"            # Fremont CA, 10 Gbps
    "https://ftp.fau.de/termux/termux-main"                 # Erlangen DE, 25 Gbps
    "https://mirror.mwt.me/termux/main"                     # US+EU CDN
    "https://grimler.se/termux/termux-main"                 # Helsinki FI
    "https://mirrors.medzik.dev/termux/termux-main"         # Frankfurt DE
    "https://plug-mirror.rcac.purdue.edu/termux/termux-main" # Indiana US
    "https://repository.su/termux/termux-main"              # Nizhny Novgorod RU
    "http://mirror.mephi.ru/termux/termux-main"             # Moscow RU (HTTP)
)

# ── Output helpers ─────────────────────────────────────────────────────
# Termux supports ANSI colors and Unicode box-drawing out of the box.
BOLD='\033[1m'
DIM='\033[2m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
CYAN='\033[36m'
RESET='\033[0m'

header() {
    printf "\n${BOLD}${CYAN}  OpenCode Local Runtime${RESET}\n"
    printf "${DIM}  Debian + proot on Termux${RESET}\n\n"
}

step() {
    STEP_NUMBER=$((STEP_NUMBER + 1))
    printf "${BOLD}  [%d] %s${RESET}\n" "$STEP_NUMBER" "$*"
}

info() {
    printf "${DIM}      %s${RESET}\n" "$*"
}

ok() {
    printf "  ${GREEN}[ok]${RESET} %s\n" "$*"
}

skip() {
    printf "  ${DIM}[--]${RESET} %s\n" "$*"
}

warn() {
    printf "  ${YELLOW}[!!]${RESET} %s\n" "$*" >&2
}

fail() {
    printf "  ${RED}[error]${RESET} %s\n" "$*" >&2
}

die() {
    fail "$*"
    exit 1
}

success_banner() {
    printf "\n"
    printf "  ${GREEN}${BOLD}Setup complete${RESET}\n"
    printf "  ${DIM}CLI control:${RESET} ${BOLD}opencode-local start|stop|status${RESET}\n"
    if (( TERMUX_RESTART_REQUIRED == 1 )); then
        printf "  ${YELLOW}${BOLD}Important:${RESET} ${DIM}Force-stop and reopen Termux once so allow-external-apps is applied.${RESET}\n"
    fi
    printf "  ${DIM}Return to OC Remote and tap Start.${RESET}\n\n"
}

# Run a command in the background with a spinner so it never looks frozen.
# Usage: spin "message" command [args...]
SPIN_FRAMES=('⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏')
spin() {
    local msg="$1"; shift
    local logfile
    logfile="$(mktemp)"

    "$@" >"$logfile" 2>&1 &
    local pid=$!
    local i=0

    # Show spinner while the process runs
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r\033[2K  ${DIM}${SPIN_FRAMES[$((i % ${#SPIN_FRAMES[@]}))]}  %s${RESET}" "$msg"
        sleep 0.12
        i=$((i + 1))
    done

    # Collect exit code
    local rc=0
    wait "$pid" || rc=$?

    # Clear the spinner line
    printf "\r\033[2K"

    if (( rc != 0 )); then
        # Dump last 20 lines of output so user can see what went wrong
        fail "$msg"
        tail -20 "$logfile" | while IFS= read -r line; do
            printf "  ${DIM}  | %s${RESET}\n" "$line"
        done >&2
        rm -f "$logfile"
        return "$rc"
    fi

    rm -f "$logfile"
    return 0
}

# ── Wake lock ──────────────────────────────────────────────────────────
acquire_wake_lock() {
    if command -v termux-wake-lock >/dev/null 2>&1; then
        termux-wake-lock >/dev/null 2>&1 || true
        WAKE_LOCK_HELD=1
    fi
}

release_wake_lock() {
    if (( WAKE_LOCK_HELD == 1 )) && command -v termux-wake-unlock >/dev/null 2>&1; then
        termux-wake-unlock >/dev/null 2>&1 || true
    fi
}

# ── Preflight checks ──────────────────────────────────────────────────
require_termux() {
    [[ -n "${TERMUX_VERSION:-}" ]] || die "This script must run inside Termux"
    [[ -d "$PREFIX" ]] || die "Termux PREFIX not found"
}

check_arch() {
    local arch
    arch="$(uname -m)"
    [[ "$arch" == "aarch64" ]] || die "Unsupported architecture: $arch (need aarch64)"
}

check_storage() {
    local avail_kb
    avail_kb="$(df -Pk "$HOME" | awk 'NR==2 {print $4}')"
    [[ -n "$avail_kb" ]] || die "Cannot determine free disk space"
    if (( avail_kb < 600000 )); then
        die "Not enough space (~600 MB required, $(( avail_kb / 1024 )) MB available)"
    fi
}

check_network() {
    if ! spin "Checking network" curl --connect-timeout 10 --max-time 15 -fsSL "https://github.com" -o /dev/null; then
        die "No network — cannot reach github.com"
    fi
}

# ── Termux setup ───────────────────────────────────────────────────────
select_fastest_mirror() {
    local best_url="" best_time="99999"

    info "Testing ${#TERMUX_MIRRORS[@]} mirrors..."
    for mirror_url in "${TERMUX_MIRRORS[@]}"; do
        # Fetch the Release file (small) and measure time
        local time_ms
        time_ms="$(curl --connect-timeout 3 --max-time 5 -fsSL \
            -o /dev/null -w '%{time_total}' \
            "${mirror_url}/dists/stable/Release" 2>/dev/null || echo "99999")"

        # Convert to ms integer for comparison (bash can't compare floats)
        local ms
        ms="$(awk "BEGIN { printf \"%.0f\", $time_ms * 1000 }" 2>/dev/null || echo "99999")"

        local host
        host="$(echo "$mirror_url" | sed 's|https\?://\([^/]*\).*|\1|')"

        if (( ms < best_time )); then
            best_time="$ms"
            best_url="$mirror_url"
            info "$host — ${ms}ms ✓"
        else
            info "$host — ${ms}ms"
        fi
    done

    if [[ -z "$best_url" ]]; then
        warn "All mirrors failed — keeping current config"
        return
    fi

    # Write sources.list
    local sources_file="$PREFIX/etc/apt/sources.list"
    echo "deb $best_url stable main" > "$sources_file"

    # Remove deb822-format files that would override sources.list
    rm -f "$PREFIX/etc/apt/sources.list.d/"*.sources 2>/dev/null || true

    local best_host
    best_host="$(echo "$best_url" | sed 's|https\?://\([^/]*\).*|\1|')"
    ok "Selected mirror: $best_host (${best_time}ms)"
}

ensure_termux_properties() {
    mkdir -p "$TERMUX_PROPERTIES_DIR"
    touch "$TERMUX_PROPERTIES_FILE"

    if grep -Eq '^\s*allow-external-apps\s*=\s*true\s*$' "$TERMUX_PROPERTIES_FILE"; then
        skip "allow-external-apps already enabled"
        return
    fi

    if grep -Eq '^\s*#?\s*allow-external-apps\s*=' "$TERMUX_PROPERTIES_FILE"; then
        sed -i 's/^\s*#\?\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
    else
        printf "\nallow-external-apps = true\n" >> "$TERMUX_PROPERTIES_FILE"
    fi

    TERMUX_RESTART_REQUIRED=1

    if command -v termux-reload-settings >/dev/null 2>&1; then
        termux-reload-settings >/dev/null 2>&1 || true
    fi
    ok "allow-external-apps enabled"
    warn "Force-stop and reopen Termux once to fully apply allow-external-apps"
}

ensure_termux_packages() {
    local missing=()

    for pkg in "${TERMUX_REQUIRED_PACKAGES[@]}"; do
        if ! dpkg -s "$pkg" >/dev/null 2>&1; then
            missing+=("$pkg")
        fi
    done

    if (( ${#missing[@]} == 0 )); then
        skip "Termux packages already installed"
        return
    fi

    info "Installing: ${missing[*]}"
    info "apt output ↓"
    if ! (apt-get update -yq && apt-get install -yq "${missing[@]}"); then
        printf "\n"
        fail "Package install failed"
        info "Try running: termux-change-repo"
        info "Then re-run this script"
        exit 1
    fi
    ok "Termux packages ready"
}

# ── Debian distro ─────────────────────────────────────────────────────
install_distro_rootfs() {
    info "Downloading Debian rootfs from GitHub CDN..."
    if ! env \
        PD_OVERRIDE_TARBALL_URL="$DEBIAN_ROOTFS_URL" \
        PD_OVERRIDE_TARBALL_SHA256="$DEBIAN_ROOTFS_SHA256" \
        proot-distro install --override-alias "$DISTRO_ALIAS" "$DISTRO_BASE"; then
        die "Failed to install Debian. Check network and try again"
    fi
}

ensure_distro_installed() {
    local rootfs_dir="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_ALIAS"

    if [[ -d "$rootfs_dir" ]] && [[ -n "$(ls -A "$rootfs_dir" 2>/dev/null)" ]]; then
        if proot-distro login "$DISTRO_ALIAS" -- /bin/sh -lc "true" >/dev/null 2>&1; then
            skip "Debian distro already installed"
            return
        fi
        warn "Existing install is broken — reinstalling"
        proot-distro remove "$DISTRO_ALIAS" >/dev/null 2>&1 || true
        rm -rf "$rootfs_dir"
    fi

    install_distro_rootfs
    ok "Debian distro installed"
}

proot_exec() {
    proot-distro login "$DISTRO_ALIAS" -- /bin/bash -lc "$1" \
        2> >(grep -vE "CPU doesn't support 32-bit instructions|can't sanitize binding \"/proc/self/fd/1\"" >&2)
}

self_update_setup_script_if_needed() {
    mkdir -p "$INSTALL_DIR"

    local local_sha
    local_sha=""
    if [[ -f "$SETUP_SHA_FILE" ]]; then
        local_sha="$(awk 'NR==1 {print $1; exit}' "$SETUP_SHA_FILE")"
    elif [[ -f "$SETUP_SCRIPT_PATH" ]]; then
        local_sha="$(sha256sum "$SETUP_SCRIPT_PATH" | awk '{print $1}')"
    fi

    local remote_sha_url remote_script_url remote_line remote_sha
    local tmp_setup tmp_sha downloaded_sha
    local i
    for i in "${!SETUP_SHA_SOURCES[@]}"; do
        remote_sha_url="${SETUP_SHA_SOURCES[$i]}"
        remote_script_url="${SETUP_SCRIPT_SOURCES[$i]}"

        remote_line="$(curl --connect-timeout 5 --max-time 12 -fsSL -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "$remote_sha_url?t=$(date +%s)" 2>/dev/null || true)"
        remote_sha="$(printf '%s' "$remote_line" | awk '{print $1}')"
        [[ -n "$remote_sha" ]] || continue
        [[ "$remote_sha" != "$local_sha" ]] || return 1

        tmp_setup="$(mktemp "$INSTALL_DIR/setup.sh.XXXXXX")"
        tmp_sha="$(mktemp "$INSTALL_DIR/setup.sha256.XXXXXX")"

        if ! curl --connect-timeout 5 --max-time 20 -fsSL -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "$remote_script_url?t=$(date +%s)" -o "$tmp_setup"; then
            rm -f "$tmp_setup" "$tmp_sha"
            continue
        fi

        downloaded_sha="$(sha256sum "$tmp_setup" | awk '{print $1}')"
        if [[ "$downloaded_sha" != "$remote_sha" ]]; then
            rm -f "$tmp_setup" "$tmp_sha"
            continue
        fi

        printf '%s\n' "$remote_line" > "$tmp_sha"
        mv "$tmp_setup" "$SETUP_SCRIPT_PATH"
        chmod 700 "$SETUP_SCRIPT_PATH"
        mv "$tmp_sha" "$SETUP_SHA_FILE"
        ok "Setup script updated"
        return 0
    done

    return 1
}

resolve_opencode_binary_in_distro() {
    proot_exec '
        for candidate in \
            /root/.opencode/bin/opencode \
            /root/.local/bin/opencode \
            /usr/local/bin/opencode
        do
            if [[ -x "$candidate" ]]; then
                printf "%s\n" "$candidate"
                exit 0
            fi
        done
        exit 1
    '
}

opencode_version_in_distro() {
    proot_exec '
        export HOME="/root"
        export PATH="/usr/local/bin:/usr/bin:/bin:/root/.opencode/bin:/root/.local/bin:$PATH"
        if command -v opencode >/dev/null 2>&1; then
            cmd_path="$(command -v opencode)"
            if [[ -n "$cmd_path" ]] && [[ -x "$cmd_path" ]] && "$cmd_path" --version >/dev/null 2>&1; then
                "$cmd_path" --version
                exit 0
            fi
        fi

        for candidate in /root/.opencode/bin/opencode /root/.local/bin/opencode /usr/local/bin/opencode; do
            if [[ -x "$candidate" ]] && "$candidate" --version >/dev/null 2>&1; then
                "$candidate" --version
                exit 0
            fi
        done

        exit 1
    ' 2>/dev/null | tr -d '\r'
}

repair_opencode_command_path() {
    local resolved
    if ! resolved="$(resolve_opencode_binary_in_distro 2>/dev/null)"; then
        return 1
    fi

    proot_exec "ln -sfn '$resolved' /usr/local/bin/opencode"
    return 0
}

setup_distro_packages() {
    local needed=""

    # Check which packages are missing inside the distro
    for pkg in ca-certificates curl git ripgrep tmux procps bash; do
        if ! proot_exec "dpkg -s $pkg" >/dev/null 2>&1; then
            needed="$needed $pkg"
        fi
    done

    if [[ -z "$needed" ]]; then
        skip "Debian packages already installed"
        return
    fi

    info "apt output ↓"
    proot_exec "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq --no-install-recommends $needed && apt-get clean" || die "Failed to install Debian packages"
    ok "Debian packages ready"
}

# ── OpenCode binary ───────────────────────────────────────────────────
install_opencode_binary() {
    local current
    current="$(opencode_version_in_distro || true)"

    if [[ -n "$current" ]]; then
        repair_opencode_command_path || true
    fi

    if [[ -n "$current" ]]; then
        skip "OpenCode already installed ($current)"
        return
    fi

    proot_exec "rm -f /usr/local/bin/opencode; curl -fsSL https://opencode.ai/install | bash" || die "Failed to install OpenCode"
    repair_opencode_command_path || die "OpenCode installed but command path could not be repaired"

    local installed
    installed="$(opencode_version_in_distro || true)"
    [[ -n "$installed" ]] || die "OpenCode installed but command still not executable"
    ok "OpenCode installed ($installed)"
}

# ── Runtime scripts ───────────────────────────────────────────────────
write_runtime_scripts() {
    mkdir -p "$INSTALL_DIR"

cat > "$INSTALL_DIR/start.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO_ALIAS="opencode-debian"
PORT="4096"
HOST="127.0.0.1"
ENV_FILE="$HOME/opencode-local/env"
SETUP_DIR="$HOME/opencode-local"
SETUP_SCRIPT="$SETUP_DIR/setup.sh"
SETUP_SHA_FILE="$SETUP_DIR/setup.sha256"
SETUP_SHA_SOURCES=(
    "https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sha256"
    "https://github.com/crim50n/oc-remote/raw/master/scripts/opencode-local-setup.sha256"
    "https://cdn.jsdelivr.net/gh/crim50n/oc-remote@master/scripts/opencode-local-setup.sha256"
)
SETUP_SCRIPT_SOURCES=(
    "https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sh"
    "https://github.com/crim50n/oc-remote/raw/master/scripts/opencode-local-setup.sh"
    "https://cdn.jsdelivr.net/gh/crim50n/oc-remote@master/scripts/opencode-local-setup.sh"
)
CLI_PROXY_URL=""
CLI_NO_PROXY=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --proxy)
            CLI_PROXY_URL="${2:-}"
            shift 2
            ;;
        --no-proxy)
            CLI_NO_PROXY="${2:-}"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

auto_refresh_runtime_scripts() {
    mkdir -p "$SETUP_DIR"

    local local_sha
    local_sha=""
    if [[ -f "$SETUP_SHA_FILE" ]]; then
        local_sha="$(awk 'NR==1 {print $1; exit}' "$SETUP_SHA_FILE")"
    elif [[ -f "$SETUP_SCRIPT" ]]; then
        local_sha="$(sha256sum "$SETUP_SCRIPT" | awk '{print $1}')"
    fi

    local remote_sha_url remote_script_url remote_line remote_sha
    local tmp_setup tmp_sha downloaded_sha
    local i
    for i in "${!SETUP_SHA_SOURCES[@]}"; do
        remote_sha_url="${SETUP_SHA_SOURCES[$i]}"
        remote_script_url="${SETUP_SCRIPT_SOURCES[$i]}"

        remote_line="$(curl --connect-timeout 5 --max-time 12 -fsSL -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "$remote_sha_url?t=$(date +%s)" 2>/dev/null || true)"
        remote_sha="$(printf '%s' "$remote_line" | awk '{print $1}')"
        [[ -n "$remote_sha" ]] || continue
        [[ "$remote_sha" != "$local_sha" ]] || return 0

        tmp_setup="$(mktemp "$SETUP_DIR/setup.sh.XXXXXX")"
        tmp_sha="$(mktemp "$SETUP_DIR/setup.sha256.XXXXXX")"

        if ! curl --connect-timeout 5 --max-time 20 -fsSL -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "$remote_script_url?t=$(date +%s)" -o "$tmp_setup"; then
            rm -f "$tmp_setup" "$tmp_sha"
            continue
        fi

        downloaded_sha="$(sha256sum "$tmp_setup" | awk '{print $1}')"
        if [[ "$downloaded_sha" != "$remote_sha" ]]; then
            rm -f "$tmp_setup" "$tmp_sha"
            continue
        fi

        printf '%s\n' "$remote_line" > "$tmp_sha"
        mv "$tmp_setup" "$SETUP_SCRIPT"
        chmod 700 "$SETUP_SCRIPT"
        mv "$tmp_sha" "$SETUP_SHA_FILE"

        bash "$SETUP_SCRIPT" refresh-runtime >/dev/null 2>&1 || true
        return 0
    done
}

auto_refresh_runtime_scripts

if [[ -f "$ENV_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
fi

if [[ -n "$CLI_PROXY_URL" ]]; then
    OPENCODE_PROXY_URL="$CLI_PROXY_URL"
fi

DEFAULT_NO_PROXY="localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
if [[ -n "$CLI_NO_PROXY" ]]; then
    NO_PROXY="$CLI_NO_PROXY"
elif [[ -n "${NO_PROXY:-}" ]]; then
    NO_PROXY="$NO_PROXY,$DEFAULT_NO_PROXY"
else
    NO_PROXY="$DEFAULT_NO_PROXY"
fi

export OPENCODE_SERVER_PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"
export NO_PROXY="$NO_PROXY"
export no_proxy="$NO_PROXY"
if [[ -n "${OPENCODE_PROXY_URL:-}" ]]; then
    export HTTP_PROXY="$OPENCODE_PROXY_URL"
    export HTTPS_PROXY="$OPENCODE_PROXY_URL"
    export ALL_PROXY="$OPENCODE_PROXY_URL"
    export http_proxy="$OPENCODE_PROXY_URL"
    export https_proxy="$OPENCODE_PROXY_URL"
    export all_proxy="$OPENCODE_PROXY_URL"
fi

PROXY_EXPORTS="export OPENCODE_SERVER_PASSWORD=\"${OPENCODE_SERVER_PASSWORD:-}\"; export NO_PROXY=\"$NO_PROXY\"; export no_proxy=\"$NO_PROXY\";"
if [[ -n "${OPENCODE_PROXY_URL:-}" ]]; then
    PROXY_EXPORTS+=" export HTTP_PROXY=\"$OPENCODE_PROXY_URL\"; export HTTPS_PROXY=\"$OPENCODE_PROXY_URL\"; export ALL_PROXY=\"$OPENCODE_PROXY_URL\"; export http_proxy=\"$OPENCODE_PROXY_URL\"; export https_proxy=\"$OPENCODE_PROXY_URL\"; export all_proxy=\"$OPENCODE_PROXY_URL\";"
fi

PROOT_CMD='
set -euo pipefail

export HOME="/root"
export PATH="/usr/local/bin:/usr/bin:/bin:/root/.opencode/bin:/root/.local/bin:$PATH"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-4096}"

OPENCODE_BIN=""
for candidate in /root/.opencode/bin/opencode /root/.local/bin/opencode /usr/local/bin/opencode; do
    if [[ -x "$candidate" ]] && "$candidate" --version >/dev/null 2>&1; then
        OPENCODE_BIN="$candidate"
        break
    fi
done
if [[ -z "$OPENCODE_BIN" ]]; then
    cmd_path="$(command -v opencode || true)"
    if [[ -n "$cmd_path" ]] && [[ -x "$cmd_path" ]] && "$cmd_path" --version >/dev/null 2>&1; then
        OPENCODE_BIN="$cmd_path"
    fi
fi
if [[ -z "$OPENCODE_BIN" ]]; then
    echo "opencode binary not found in distro" >&2
    exit 127
fi

repair_runtime_dir() {
    local dir="$1"
    rm -rf "$dir/node_modules" "$dir/bun.lock" 2>/dev/null || true
}

has_invalid_modules() {
    local dir="$1"
    local package_json="$dir/package.json"

    [[ -f "$package_json" ]] || return 1
    [[ -d "$dir/node_modules" ]] || return 1

    local dep
    while IFS= read -r dep; do
        [[ -z "$dep" ]] && continue
        if [[ ! -f "$dir/node_modules/$dep/package.json" ]]; then
            return 0
        fi
    done < <(awk "/\"dependencies\"[[:space:]]*:[[:space:]]*\\{/ {in_dep=1; next} in_dep && /\\}/ {exit} in_dep { if (match(\$0, /\"([^\"]+)\"[[:space:]]*:/)) { line=substr(\$0, RSTART, RLENGTH); gsub(/\"|[[:space:]]|:/, \"\", line); print line } }" "$package_json")

    return 1
}

for runtime_dir in /root/.cache/opencode /root/.config/opencode /root/.opencode; do
    if has_invalid_modules "$runtime_dir"; then
        echo "Detected incomplete OpenCode modules in $runtime_dir, rebuilding runtime cache..." >&2
        repair_runtime_dir "$runtime_dir"
    fi
done

LOG_FILE="/root/.cache/opencode/last-serve-error.log"
mkdir -p /root/.cache/opencode

set +e
"$OPENCODE_BIN" serve --hostname "$HOST" --port "$PORT" 2>"$LOG_FILE"
EXIT_CODE=$?
set -e

if [[ $EXIT_CODE -ne 0 ]] && grep -Eiq "resolveerror|cannot find module|module not found|can.t import|no such file or directory.*node_modules|error: cannot resolve" "$LOG_FILE"; then
    echo "Detected broken OpenCode runtime cache, reinstalling runtime assets and retrying..." >&2
    for runtime_dir in /root/.cache/opencode /root/.config/opencode /root/.opencode; do
        repair_runtime_dir "$runtime_dir"
    done
    "$OPENCODE_BIN" upgrade --method curl >/dev/null 2>&1 || true
    exec "$OPENCODE_BIN" serve --hostname "$HOST" --port "$PORT"
fi

cat "$LOG_FILE" >&2 || true
exit $EXIT_CODE
'

exec proot-distro login "$DISTRO_ALIAS" -- /bin/bash -lc "$PROXY_EXPORTS $PROOT_CMD" \
    2> >(grep -vE "CPU doesn't support 32-bit instructions|can't sanitize binding \"/proc/self/fd/1\"|Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured" >&2)
EOF

    cat > "$INSTALL_DIR/stop.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

proot-distro login opencode-debian -- /bin/bash -lc 'pkill -f "opencode serve" >/dev/null 2>&1 || true' >/dev/null 2>&1 || true
pkill -f "proot-distro login opencode-debian" >/dev/null 2>&1 || true
EOF

    cat > "$INSTALL_DIR/status.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

if curl -fsS "http://127.0.0.1:4096/global/health" 2>/dev/null | grep -q '"healthy":true'; then
    echo "running"
else
    echo "stopped"
fi
EOF

    cat > "$PREFIX/bin/opencode-local" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

INSTALL_DIR="$HOME/opencode-local"

usage() {
    cat <<'USAGE'
Usage: opencode-local <command>

Commands:
  start [--proxy URL] [--no-proxy LIST]  Start local OpenCode server
  stop     Stop local OpenCode server
  status   Print running/stopped
  doctor   Show local runtime diagnostics
USAGE
}

if [[ $# -eq 0 ]]; then
    usage
    if [[ -x "$INSTALL_DIR/status.sh" ]]; then
        echo
        echo -n "Current status: "
        "$INSTALL_DIR/status.sh"
    fi
    exit 0
fi

cmd="$1"
case "$cmd" in
    start)
        shift || true
        bash "$INSTALL_DIR/setup.sh" refresh-runtime >/dev/null 2>&1 || true
        exec "$INSTALL_DIR/start.sh" "$@"
        ;;
    stop)
        exec "$INSTALL_DIR/stop.sh"
        ;;
    status)
        exec "$INSTALL_DIR/status.sh"
        ;;
    doctor)
        if [[ -x "$INSTALL_DIR/status.sh" ]]; then
            echo "Local runtime scripts: present"
            echo -n "Server: "
            "$INSTALL_DIR/status.sh"
        else
            echo "Local runtime scripts: missing"
            echo "Run setup first: curl -fsSL https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sh | bash"
            exit 1
        fi
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        usage
        exit 1
        ;;
esac
EOF

    if [[ ! -f "$INSTALL_DIR/env" ]]; then
        cat > "$INSTALL_DIR/env" <<'EOF'
# Optional auth for local OpenCode server.
# Set a value and restart local server.
OPENCODE_SERVER_PASSWORD=
EOF
    fi

    chmod 700 "$INSTALL_DIR/start.sh" "$INSTALL_DIR/stop.sh" "$INSTALL_DIR/status.sh" "$PREFIX/bin/opencode-local"
    chmod 600 "$INSTALL_DIR/env"
    ok "Runtime scripts written"
    ok "CLI command installed: opencode-local"
}

# ── Doctor ─────────────────────────────────────────────────────────────
doctor() {
    require_termux
    check_arch

    printf "\n${BOLD}  Diagnostics${RESET}\n\n"

    # Termux info
    printf "  %-28s %s\n" "Termux" "${TERMUX_VERSION:-unknown}"
    printf "  %-28s %s\n" "Architecture" "$(uname -m)"

    # Termux packages
    for pkg in "${TERMUX_REQUIRED_PACKAGES[@]}"; do
        if dpkg -s "$pkg" >/dev/null 2>&1; then
            printf "  %-28s ${GREEN}installed${RESET}\n" "$pkg"
        else
            printf "  %-28s ${RED}missing${RESET}\n" "$pkg"
        fi
    done

    # allow-external-apps
    if grep -Eq '^\s*allow-external-apps\s*=\s*true\s*$' "$TERMUX_PROPERTIES_FILE" 2>/dev/null; then
        printf "  %-28s ${GREEN}enabled${RESET}\n" "allow-external-apps"
    else
        printf "  %-28s ${RED}disabled${RESET}\n" "allow-external-apps"
    fi

    # Distro
    local rootfs_dir="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_ALIAS"
    if [[ -d "$rootfs_dir" ]] && [[ -n "$(ls -A "$rootfs_dir" 2>/dev/null)" ]]; then
        printf "  %-28s ${GREEN}installed${RESET}\n" "Debian distro"
    else
        printf "  %-28s ${RED}missing${RESET}\n" "Debian distro"
    fi

    # OpenCode binary
    local ver
    ver="$(opencode_version_in_distro || true)"
    if [[ -n "$ver" ]]; then
        printf "  %-28s ${GREEN}${ver}${RESET}\n" "OpenCode"
    else
        printf "  %-28s ${RED}missing${RESET}\n" "OpenCode"
    fi

    # Runtime scripts
    if [[ -x "$INSTALL_DIR/start.sh" ]]; then
        printf "  %-28s ${GREEN}present${RESET}\n" "Runtime scripts"
    else
        printf "  %-28s ${RED}missing${RESET}\n" "Runtime scripts"
    fi

    # Server health
    if curl -fsS "http://${LOCAL_HOST}:${LOCAL_PORT}/global/health" 2>/dev/null | grep -q '"healthy":true'; then
        printf "  %-28s ${GREEN}running${RESET}\n" "Server"
    else
        printf "  %-28s ${DIM}stopped${RESET}\n" "Server"
    fi

    printf "\n"
}

# ── Install ────────────────────────────────────────────────────────────
install_all() {
    header
    require_termux
    acquire_wake_lock
    check_arch
    check_storage
    check_network

    step "Termux configuration"
    ensure_termux_properties

    step "Package mirror"
    select_fastest_mirror

    step "Termux packages"
    ensure_termux_packages

    step "Debian environment"
    ensure_distro_installed

    step "Debian packages"
    setup_distro_packages

    step "OpenCode binary"
    install_opencode_binary

    step "Runtime scripts"
    write_runtime_scripts

    success_banner
}

refresh_runtime_scripts_only() {
    local skip_self_update="${1:-0}"
    require_termux

    if (( skip_self_update == 0 )) && self_update_setup_script_if_needed; then
        exec bash "$SETUP_SCRIPT_PATH" --skip-self-update refresh-runtime
    fi

    write_runtime_scripts
}

# ── Server commands ────────────────────────────────────────────────────
start_server() {
    require_termux
    [[ -x "$INSTALL_DIR/start.sh" ]] || die "Not installed. Run install first"
    "$INSTALL_DIR/start.sh"
}

stop_server() {
    require_termux
    [[ -x "$INSTALL_DIR/stop.sh" ]] || die "Not installed. Run install first"
    "$INSTALL_DIR/stop.sh"
}

status_server() {
    require_termux
    [[ -x "$INSTALL_DIR/status.sh" ]] || die "Not installed. Run install first"
    "$INSTALL_DIR/status.sh"
}

# ── Main ───────────────────────────────────────────────────────────────
main() {
    trap release_wake_lock EXIT
    local skip_self_update=0
    if [[ "${1:-}" == "--skip-self-update" ]]; then
        skip_self_update=1
        shift || true
    fi

    local cmd="${1:-install}"

    if [[ "$cmd" == "install" ]] && (( skip_self_update == 0 )) && self_update_setup_script_if_needed; then
        exec bash "$SETUP_SCRIPT_PATH" --skip-self-update install
    fi

    case "$cmd" in
        install) install_all ;;
        refresh-runtime) refresh_runtime_scripts_only "$skip_self_update" ;;
        doctor)  doctor ;;
        start)   start_server ;;
        stop)    stop_server ;;
        status)  status_server ;;
        *) die "Unknown command: $cmd (expected: install|refresh-runtime|doctor|start|stop|status)" ;;
    esac
}

main "$@"
