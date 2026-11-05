"""Retire only the six audited, empty DMWS game tables on Linux/MySQL.

Requires PyYAML and mysql/mysqldump. Defaults to read-only audit.
--prepare rehearses a full backup restore without stopping the service.
--apply --artifact PATH stops dmws, repeats backup/restore verification,
removes empty tables and installs the supplied jar, with rollback on failure.
Credentials are read from the existing server config and never printed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile

import yaml


TABLES = ("user_info", "user_backpack", "user_sign_in_history", "user_status",
          "item_data", "hospitalization_history")
BASE = Path("/www/luqing/bot/dmw")
JAR = BASE / "dmws-1.1.1.jar"


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--artifact", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    cfg = yaml.safe_load((BASE / "application.yaml").read_text())
    db = cfg["spring"]["datasource"]["dmw"]
    url = urllib.parse.urlsplit(db["url"].removeprefix("jdbc:"))
    if url.path != "/dmw":
        raise RuntimeError("Refusing a database other than dmw")
    connection = ["-h", url.hostname, "-P", str(url.port or 3306), "-u", db["username"]]
    env = dict(os.environ, MYSQL_PWD=str(db["password"]))

    def sql(statement, database="dmw", input_file=None):
        command = ["mysql", "--batch", "--skip-column-names"] + connection + [database]
        if input_file:
            with open(input_file, "rb") as source:
                result = subprocess.run(command, stdin=source, env=env, capture_output=True)
        else:
            result = subprocess.run(command + ["-e", statement], env=env, capture_output=True)
        if result.returncode:
            raise RuntimeError("MySQL command failed: " + result.stderr.decode(errors="replace"))
        return result.stdout.decode().strip()

    def inventory():
        names = sql("SHOW TABLES").splitlines()
        return {name: int(sql("SELECT COUNT(*) FROM `" + name.replace("`", "``") + "`"))
                for name in names}

    def guard():
        counts = inventory()
        if any(name not in counts or counts[name] != 0 for name in TABLES):
            raise RuntimeError("Legacy tables missing or nonempty; no automatic deletion allowed")
        # Dependencies and executable database objects need separate review.
        for query in (
            "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='dmw' AND REFERENCED_TABLE_NAME IS NOT NULL",
            "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='dmw'",
            "SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='dmw'",
            "SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='dmw'",
            "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='dmw'",
        ):
            if int(sql(query)):
                raise RuntimeError("Database dependencies require manual review")
        print(json.dumps({"legacy_counts": {name: counts[name] for name in TABLES},
                          "fish_rows_kept": counts.get("fish")}), flush=True)
        return counts

    def dump(path, tables=()):
        command = ["mysqldump"] + connection + ["--single-transaction", "--no-tablespaces",
                    "--set-gtid-purged=OFF", "--hex-blob", "dmw"] + list(tables)
        with path.open("wb") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, env=env)
        if result.returncode or path.stat().st_size == 0:
            raise RuntimeError("Database backup failed")

    def backup(counts, frozen):
        directory = Path("/root/dmws-backups") / ("legacy-retire-" + time.strftime("%Y%m%d-%H%M%S"))
        directory.mkdir(parents=True, exist_ok=False, mode=0o700)
        dump(directory / "dmw.sql")
        dump(directory / "legacy-empty.sql", TABLES)
        shutil.copy2(JAR, directory / JAR.name)
        shutil.copy2(BASE / "application.yaml", directory / "application.yaml")
        shutil.copy2("/etc/systemd/system/dmws.service", directory / "dmws.service")
        clone = "dmws_restore_" + time.strftime("%Y%m%d%H%M%S")
        sql("CREATE DATABASE `" + clone + "` CHARACTER SET utf8mb4")
        try:
            sql("", clone, directory / "dmw.sql")
            restored = sql("SHOW TABLES", clone).splitlines()
            if set(restored) != set(counts):
                raise RuntimeError("Restore table inventory mismatch")
            restored_counts = {table: int(sql("SELECT COUNT(*) FROM `" + table + "`", clone))
                               for table in restored}
            if frozen and restored_counts != counts:
                raise RuntimeError("Restore row-count verification failed")
            if any(restored_counts[name] != 0 for name in TABLES):
                raise RuntimeError("Legacy data changed during backup")
            # CHECK TABLE verifies physical restore integrity; all results must say OK.
            for table in restored:
                check = sql("CHECK TABLE `" + table + "`", clone)
                if not check.endswith("\tOK"):
                    raise RuntimeError("Restored table integrity check failed")
            evidence = {"restore_verified": True, "frozen": frozen, "restored_counts": restored_counts,
                        "sha256": hashlib.sha256((directory / "dmw.sql").read_bytes()).hexdigest()}
            (directory / "verification.json").write_text(json.dumps(evidence, indent=2))
        finally:
            sql("DROP DATABASE `" + clone + "`")
        print("VERIFIED_BACKUP=" + str(directory), flush=True)
        return directory

    counts = guard()
    if not args.prepare and not args.apply:
        return
    if args.prepare:
        backup(counts, False)
        return
    if not args.artifact or not args.artifact.is_file():
        raise RuntimeError("A staged artifact is required")
    with zipfile.ZipFile(args.artifact) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Corrupt jar")
        for legacy in ("UserInfo", "UserBackpack", "UserSignInHistory", "UserStatus",
                       "ItemData", "HospitalizationHistory"):
            if any(name.endswith("/" + legacy + ".class") for name in archive.namelist()):
                raise RuntimeError("Jar still includes retired mappings")
        if not any(name.endswith("/entity/dmw/fish/Fish.class") for name in archive.namelist()):
            raise RuntimeError("Jar must keep the active fish entity")
    subprocess.run(["systemctl", "stop", "dmws"], check=True)
    directory = None
    changed = False
    try:
        counts = guard()
        directory = backup(counts, True)
        changed = True
        sql("DROP TABLE " + ",".join("`" + name + "`" for name in TABLES))
        installed = JAR.with_suffix(".jar.pending")
        shutil.copy2(args.artifact, installed)
        os.replace(installed, JAR)
        subprocess.run(["systemctl", "start", "dmws"], check=True)
        port = cfg.get("server", {}).get("port", 8080)
        for attempt in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:" + str(port) + "/actuator/health", timeout=2) as health:
                    if json.load(health).get("status") == "UP":
                        break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("New application health check failed")
        after = inventory()
        if any(name in after for name in TABLES):
            raise RuntimeError("Legacy tables were recreated")
        for name, count in counts.items():
            if name not in TABLES and after.get(name, -1) < count:
                raise RuntimeError("Active table row count decreased: " + name)
        (directory / "completed.json").write_text(json.dumps({"removed": TABLES, "after": after}, indent=2))
        print("COMPLETED=" + str(directory), flush=True)
    except BaseException:
        if changed:
            subprocess.run(["systemctl", "stop", "dmws"], check=True)
            # Only restore the six empty legacy tables, never overwrite live economy data.
            sql("", input_file=directory / "legacy-empty.sql")
            shutil.copy2(directory / JAR.name, JAR)
        subprocess.run(["systemctl", "start", "dmws"], check=True)
        raise


if __name__ == "__main__":
    main()
