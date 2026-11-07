#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
umask 077

# Keep this file self-contained: the public curl | bash entry point has no siblings.
DISTRO_ALIAS="opencode-debian"
DISTRO_BASE="debian"
INSTALL_DIR="$HOME/opencode-local"
GUEST_BASE="/opt/oc-remote"
GUEST_HOME="/root"
CONTROL_DIR="/oc-remote-control"
SCRIPT_SOURCE="${BASH_SOURCE[0]:-}"
LOCAL_PORT=4096
SETUP_URL="https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-runtime.sh"
DEBIAN_ROOTFS_URL="https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
DEBIAN_ROOTFS_SHA256="3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
WAKE_LOCK_HELD=0
LOCK_HELD=0
STAGE=""
START_REGISTRATION_POLLS=100
START_REQUEST_ID=""
START_JSON=0
START_RESULT_EMITTED=0
START_ATTEMPT=""
START_ACKNOWLEDGED=0
START_RESULT=failed

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
usage_error() { printf 'Error: %s\n' "$*" >&2; exit 2; }
info() { printf '%s\n' "$*" >&2; }
quote() { printf '%q' "$1"; }

require_termux() {
    [[ -n "${TERMUX_VERSION:-}" && -d "${PREFIX:-}" ]] || die "Run this command inside Termux"
}

cleanup() {
    if [[ -n "$START_ATTEMPT" ]] && (( START_ACKNOWLEDGED == 0 )); then
        # Do not release the lifecycle lock while a pending guest can still register.
        # A blocked filesystem/gate delays cleanup rather than declaring cancellation safe.
        until revoke_attempt "$START_REQUEST_ID"; do
            info "Waiting to revoke launch $START_REQUEST_ID safely"
            sleep 0.1
        done
    fi
    if (( START_JSON && START_RESULT_EMITTED == 0 )); then emit_start_result "$START_RESULT" null; fi
    unlock_runtime
    # Unpublished prefixes are intentionally retained for diagnosis; never remove profiles.
    [[ -z "$STAGE" ]] || info "Unpublished candidate retained: $STAGE"
    if (( WAKE_LOCK_HELD )) && command -v termux-wake-unlock >/dev/null; then
        termux-wake-unlock >/dev/null 2>&1 || true
    fi
}

lock_runtime() {
    mkdir -p "$INSTALL_DIR"
    command -v flock >/dev/null || die "flock is missing; install Termux util-linux first"
    exec 9>"$INSTALL_DIR/lifecycle.lock"
    if ! flock -n 9; then
        info "Another local runtime operation is in progress"
        exit 4
    fi
    LOCK_HELD=1
}

unlock_runtime() {
    if (( LOCK_HELD )); then
        flock -u 9
        exec 9>&-
        LOCK_HELD=0
    fi
}

proot_exec() {
    proot-distro login --bind "$INSTALL_DIR:$CONTROL_DIR" "$DISTRO_ALIAS" -- /bin/bash --noprofile --norc -c "$1"
}

rootfs_present() {
    [[ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO_ALIAS" ||
       -d "$PREFIX/var/lib/proot-distro/containers/$DISTRO_ALIAS/rootfs" ]]
}

