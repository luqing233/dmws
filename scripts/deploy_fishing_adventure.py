"""HK fishing release: default restored-MySQL rehearsal; --apply deploys after frozen backup verification.

Run on the server with --stage pointing to the staged jar, release.json and FishingReleaseProbe.java.
No messages or real-player gameplay actions are used for verification.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

sys.path.insert(0, "/root")
from reconcile_legacy_economy import Database, fingerprint, source_expression, identifier

APP = Path("/www/luqing/bot/dmw")
JAR = APP / "dmws-1.1.1.jar"
OLD_SHA = "ae692439388ede1f95aaf669ca83f623e742c5816797c4cdeb1be8325a7c09e4"
ASTR = "astrbot_yyhi-astrbot_yYhi-1"
DATA = Path("/www/dk_project/dk_app/astrbot/astrbot_yYhi/data")


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def emit(step, **details):
    print(json.dumps(dict(step=step, **details)), flush=True)


def migrate(db, schema):
    if schema != "dmw" and not re.fullmatch(r"dmw_fishing_restore_[0-9]+", schema):
        raise ValueError("Unexpected migration database")
    for table, column, definition in [
        ("economy_player_condition", "crime_decay_at", "datetime(6) NULL"),
        ("economy_fishing_catch", "group_id", "bigint NULL"),
        ("economy_fishing_catch", "group_name", "varchar(255) NULL"),
        ("economy_fishing_catch", "nickname", "varchar(255) NULL"),
    ]:
        present = db.sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema
                         + "' AND TABLE_NAME='" + table + "' AND COLUMN_NAME='" + column + "'")
        if int(present) == 0:
            db.sql("ALTER TABLE " + identifier(table) + " ADD COLUMN " + identifier(column) + " " + definition, schema)
    reason = db.sql("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema
                    + "' AND TABLE_NAME='economy_ledger_entry' AND COLUMN_NAME='reason'")
    if reason.startswith("enum("):
        for value in ("FISHING_PENALTY", "FISHING_EVENT_REWARD"):
            if "'" + value + "'" not in reason:
                reason = reason[:-1] + ",'" + value + "')"
        db.sql("ALTER TABLE economy_ledger_entry MODIFY reason " + reason + " NOT NULL", schema)
    elif not reason.startswith("varchar("):
        raise RuntimeError("Unsupported ledger reason storage")
    # Start the new hourly clock now; never infer pre-release hourly credit from a calendar date.
    db.sql("UPDATE economy_player_condition SET crime_decay_at=CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00') "
           "WHERE crime>0 AND crime_decay_at IS NULL", schema)


def projections(db, schema):
    result = {}
    maximum = int(db.sql("SELECT COALESCE(MAX(id),0) FROM fish", schema))
    for table in db.tables(schema):
        payload, key = source_expression(db, schema, table)
        where = " WHERE id<=" + str(maximum) if table == "fish" else ""
        if table == "economy_item_definition":
            # Only these three public item descriptions intentionally change at startup.
            payload = "IF(code IN ('electric-baton','red-bull','fishing-luck-bait'),JSON_REMOVE(" + payload + ", '$.description')," + payload + ")"
        result[table] = (payload, key, where)
    return result


def projected_hashes(db, schema, expressions):
    result = {}
    for table, (payload, key, where) in expressions.items():
        rows = db.sql("SELECT " + key + ",SHA2(CAST(" + payload + " AS CHAR),256) FROM "
                      + identifier(table) + where + " ORDER BY 1,2", schema)
        result[table] = hashlib.sha256(rows.encode()).hexdigest()
    return result


def run_probe(db, clone, stage, bootstrap):
    import yaml
    import urllib.parse
    cfg = {"spring": {"datasource": {"dmw": dict(db.config["spring"]["datasource"]["dmw"])},
                      "jpa": {"hibernate": {"ddl-auto": "update"}}, "main": {"web-application-type": "none"}},
           "shiro": {"ws": {"client": {"enable": False}}}, "logging": {"level": {"root": "WARN"}},
           "probe": {"bootstrap-only": bootstrap}}
    cfg["spring"]["datasource"]["dmw"]["url"] = re.sub(r"/dmw(?=[?]|$)", "/" + clone,
                                                         cfg["spring"]["datasource"]["dmw"]["url"])
    if urllib.parse.urlsplit(cfg["spring"]["datasource"]["dmw"]["url"].removeprefix("jdbc:")).path != "/" + clone:
        raise RuntimeError("Probe URL must select the isolated clone before Spring starts")
    config = stage / "probe-private.yaml"
    config.write_text(yaml.safe_dump(cfg))
    os.chmod(config, 0o600)
    cp = str(stage / "classes") + ":" + str(stage / "unpacked/BOOT-INF/classes") + ":" + str(stage / "unpacked/BOOT-INF/lib/*")
    log = stage / ("probe-bootstrap.log" if bootstrap else "probe-features.log")
    with log.open("w") as output:
        subprocess.run(["java", "-cp", cp, "FishingReleaseProbe", "--spring.config.location=file:" + str(config)],
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=120)
    expected = "FISHING_MYSQL_BOOTSTRAP_OK" if bootstrap else "FISHING_MYSQL_CLONE_PROBE_OK"
    if expected not in log.read_text():
        raise RuntimeError("MySQL clone probe did not complete")
    config.unlink()


def rehearse(db, backup, stage, frozen):
    clone = "dmw_fishing_restore_" + time.strftime("%Y%m%d%H%M%S")
    db.sql("CREATE DATABASE " + identifier(clone) + " CHARACTER SET utf8mb4")
    try:
        db.sql("", clone, backup / "dmw.sql")
        db.sql("", clone, backup / "restore-history-json-exact.sql")
        tables = db.tables(clone)
        restored = {table: fingerprint(db, clone, table) for table in tables}
        if frozen:
            actual = {table: fingerprint(db, "dmw", table) for table in db.tables("dmw")}
            if actual != restored:
                raise RuntimeError("Frozen backup did not restore exactly")
        expressions = projections(db, clone)
        before = projected_hashes(db, clone, expressions)
        migrate(db, clone)
        run_probe(db, clone, stage, True)
        after = projected_hashes(db, clone, expressions)
        if before != after:
            raise RuntimeError("Original data changed in migration rehearsal: "
                               + str([name for name in before if before[name] != after[name]]))
        # Prove the migration is repeatable before exercising gameplay on the isolated clone.
        migrate(db, clone)
        if after != projected_hashes(db, clone, expressions):
            raise RuntimeError("Repeated migration changed original rows")
        run_probe(db, clone, stage, False)
        proof = dict(restore_tables=len(tables), frozen_exact_restore=frozen, original_rows_preserved=True,
                     migration_repeatable=True, mysql_gameplay_probe=True, production_gameplay_actions=0)
        (backup / "rehearsal.json").write_text(json.dumps(proof, indent=2))
        emit("rehearsal_ok", **proof)
    finally:
        db.sql("DROP DATABASE " + identifier(clone))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    stage = args.stage.resolve()
    release = json.loads((stage / "release.json").read_text())
    artifact = stage / "dmws-1.1.1.jar"
    if sha(artifact) != release["jar_sha256"] or sha(JAR) != OLD_SHA:
        raise RuntimeError("Unexpected staged or production artifact")
    if release["java_tests"] < 200 or release["python_tests"] < 45:
        raise RuntimeError("Required verification is missing")
    unpacked = stage / "unpacked"
    unpacked.mkdir(exist_ok=True)
    with zipfile.ZipFile(artifact) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") or name.startswith("BOOT-INF/lib/"):
                target = (unpacked / name).resolve()
                if not target.is_relative_to(unpacked.resolve()):
                    raise RuntimeError("Unexpected jar path")
                archive.extract(name, unpacked)
    (stage / "classes").mkdir(exist_ok=True)
    cp = str(unpacked / "BOOT-INF/classes") + ":" + str(unpacked / "BOOT-INF/lib/*")
    if not (stage / "classes/FishingReleaseProbe.class").exists():
        subprocess.run(["javac", "-cp", cp, "-d", str(stage / "classes"), str(stage / "FishingReleaseProbe.java")], check=True)
    backup = Path("/root/dmws-backups") / ("fishing-adventure-" + time.strftime("%Y%m%d%H%M%S")
                                             + ("" if args.apply else "-rehearsal"))
    backup.mkdir(mode=0o700)
    stopped = installed = override_created = False
    override = Path("/run/systemd/system/dmws.service.d/90-fishing-release.conf")
    try:
        if args.apply:
            if override.exists():
                raise RuntimeError("A release override already exists; inspect it before deployment")
            subprocess.run(["docker", "stop", "--time", "30", ASTR], check=True, stdout=subprocess.DEVNULL)
            stopped = True
            subprocess.run(["systemctl", "stop", "dmws"], check=True)
            override.parent.mkdir(parents=True, exist_ok=True)
            override.write_text("[Service]\nEnvironment=SHIRO_WS_CLIENT_ENABLE=false\nEnvironment=DMWS_MCP_SERVER_ENABLED=false\n")
            override_created = True
            subprocess.run(["systemctl", "daemon-reload"], check=True)
        for path in [JAR, APP / "application.yaml", Path("/etc/systemd/system/dmws.service")]:
            shutil.copy2(path, backup / path.name)
        for name in ["mcp_server.json", "cmd_config.json", "dmw-economy-context.key", "dmw-tomato-policy.json"]:
            if (DATA / name).exists():
                shutil.copy2(DATA / name, backup / ("astrbot-" + name))
        shutil.copy2("/root/dmws-backups/equipment-20261105125437/restore-history-json-exact.sql",
                     backup / "restore-history-json-exact.sql")
        db = Database()
        db.dump("dmw", backup / "dmw.sql")
        emit("backup_complete", backup=str(backup), sha256=sha(backup / "dmw.sql"), frozen=args.apply)
        rehearse(db, backup, stage, args.apply)
        if not args.apply:
            return
        expressions = projections(db, "dmw")
        before = projected_hashes(db, "dmw", expressions)
        (backup / "before-projections.json").write_text(json.dumps(before, indent=2))
        migrate(db, "dmw")
        if before != projected_hashes(db, "dmw", expressions):
            raise RuntimeError("Migration changed existing production data")
        new = APP / "dmws-1.1.1.jar.fishing-new"
        shutil.copyfile(artifact, new)
        shutil.chown(new, user="www", group="www")
        os.chmod(new, 0o644)
        os.replace(new, JAR)
        installed = True
        subprocess.run(["systemctl", "start", "dmws"], check=True)
        health = None
        for _ in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:8002/actuator/health", timeout=2) as response:
                    health = json.load(response)
                if health.get("status") == "UP":
                    break
            except Exception:
                pass
            time.sleep(1)
        if not health or health.get("status") != "UP":
            raise RuntimeError("New application is not healthy")
        if sha(APP / "application.yaml") != sha(backup / "application.yaml"):
            raise RuntimeError("Runtime configuration changed")
        for name in ["mcp_server.json", "cmd_config.json", "dmw-economy-context.key", "dmw-tomato-policy.json"]:
            original = backup / ("astrbot-" + name)
            if original.exists() and sha(DATA / name) != sha(original):
                raise RuntimeError("AstrBot runtime configuration changed")
        after = projected_hashes(db, "dmw", expressions)
        if before != after:
            raise RuntimeError("Original production rows changed: " + str([t for t in before if before[t] != after[t]]))
        if int(db.sql("SELECT COUNT(*) FROM economy_account a WHERE a.balance<>COALESCE((SELECT SUM(e.delta) "
                      "FROM economy_ledger_entry e WHERE e.account_key=a.account_key AND e.scope=a.scope),0)")):
            raise RuntimeError("Ledger reconciliation failed")
        if int(db.sql("SELECT COUNT(*) FROM fish")) < 80 or int(db.sql("SELECT COUNT(*) FROM economy_fishing_event")) != 7:
            raise RuntimeError("Catalog bootstrap failed")
        # Original-row verification above runs before reopening either message transport.
        override.unlink()
        subprocess.run(["systemctl", "daemon-reload"], check=True)
        subprocess.run(["systemctl", "restart", "dmws"], check=True)
        for _ in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:8002/actuator/health", timeout=2) as response:
                    health = json.load(response)
                if health.get("status") == "UP":
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Activated service did not become healthy")
        subprocess.run(["docker", "start", ASTR], check=True, stdout=subprocess.DEVNULL)
        stopped = False
        proof = dict(release, backup=str(backup), deployed_at=time.strftime("%Y-%m-%d %H:%M:%S %z"),
                     health="UP", original_data_preserved=True, ledger_drift=0, qq_test_messages=0)
        proof["jar_sha256"] = sha(JAR)
        (backup / "deployment.json").write_text(json.dumps(proof, indent=2))
        shutil.copy2(__file__, backup / Path(__file__).name)
        shutil.copy2(stage / "FishingReleaseProbe.java", backup / "FishingReleaseProbe.java")
        emit("deployed", **proof)
    except BaseException:
        if installed:
            subprocess.run(["systemctl", "stop", "dmws"], check=False)
            shutil.copy2(backup / JAR.name, JAR)
            emit("jar_rolled_back", note="Additive schema retained; no gameplay data restored or erased")
            stopped = True
        raise
    finally:
        if override_created and override.exists():
            override.unlink()
            subprocess.run(["systemctl", "daemon-reload"], check=False)
        if stopped:
            subprocess.run(["systemctl", "start", "dmws"], check=False)
            subprocess.run(["docker", "start", ASTR], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
