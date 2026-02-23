#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────
DISTRO_ALIAS="opencode-debian"
DISTRO_BASE="debian"
OPENCODE_VERSION="1.2.10"
LOCAL_PORT="4096"
LOCAL_HOST="127.0.0.1"
INSTALL_DIR="$HOME/opencode-local"
TERMUX_PROPERTIES_DIR="$HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_PROPERTIES_DIR/termux.properties"

# Debian rootfs from GitHub Releases CDN (fast).
# proot-distro's default CDN (easycli.sh) is often extremely slow.
DEBIAN_ROOTFS_URL="https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
DEBIAN_ROOTFS_SHA256="3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"

TERMUX_REQUIRED_PACKAGES=(proot-distro curl jq)
WAKE_LOCK_HELD=0
STEP_NUMBER=0

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

    if grep -Eq '^\s*allow-external-apps\s*=' "$TERMUX_PROPERTIES_FILE"; then
        sed -i 's/^\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
    else
        printf "\nallow-external-apps = true\n" >> "$TERMUX_PROPERTIES_FILE"
    fi

    if command -v termux-reload-settings >/dev/null 2>&1; then
        termux-reload-settings >/dev/null 2>&1 || true
    fi
    ok "allow-external-apps enabled"
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
    info "pkg output ↓"
    if ! pkg install -y "${missing[@]}"; then
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
    proot-distro login "$DISTRO_ALIAS" -- /bin/sh -lc "$1"
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
    current="$(proot_exec "opencode --version 2>/dev/null || true" | tr -d '\r')"

    if [[ "$current" == "$OPENCODE_VERSION" ]]; then
        skip "OpenCode $OPENCODE_VERSION already installed"
        return
    fi

    if [[ -n "$current" ]]; then
        info "Upgrading OpenCode $current -> $OPENCODE_VERSION"
    fi

    proot_exec "rm -f /usr/local/bin/opencode; curl -fsSL https://opencode.ai/install | OPENCODE_VERSION=$OPENCODE_VERSION bash" || die "Failed to install OpenCode"

    local installed
    installed="$(proot_exec "opencode --version" | tr -d '\r')"
    [[ "$installed" == "$OPENCODE_VERSION" ]] || die "Version mismatch: got $installed, expected $OPENCODE_VERSION"
    ok "OpenCode $OPENCODE_VERSION installed"
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

if [[ -f "$ENV_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
fi

exec proot-distro login "$DISTRO_ALIAS" -- /bin/sh -lc "export OPENCODE_SERVER_PASSWORD=\"${OPENCODE_SERVER_PASSWORD:-}\"; exec opencode serve --hostname $HOST --port $PORT"
EOF

    cat > "$INSTALL_DIR/stop.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

proot-distro login opencode-debian -- /bin/sh -lc 'pkill -f "opencode serve" >/dev/null 2>&1 || true'
pkill -f "proot-distro login opencode-debian" >/dev/null 2>&1 || true
EOF

    cat > "$INSTALL_DIR/status.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

if curl -fsS "http://127.0.0.1:4096/global/health" | grep -q '"healthy":true'; then
    echo "running"
else
    echo "stopped"
fi
EOF

    if [[ ! -f "$INSTALL_DIR/env" ]]; then
        cat > "$INSTALL_DIR/env" <<'EOF'
# Optional auth for local OpenCode server.
# Set a value and restart local server.
OPENCODE_SERVER_PASSWORD=
EOF
    fi

    chmod 700 "$INSTALL_DIR/start.sh" "$INSTALL_DIR/stop.sh" "$INSTALL_DIR/status.sh"
    chmod 600 "$INSTALL_DIR/env"
    ok "Runtime scripts written"
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
    if proot_exec "command -v opencode >/dev/null 2>&1"; then
        local ver
        ver="$(proot_exec "opencode --version" | tr -d '\r')"
        if [[ "$ver" == "$OPENCODE_VERSION" ]]; then
            printf "  %-28s ${GREEN}${ver}${RESET}\n" "OpenCode"
        else
            printf "  %-28s ${YELLOW}${ver}${RESET} (expected ${OPENCODE_VERSION})\n" "OpenCode"
        fi
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
    local cmd="${1:-install}"
    case "$cmd" in
        install) install_all ;;
        doctor)  doctor ;;
        start)   start_server ;;
        stop)    stop_server ;;
        status)  status_server ;;
        *) die "Unknown command: $cmd (expected: install|doctor|start|stop|status)" ;;
    esac
}

main "$@"