prepare_environment() {
    [[ "$(uname -m)" == aarch64 ]] || die "Only aarch64 Termux is supported"
    local available
    available="$(df -Pk "$HOME" | awk 'NR==2 {print $4}')"
    [[ "$available" =~ ^[0-9]+$ && "$available" -ge 600000 ]] || die "At least 600 MB free space is required"
    if command -v termux-wake-lock >/dev/null; then
        if termux-wake-lock; then WAKE_LOCK_HELD=1; fi
    fi
    local missing=() package
    for package in proot-distro curl jq util-linux; do
        dpkg -s "$package" >/dev/null 2>&1 || missing+=("$package")
    done
    if (( ${#missing[@]} )); then
        apt-get update -yq
        apt-get install -yq "${missing[@]}"
    fi
    # Existing unhealthy distributions must never be removed by installation/maintenance.
    if rootfs_present; then
        proot_exec true || die "Existing Debian is unhealthy; preserve it and repair it manually"
    else
        env PD_OVERRIDE_TARBALL_URL="$DEBIAN_ROOTFS_URL" \
            PD_OVERRIDE_TARBALL_SHA256="$DEBIAN_ROOTFS_SHA256" \
            proot-distro install --override-alias "$DISTRO_ALIAS" "$DISTRO_BASE"
    fi
    ensure_guest_tools
    mkdir -p "$HOME/.termux"
    local properties="$HOME/.termux/termux.properties"
    touch "$properties"
    if ! grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*$' "$properties"; then
        if grep -Eq '^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=' "$properties"; then
            sed -i 's/^[[:space:]]*#\?[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$properties"
        else
            printf '\nallow-external-apps = true\n' >>"$properties"
        fi
        info "Force-stop and reopen Termux once to apply allow-external-apps"
    fi
}

ensure_guest_tools() {
    proot_exec 'set -e
        if command -v npm >/dev/null && command -v node >/dev/null && command -v ss >/dev/null &&
            command -v setsid >/dev/null && command -v timeout >/dev/null &&
            dpkg -s ca-certificates curl git ripgrep tmux procps bash >/dev/null 2>&1; then exit; fi
        export DEBIAN_FRONTEND=noninteractive
        apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=20 update -qq
        apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=20 install -y -qq --no-install-recommends \
            ca-certificates curl git ripgrep tmux procps bash nodejs npm util-linux iproute2'
}

# Accept exactly one stable version line, optionally prefixed by the product name/v.
# Opaque banners, prereleases, multiple version lines and unsupported majors fail closed.
parse_version() {
    local value="${1//$'\r'/}"
    if [[ "$value" =~ ^(opencode[[:space:]]+)?v?((1|2)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*))$ ]]; then
        printf '%s\n' "${BASH_REMATCH[2]}"
    else
        return 1
    fi
}

version_at_least() {
    # Stable semver only. Decimal length/lexical comparison avoids integer overflow,
    # locale/version-sort surprises, and any implicit ordering of prereleases.
    local left right i LC_ALL=C
    left="$(parse_version "$1")" && right="$(parse_version "$2")" || return 1
    local -a lhs rhs
    IFS=. read -ra lhs <<<"$left"
    IFS=. read -ra rhs <<<"$right"
    for i in 0 1 2; do
        if (( ${#lhs[$i]} != ${#rhs[$i]} )); then
            (( ${#lhs[$i]} > ${#rhs[$i]} )); return
        fi
        [[ "${lhs[$i]}" == "${rhs[$i]}" ]] || { [[ "${lhs[$i]}" > "${rhs[$i]}" ]]; return; }
    done
}

profile_version_floor() {
    local profile="$1" floor="" value metadata
    if [[ -f "$INSTALL_DIR/profile-versions/$profile" ]]; then
        value="$(cat "$INSTALL_DIR/profile-versions/$profile")"
        floor="$(parse_version "$value")" || die "Unrecognized version highwater for $profile"
    fi
    # Also recover floors from installations made before highwater tracking existed.
    # Unpublished candidates have never used the profile and cannot establish a floor.
    for metadata in "$INSTALL_DIR/active/metadata.json" "$INSTALL_DIR/previous/metadata.json"; do
        [[ -f "$metadata" ]] || continue
        value="$(jq -r --arg profile "$profile" 'if .profile == $profile then .version else empty end' "$metadata")" \
            || die "Unreadable retained profile descriptor"
        [[ -n "$value" ]] || continue
        value="$(parse_version "$value")" || die "Unrecognized retained profile version"
        if [[ -z "$floor" ]] || version_at_least "$value" "$floor"; then floor="$value"; fi
    done
    if [[ "${PROFILE:-}" == "$profile" && "${INTEGRITY:-}" == ok ]]; then
        if [[ -z "$floor" ]] || version_at_least "$VERSION" "$floor"; then floor="$VERSION"; fi
    fi
    printf '%s\n' "$floor"
}

require_profile_version() {
    (( LOCK_HELD == 1 )) || die "Profile version validation requires lifecycle lock"
    local floor
    floor="$(profile_version_floor "$1")"
    [[ -z "$floor" ]] || version_at_least "$2" "$floor" \
        || die "Refusing profile downgrade for $1 from $floor to $2"
}

record_profile_version() {
    (( LOCK_HELD == 1 )) || die "Profile highwater update requires lifecycle lock"
    local value="$2" floor
    floor="$(profile_version_floor "$1")"
    if [[ -n "$floor" ]] && ! version_at_least "$value" "$floor"; then value="$floor"; fi
    mkdir -p "$INSTALL_DIR/profile-versions"
    printf '%s\n' "$value" >"$INSTALL_DIR/profile-versions/.$1.$$"
    mv -f "$INSTALL_DIR/profile-versions/.$1.$$" "$INSTALL_DIR/profile-versions/$1"
}

binary_version() {
    local raw
    # Version probes must not initialize/migrate the user's profile.
    raw="$(proot_exec "set -e; p=\$(mktemp -d); trap 'rm -rf \"\$p\"' EXIT
        export HOME=\"\$p\" XDG_DATA_HOME=\"\$p/data\" XDG_CONFIG_HOME=\"\$p/config\"
        export XDG_STATE_HOME=\"\$p/state\" XDG_CACHE_HOME=\"\$p/cache\"
        export TMPDIR=\"\$p/tmp\"; mkdir -p \"\$TMPDIR\"
        unset OPENCODE_CONFIG OPENCODE_CONFIG_DIR OPENCODE_CONFIG_CONTENT
        unset OPENCODE_BIN_PATH OPENCODE_TEST_HOME
        export OPENCODE_DISABLE_AUTOUPDATE=true
        cd \"\$p\"; timeout 20 $(quote "$1") --version" 2>/dev/null)" || return 1
    parse_version "$raw"
}

# Snapshot variables are never sourced from a file. Only validated JSON fields enter commands.
detect_installation() {
    CHANNEL="" VERSION="" BINARY="" PROFILE="" SOURCE=missing INTEGRITY=missing
    if [[ -L "$INSTALL_DIR/active" || -e "$INSTALL_DIR/active" ]]; then
        SOURCE=managed INTEGRITY=unknown
        local target metadata
        target="$(readlink "$INSTALL_DIR/active" 2>/dev/null || true)"
        [[ "$target" =~ ^releases/[A-Za-z0-9._-]+$ ]] || return 0
        metadata="$INSTALL_DIR/$target/metadata.json"
        jq -e '(.channel == "v1" or .channel == "v2") and
            (.profile == .channel or .profile == ("legacy-" + .channel) or
             (.channel == "v2" and (.profile == "v1" or .profile == "legacy-v1"))) and
            (.executable | type == "string") and (.version | type == "string")' "$metadata" >/dev/null 2>&1 || return 0
        CHANNEL="$(jq -r .channel "$metadata")"
        PROFILE="$(jq -r .profile "$metadata")"
        BINARY="$(jq -r .executable "$metadata")"
        local expected observed
        expected="$(jq -r .version "$metadata")"
        if ! VERSION="$(parse_version "$expected")" || [[ "v${VERSION%%.*}" != "$CHANNEL" ]]; then
            VERSION="" INTEGRITY=mismatch
            return 0
        fi
        observed="$(binary_version "$BINARY" || true)"
        if [[ "$observed" == "$VERSION" ]]; then INTEGRITY=ok; else INTEGRITY=mismatch; fi
        return 0
    fi
    # Existing environments stay present even when proot or executable discovery fails.
    # Missing is reserved for an actually absent rootfs with no legacy launcher evidence.
    if ! rootfs_present; then
        if [[ -e "$PREFIX/var/lib/proot-distro/containers/$DISTRO_ALIAS" ||
              -L "$PREFIX/var/lib/proot-distro/containers/$DISTRO_ALIAS" ]]; then
            SOURCE=unknown INTEGRITY=incomplete
        elif [[ -e "$INSTALL_DIR/start.sh" || -L "$INSTALL_DIR/start.sh" ]] &&
            [[ "$(readlink "$INSTALL_DIR/start.sh" 2>/dev/null || true)" != runtime-start.sh ]]; then
            SOURCE=unknown INTEGRITY=unknown
        fi
        return 0
    fi
    SOURCE=unknown INTEGRITY=unknown
    command -v proot-distro >/dev/null || { INTEGRITY=probe-failed; return 0; }
    # Honor the same executable precedence as the old start.sh; never silently fall through
    # an unrecognized first executable to a different major later in PATH.
    BINARY="$(proot_exec "for p in $(quote "$GUEST_HOME/.opencode/bin/opencode") \
        $(quote "$GUEST_HOME/.local/bin/opencode") /usr/local/bin/opencode; do
        if [[ -x \"\$p\" ]]; then printf '%s\\n' \"\$p\"; exit; fi; done
        command -v opencode || true" 2>/dev/null)" || { BINARY=""; INTEGRITY=probe-failed; return 0; }
    [[ -n "$BINARY" ]] || { INTEGRITY=incomplete; return 0; }
    if VERSION="$(binary_version "$BINARY")"; then
        CHANNEL="v${VERSION%%.*}"
        PROFILE="legacy-$CHANNEL"
        SOURCE=legacy INTEGRITY=ok
    fi
}

require_known_installation() {
    [[ "$INTEGRITY" == ok && -n "$CHANNEL" ]] || die "Installed version is missing, unknown or mismatched; inspect doctor before making a branch choice"
}

require_expected_channel() {
    # Android's earlier status snapshot is not authority for a later mutation.
    # Detection and this comparison must both happen while the lifecycle lock is held.
    (( LOCK_HELD == 1 )) || die "Expected-channel validation requires the lifecycle lock"
    require_known_installation
    [[ -z "$1" || "$CHANNEL" == "$1" ]] \
        || die "Expected channel $1, but installed channel is $CHANNEL; refresh status before retrying"
}

write_metadata() {
    local directory="$1" channel="$2" version="$3" binary="$4" profile="$5"
    mkdir -p "$directory"
    jq -n --arg channel "$channel" --arg version "$version" --arg executable "$binary" \
        --arg profile "$profile" '{channel:$channel,version:$version,executable:$executable,profile:$profile}' \
        >"$directory/metadata.json"
}

publish_pointer() {
    local name="$1" target="$2" temporary
    temporary="$INSTALL_DIR/.$name.$$"
    ln -s "$target" "$temporary"
    if ! mv -Tf "$temporary" "$INSTALL_DIR/$name"; then
        rm -f "$temporary"
        return 1
    fi
}

preserve_previous() {
    local old
    if [[ "$INTEGRITY" == ok ]]; then record_profile_version "$PROFILE" "$VERSION"; fi
    if [[ "$SOURCE" == managed ]]; then
        old="$(readlink "$INSTALL_DIR/active")"
    elif [[ "$INTEGRITY" == ok ]]; then
        old="releases/legacy-$(date +%s)-$$"
        write_metadata "$INSTALL_DIR/$old" "$CHANNEL" "$VERSION" "$BINARY" "$PROFILE"
    else
        return 0
    fi
    publish_pointer previous "$old"
}

choose_channel() {
    [[ -z "$REQUESTED_CHANNEL" ]] || return 0
    if ! { exec 8<>/dev/tty; } 2>/dev/null; then
        usage_error "Choose a major explicitly: install --channel v1|v2"
    fi
    printf 'Install OpenCode v1 or v2? [v1/v2]: ' >&8
    IFS= read -r REQUESTED_CHANNEL <&8 || REQUESTED_CHANNEL=""
    exec 8>&-
    [[ "$REQUESTED_CHANNEL" == v1 || "$REQUESTED_CHANNEL" == v2 ]] || usage_error "No major selected"
}

confirm_v2_upgrade() {
    info "One-way upgrade to V2 using the current configuration and data. Returning to V1 or the previous configuration is not supported. V1-only clients/plugins may stop working."
    (( CONFIRMED )) && return 0
    local answer=""
    if { exec 8<>/dev/tty; } 2>/dev/null; then
        printf 'Upgrade this profile permanently to V2? [y/N]: ' >&8
        IFS= read -r answer <&8 || true
        exec 8>&-
    fi
    case "$answer" in y|Y|yes|YES) return 0 ;; esac
    info "Upgrade cancelled; use --yes only after explicit confirmation"
    exit 3
}

# Registration and revocation use the same gate in both host and guest. Attempt state is
# monotonic: only pending can register; revoked/failed/exited can never become running.
write_serve_script() {
    local temporary
    temporary="$(mktemp "$INSTALL_DIR/.serve.XXXXXX")"
    {
        printf '#!/bin/bash\nset -euo pipefail\numask 077\n'
        printf 'CONTROL=%q\nGUEST_HOME=%q\n' "$CONTROL_DIR" "$GUEST_HOME"
        cat <<'SERVE'
binary="$1"; profile="$2"; host="$3"; port="$4"; request="$5"
attempt="$CONTROL/attempts/$request"
exec 8>"$CONTROL/attempt-gate.lock"
flock -x 8
gate_held=1
[[ "$(cat "$attempt/state")" == pending ]] || exit 125
set_state() {
    printf '%s\n' "$1" >"$attempt/state.tmp.$$"
    mv -f "$attempt/state.tmp.$$" "$attempt/state"
}
finish() {
    local rc=$?
    trap '' TERM INT HUP
    # A failure after fork but before acknowledgement must not strand the child.
    if [[ -n "${child:-}" ]]; then
        kill -TERM -- "-$$" 2>/dev/null || true
        wait "$child" 2>/dev/null || true
    fi
    if (( gate_held == 0 )); then flock -x 8; fi
    case "$(cat "$attempt/state")" in
        pending) set_state failed ;;
        running) set_state exited ;;
    esac
    if [[ "$(readlink "$CONTROL/owner" 2>/dev/null || true)" == "attempts/$request/owner" ]]; then
        rm -f "$CONTROL/owner"
    fi
    rm -f "$attempt/env"
    flock -u 8
    exit "$rc"
}
terminate() {
    trap '' TERM INT HUP
    exit 143
}
trap finish EXIT
trap terminate TERM INT HUP
parent_alive() {
    local pid ticks boot hash stat
    read -r pid ticks boot hash <"$attempt/parent"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
    stat="$(cat "/proc/$pid/stat" 2>/dev/null)" || return 1
    stat="${stat##*) }"
    local -a parent_fields
    read -ra parent_fields <<<"$stat"
    [[ "${parent_fields[0]}" != Z && "${parent_fields[19]}" == "$ticks" &&
        "$(cat /proc/sys/kernel/random/boot_id)" == "$boot" &&
        "$(sha256sum "/proc/$pid/cmdline" | cut -d' ' -f1)" == "$hash" ]]
}
# Never replace an owner published by another attempt, even if startup was delayed.
[[ ! -e "$CONTROL/owner" && ! -L "$CONTROL/owner" ]] || exit 1
parent_alive || exit 1
unset OPENCODE_PASSWORD
source "$attempt/env"
rm -f "$attempt/env"
export HOME="$GUEST_HOME"
unset XDG_DATA_HOME XDG_CONFIG_HOME XDG_STATE_HOME XDG_CACHE_HOME
unset OPENCODE_CONFIG OPENCODE_CONFIG_DIR OPENCODE_CONFIG_CONTENT
unset OPENCODE_BIN_PATH OPENCODE_TEST_HOME
export OPENCODE_DISABLE_AUTOUPDATE=true
if [[ "$profile" != legacy-* ]]; then
    export HOME="$GUEST_HOME/.oc-remote/profiles/$profile"
    export XDG_DATA_HOME="$HOME/.local/share" XDG_CONFIG_HOME="$HOME/.config"
    export XDG_STATE_HOME="$HOME/.local/state" XDG_CACHE_HOME="$HOME/.cache"
fi
mkdir -p "$HOME" "${XDG_DATA_HOME:-$HOME/.local/share}" "${XDG_CONFIG_HOME:-$HOME/.config}" \
    "${XDG_STATE_HOME:-$HOME/.local/state}" "${XDG_CACHE_HOME:-$HOME/.cache}"
export TMPDIR="${XDG_CACHE_HOME:-$HOME/.cache}/tmp"
mkdir -p "$TMPDIR"
cd "$HOME"
stat="$(cat "/proc/$$/stat")"; stat="${stat##*) }"
read -ra fields <<<"$stat"
[[ "${fields[2]}" == "$$" && "${fields[3]}" == "$$" ]] || exit 1
boot="$(cat /proc/sys/kernel/random/boot_id)"
hash="$(sha256sum "/proc/$$/cmdline" | cut -d' ' -f1)"
printf '%s %s %s %s\n' "$$" "${fields[19]}" "$boot" "$hash" >"$attempt/owner"
parent_alive || exit 1
# No long-lived child may inherit either gate or lifecycle lock descriptor.
(exec 8>&- 9>&-; exec "$binary" serve --hostname "$host" --port "$port") &
child=$!
ln -s "attempts/$request/owner" "$CONTROL/owner"
set_state running
exec 9>&-
flock -u 8
gate_held=0
# A killed caller cannot leave an unacknowledged launch running. Once the caller
# commits acknowledgement, intentional detached operation no longer needs that parent.
for (( handshake=0; handshake<100; handshake++ )); do
    [[ ! -f "$attempt/acknowledged" ]] || break
    parent_alive || exit 1
    [[ "$(cat "$attempt/state")" == running ]] || exit 1
    sleep 0.05
done
[[ -f "$attempt/acknowledged" ]] || exit 1
wait "$child"
SERVE
    } >"$temporary"
    chmod 700 "$temporary"
    mv -f "$temporary" "$INSTALL_DIR/serve.sh"
}

guest_process_state() {
    # Output is one of owned/stopped/unknown. Android can deny ss/netlink access;
    # in that case prove port availability with a short, exclusive dual-stack bind.
    proot_exec "CONTROL=$(quote "$CONTROL_DIR"); PORT=$LOCAL_PORT; $(cat <<'STATE'
set -euo pipefail
if [[ -f "$CONTROL/owner" ]]; then
    read -r pid start boot hash extra <"$CONTROL/owner" || { echo unknown; exit; }
    if [[ ! "$pid" =~ ^[1-9][0-9]*$ || ! "$start" =~ ^[0-9]+$ || -n "$extra" ]]; then
        echo unknown; exit
    fi
    if [[ -e "/proc/$pid" ]]; then
        stat="$(cat "/proc/$pid/stat" 2>/dev/null)" || { echo unknown; exit; }
        stat="${stat##*) }"; read -ra fields <<<"$stat"
        if [[ "${fields[0]}" != Z && "${fields[19]}" == "$start" && "${fields[2]}" == "$pid" && "${fields[3]}" == "$pid" &&
            "$(cat /proc/sys/kernel/random/boot_id)" == "$boot" &&
            "$(sha256sum "/proc/$pid/cmdline" | cut -d' ' -f1)" == "$hash" ]]; then
            echo owned; exit
        fi
        # PID reuse or an identity change must never authorize a signal.
        if [[ "${fields[0]}" != Z ]]; then echo unknown; exit; fi
    fi
fi
errors="$(mktemp)"
if ! listeners="$(ss -H -ltn "sport = :$PORT" 2>"$errors")" || [[ -s "$errors" ]]; then
    rm -f "$errors"
    PORT="$PORT" timeout 3 node --eval '
        const net = require("node:net");
        const finish = (state) => { console.log(state); process.exit(0); };
        setTimeout(() => finish("unknown"), 1500).unref();
        function probe(host) {
            const socket = net.createServer();
            socket.once("error", (error) => {
                if (host === "::" && ["EAFNOSUPPORT", "EADDRNOTAVAIL"].includes(error.code)) {
                    probe("0.0.0.0");
                } else finish("unknown");
            });
            socket.listen({host, port: Number(process.env.PORT), exclusive: true, ipv6Only: false},
                () => socket.close(() => finish("stopped")));
        }
        probe("::");
    ' 2>/dev/null || echo unknown
    exit
fi
rm -f "$errors"
if [[ -n "$listeners" ]]; then echo unknown; else echo stopped; fi
STATE
)"
}

process_state() {
    if ! command -v proot-distro >/dev/null || ! rootfs_present; then
        printf 'stopped\n'
        return
    fi
    local state
    state="$(guest_process_state 2>/dev/null)" || state=unknown
    case "$state" in owned|stopped|unknown) printf '%s\n' "$state" ;; *) printf 'unknown\n' ;; esac
}

stop_owned_server() {
    local expected_request="${1:-}" state
    state="$(process_state)"
    [[ "$state" != unknown ]] || die "Unverified process/listener on the local runtime; refusing to signal it"
    [[ "$state" == owned ]] || return 0
    local target
    target="$(readlink "$INSTALL_DIR/owner" 2>/dev/null || true)"
    [[ -z "$expected_request" || "$target" == "attempts/$expected_request/owner" ]] \
        || die "Owned launch changed; refusing to stop another request"
    if [[ "$target" =~ ^attempts/([A-Za-z0-9][A-Za-z0-9_-]{0,63})/owner$ ]]; then
        revoke_attempt "${BASH_REMATCH[1]}"
        return
    fi
    # Compatibility with verified owner records written by the previous supervisor.
    signal_owner_record "$CONTROL_DIR/owner"
    wait_server_stopped
}

signal_owner_record() {
    # Revalidate immediately before signaling, inside the same guest invocation.
    # The actual signal command repeats all ownership checks (no name-based kills).
    proot_exec "record=$(quote "$1"); $(cat <<'STOP'
set -euo pipefail
read -r pid start boot hash extra <"$record"
[[ "$pid" =~ ^[1-9][0-9]*$ && "$start" =~ ^[0-9]+$ && -z "$extra" ]]
[[ -e "/proc/$pid" ]] || exit 0
stat="$(cat "/proc/$pid/stat")"; stat="${stat##*) }"; read -ra fields <<<"$stat"
[[ "${fields[0]}" != Z ]] || exit 0
[[ "${fields[0]}" != Z && "${fields[19]}" == "$start" && "${fields[2]}" == "$pid" && "${fields[3]}" == "$pid" ]]
[[ "$(cat /proc/sys/kernel/random/boot_id)" == "$boot" ]]
[[ "$(sha256sum "/proc/$pid/cmdline" | cut -d' ' -f1)" == "$hash" ]]
kill -TERM -- "-$pid"
STOP
)"
}

wait_server_stopped() {
    local attempt state
    local attempt
    for attempt in {1..50}; do
        state="$(process_state)"
        [[ "$state" != stopped ]] || return 0
        sleep 0.1
    done
    die "Runtime did not stop cleanly; active installation was preserved"
}

revoke_attempt() {
    local id="$1" attempt="$INSTALL_DIR/attempts/$1" state signal=0 rc=0
    [[ "$id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ && -d "$attempt" ]] || return 1
    exec 8>"$INSTALL_DIR/attempt-gate.lock"
    flock -x 8
    state="$(cat "$attempt/state" 2>/dev/null || true)"
    if [[ "$state" != failed && "$state" != exited ]]; then
        # Commit terminal state under the gate before allowing a retry to take the lock.
        if ! { printf 'revoked\n' >"$attempt/state.tmp.$$" && mv -f "$attempt/state.tmp.$$" "$attempt/state"; }; then
            exec 8>&-; return 1
        fi
    fi
    rm -f "$attempt/env"
    if [[ -f "$attempt/owner" ]]; then
        signal=1
        signal_owner_record "$CONTROL_DIR/attempts/$id/owner" || rc=1
    fi
    flock -u 8
    exec 8>&-
    (( rc == 0 )) || return 1
    if (( signal )) && [[ "$(readlink "$INSTALL_DIR/owner" 2>/dev/null || true)" == "attempts/$id/owner" ]]; then
        wait_server_stopped
    fi
}

owned_launch_json() {
    local target id pid ticks boot hash extra context
    target="$(readlink "$INSTALL_DIR/owner" 2>/dev/null || true)"
    if [[ ! "$target" =~ ^attempts/([A-Za-z0-9][A-Za-z0-9_-]{0,63})/owner$ ]]; then printf 'null\n'; return; fi
    id="${BASH_REMATCH[1]}"
    [[ "$(process_state)" == owned ]] || { printf 'null\n'; return; }
    read -r pid ticks boot hash extra <"$INSTALL_DIR/$target" || { printf 'null\n'; return; }
    [[ "$pid" =~ ^[1-9][0-9]*$ && "$ticks" =~ ^[0-9]+$ && -z "$extra" ]] || { printf 'null\n'; return; }
    # Owner slot cannot be mistaken for a different attempt during a concurrent cleanup.
    [[ "$(readlink "$INSTALL_DIR/owner" 2>/dev/null || true)" == "$target" ]] || { printf 'null\n'; return; }
    context="$INSTALL_DIR/attempts/$id/metadata.json"
    jq -n -c --arg id "$id" --argjson pid "$pid" --arg ticks "$ticks" --arg boot "$boot" \
        --arg hash "$hash" --arg phase "$(cat "$INSTALL_DIR/attempts/$id/state")" --slurpfile context "$context" '
        {requestId:$id,pid:$pid,startTicks:$ticks,bootId:$boot,cmdlineSha256:$hash,phase:$phase,
        channel:$context[0].channel,version:$context[0].version,profile:$context[0].profile}'
}

emit_start_result() {
    local result="$1" owner="$2"
    START_RESULT_EMITTED=1
    (( START_JSON )) || return 0
    jq -n -c --arg id "$START_REQUEST_ID" --arg result "$result" --arg channel "${CHANNEL:-}" \
        --arg version "${VERSION:-}" --arg profile "${PROFILE:-}" --argjson owner "$owner" '
        {schemaVersion:1,operation:"start",requestId:(if $id == "" then null else $id end),
        result:$result,channel:(if $channel == "" then null else $channel end),
        version:(if $version == "" then null else $version end),
        profile:(if $profile == "" then null else $profile end),owner:$owner}'
}

stage_release() {
    local channel="$1" profile="$2" spec id prefix version
    case "$channel" in v1) spec="opencode-ai@1" ;; v2) spec="@opencode/cli@2" ;; esac
    id="$channel-$(date +%s)-$$-$RANDOM"
    prefix="$GUEST_BASE/releases/$id"
    STAGE="$prefix"
    info "Staging $spec (stable releases within $channel)"
    # npm's major range excludes prereleases. Each attempt gets a fresh prefix and probe HOME.
    proot_exec "set -e; mkdir -p $(quote "$prefix"); p=\$(mktemp -d)
        trap 'rm -rf \"\$p\"' EXIT
        export HOME=\"\$p\" XDG_DATA_HOME=\"\$p/data\" XDG_CONFIG_HOME=\"\$p/config\"
        export XDG_STATE_HOME=\"\$p/state\" XDG_CACHE_HOME=\"\$p/cache\"
        cd \"\$p\"
        npm install --global --prefix $(quote "$prefix") --no-audit --no-fund $(quote "$spec")" >&2 \
        || die "Package installation failed; active binary/profile preserved"
    version="$(binary_version "$prefix/bin/opencode")" || die "Candidate did not report a stable V1/V2 version"
    [[ "v${version%%.*}" == "$channel" ]] || die "Wrong package major: expected $channel, received $version"
    require_profile_version "$profile" "$version"
    write_metadata "$INSTALL_DIR/releases/$id" "$channel" "$version" "$prefix/bin/opencode" "$profile"
    # Wrapper publication can fail (e.g. full storage). Do it before committing a new binary.
    write_runtime_scripts
    # Stage and validate before stopping a working server. Never restart with lost auth/proxy args.
    stop_owned_server
    preserve_previous
    publish_pointer active "releases/$id" || die "Cannot publish candidate; prior active pointer preserved"
    record_profile_version "$profile" "$version"
    STAGE=""
    info "Selected $channel $version ($profile). Server is stopped; run opencode-runtime start."
}

install_or_maintain() {
    local operation="$1"
    detect_installation
    case "$operation" in
        install)
            choose_channel
            if [[ "$SOURCE" != missing ]]; then
                require_known_installation
                [[ "$CHANNEL" == "$REQUESTED_CHANNEL" ]] || die "Existing $CHANNEL is retained. Only upgrade-v2 --expected-channel v1 --yes can change the major"
                require_profile_version "$PROFILE" "$VERSION"
                prepare_environment
                write_runtime_scripts
                if [[ "$SOURCE" == legacy ]]; then
                    preserve_previous
                    publish_pointer active "$(readlink "$INSTALL_DIR/previous")"
                fi
                info "Existing $CHANNEL $VERSION preserved ($PROFILE); use update for a newer same-major version"
                return
            fi
            prepare_environment
            # Environment preparation can expose an existing binary: recheck before replacement.
            detect_installation
            # This branch started with no rootfs/launcher evidence. A successfully probed,
            # still-empty rootfs just provisioned by prepare_environment is a fresh target.
            [[ "$SOURCE" == missing || ( "$SOURCE" == unknown && "$INTEGRITY" == incomplete ) ]] \
                || die "An existing or unverified binary was discovered during setup; run doctor before retrying"
            stage_release "$REQUESTED_CHANNEL" "$REQUESTED_CHANNEL"
            ;;
        update)
            require_expected_channel "$EXPECTED_CHANNEL"
            ensure_guest_tools
            stage_release "$CHANNEL" "$PROFILE"
            ;;
        upgrade-v2)
            [[ "$EXPECTED_CHANNEL" == v1 ]] || die "Upgrade requires the confirmed V1 installation"
            require_expected_channel v1
            confirm_v2_upgrade
            ensure_guest_tools
            # V2 migrates supported V1 data at first start. Keep the same HOME/XDG roots.
            stage_release v2 "$PROFILE"
            ;;
    esac
}

