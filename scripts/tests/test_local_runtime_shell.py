"""Offline shell lifecycle fixtures. No package downloads, sockets, Termux or Android.

Run: python3 -m unittest discover -s scripts/tests -p 'test_local_runtime_shell.py' -v
Each shell sources the production implementation and substitutes only the proot boundary,
guest paths and environment provisioning. npm/ss are command stubs in a temporary sandbox.
"""

import json
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "opencode-local-runtime.sh"

DRIVER = r'''
source "$SCRIPT"
production_prepare="$(declare -f prepare_environment)"
GUEST_HOME="$SANDBOX/guest/root"
GUEST_BASE="$SANDBOX/guest/opt/oc-remote"
CONTROL_DIR="$INSTALL_DIR"
START_REGISTRATION_POLLS="${FIXTURE_REGISTRATION_POLLS:-100}"
rootfs_present() { [[ -e "$SANDBOX/rootfs" ]]; }
proot_exec() {
    local code="$1"
    [[ -z "${BROKEN_ROOTFS:-}" || "$code" != true ]] || return 8
    if [[ -n "${VERIFY_DETECTION_LOCK:-}" && "$code" == *"timeout 20"* ]]; then
        if flock -n "$INSTALL_DIR/lifecycle.lock" true; then
            touch "$SANDBOX/detection-unlocked"
            return 99
        fi
        touch "$SANDBOX/detection-locked"
    fi
    code="${code//\/usr\/local\/bin\/opencode/$SANDBOX/guest/usr/local/bin/opencode}"
    code="${code//command -v opencode || true/true}"
    local held="" rc=0
    if [[ -n "${HOLD_LAUNCH:-}" && "$code" == *"exec setsid /bin/bash"* ]]; then
        held="$HOLD_LAUNCH"
        touch "$SANDBOX/launch-held-$held"
        while [[ ! -f "$SANDBOX/launch-release-$held" ]]; do sleep 0.02; done
    fi
    /bin/bash -c "$code" || rc=$?
    [[ -z "$held" ]] || touch "$SANDBOX/launch-returned-$held"
    return "$rc"
}
prepare_environment() { touch "$SANDBOX/rootfs"; }
ensure_guest_tools() { touch "$SANDBOX/guest-tools-checked"; }
if [[ -n "${PREPARE_REAL:-}" ]]; then eval "$production_prepare"; fi
main "$@"
'''

