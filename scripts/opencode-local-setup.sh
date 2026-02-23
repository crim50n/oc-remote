#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO_ALIAS="alpine"
OPENCODE_VERSION="1.2.10"
LOCAL_PORT="4096"
LOCAL_HOST="127.0.0.1"
INSTALL_DIR="$HOME/opencode-local"
TERMUX_PROPERTIES_DIR="$HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_PROPERTIES_DIR/termux.properties"

# Alpine minirootfs from official CDN (fast global mirrors).
# proot-distro's default CDN (easycli.sh) is often extremely slow.
ALPINE_ROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.3-aarch64.tar.gz"
ALPINE_ROOTFS_SHA256="f219bb9d65febed9046951b19f2b893b331315740af32c47e39b38fcca4be543"
TERMUX_REQUIRED_PACKAGES=(proot-distro curl jq)

log() {
    printf "[opencode-local] %s\n" "$*"
}

warn() {
    printf "[opencode-local][warn] %s\n" "$*" >&2
}

die() {
    printf "[opencode-local][error] %s\n" "$*" >&2
    exit 1
}

require_termux() {
    [[ -n "${TERMUX_VERSION:-}" ]] || die "Run this script inside Termux"
    [[ -d "$PREFIX" ]] || die "Termux PREFIX not found"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

check_arch() {
    local arch
    arch="$(uname -m)"
    [[ "$arch" == "aarch64" ]] || die "Unsupported architecture: $arch (expected aarch64)"
}

check_storage() {
    local avail_kb
    avail_kb="$(df -Pk "$HOME" | awk 'NR==2 {print $4}')"
    [[ -n "$avail_kb" ]] || die "Unable to determine free disk space"
    if (( avail_kb < 600000 )); then
        die "Not enough free space. Need at least ~600MB"
    fi
}

check_network() {
    if ! curl --connect-timeout 10 --max-time 15 -fsSL "https://opencode.ai" >/dev/null 2>&1; then
        die "Network check failed: cannot reach https://opencode.ai"
    fi
}

ensure_termux_properties() {
    mkdir -p "$TERMUX_PROPERTIES_DIR"
    touch "$TERMUX_PROPERTIES_FILE"

    if grep -Eq '^\s*allow-external-apps\s*=\s*true\s*$' "$TERMUX_PROPERTIES_FILE"; then
        log "allow-external-apps already enabled"
        return
    fi

    if grep -Eq '^\s*allow-external-apps\s*=' "$TERMUX_PROPERTIES_FILE"; then
        sed -i 's/^\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
    else
        printf "\nallow-external-apps = true\n" >> "$TERMUX_PROPERTIES_FILE"
    fi

    log "Enabled allow-external-apps in termux.properties"

    # Apply immediately — no manual Termux restart needed
    if command -v termux-reload-settings >/dev/null 2>&1; then
        termux-reload-settings
        log "Settings reloaded"
    else
        warn "termux-reload-settings not found; restart Termux manually to apply"
    fi
}

ensure_termux_packages() {
    local missing_packages=()

    for pkg_name in "${TERMUX_REQUIRED_PACKAGES[@]}"; do
        if ! dpkg -s "$pkg_name" >/dev/null 2>&1; then
            missing_packages+=("$pkg_name")
        fi
    done

    if (( ${#missing_packages[@]} == 0 )); then
        log "Required Termux packages already installed"
        return
    fi

    log "Installing missing Termux packages: ${missing_packages[*]}"
    pkg update -y >/dev/null
    pkg install -y "${missing_packages[@]}" >/dev/null
}

ensure_alpine_installed() {
    local installed_rootfs_dir="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_ALIAS"
    if [[ -d "$installed_rootfs_dir" ]] && [[ -n "$(ls -A "$installed_rootfs_dir" 2>/dev/null)" ]]; then
        log "Alpine is already installed"
    else
        log "Installing Alpine distro (from official Alpine CDN)"
        # Use official Alpine CDN instead of proot-distro's default easycli.sh
        # which is often extremely slow or unreachable.
        # strip=0 because Alpine minirootfs has files at root level.
        # Some proot-distro versions run with nounset and reference
        # PD_OVERRIDE_TARBALL_SHA256 unconditionally, so set it explicitly.
        env \
            PD_OVERRIDE_TARBALL_URL="$ALPINE_ROOTFS_URL" \
            PD_OVERRIDE_TARBALL_SHA256="$ALPINE_ROOTFS_SHA256" \
            PD_OVERRIDE_TARBALL_STRIP_OPT=0 \
            proot-distro install "$DISTRO_ALIAS"
        log "Alpine installed successfully"
    fi
}

proot_exec() {
    local cmd="$1"
    proot-distro login "$DISTRO_ALIAS" -- /bin/sh -lc "$cmd"
}

setup_alpine_packages() {
    log "Updating Alpine packages"
    proot_exec "apk update && apk upgrade"
    log "Installing Alpine dependencies"
    proot_exec "apk add --no-progress bash curl git ripgrep tmux procps libstdc++ libgcc"
}

install_opencode_binary() {
    log "Installing OpenCode $OPENCODE_VERSION inside Alpine"
    proot_exec "set -e; current=\$(opencode --version 2>/dev/null || true); if [ \"\$current\" = \"$OPENCODE_VERSION\" ]; then echo 'OpenCode already pinned to $OPENCODE_VERSION'; exit 0; fi; rm -f /usr/local/bin/opencode; curl -fsSL https://opencode.ai/install | OPENCODE_VERSION=$OPENCODE_VERSION bash"
    local version
    version="$(proot_exec "opencode --version" | tr -d '\r')"
    [[ "$version" == "$OPENCODE_VERSION" ]] || die "OpenCode version mismatch: got '$version', expected '$OPENCODE_VERSION'"
}

write_runtime_scripts() {
    mkdir -p "$INSTALL_DIR"

    cat > "$INSTALL_DIR/start.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DISTRO_ALIAS="alpine"
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

proot-distro login alpine -- /bin/sh -lc 'pkill -f "opencode serve" >/dev/null 2>&1 || true'
pkill -f "proot-distro login alpine" >/dev/null 2>&1 || true
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
}

doctor() {
    require_termux
    check_arch
    require_command curl
    require_command proot-distro

    log "Doctor report"
    log "- Termux version: ${TERMUX_VERSION:-unknown}"
    log "- Architecture: $(uname -m)"

    local installed_rootfs_dir="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_ALIAS"
    if [[ -d "$installed_rootfs_dir" ]] && [[ -n "$(ls -A "$installed_rootfs_dir" 2>/dev/null)" ]]; then
        log "- Alpine distro: installed"
    else
        warn "- Alpine distro: missing"
    fi

    if proot_exec "command -v opencode >/dev/null 2>&1"; then
        log "- OpenCode version: $(proot_exec "opencode --version" | tr -d '\r')"
    else
        warn "- OpenCode: missing"
    fi

    if [[ -f "$INSTALL_DIR/start.sh" ]]; then
        log "- Runtime scripts: present in $INSTALL_DIR"
    else
        warn "- Runtime scripts: missing"
    fi

    if grep -Eq '^\s*allow-external-apps\s*=\s*true\s*$' "$TERMUX_PROPERTIES_FILE" 2>/dev/null; then
        log "- allow-external-apps: enabled"
    else
        warn "- allow-external-apps: disabled"
    fi

    if curl -fsS "http://${LOCAL_HOST}:${LOCAL_PORT}/global/health" | grep -q '"healthy":true'; then
        log "- Server health: running"
    else
        warn "- Server health: not running"
    fi
}

install_all() {
    require_termux
    check_arch
    check_storage
    require_command curl
    check_network

    # Enable allow-external-apps FIRST so OC Remote can
    # manage the server right after installation completes.
    ensure_termux_properties

    ensure_termux_packages
    ensure_alpine_installed
    setup_alpine_packages
    install_opencode_binary
    write_runtime_scripts

    log ""
    log "============================="
    log "  Installation complete!"
    log "============================="
    log ""
    log "Return to OC Remote and tap 'Start Local'."
}

start_server() {
    require_termux
    [[ -x "$INSTALL_DIR/start.sh" ]] || die "Missing start script. Run install first"
    "$INSTALL_DIR/start.sh"
}

stop_server() {
    require_termux
    [[ -x "$INSTALL_DIR/stop.sh" ]] || die "Missing stop script. Run install first"
    "$INSTALL_DIR/stop.sh"
}

status_server() {
    require_termux
    [[ -x "$INSTALL_DIR/status.sh" ]] || die "Missing status script. Run install first"
    "$INSTALL_DIR/status.sh"
}

main() {
    local cmd="${1:-install}"
    case "$cmd" in
        install)
            install_all
            ;;
        doctor)
            doctor
            ;;
        start)
            start_server
            ;;
        stop)
            stop_server
            ;;
        status)
            status_server
            ;;
        *)
            die "Unknown command: $cmd (expected: install|doctor|start|stop|status)"
            ;;
    esac
}

main "$@"