write_runtime_scripts() {
    mkdir -p "$INSTALL_DIR" "$PREFIX/bin"
    local source="$SCRIPT_SOURCE" temporary name
    [[ -f "$source" ]] || die "Save this setup script to a file before refreshing wrappers"
    if [[ ! "$source" -ef "$INSTALL_DIR/runtime.sh" ]]; then
        temporary="$(mktemp "$INSTALL_DIR/.setup.XXXXXX")"
        cp "$source" "$temporary"
        chmod 700 "$temporary"
        mv -f "$temporary" "$INSTALL_DIR/runtime.sh"
    fi
    for name in start stop status; do
        temporary="$(mktemp "$INSTALL_DIR/.$name.XXXXXX")"
        printf '#!/data/data/com.termux/files/usr/bin/bash\nexec bash "$HOME/opencode-local/runtime.sh" %s "$@"\n' "$name" >"$temporary"
        chmod 700 "$temporary"
        mv -f "$temporary" "$INSTALL_DIR/runtime-$name.sh"
        # Fresh installs remain usable by old OC Remote. Never replace an existing
        # legacy launcher: older applications keep their original control path.
        if [[ ! -e "$INSTALL_DIR/$name.sh" && ! -L "$INSTALL_DIR/$name.sh" ]]; then
            ln -s "runtime-$name.sh" "$INSTALL_DIR/$name.sh"
        fi
    done
    temporary="$(mktemp "$PREFIX/bin/.opencode-runtime.XXXXXX")"
    printf '#!/data/data/com.termux/files/usr/bin/bash\nexec bash "$HOME/opencode-local/runtime.sh" "$@"\n' >"$temporary"
    chmod 700 "$temporary"
    mv -f "$temporary" "$PREFIX/bin/opencode-runtime"
    if [[ ! -e "$PREFIX/bin/opencode-local" && ! -L "$PREFIX/bin/opencode-local" ]]; then
        ln -s opencode-runtime "$PREFIX/bin/opencode-local"
    fi
    if [[ ! -e "$INSTALL_DIR/env" ]]; then
        printf '# Optional server auth/proxy. Shell assignments; chmod 600.\nOPENCODE_SERVER_USERNAME=\nOPENCODE_SERVER_PASSWORD=\n' >"$INSTALL_DIR/env"
    fi
    chmod 600 "$INSTALL_DIR/env"
    write_serve_script
}