NPM = r'''#!/usr/bin/python3
import json, os, pathlib, sys, time
args = sys.argv[1:]
base = pathlib.Path(os.environ['SANDBOX'])
with (base / 'npm-calls').open('a') as stream:
    stream.write(json.dumps(args) + '\n')
if os.environ.get('NPM_HOLD'):
    (base / 'npm-entered').touch()
    while not (base / 'npm-release').exists(): time.sleep(.02)
prefix = pathlib.Path(args[args.index('--prefix') + 1])
(prefix / 'bin').mkdir(parents=True)
version = os.environ.get('CANDIDATE_VERSION', '1.19.6' if args[-1] == 'opencode-ai@1' else '2.2.6')
binary = prefix / 'bin/opencode'
binary.write_text('#!/bin/bash\nif [[ "$1" == --version ]]; then printf "%s\\n" ' + repr(version) + '; exit; fi\n'
    + 'printf "%s\\n" "$HOME" "$XDG_DATA_HOME" "$OPENCODE_SERVER_PASSWORD" "$HTTP_PROXY" "$NO_PROXY" "$@" >"$SANDBOX/serve-observed.tmp.$$"\n'
    + 'mv "$SANDBOX/serve-observed.tmp.$$" "$SANDBOX/serve-observed"\n'
    + 'printf "%s|%s\\n" "$HOME" "$OPENCODE_SERVER_PASSWORD" >>"$SANDBOX/serve-starts"\n'
    + 'trap "exit 0" TERM INT HUP\nwhile :; do sleep 0.05; done\n')
binary.chmod(0o700)
sys.exit(int(os.environ.get('NPM_FAIL', '0')))
'''


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="oc-shell-fixture-")
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.install = self.home / "opencode-local"
        self.prefix = self.root / "termux"
        self.guest = self.root / "guest/root"
        self.bin = self.root / "bin"
        for directory in [self.install, self.prefix / "bin", self.guest, self.bin, self.root / "tmp"]:
            directory.mkdir(parents=True)
        self.env = dict(os.environ, HOME=str(self.home), PREFIX=str(self.prefix),
                        TERMUX_VERSION="fixture", SANDBOX=str(self.root), SCRIPT=str(SCRIPT),
                        TMPDIR=str(self.root / "tmp"),
                        PATH=f"{self.bin}:/usr/bin:/bin", HTTP_PROXY="", HTTPS_PROXY="", ALL_PROXY="")
        self.stub("npm", NPM)
        self.stub("ss", '#!/bin/bash\n[[ -z "${SS_FAIL:-}" ]] || exit 1\n'
                  '[[ -z "${SS_WARNING:-}" ]] || printf "Cannot open netlink socket\\n" >&2\n'
                  '[[ -z "${CONFLICT:-}" ]] || printf "LISTEN 0 1 127.0.0.1:4096\\n"\nexit 0\n')
        self.stub("proot-distro", '#!/bin/bash\nexit 98\n')
        # Never open real sockets in these fixtures, including ss's Android fallback.
        self.stub("node", '#!/bin/bash\nprintf "%s\\n" "${NODE_PROBE_RESULT:-stopped}"\n')
        # Fail if any fixture unexpectedly crosses the provisioning/network boundary.
        for name in ["curl", "apt-get", "pkg", "pkill"]:
            self.stub(name, '#!/bin/bash\nprintf "forbidden fixture command\\n" >&2\nexit 99\n')
        self.running = []

    def tearDown(self):
        # Only fixture-owned supervisors, using the production verified stop operation.
        if (self.install / "owner").exists():
            self.run_cli("stop", check=False)
        for process in self.running:
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.communicate(timeout=5)
        self.temporary.cleanup()

    def stub(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o700)
        return path

    def run_cli(self, *args, check=True, env=None):
        result = subprocess.run(["/bin/bash", "-c", DRIVER, "fixture", *args],
                                env=dict(self.env, **(env or {})), text=True,
                                capture_output=True, timeout=25, start_new_session=True)
        if check:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def popen(self, *args, env=None):
        process = subprocess.Popen(["/bin/bash", "-c", DRIVER, "fixture", *args],
                                   env=dict(self.env, **(env or {})), text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        self.running.append(process)
        return process

    def wait_file(self, filename):
        path = self.root / filename
        for _ in range(150):
            if path.exists(): return path
            time.sleep(.02)
        self.fail(f"fixture did not create {path}")

    def snapshot(self, **env):
        return json.loads(self.run_cli("status", "--json", env=env).stdout)

    def wait_started(self):
        for _ in range(100):
            snapshot = self.snapshot()
            if snapshot["running"] == "owned" and not snapshot["busy"]:
                return
            time.sleep(.02)
        self.fail("runtime did not finish ownership handshake and release lifecycle lock")

    def active(self):
        return os.readlink(self.install / "active")

    def metadata(self):
        return json.loads((self.install / self.active() / "metadata.json").read_text())

    def legacy(self, version="1.19.5"):
        (self.root / "rootfs").touch()
        path = self.guest / ".opencode/bin/opencode"
        path.parent.mkdir(parents=True)
        path.write_text(f'#!/bin/bash\nprintf "%s\\n" {shlex.quote(version)}\n')
        path.chmod(0o700)
        for relative in [".local/share/opencode/auth.json", ".config/opencode/opencode.json",
                         ".local/state/opencode/history", "projects/demo/README"]:
            target = self.guest / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("original sentinel")
        return path

    def assert_legacy_data(self):
        for relative in [".local/share/opencode/auth.json", ".config/opencode/opencode.json",
                         ".local/state/opencode/history", "projects/demo/README"]:
            self.assertEqual((self.guest / relative).read_text(), "original sentinel")

    def test_fresh_install_v1_and_wrappers(self):
        self.run_cli("install", "--channel", "v1")
        self.assertEqual(self.metadata()["channel"], "v1")
        self.assertEqual(self.metadata()["profile"], "v1")
        self.assertIn("opencode-ai@1", (self.root / "npm-calls").read_text())
        self.assertEqual(self.snapshot()["integrity"], "ok")
        for name in ["runtime", "runtime-start", "runtime-stop", "runtime-status", "serve"]:
            subprocess.run(["bash", "-n", self.install / f"{name}.sh"], check=True)
        self.assertEqual((self.install / "env").stat().st_mode & 0o777, 0o600)

    def test_controller_refresh_preserves_old_runtime_and_supports_both_status_protocols(self):
        binary = self.legacy("1.19.3")
        original = binary.read_bytes()
        (self.install / "setup.sh").write_text("# legacy controller without JSON status\n")
        for name in ["start", "stop", "status"]:
            (self.install / f"{name}.sh").write_text(f"# old {name} stays intact\n")
        (self.install / "env").write_text("# keep auth and proxy settings\n")
        self.run_cli("refresh-runtime")
        self.assertEqual((self.install / "setup.sh").read_text(), "# legacy controller without JSON status\n")
        for name in ["start", "stop", "status"]:
            self.assertEqual((self.install / f"{name}.sh").read_text(), f"# old {name} stays intact\n")
        self.assertIn('start "$@"', (self.install / "runtime-start.sh").read_text())
        self.assertEqual(self.run_cli("status").stdout.strip(), "stopped")
        snapshot = json.loads(self.run_cli("status", "--json").stdout)
        self.assertEqual(snapshot["version"], "1.19.3")
        self.assertEqual(snapshot["source"], "legacy")
        self.assertEqual(snapshot["channel"], "v1")
        self.assertEqual(binary.read_bytes(), original)
        self.assertEqual((self.install / "env").read_text(), "# keep auth and proxy settings\n")
        self.assertFalse((self.root / "npm-calls").exists())
        self.assertFalse((self.root / "guest-tools-checked").exists())
        self.assert_legacy_data()
        for name in ["runtime", "runtime-start", "runtime-stop", "runtime-status", "serve"]:
            subprocess.run(["bash", "-n", self.install / f"{name}.sh"], check=True)
        self.assertEqual((self.install / "env").stat().st_mode & 0o777, 0o600)

    def test_fresh_install_v2(self):
        self.run_cli("install", "--channel", "v2")
        self.assertEqual(self.metadata()["channel"], "v2")
        self.assertIn("@opencode/cli@2", (self.root / "npm-calls").read_text())

    def test_stdin_entrypoint_help_and_missing_choice(self):
        for arguments, expected in [(["help"], 0), (["install"], 2)]:
            result = subprocess.run(["/bin/bash", "-s", "--", *arguments],
                                    input=SCRIPT.read_text(), env=self.env,
                                    text=True, capture_output=True, timeout=5, start_new_session=True)
            self.assertEqual(result.returncode, expected, result.stderr)

    def test_stdin_install_preserves_selected_channel_in_bootstrap(self):
        # The downloaded fixture records positional arguments instead of installing anything.
        bootstrap = self.root / "bootstrap.sh"
        bootstrap.write_text('#!/bin/bash\nprintf "%s\\n" "$@" >"$SANDBOX/bootstrap-args"\n')
        checksum = hashlib.sha256(bootstrap.read_bytes()).hexdigest()
        self.stub("curl", f'''#!/bin/bash
if [[ "$*" == *sha256* ]]; then printf '%s  opencode-local-runtime.sh\\n' {checksum}; exit; fi
while [[ "$1" != -o ]]; do shift; done
cp "$SANDBOX/bootstrap.sh" "$2"
''')
        result = subprocess.run(["/bin/bash", "-s", "--", "install", "--channel", "v2"],
                                input=SCRIPT.read_text(), env=self.env,
                                text=True, capture_output=True, timeout=5, start_new_session=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "bootstrap-args").read_text().splitlines(),
                         ["install", "--channel", "v2"])

    def test_choice_required_without_tty(self):
        result = self.run_cli("install", check=False)
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.root / "npm-calls").exists())

    def test_same_major_update_retains_previous(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        self.run_cli("update", env={"CANDIDATE_VERSION": "1.20.0"})
        self.assertEqual(self.metadata()["version"], "1.20.0")
        self.assertEqual(os.readlink(self.install / "previous"), before)
        self.assertTrue((self.install / before / "metadata.json").exists())
        self.assertEqual(self.metadata()["profile"], "v1")

    def test_update_rejects_channel_flag(self):
        self.assertEqual(self.run_cli("update", "--channel", "v2", check=False).returncode, 2)

    def test_expected_channel_argument_validation(self):
        for command in ["update", "start"]:
            for arguments in [("--expected-channel",), ("--expected-channel", ""),
                              ("--expected-channel", "v3"), ("--expected-channel", "--yes"),
                              ("--expected-channel", "v1", "--expected-channel", "v2")]:
                with self.subTest(command=command, arguments=arguments):
                    self.assertEqual(self.run_cli(command, *arguments, check=False).returncode, 2)
        for command in ["install", "switch", "stop", "status", "doctor", "refresh-runtime", "uninstall"]:
            with self.subTest(command=command):
                self.assertEqual(self.run_cli(command, "--expected-channel", "v1", check=False).returncode, 2)
        self.assertFalse((self.root / "npm-calls").exists())
        self.assertFalse((self.root / "serve-observed").exists())

    def test_stale_android_status_after_switch_rejects_update_and_start_without_effects(self):
        self.run_cli("install", "--channel", "v1")
        observed = self.snapshot()["channel"]
        self.run_cli("switch", "--channel", "v2", "--yes")
        active = self.active()
        previous = os.readlink(self.install / "previous")
        npm_calls = (self.root / "npm-calls").read_bytes()
        releases = list((self.install / "releases").iterdir())
        (self.root / "guest-tools-checked").unlink()
        with (self.install / "env").open("a") as stream:
            stream.write('\ntouch "$SANDBOX/env-sourced"\n')
        for command in ["update", "start"]:
            result = self.run_cli(command, "--expected-channel", observed, check=False)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn("Expected channel v1, but installed channel is v2", result.stderr)
            self.assertEqual(self.active(), active)
            self.assertEqual(os.readlink(self.install / "previous"), previous)
            self.assertEqual((self.root / "npm-calls").read_bytes(), npm_calls)
            self.assertEqual(list((self.install / "releases").iterdir()), releases)
            self.assertFalse((self.root / "guest-tools-checked").exists())
            self.assertFalse((self.root / "env-sourced").exists())
            self.assertFalse((self.root / "serve-observed").exists())
            self.assertFalse((self.install / "launch-env").exists())
            self.assertFalse((self.install / "owner").exists())
            self.assertFalse((self.guest / ".oc-remote/profiles/v2").exists())

    def test_expected_channel_mismatch_does_not_stop_owned_new_major(self):
        self.run_cli("install", "--channel", "v1")
        observed = self.snapshot()["channel"]
        self.run_cli("switch", "--channel", "v2", "--yes")
        process = self.popen("start", "--expected-channel", "v2", "--hostname", "127.0.0.1")
        self.wait_file("serve-observed")
        self.wait_started()
        owner = (self.install / "owner").read_bytes()
        active = self.active()
        npm_calls = (self.root / "npm-calls").read_bytes()
        for command in ["update", "start"]:
            self.assertEqual(self.run_cli(command, "--expected-channel", observed, check=False).returncode, 1)
            self.assertEqual((self.install / "owner").read_bytes(), owner)
            self.assertEqual(self.active(), active)
            self.assertEqual((self.root / "npm-calls").read_bytes(), npm_calls)
            self.assertEqual(self.snapshot()["running"], "owned")
            self.assertIsNone(process.poll())
        self.run_cli("stop")
        process.communicate(timeout=10)

    def test_expected_channel_update_and_start_detect_under_real_lifecycle_lock(self):
        self.run_cli("install", "--channel", "v2")
        self.run_cli("update", "--expected-channel", "v2", env={"VERIFY_DETECTION_LOCK": "1"})
        self.assertTrue((self.root / "detection-locked").exists())
        (self.root / "detection-locked").unlink()
        process = self.popen("start", "--expected-channel", "v2", env={"VERIFY_DETECTION_LOCK": "1"})
        self.wait_file("serve-observed")
        self.wait_started()
        self.assertTrue((self.root / "detection-locked").exists())
        self.assertFalse((self.root / "detection-unlocked").exists())
        self.run_cli("stop")
        process.communicate(timeout=10)

    def test_expected_channel_accepts_legacy_and_rejects_unknown_or_mismatched_integrity(self):
        self.legacy()
        self.run_cli("update", "--expected-channel", "v1")
        self.assertEqual(self.metadata()["profile"], "legacy-v1")
        self.assert_legacy_data()
        npm_calls = (self.root / "npm-calls").read_bytes()
        binary = Path(self.metadata()["executable"])
        for version in ["2.2.5", "custom nightly"]:
            binary.write_text(f'#!/bin/bash\nprintf "%s\\n" {shlex.quote(version)}\n')
            for command in ["update", "start"]:
                self.assertEqual(self.run_cli(command, "--expected-channel", "v1", check=False).returncode, 1)
            self.assertEqual((self.root / "npm-calls").read_bytes(), npm_calls)
            self.assertFalse((self.root / "serve-observed").exists())
            self.assert_legacy_data()

    def test_companion_checksum_matches_script_and_existing_basename_convention(self):
        digest = hashlib.sha256(SCRIPT.read_bytes()).hexdigest()
        self.assertEqual(SCRIPT.with_suffix(".sha256").read_text(), f"{digest}  {SCRIPT.name}\n")

    def test_explicit_switch_and_switch_back_preserve_legacy(self):
        old = self.legacy()
        self.run_cli("switch", "--channel", "v2", "--yes")
        self.assertEqual(self.metadata()["profile"], "v2")
        previous = json.loads((self.install / "previous/metadata.json").read_text())
        self.assertEqual(previous["executable"], str(old))
        self.assertEqual(previous["profile"], "legacy-v1")
        self.assert_legacy_data()
        self.run_cli("switch", "--channel", "v1", "--yes")
        self.assertEqual(self.metadata()["profile"], "legacy-v1")
        self.assert_legacy_data()

    def test_switch_cancel_leaves_everything_untouched(self):
        self.legacy()
        result = self.run_cli("switch", "--channel", "v2", check=False)
        self.assertEqual(result.returncode, 3)
        self.assertFalse((self.install / "active").exists())
        self.assertFalse((self.root / "npm-calls").exists())
        self.assert_legacy_data()

    def test_install_adopts_legacy_without_installing(self):
        binary = self.legacy()
        before = binary.read_bytes()
        self.run_cli("install", "--channel", "v1")
        self.assertEqual(self.metadata()["executable"], str(binary))
        self.assertEqual(self.snapshot()["profileIsolated"], False)
        self.assertEqual(binary.read_bytes(), before)
        self.assertFalse((self.root / "npm-calls").exists())
        self.assert_legacy_data()

    def test_install_different_major_requires_switch(self):
        self.legacy()
        self.assertEqual(self.run_cli("install", "--channel", "v2", check=False).returncode, 1)
        self.assertFalse((self.install / "active").exists())
        self.assert_legacy_data()

    def test_unhealthy_existing_rootfs_is_never_reinstalled(self):
        self.legacy()
        self.stub("uname", '#!/bin/bash\necho aarch64\n')
        self.stub("df", '#!/bin/bash\nprintf "Filesystem Blocks Used Available Capacity Mount\\nfixture 1000000 0 1000000 0%% /\\n"\n')
        self.stub("dpkg", '#!/bin/bash\nexit 0\n')
        self.assertEqual(self.run_cli("install", "--channel", "v1", check=False,
                                      env={"PREPARE_REAL": "1", "BROKEN_ROOTFS": "1"}).returncode, 1)
        self.assertTrue((self.root / "rootfs").exists())
        self.assertFalse((self.root / "npm-calls").exists())
        self.assert_legacy_data()

    def test_unknown_legacy_version_blocks_all_replacements(self):
        self.legacy("nightly custom build")
        status = self.snapshot()
        self.assertEqual(status["source"], "unknown")
        self.assertIsNone(status["channel"])
        for args in [("update",), ("switch", "--channel", "v2", "--yes"),
                     ("install", "--channel", "v1")]:
            self.assertEqual(self.run_cli(*args, check=False).returncode, 1)
        self.assertFalse((self.root / "npm-calls").exists())
        self.assert_legacy_data()

    def test_package_install_failure_rolls_back(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        self.assertEqual(self.run_cli("update", env={"NPM_FAIL": "7"}, check=False).returncode, 1)
        self.assertEqual(self.active(), before)
        self.assertEqual(self.snapshot()["version"], "1.19.6")

    def test_wrong_major_and_unrecognized_candidate_roll_back(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        for version in ["2.2.5", "1.20.0-beta.1", "1.19.3", "1.19.7\n2.2.5", "99.1.0"]:
            self.assertEqual(self.run_cli("update", env={"CANDIDATE_VERSION": version}, check=False).returncode, 1)
            self.assertEqual(self.active(), before)

    def test_switch_validation_failure_keeps_legacy(self):
        self.legacy()
        self.assertEqual(self.run_cli("switch", "--channel", "v2", "--yes",
                                      env={"CANDIDATE_VERSION": "1.19.5"}, check=False).returncode, 1)
        self.assertFalse((self.install / "active").exists())
        self.assert_legacy_data()

    def test_atomic_publication_failure_keeps_active(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        self.stub("mv", '#!/bin/bash\n[[ "${*: -1}" != "$HOME/opencode-local/active" ]] || exit 6\nexec /usr/bin/mv "$@"\n')
        self.assertEqual(self.run_cli("update", check=False).returncode, 1)
        self.assertEqual(self.active(), before)

    def test_wrapper_failure_does_not_publish_candidate(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        self.stub("mv", '#!/bin/bash\n[[ "${*: -1}" != "$HOME/opencode-local/runtime-start.sh" ]] || exit 6\nexec /usr/bin/mv "$@"\n')
        self.assertEqual(self.run_cli("update", check=False).returncode, 1)
        self.assertEqual(self.active(), before)

    def test_detect_metadata_binary_mismatch(self):
        self.run_cli("install", "--channel", "v1")
        Path(self.metadata()["executable"]).write_text('#!/bin/bash\necho 2.2.5\n')
        self.assertEqual(self.snapshot()["integrity"], "mismatch")
        self.assertEqual(self.run_cli("update", check=False).returncode, 1)

    def test_legacy_update_preserves_original_binary_and_profile(self):
        binary = self.legacy()
        before = binary.read_bytes()
        self.run_cli("update")
        self.assertEqual(self.metadata()["profile"], "legacy-v1")
        self.assertEqual(binary.read_bytes(), before)
        self.assert_legacy_data()

    def test_unknown_listener_blocks_start_stop_and_publication(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        self.assertEqual(self.snapshot(CONFLICT="1")["running"], "unknown")
        for args in [("start",), ("stop",), ("update",)]:
            self.assertEqual(self.run_cli(*args, env={"CONFLICT": "1"}, check=False).returncode, 1)
        self.assertEqual(self.active(), before)

    def test_unreadable_listener_inventory_fails_closed(self):
        self.legacy()
        self.assertEqual(self.snapshot(SS_FAIL="1", NODE_PROBE_RESULT="unknown")["running"], "unknown")
        self.assertEqual(self.run_cli("stop", env={"SS_FAIL": "1", "NODE_PROBE_RESULT": "unknown"}, check=False).returncode, 1)
        self.assertEqual(self.snapshot(SS_WARNING="1", NODE_PROBE_RESULT="unknown")["running"], "unknown")

    def test_android_listener_probe_fallback_uses_stubbed_exclusive_bind(self):
        self.legacy()
        self.assertEqual(self.snapshot(SS_FAIL="1")["running"], "stopped")
        self.assertEqual(self.snapshot(SS_WARNING="1")["running"], "stopped")

    def test_listener_fallback_program_with_socket_api_mock(self):
        node = shutil.which("node", path="/usr/bin:/bin")
        if node is None:
            self.skipTest("Node is unavailable for the socket-free probe-program fixture")
        program = SCRIPT.read_text().split("node --eval '\n", 1)[1].split("' 2>/dev/null", 1)[0]
        harness = r'''
const vm = require("node:vm");
const source = require("node:fs").readFileSync(0, "utf8");
for (const scenario of ["free", "ipv4-only", "busy", "denied", "ipv4-busy"]) {
    const calls = [], output = [], done = {};
    const net = {createServer() {
        let failure;
        return {
            once(event, callback) { if (event !== "error") throw Error(event); failure = callback; },
            close(callback) { callback(); },
            listen(options, callback) {
                calls.push(options);
                const code = scenario.startsWith("ipv4") && options.host === "::" ? "EAFNOSUPPORT"
                    : scenario.endsWith("busy") ? "EADDRINUSE" : scenario === "denied" ? "EACCES" : null;
                if (code) failure({code}); else callback();
            },
        };
    }};
    try {
        vm.runInNewContext(source, {
            require(name) { if (name !== "node:net") throw Error(name); return net; },
            process: {env: {PORT:"4096"}, exit(code) { if (code !== 0) throw Error(code); throw done; }},
            console: {log(value) { output.push(value); }},
            setTimeout() { return {unref() {}}; },
        });
    } catch (error) { if (error !== done) throw error; }
    console.log(JSON.stringify({scenario, output, calls}));
}
'''
        result = subprocess.run([node, "--eval", harness], input=program, text=True,
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        cases = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual([case["output"] for case in cases],
                         [["stopped"], ["stopped"], ["unknown"], ["unknown"], ["unknown"]])
        self.assertEqual([call["host"] for call in cases[1]["calls"]], ["::", "0.0.0.0"])
        for case in cases:
            for call in case["calls"]:
                self.assertEqual(call["port"], 4096)
                self.assertTrue(call["exclusive"])
                self.assertFalse(call["ipv6Only"])

    def test_legacy_version_probe_does_not_initialize_user_home(self):
        binary = self.legacy()
        binary.write_text('#!/bin/bash\ntouch "$HOME/PROBE_SEEN"\necho 1.19.5\n')
        self.assertEqual(self.snapshot()["channel"], "v1")
        self.assertFalse((self.guest / "PROBE_SEEN").exists())
        self.assertFalse((self.home / "PROBE_SEEN").exists())
        self.assert_legacy_data()

    def test_concurrent_mutation_rejected_and_busy_snapshot(self):
        self.run_cli("install", "--channel", "v1")
        pending = self.popen("update", env={"NPM_HOLD": "1"})
        self.wait_file("npm-entered")
        self.assertTrue(self.snapshot()["busy"])
        self.assertEqual(self.run_cli("update", check=False).returncode, 4)
        self.assertEqual(self.run_cli("start", check=False).returncode, 4)
        (self.root / "npm-release").touch()
        output = pending.communicate(timeout=10)
        self.assertEqual(pending.returncode, 0, output)
        self.assertFalse(self.snapshot()["busy"])

    def test_cancelled_staging_keeps_active_and_releases_lock(self):
        self.run_cli("install", "--channel", "v1")
        before = self.active()
        pending = self.popen("update", env={"NPM_HOLD": "1"})
        self.wait_file("npm-entered")
        # Terminate this fixture's isolated command group, never a name-selected process.
        os.killpg(pending.pid, signal.SIGTERM)
        pending.communicate(timeout=10)
        self.assertEqual(self.active(), before)
        self.assertFalse(self.snapshot()["busy"])

    def test_secure_bind_requires_password(self):
        self.run_cli("install", "--channel", "v2")
        self.assertEqual(self.run_cli("start", "--hostname", "0.0.0.0", check=False).returncode, 1)
        self.assertFalse((self.install / "owner").exists())

    def test_owned_start_stop_and_quoted_secret_proxy_profile(self):
        self.run_cli("install", "--channel", "v2")
        secret = 'p"ass;$(touch SHOULD_NOT_EXIST)'
        proxy = 'http://name:p"ass@example.invalid:8080'
        process = self.popen("start", "--hostname", "0.0.0.0", "--server-password", secret,
                             "--proxy", proxy, "--no-proxy", "localhost,127.0.0.1")
        observed = self.wait_file("serve-observed").read_text().splitlines()
        self.wait_started()
        self.assertEqual(observed[0], str(self.guest / ".oc-remote/profiles/v2"))
        self.assertEqual(observed[2:5], [secret, proxy, "localhost,127.0.0.1"])
        self.assertFalse((self.guest / "SHOULD_NOT_EXIST").exists())
        self.assertEqual(self.snapshot()["running"], "owned")
        self.assertFalse(self.snapshot()["busy"])
        snapshot = self.run_cli("doctor", "--json").stdout
        self.assertNotIn(secret, snapshot)
        self.assertNotIn(proxy, snapshot)
        self.run_cli("stop")
        process.communicate(timeout=10)
        self.assertEqual(self.snapshot()["running"], "stopped")

    def test_update_stops_only_owned_runtime_after_validation(self):
        self.run_cli("install", "--channel", "v1")
        process = self.popen("start")
        self.wait_file("serve-observed")
        self.wait_started()
        before = self.active()
        self.run_cli("update", env={"NPM_FAIL": "7"}, check=False)
        self.assertEqual(self.active(), before)
        self.assertEqual(self.snapshot()["running"], "owned")
        self.run_cli("update", env={"CANDIDATE_VERSION": "1.20.0"})
        process.communicate(timeout=10)
        self.assertEqual(self.snapshot()["running"], "stopped")
        self.assertEqual(self.metadata()["version"], "1.20.0")

    def test_foreground_cancellation_stops_its_owned_launch(self):
        self.run_cli("install", "--channel", "v2")
        process = self.popen("start")
        self.wait_file("serve-observed")
        self.assertEqual(self.snapshot()["running"], "owned")
        process.terminate()
        process.communicate(timeout=10)
        self.assertEqual(process.returncode, 143)
        self.assertEqual(self.snapshot()["running"], "stopped")

    def start_ack(self, request, channel="v1", **options):
        return json.loads(self.run_cli("start", "--expected-channel", channel, "--request-id", request,
                                      "--json", "--detach", *options.get("arguments", ())).stdout)

    def test_start_acknowledgement_and_status_correlate_exact_owned_request(self):
        self.run_cli("install", "--channel", "v1")
        acknowledgement = self.start_ack("android-request-1")
        self.assertEqual(acknowledgement["result"], "started")
        self.assertEqual(acknowledgement["requestId"], "android-request-1")
        self.assertEqual(acknowledgement["owner"]["phase"], "running")
        self.wait_file("serve-observed")
        snapshot = self.snapshot()
        self.assertEqual(snapshot["owner"], acknowledgement["owner"])
        self.assertEqual(snapshot["owner"]["channel"], "v1")
        self.assertEqual(snapshot["owner"]["profile"], "v1")
        self.assertFalse(snapshot["busy"])
        attempt = self.install / "attempts/android-request-1"
        self.assertFalse((attempt / "env").exists())
        self.assertEqual((attempt / "state").read_text(), "running\n")
        self.run_cli("stop")
        self.assertEqual((attempt / "state").read_text(), "revoked\n")
        self.assertIsNone(self.snapshot()["owner"])
        reused = self.run_cli("start", "--request-id", "android-request-1", "--json", "--detach", check=False)
        self.assertEqual(reused.returncode, 1)
        self.assertEqual(json.loads(reused.stdout)["owner"], None)
        self.assertEqual((attempt / "state").read_text(), "revoked\n")

    def test_request_id_validation_and_scopes(self):
        for request in ["../escape", ".", "a/b", "-first", "_first", "two words", "line\nbreak", "a" * 65]:
            with self.subTest(request=request):
                self.assertEqual(self.run_cli("start", "--request-id", request, check=False).returncode, 2)
        for arguments in [("--request-id",), ("--request-id", ""),
                          ("--request-id", "same", "--request-id", "same"), ("--detach",)]:
            self.assertEqual(self.run_cli("start", *arguments, check=False).returncode, 2)
        for command in ["install", "update", "switch", "status", "doctor", "stop"]:
            for arguments in [("--request-id", "request"), ("--detach",)]:
                self.assertEqual(self.run_cli(command, *arguments, check=False).returncode, 2)
        self.assertFalse((self.install / "attempts").exists())

    def test_timed_out_delayed_guest_is_revoked_before_retry_and_cannot_read_retry_credentials(self):
        self.run_cli("install", "--channel", "v2")
        first = self.popen("start", "--expected-channel", "v2", "--request-id", "delayed-A", "--json", "--detach",
                           "--server-password", "secret-A", env={"HOLD_LAUNCH": "A", "FIXTURE_REGISTRATION_POLLS": "6"})
        self.wait_file("launch-held-A")
        try:
            stdout, stderr = first.communicate(timeout=6)
            self.assertEqual(first.returncode, 1, stderr)
            result = json.loads(stdout)
            self.assertEqual((result["result"], result["requestId"], result["owner"]), ("revoked", "delayed-A", None))
            attempt = self.install / "attempts/delayed-A"
            self.assertEqual((attempt / "state").read_text(), "revoked\n")
            self.assertFalse((attempt / "env").exists())
            self.assertFalse((attempt / "owner").exists())
            self.assertFalse((self.install / "owner").exists())
            self.assertFalse(self.snapshot()["busy"])
            self.assertFalse((self.guest / ".oc-remote/profiles/v2").exists())
            acknowledgement = self.start_ack("retry-B", "v2", arguments=("--server-password", "secret-B"))
            self.wait_file("serve-observed")
            owner = acknowledgement["owner"]
            self.assertEqual(owner["requestId"], "retry-B")
            (self.root / "launch-release-A").touch()
            self.wait_file("launch-returned-A")
            self.assertEqual(self.snapshot()["owner"], owner)
            self.assertEqual((attempt / "state").read_text(), "revoked\n")
            self.assertFalse((attempt / "owner").exists())
            self.assertEqual((self.root / "serve-observed").read_text().splitlines()[2], "secret-B")
            starts = (self.root / "serve-starts").read_text().splitlines()
            self.assertEqual(len(starts), 1)
            self.assertTrue(starts[0].endswith("|secret-B"))
            self.run_cli("stop")
            self.assertIsNone(self.snapshot()["owner"])
        finally:
            (self.root / "launch-release-A").touch()

    def test_cancelled_delayed_registration_has_terminal_state_and_cannot_start_on_resume(self):
        self.run_cli("install", "--channel", "v1")
        process = self.popen("start", "--request-id", "cancelled", "--json", env={"HOLD_LAUNCH": "cancel"})
        self.wait_file("launch-held-cancel")
        try:
            process.terminate()
            stdout, stderr = process.communicate(timeout=6)
            self.assertEqual(process.returncode, 143, stderr)
            self.assertEqual(json.loads(stdout)["result"], "revoked")
            self.assertFalse(self.snapshot()["busy"])
            (self.root / "launch-release-cancel").touch()
            self.wait_file("launch-returned-cancel")
            self.assertEqual((self.install / "attempts/cancelled/state").read_text(), "revoked\n")
            self.assertFalse((self.install / "owner").exists())
            self.assertFalse((self.root / "serve-observed").exists())
        finally:
            (self.root / "launch-release-cancel").touch()

    def test_delayed_registration_refuses_owner_collision_without_overwriting_slot(self):
        self.run_cli("install", "--channel", "v1")
        process = self.popen("start", "--request-id", "collision", "--json", "--detach", env={"HOLD_LAUNCH": "collision"})
        self.wait_file("launch-held-collision")
        foreign = b"foreign-owner-sentinel\n"
        try:
            (self.install / "owner").write_bytes(foreign)
            (self.root / "launch-release-collision").touch()
            stdout, stderr = process.communicate(timeout=6)
            self.assertEqual(process.returncode, 1, stderr)
            self.assertEqual(json.loads(stdout)["result"], "failed")
            self.assertEqual((self.install / "owner").read_bytes(), foreign)
            self.assertFalse((self.install / "attempts/collision/owner").exists())
            self.assertFalse((self.root / "serve-observed").exists())
            self.assertFalse((self.guest / ".oc-remote/profiles/v1").exists())
        finally:
            (self.root / "launch-release-collision").touch()
            (self.install / "owner").unlink(missing_ok=True)

    def test_timeout_during_gated_registration_holds_lifecycle_until_exact_attempt_is_stopped(self):
        self.run_cli("install", "--channel", "v1")
        self.stub("mkdir", '''#!/bin/bash
if [[ "${HOLD_PROFILE_SETUP:-}" == 1 && "$*" == *"/.oc-remote/profiles/v1"* ]]; then
    touch "$SANDBOX/registration-gate-held"
    while [[ ! -f "$SANDBOX/registration-gate-release" ]]; do sleep 0.02; done
fi
exec /usr/bin/mkdir "$@"
''')
        process = self.popen("start", "--request-id", "gated", "--json", "--detach",
                             env={"HOLD_PROFILE_SETUP": "1", "FIXTURE_REGISTRATION_POLLS": "4"})
        self.wait_file("registration-gate-held")
        try:
            time.sleep(.8)
            self.assertIsNone(process.poll(), "timeout must not release lock before terminal revocation")
            self.assertTrue(self.snapshot()["busy"])
            self.assertEqual(self.run_cli("start", "--request-id", "blocked-retry", check=False).returncode, 4)
            (self.root / "registration-gate-release").touch()
            stdout, stderr = process.communicate(timeout=8)
            self.assertEqual(process.returncode, 1, stderr)
            self.assertEqual(json.loads(stdout)["result"], "revoked")
            attempt = self.install / "attempts/gated"
            self.assertEqual((attempt / "state").read_text(), "revoked\n")
            self.assertFalse((attempt / "acknowledged").exists())
            self.assertFalse((attempt / "env").exists())
            self.assertIsNone(self.snapshot()["owner"])
            self.assertFalse(self.snapshot()["busy"])
            if (attempt / "owner").exists():
                pid = (attempt / "owner").read_text().split()[0]
                status = Path(f"/proc/{pid}/stat")
                if status.exists():
                    self.assertEqual(status.read_text().rsplit(") ", 1)[1].split()[0], "Z")
        finally:
            (self.root / "registration-gate-release").touch()

    def test_parent_killed_before_delayed_guest_cannot_create_unacknowledged_orphan(self):
        self.run_cli("install", "--channel", "v1")
        process = self.popen("start", "--request-id", "killed-parent", "--json", "--detach",
                             env={"HOLD_LAUNCH": "killed"})
        self.wait_file("launch-held-killed")
        try:
            process.kill()
            process.communicate(timeout=5)
            # Inherited flock fails closed while the blocked guest has not returned.
            self.assertEqual(self.run_cli("start", "--request-id", "retry-too-early", check=False).returncode, 4)
            (self.root / "launch-release-killed").touch()
            self.wait_file("launch-returned-killed")
            self.assertEqual((self.install / "attempts/killed-parent/state").read_text(), "failed\n")
            self.assertFalse((self.root / "serve-observed").exists())
            self.assertIsNone(self.snapshot()["owner"])
            self.assertFalse(self.snapshot()["busy"])
            self.start_ack("retry-after-dead-parent")
            self.run_cli("stop")
        finally:
            (self.root / "launch-release-killed").touch()

    def test_signal_at_ack_publication_revokes_before_result_and_never_strands_server(self):
        self.run_cli("install", "--channel", "v1")
        self.stub("mv", '''#!/bin/bash
exec_target="${*: -1}"
/usr/bin/mv "$@" || exit
if [[ "$exec_target" == *"/acknowledged" ]]; then
    touch "$SANDBOX/ack-publication-held"
    while [[ ! -f "$SANDBOX/ack-publication-release" ]]; do sleep 0.02; done
fi
''')
        process = self.popen("start", "--request-id", "ack-cancel", "--json", "--detach")
        self.wait_file("ack-publication-held")
        try:
            process.terminate()
            (self.root / "ack-publication-release").touch()
            stdout, stderr = process.communicate(timeout=8)
            self.assertEqual(process.returncode, 143, stderr)
            self.assertEqual(json.loads(stdout)["result"], "revoked")
            self.assertEqual((self.install / "attempts/ack-cancel/state").read_text(), "revoked\n")
            self.assertIsNone(self.snapshot()["owner"])
        finally:
            (self.root / "ack-publication-release").touch()

    def test_foreground_cancellation_can_revoke_own_attempt_during_package_staging(self):
        self.run_cli("install", "--channel", "v1")
        server = self.popen("start", "--request-id", "cancel-during-update")
        self.wait_file("serve-observed")
        self.wait_started()
        update = self.popen("update", env={"NPM_HOLD": "1", "CANDIDATE_VERSION": "1.20.0"})
        self.wait_file("npm-entered")
        try:
            server.terminate()
            stdout, stderr = server.communicate(timeout=8)
            self.assertEqual(server.returncode, 143, stderr)
            self.assertEqual((self.install / "attempts/cancel-during-update/state").read_text(), "revoked\n")
            self.assertIsNone(self.snapshot()["owner"])
            (self.root / "npm-release").touch()
            update.communicate(timeout=8)
            self.assertEqual(update.returncode, 0)
        finally:
            (self.root / "npm-release").touch()

    def test_foreground_json_ack_is_single_record_while_process_lifetime_is_preserved(self):
        self.run_cli("install", "--channel", "v1")
        process = self.popen("start", "--request-id", "foreground-json", "--json")
        self.wait_file("serve-observed")
        self.wait_started()
        self.assertIsNone(process.poll())
        self.run_cli("stop")
        stdout, stderr = process.communicate(timeout=5)
        acknowledgement = json.loads(stdout)
        self.assertEqual(acknowledgement["requestId"], "foreground-json")
        self.assertEqual(acknowledgement["result"], "started")
        self.assertNotIn("OPENCODE_SERVER_PASSWORD", stdout)

    def test_switch_back_rejects_lower_version_than_used_destination_profile(self):
        self.run_cli("install", "--channel", "v1", env={"CANDIDATE_VERSION": "1.20.0"})
        self.start_ack("used-v1")
        self.wait_file("serve-observed")
        target = self.guest / ".oc-remote/profiles/v1"
        (target / "auth-sentinel").write_text("retain auth/data")
        self.run_cli("switch", "--channel", "v2", "--yes")
        active = self.active()
        owner = self.start_ack("current-v2", "v2")["owner"]
        before = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob("*") if p.is_file()}
        watermark = (self.install / "profile-versions/v1").read_bytes()
        for version in ["1.19.0", "1.19.99", "1.20.0-beta.1"]:
            result = self.run_cli("switch", "--channel", "v1", "--yes", env={"CANDIDATE_VERSION": version}, check=False)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(self.active(), active)
            self.assertEqual(self.snapshot()["owner"], owner)
            self.assertEqual((self.install / "profile-versions/v1").read_bytes(), watermark)
            after = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob("*") if p.is_file()}
            self.assertEqual(after, before)
        self.run_cli("switch", "--channel", "v1", "--yes", env={"CANDIDATE_VERSION": "1.20.1"})
        self.assertEqual(self.metadata()["profile"], "v1")
        self.assertEqual((self.install / "profile-versions/v1").read_text(), "1.20.1\n")

    def test_legacy_destination_floor_survives_switch_and_missing_highwater_migration(self):
        self.legacy("1.20.0")
        self.run_cli("switch", "--channel", "v2", "--yes")
        watermark = self.install / "profile-versions/legacy-v1"
        self.assertEqual(watermark.read_text(), "1.20.0\n")
        # Retained descriptors protect profiles created before highwater tracking.
        watermark.unlink()
        active = self.active()
        result = self.run_cli("switch", "--channel", "v1", "--yes", env={"CANDIDATE_VERSION": "1.19.0"}, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("profile downgrade", result.stderr)
        self.assertEqual(self.active(), active)
        self.assertFalse(watermark.exists())
        self.assert_legacy_data()

    def test_highwater_alone_blocks_older_restored_binary_start_and_switch(self):
        self.run_cli("install", "--channel", "v1")
        old = self.active()
        self.run_cli("update", env={"CANDIDATE_VERSION": "1.20.0"})
        (self.install / "active").unlink()
        (self.install / "active").symlink_to(old)
        result = self.run_cli("start", "--request-id", "old-binary", "--json", "--detach", check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("profile downgrade", result.stderr)
        self.assertFalse((self.install / "attempts/old-binary").exists())
        self.assertFalse((self.root / "serve-observed").exists())
        self.run_cli("switch", "--channel", "v2", "--yes")
        for metadata in (self.install / "releases").glob("v1-*/metadata.json"):
            metadata.unlink()
        result = self.run_cli("switch", "--channel", "v1", "--yes", env={"CANDIDATE_VERSION": "1.19.0"}, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual((self.install / "profile-versions/v1").read_text(), "1.20.0\n")

    def test_stable_version_comparison_does_not_overflow_or_sort_prereleases(self):
        for left, right, expected in [
            ("1.20.0", "1.19.99", 0), ("1.9.99", "1.10.0", 1), ("1.20.0", "1.20.0", 0),
            ("1.20.1", "1.20.0", 0), ("1.999999999999999999999999999999.0", "1.20.0", 0),
            ("1.1.999999999999999999999999999999", "1.1.999999999999999999999999999998", 0),
            ("1.20.0-beta.1", "1.19.0", 1), ("1.20.0+build", "1.19.0", 1), ("1.020.0", "1.19.0", 1),
        ]:
            with self.subTest(left=left, right=right):
                result = subprocess.run(["bash", "-c", 'source "$SCRIPT"; version_at_least "$1" "$2"', "fixture", left, right],
                                        env=self.env, text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_pid_identity_mismatch_does_not_kill_process(self):
        self.legacy()
        unrelated = subprocess.Popen(["sleep", "30"], start_new_session=True)
        try:
            (self.install / "owner").write_text(f"{unrelated.pid} 1 wrong-boot wrong-hash\n")
            self.assertEqual(self.run_cli("stop", check=False).returncode, 1)
            self.assertIsNone(unrelated.poll())
        finally:
            (self.install / "owner").unlink()
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_retired_reinstall_does_not_remove_data(self):
        self.legacy()
        for command in ["reinstall", "reinstall-opencode"]:
            self.assertNotEqual(self.run_cli(command, check=False).returncode, 0)
        self.assert_legacy_data()


if __name__ == "__main__":
    unittest.main()
