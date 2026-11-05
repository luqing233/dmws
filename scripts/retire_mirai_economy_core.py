"""Retire the unused HK Mirai economy-core after dependency and restore checks.

Default is read-only. --apply backs up the plugin/config/database, restarts Mirai
with the original argv/environment, validates the remaining plugins and account
APIs, and only then removes the three archived economy tables. DMWS is not stopped.
Requires reconcile_legacy_economy.py, Linux, tmux, PyYAML and MySQL CLI tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile

import yaml

from reconcile_legacy_economy import Database, fingerprint, identifier, verify_retired_archive


BASE = Path("/www/luqing/bot/overflow")
CORE = BASE / "plugins/mirai-economy-core-1.0.6.mirai2.jar"
TABLES = {"economy_account_record", "economy_balance_record", "economy_hard_record"}
TOKENS = (b"mirai-economy-core", b"xyz/cssxsh/mirai/economy", b"xyz.cssxsh.mirai.economy",
          b"economy_balance_record", b"EconomyService")


def dependency_scan():
    hits = {}
    for jar in sorted((BASE / "plugins").glob("*.jar")):
        if jar == CORE:
            continue
        with zipfile.ZipFile(jar) as archive:
            references = [name for name in archive.namelist()
                if name.endswith((".class", ".yml", ".yaml", ".json", ".properties", ".txt"))
                and any(token in archive.read(name) for token in TOKENS)]
        hits[jar.name] = references
    if any(hits.values()):
        raise RuntimeError("Other plugins reference economy-core: " + json.dumps(hits))
    return hits


def api_check():
    config = yaml.safe_load((BASE / "config/net.mamoe.mirai-api-http/setting.yml").read_text())
    endpoint = "http://127.0.0.1:" + str(config["adapterSettings"]["http"]["port"])

    def request(path, data=None, session=None):
        headers = {"Content-Type": "application/json"}
        if session:
            headers["sessionKey"] = session
        req = urllib.request.Request(endpoint + path, headers=headers,
            data=json.dumps(data).encode() if data is not None else None)
        with urllib.request.urlopen(req, timeout=10) as response:
            result = json.load(response)
        if result.get("code") != 0:
            raise RuntimeError("Mirai read-only API failed: " + path)
        return result

    bots = request("/botList")["data"]
    if not bots:
        raise RuntimeError("No Mirai account online")
    checks = {}
    for bot in bots:
        session = request("/verify", {"verifyKey": config["verifyKey"]})["session"]
        try:
            request("/bind", {"sessionKey": session, "qq": bot})
            checks[str(bot)] = {"groups": len(request("/groupList", session=session)["data"]),
                                "friends": len(request("/friendList", session=session)["data"])}
        finally:
            request("/release", {"sessionKey": session, "qq": bot})
    return checks


def find_java():
    for process in Path("/proc").iterdir():
        try:
            if process.name.isdigit() and (process / "comm").read_text().strip() == "java" \
                    and Path(os.readlink(process / "cwd")) == BASE:
                return int(process.name)
        except OSError:
            pass
    raise RuntimeError("Mirai JVM not found")


def replacement_is_running(old_pid, new_pid, proc=Path("/proc")):
    if old_pid == new_pid or (proc / str(old_pid)).exists():
        return False
    try:
        return (proc / str(new_pid) / "comm").read_text().strip() == "java"
    except OSError:
        return False


def wait_for_restart(directory, old_pid, pane):
    # Validate the target before creating a standby launcher.
    subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#{pane_pid}"],
                   check=True, stdout=subprocess.DEVNULL)
    # A second tmux window keeps the session alive even when the original shell exits.
    helper = directory / "restart.py"
    helper.write_text('''import json,os,pathlib,sys,time
p=pathlib.Path(__file__).parent
old=int(sys.argv[1]); deadline=time.time()+60
while pathlib.Path('/proc/'+str(old)).exists():
    if time.time()>deadline: raise RuntimeError('Original JVM did not stop')
    time.sleep(0.2)
r=json.loads((p/'runtime-private.json').read_text())
os.chdir(r['cwd'])
(p/'running.json').write_text(json.dumps({'pid':os.getpid()}))
os.execvpe(r['argv'][0],r['argv'],r['env'])
''')
    running = directory / "running.json"
    running.unlink(missing_ok=True)
    new_pane = subprocess.check_output(["tmux", "new-window", "-d", "-t", "overflow",
        "-n", "mirai-core-retired", "-P", "-F", "#{pane_id}",
        "python3 " + str(helper) + " " + str(old_pid)], text=True).strip()
    subprocess.run(["tmux", "send-keys", "-t", pane, "-l", "/stop"], check=True)
    subprocess.run(["tmux", "send-keys", "-t", pane, "Enter"], check=True)
    for attempt in range(90):
        try:
            new_pid = json.loads(running.read_text())["pid"]
            if replacement_is_running(old_pid, new_pid):
                return new_pane
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise RuntimeError("Replacement JVM did not start after the original exited")


def wait_for_api(expected):
    for attempt in range(90):
        try:
            actual = api_check()
            if actual == expected:
                return actual
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Mirai accounts/group/friend APIs did not recover")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--pane")
    args = parser.parse_args()
    os.umask(0o077)
    scan = dependency_scan()
    baseline = api_check()
    db = Database()
    if not CORE.exists() and not db.tables("economy"):
        verify_retired_archive(db)
        print(json.dumps({"already_retired": True, "remaining_plugins": len(scan),
                          "online_accounts": len(baseline), "economy_tables_remaining": 0}), flush=True)
        return
    if set(db.tables("economy")) != TABLES:
        raise RuntimeError("Unexpected economy schema; refusing cleanup")
    manifests = verify_retired_archive(db)
    expected = {r["table"]: (r["hash"], r["rows"]) for r in manifests if r["schema"] == "economy"}
    for table in TABLES:
        if fingerprint(db, "economy", table) != expected[table]:
            raise RuntimeError("Source changed since archival: " + table)
    print(json.dumps({"plugins_checked": len(scan), "dependencies_found": 0,
        "online_accounts": len(baseline), "account_checks": list(baseline.values())}), flush=True)
    if not args.apply:
        return
    directory = Path("/root/dmws-backups") / ("mirai-core-retire-" + time.strftime("%Y%m%d-%H%M%S"))
    directory.mkdir(parents=True, mode=0o700)
    (directory / "dependencies.json").write_text(json.dumps(scan, indent=2))
    (directory / "baseline.json").write_text(json.dumps(baseline, indent=2))
    shutil.copy2(CORE, directory / CORE.name)
    shutil.copytree(BASE / "config", directory / "config")
    shutil.copy2(BASE / "start.sh", directory / "start.sh")
    db.dump("economy", directory / "economy.sql")
    clone = "dmws_core_restore_" + str(int(time.time()))
    db.sql("CREATE DATABASE " + identifier(clone) + " CHARACTER SET utf8mb4")
    try:
        db.sql("", clone, directory / "economy.sql")
        for table in TABLES:
            if fingerprint(db, clone, table) != expected[table]:
                raise RuntimeError("Restored backup mismatch")
    finally:
        db.sql("DROP DATABASE " + identifier(clone))
    pid = find_java()
    if args.pane is None:
        ancestors = {pid}
        cursor = pid
        while cursor > 1:
            status = (Path("/proc") / str(cursor) / "status").read_text()
            cursor = int(re.search(r"^PPid:\s*(\d+)", status, re.M).group(1))
            ancestors.add(cursor)
        panes = subprocess.check_output(["tmux", "list-panes", "-a", "-F", "#{pane_id} #{pane_pid}"], text=True)
        matches = [line.split()[0] for line in panes.splitlines() if int(line.split()[1]) in ancestors]
        if len(matches) != 1:
            raise RuntimeError("Cannot identify the current Mirai pane uniquely")
        args.pane = matches[0]
    process = Path("/proc") / str(pid)
    argv = [part.decode() for part in (process / "cmdline").read_bytes().split(b"\0") if part]
    env = dict(part.decode().split("=", 1) for part in (process / "environ").read_bytes().split(b"\0") if part)
    (directory / "runtime-private.json").write_text(json.dumps({"argv": argv, "env": env, "cwd": str(BASE)}))
    log = BASE / "logs" / (time.strftime("%Y-%m-%d") + ".log")
    offset = log.stat().st_size
    old_plugins = set(re.findall(r"Successfully loaded plugin (.+) v[^\n]+", log.read_text(errors="replace")))
    expected_plugins = old_plugins - {"mirai-economy-core"}
    if len(expected_plugins) != len(scan):
        raise RuntimeError("Runtime plugin list does not match inspected jars")
    disabled = directory / "disabled"
    disabled.mkdir()
    shutil.move(str(CORE), disabled / CORE.name)
    new_pane = None
    try:
        print("BACKUP_RESTORE_VERIFIED=" + str(directory), flush=True)
        new_pane = wait_for_restart(directory, pid, args.pane)
        after = wait_for_api(baseline)
        with log.open("rb") as stream:
            stream.seek(offset)
            startup = stream.read().decode(errors="replace")
        loaded = set(re.findall(r"Successfully loaded plugin (.+) v[^\n]+", startup))
        if loaded != expected_plugins:
            raise RuntimeError("Plugin load mismatch: " + repr(loaded ^ expected_plugins))
        if int(db.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='economy'")):
            raise RuntimeError("economy still has connected clients")
        for table in TABLES:
            if fingerprint(db, "economy", table) != expected[table]:
                raise RuntimeError("Source changed before cleanup")
        if int(db.sql("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA='economy'")):
            raise RuntimeError("Foreign keys prevent cleanup")
        db.sql("DROP TABLE " + ",".join("economy." + identifier(t) for t in sorted(TABLES)))
        if db.tables("economy"):
            raise RuntimeError("Old tables were recreated")
        checks = {"remaining_plugins": sorted(loaded), "accounts_after": after,
                  "economy_tables_remaining": [], "pane": new_pane,
                  "mysql_backup_sha256": hashlib.sha256((directory / "economy.sql").read_bytes()).hexdigest()}
        (directory / "completed.json").write_text(json.dumps(checks, indent=2))
        print("COMPLETED=" + str(directory), flush=True)
        print(json.dumps({"remaining_plugins": len(loaded), "online_accounts": len(after),
                          "economy_tables_remaining": 0}), flush=True)
    except BaseException:
        if not CORE.exists():
            shutil.copy2(directory / CORE.name, CORE)
        # Restore old tables before re-enabling the plugin. No dmw rows are touched.
        if set(db.tables("economy")) != TABLES:
            db.sql("", "economy", directory / "economy.sql")
        if new_pane:
            try:
                current = find_java()
                wait_for_restart(directory, current, new_pane)
                wait_for_api(baseline)
            except Exception:
                print("Rollback needs attention; preserved backup: " + str(directory), flush=True)
        raise


if __name__ == "__main__":
    main()