# Refresh only the new controller scripts. Keep legacy setup/start/stop/status intact.
refresh_runtime_controller() {
    bootstrap_stdin refresh-runtime
    lock_runtime
    write_runtime_scripts
}

# Explicit consent path for an old installation: prepare the new supervisor's tools
# without installing/upgrading OpenCode, replacing the rootfs, or changing its profile.
upgrade_runtime_controller() {
    bootstrap_stdin upgrade-control
    if ! command -v flock >/dev/null; then apt-get install -yq util-linux; fi
    lock_runtime
    if rootfs_present; then
        proot_exec true || die "Existing Debian is unhealthy; preserve it and repair it manually"
        proot_exec 'set -e
            if command -v node >/dev/null && command -v ss >/dev/null &&
                command -v setsid >/dev/null && command -v flock >/dev/null && command -v timeout >/dev/null; then exit; fi
            export DEBIAN_FRONTEND=noninteractive
            apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=20 update -qq
            apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=20 install -y -qq --no-install-recommends nodejs util-linux iproute2'
    fi
    write_runtime_scripts
}

start_server() {
    local host=127.0.0.1 proxy="" no_proxy_arg="" username="" password="" expected_channel=""
    local detached=0 request_seen=0
    while (( $# )); do
        case "$1" in
            --json) (( START_JSON == 0 )) || usage_error "Duplicate --json"; START_JSON=1; shift; continue ;;
            --detach) (( detached == 0 )) || usage_error "Duplicate --detach"; detached=1; shift; continue ;;
        esac
        [[ $# -ge 2 && -n "$2" ]] || usage_error "Missing value for $1"
        case "$1" in
            --hostname|--host) host="$2" ;;
            --proxy) proxy="$2" ;;
            --no-proxy) no_proxy_arg="$2" ;;
            --server-username) username="$2" ;;
            --server-password) password="$2" ;;
            --expected-channel)
                [[ -z "$expected_channel" ]] || usage_error "Only one --expected-channel is allowed"
                [[ "$2" == v1 || "$2" == v2 ]] || usage_error "Expected channel must be v1 or v2"
                expected_channel="$2"
                ;;
            --request-id)
                (( request_seen == 0 )) || usage_error "Duplicate --request-id"
                [[ "$2" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]] || usage_error "Invalid request ID"
                START_REQUEST_ID="$2"; request_seen=1
                ;;
            *) usage_error "Unknown start argument: $1" ;;
        esac
        shift 2
    done
    (( detached == 0 || START_JSON == 1 )) || usage_error "--detach requires --json"
    detect_installation
    require_expected_channel "$expected_channel"
    require_profile_version "$PROFILE" "$VERSION"
    [[ "$(process_state)" == stopped ]] || die "A runtime is already running or its ownership is unknown"
    [[ -n "$START_REQUEST_ID" ]] || START_REQUEST_ID="launch-$(date +%s)-$$-$RANDOM"
    mkdir -p "$INSTALL_DIR/attempts"
    mkdir "$INSTALL_DIR/attempts/$START_REQUEST_ID" || die "Request ID is already used; inspect status and use a fresh ID"
    START_ATTEMPT="$INSTALL_DIR/attempts/$START_REQUEST_ID"
    printf 'pending\n' >"$START_ATTEMPT/state"
    local cancel_requested=0
    trap 'cancel_requested=1' TERM INT HUP
    # Clear only a proven stopped stale slot under the same gate registration uses.
    exec 8>"$INSTALL_DIR/attempt-gate.lock"
    flock -x 8
    [[ "$(process_state)" == stopped ]] || die "Runtime ownership changed during startup"
    rm -f "$INSTALL_DIR/owner"
    flock -u 8
    exec 8>&-
    write_metadata "$START_ATTEMPT" "$CHANNEL" "$VERSION" "$BINARY" "$PROFILE"
    local parent_stat
    parent_stat="$(cat "/proc/$$/stat")"; parent_stat="${parent_stat##*) }"
    local -a parent_fields
    read -ra parent_fields <<<"$parent_stat"
    printf '%s %s %s %s\n' "$$" "${parent_fields[19]}" "$(cat /proc/sys/kernel/random/boot_id)" \
        "$(sha256sum "/proc/$$/cmdline" | cut -d' ' -f1)" >"$START_ATTEMPT/parent"
    record_profile_version "$PROFILE" "$VERSION"
    if [[ -f "$INSTALL_DIR/env" ]]; then
        # Existing user-controlled shell assignments remain supported.
        source "$INSTALL_DIR/env"
    fi
    [[ -z "$proxy" ]] || OPENCODE_PROXY_URL="$proxy"
    [[ -z "$password" ]] || OPENCODE_SERVER_PASSWORD="$password"
    [[ -z "$username" ]] || OPENCODE_SERVER_USERNAME="$username"
    case "$host" in
        localhost|127.0.0.1|::1) ;;
        *) [[ -n "${OPENCODE_SERVER_PASSWORD:-}" ]] || die "Non-loopback binding requires --server-password" ;;
    esac
    export OPENCODE_SERVER_PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"
    export OPENCODE_SERVER_USERNAME="${OPENCODE_SERVER_USERNAME:-opencode}"
    local defaults="localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
    export NO_PROXY="${no_proxy_arg:-${NO_PROXY:+$NO_PROXY,}$defaults}"
    export no_proxy="$NO_PROXY"
    if [[ -n "${OPENCODE_PROXY_URL:-}" ]]; then
        export HTTP_PROXY="$OPENCODE_PROXY_URL" HTTPS_PROXY="$OPENCODE_PROXY_URL" ALL_PROXY="$OPENCODE_PROXY_URL"
        export http_proxy="$OPENCODE_PROXY_URL" https_proxy="$OPENCODE_PROXY_URL" all_proxy="$OPENCODE_PROXY_URL"
    fi
    # proot-distro may sanitize the environment: carry values as quoted shell assignments,
    # never interpolate unescaped credentials/proxy strings into a command.
    local variable
    : >"$START_ATTEMPT/env"
    chmod 600 "$START_ATTEMPT/env"
    for variable in OPENCODE_SERVER_PASSWORD OPENCODE_SERVER_USERNAME NO_PROXY no_proxy \
        HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy; do
        [[ ! -v "$variable" ]] || printf 'export %s=%q\n' "$variable" "${!variable}" >>"$START_ATTEMPT/env"
    done
    write_serve_script
    cp "$INSTALL_DIR/serve.sh" "$START_ATTEMPT/serve.sh"
    local launcher state attempt owner rc=0
    # Keep the inherited lifecycle descriptor until the guest's terminal handshake.
    # Streams and script/env/record paths belong only to this attempt, also in detached mode.
    (trap - EXIT ERR TERM INT HUP
        proot_exec "exec setsid /bin/bash $(quote "$CONTROL_DIR/attempts/$START_REQUEST_ID/serve.sh") \
        $(quote "$BINARY") $(quote "$PROFILE") $(quote "$host") $LOCAL_PORT $(quote "$START_REQUEST_ID")") \
        </dev/null >"$START_ATTEMPT/serve.log" 2>&1 &
    launcher=$!
    owner=null
    for (( attempt=0; attempt<START_REGISTRATION_POLLS; attempt++ )); do
        if (( cancel_requested )); then START_RESULT=revoked; info "Startup cancelled"; exit 143; fi
        state="$(cat "$START_ATTEMPT/state")"
        if [[ "$state" == running ]]; then
            owner="$(owned_launch_json)"
            if jq -e --arg id "$START_REQUEST_ID" '.requestId == $id and .phase == "running"' <<<"$owner" >/dev/null; then break; fi
        elif [[ "$state" != pending ]]; then
            die "Runtime registration ended in $state"
        fi
        if ! kill -0 "$launcher" 2>/dev/null; then
            wait "$launcher" || rc=$?
            die "Runtime exited before ownership registration (exit $rc)"
        fi
        sleep 0.1
    done
    if ! jq -e --arg id "$START_REQUEST_ID" '.requestId == $id and .phase == "running"' <<<"$owner" >/dev/null; then
        START_RESULT=revoked
        die "Runtime ownership registration timed out; revoking exact attempt"
    fi
    if (( cancel_requested )); then START_RESULT=revoked; info "Startup cancelled"; exit 143; fi
    exec 8>"$INSTALL_DIR/attempt-gate.lock"
    flock -x 8
    [[ "$(cat "$START_ATTEMPT/state")" == running &&
        "$(readlink "$INSTALL_DIR/owner" 2>/dev/null || true)" == "attempts/$START_REQUEST_ID/owner" ]] \
        || die "Ownership changed before launch acknowledgement"
    printf 'acknowledged\n' >"$START_ATTEMPT/acknowledged.tmp.$$"
    mv -f "$START_ATTEMPT/acknowledged.tmp.$$" "$START_ATTEMPT/acknowledged"
    flock -u 8
    exec 8>&-
    if (( cancel_requested )); then START_RESULT=revoked; info "Startup cancelled"; exit 143; fi
    emit_start_result started "$owner"
    START_ACKNOWLEDGED=1
    unlock_runtime
    trap 'stop_launch_on_signal' TERM INT HUP
    (( cancel_requested == 0 )) || stop_launch_on_signal
    if (( detached )); then exit 0; fi
    wait "$launcher" || rc=$?
    trap - TERM INT HUP
    exit "$rc"
}

stop_launch_on_signal() {
    # The old foreground wrapper must never stop a newer launch after maintenance/restart.
    # Cancellation targets only this immutable attempt under its gate; it need not wait
    # for unrelated package staging to release the global lifecycle lock.
    revoke_attempt "$START_REQUEST_ID"
    exit 143
}

status_snapshot() {
    detect_installation
    local running busy=false owner=null available
    available="$(df -Pk "$HOME" 2>/dev/null | awk 'NR == 2 && $4 ~ /^[0-9]+$/ {printf "%.0f", $4 * 1024}')" || available=""
    [[ "$available" =~ ^[0-9]+$ ]] || available=null
    running="$(process_state)"
    if [[ "$running" == owned ]]; then owner="$(owned_launch_json)"; fi
    if [[ -f "$INSTALL_DIR/lifecycle.lock" ]] && command -v flock >/dev/null; then
        flock -n "$INSTALL_DIR/lifecycle.lock" true || busy=true
    fi
    jq -n -c --arg channel "$CHANNEL" --arg version "$VERSION" --arg profile "$PROFILE" \
        --arg source "$SOURCE" --arg integrity "$INTEGRITY" --arg running "$running" --argjson busy "$busy" --argjson owner "$owner" \
        --argjson available "$available" '
        {schemaVersion:1,installed:($source != "missing"),
        channel:(if $channel == "" then null else $channel end),
        version:(if $version == "" then null else $version end),source:$source,
        profile:(if $profile == "" then null else $profile end),
        profileIsolated:($profile == "v1" or $profile == "v2"),
        integrity:$integrity,running:$running,busy:$busy,owner:$owner,controllerReady:true,
        supportsV2Upgrade:true,availableBytes:$available}'
}

bootstrap_stdin() {
    # A piped script cannot copy its own bytes. Fetch a verified persistent copy once,
    # retaining every positional argument. Runtime start/update never self-refresh.
    [[ -f "$SCRIPT_SOURCE" ]] && return 0
    command -v curl >/dev/null || die "curl is required to save setup.sh"
    mkdir -p "$INSTALL_DIR"
    local script checksum expected actual
    script="$(mktemp "$INSTALL_DIR/.bootstrap.XXXXXX")"
    checksum="$(curl --connect-timeout 10 --max-time 30 -fsSL "${SETUP_URL%.sh}.sha256")" || die "Cannot fetch setup checksum"
    expected="${checksum%% *}"
    [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || die "Invalid setup checksum"
    curl --connect-timeout 10 --max-time 60 -fsSL "$SETUP_URL" -o "$script" || die "Cannot save setup script"
    actual="$(sha256sum "$script" | cut -d' ' -f1)"
    [[ "$actual" == "$expected" ]] || die "Setup checksum mismatch; retry after release publication completes"
    chmod 700 "$script"
    exec bash "$script" "$@"
}

usage() {
    cat <<'USAGE'
Usage: opencode-runtime <command>
  install --channel v1|v2             Install or adopt the selected major
  update [--expected-channel v1|v2]    Update within the active major; reject stale major
  upgrade-v2 --expected-channel v1 [--yes]  One-way upgrade of the current profile to V2
  start [--expected-channel v1|v2] [--proxy URL] [--no-proxy LIST] [--hostname HOST]
        [--server-username USER] [--server-password PASS]
        [--request-id ID] [--json [--detach]]  Correlated ownership acknowledgement
  stop                                Stop only the verified owned server
    [--expected-request-id ID]         Refuse a replaced launch, including delayed app commands
  status [--json]                      Process status / safe version snapshot
  doctor [--json]                      Local installation diagnostics
  refresh-runtime                     Regenerate local wrappers, no download
  upgrade-control                     Prepare supervisor tools and refresh wrappers; preserve OpenCode/data
  uninstall [--force]                  DELETE distro, projects and all profiles
USAGE
}

main() {
    trap cleanup EXIT
    trap 'info "Local runtime operation failed; inspect doctor before retrying"; exit 1' ERR
    [[ "${1:-}" != --skip-self-update ]] || shift
    local command="${1:-install}"
    (( $# == 0 )) || shift
    REQUESTED_CHANNEL="" EXPECTED_CHANNEL="" EXPECTED_REQUEST_ID="" CONFIRMED=0
    local json=0 force=0
    if [[ "$command" == start ]]; then
        require_termux
        lock_runtime
        start_server "$@"
        return
    fi
    while (( $# )); do
        case "$1" in
            --channel)
                [[ "$command" == install ]] || usage_error "--channel is only valid for install"
                [[ $# -ge 2 && -z "$REQUESTED_CHANNEL" ]] || usage_error "One --channel value is required"
                REQUESTED_CHANNEL="$2"; shift
                [[ "$REQUESTED_CHANNEL" == v1 || "$REQUESTED_CHANNEL" == v2 ]] || usage_error "Channel must be v1 or v2"
                ;;
            --expected-channel)
                [[ "$command" == update || "$command" == upgrade-v2 ]] || usage_error "--expected-channel is only valid for update/upgrade-v2/start"
                [[ $# -ge 2 && -z "$EXPECTED_CHANNEL" ]] || usage_error "One --expected-channel value is required"
                EXPECTED_CHANNEL="$2"; shift
                [[ "$EXPECTED_CHANNEL" == v1 || "$EXPECTED_CHANNEL" == v2 ]] || usage_error "Expected channel must be v1 or v2"
                ;;
            --expected-request-id)
                [[ "$command" == stop && $# -ge 2 && -z "$EXPECTED_REQUEST_ID" ]] || usage_error "One stop request ID is required"
                [[ "$2" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]] || usage_error "Invalid stop request ID"
                EXPECTED_REQUEST_ID="$2"; shift
                ;;
            --yes) [[ "$command" == upgrade-v2 ]] || usage_error "--yes is only valid for upgrade-v2"; CONFIRMED=1 ;;
            --json) [[ "$command" == status || "$command" == doctor ]] || usage_error "--json is only valid for status/doctor"; json=1 ;;
            --force) [[ "$command" == uninstall ]] || usage_error "--force is only valid for uninstall"; force=1 ;;
            *) usage_error "Unknown argument: $1" ;;
        esac
        shift
    done
    case "$command" in help|-h|--help) usage; return ;; esac
    require_termux
    case "$command" in
        status|doctor)
            command -v jq >/dev/null || die "jq is missing; install the Termux jq package"
            local snapshot
            snapshot="$(status_snapshot)"
            if (( json )); then printf '%s\n' "$snapshot"
            elif [[ "$command" == doctor ]]; then printf '%s\n' "$snapshot" | jq .
            else
                case "$(printf '%s' "$snapshot" | jq -r .running)" in
                    owned) printf 'running\n' ;; stopped) printf 'stopped\n' ;; *) printf 'unknown\n' ;;
                esac
            fi
            ;;
        install|update|upgrade-v2)
            [[ "$command" != install ]] || choose_channel
            local bootstrap_args=("$command")
            [[ -z "$REQUESTED_CHANNEL" ]] || bootstrap_args+=(--channel "$REQUESTED_CHANNEL")
            [[ -z "$EXPECTED_CHANNEL" ]] || bootstrap_args+=(--expected-channel "$EXPECTED_CHANNEL")
            (( CONFIRMED == 0 )) || bootstrap_args+=(--yes)
            bootstrap_stdin "${bootstrap_args[@]}"
            # util-linux must be available before any mutation; avoid two competing bootstraps.
            if [[ "$command" == install ]] && ! command -v flock >/dev/null; then
                apt-get install -yq util-linux
            fi
            lock_runtime
            install_or_maintain "$command"
            ;;
        stop) lock_runtime; stop_owned_server "$EXPECTED_REQUEST_ID" ;;
        refresh-runtime) refresh_runtime_controller ;;
        upgrade-control) upgrade_runtime_controller ;;
        switch|reinstall|reinstall-opencode)
            die "Branch switching and destructive reinstall are retired. Use update or the one-way upgrade-v2 --expected-channel v1 --yes"
            ;;
        uninstall)
            (( force )) || die "Uninstall deletes Debian/projects/all profiles; pass --force only after backing up"
            lock_runtime
            stop_owned_server
            proot-distro remove "$DISTRO_ALIAS"
            rm -rf "$INSTALL_DIR"
            rm -f "$PREFIX/bin/opencode-runtime"
            ;;
        *) usage_error "Unknown command: $command" ;;
    esac
}

if [[ -z "$SCRIPT_SOURCE" || "$SCRIPT_SOURCE" == "$0" ]]; then main "$@"; fi
