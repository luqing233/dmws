"""Audited HK economy reconciliation. Default: read-only plan.

--prepare: back up dmw/economy/hy_economy and rehearse on restored databases.
--apply: stop DMWS, repeat the verified backup/rehearsal, reconcile transactionally,
archive every source row, and retire only the unused hy_economy tables.
The shared, still-loaded Mirai economy-core tables are deliberately retained.
Requires Linux, mysql/mysqldump and PyYAML. No passwords are printed or embedded.
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


MIGRATION = "history-reconcile-20261104"
SOURCES = ("economy", "hy_economy")
MAPPING = {"sign-2": "double-sign-card", "sign-3": "triple-sign-card",
           "sign-in": "sign-in-card", "sign-monthly": "sign-monthly-card",
           "baton": "electric-baton", "red-eyes": "red-bull", "health": "health-card",
           "rename": "rename-card"}


def identifier(value):
    if not re.fullmatch(r"[A-Za-z0-9_]+", value):
        raise ValueError("Unexpected SQL identifier")
    return "`" + value + "`"


def literal(value):
    return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)" if str(value) else "''"


def corrected_quantity(prop):
    """Extracted columns supersede stale JSON in the newer PropsData format."""
    if prop.get("prop_id") is None:
        # Broken legacy reference: retain the already-imported unit, flag separately.
        return 1
    if prop.get("column_code"):
        return max(0, int(prop.get("column_num") or 0))
    return max(0, int(prop.get("json_num") if prop.get("json_num") is not None else 1))


def imported_quantity(prop):
    return max(1, int(prop.get("json_num") if prop.get("json_num") is not None else 1))


def mapped_code(code):
    return "fishing-luck-bait" if code.startswith("bait") else MAPPING.get(code, code)


def adjustment(current, imported, corrected):
    raw = current + corrected - imported
    return max(0, raw), max(0, -raw)


class Database:
    def __init__(self):
        import yaml
        self.config = yaml.safe_load(Path("/www/luqing/bot/dmw/application.yaml").read_text())
        db = self.config["spring"]["datasource"]["dmw"]
        url = urllib.parse.urlsplit(db["url"].removeprefix("jdbc:"))
        if url.path != "/dmw":
            raise RuntimeError("Unexpected production database")
        self.connection = ["-h", url.hostname, "-P", str(url.port or 3306), "-u", db["username"]]
        self.env = dict(os.environ, MYSQL_PWD=str(db["password"]))

    def sql(self, statement, database="dmw", source=None):
        command = ["mysql", "--default-character-set=utf8mb4", "--batch", "--raw",
                   "--skip-column-names"] + self.connection + [database]
        if source:
            with Path(source).open("rb") as data:
                result = subprocess.run(command, stdin=data, env=self.env, capture_output=True)
        else:
            result = subprocess.run(command, input=statement.encode(), env=self.env, capture_output=True)
        if result.returncode:
            raise RuntimeError("MySQL failed: " + result.stderr.decode(errors="replace"))
        return result.stdout.decode().strip()

    def rows(self, statement):
        return [json.loads(row) for row in self.sql(statement).splitlines() if row]

    def tables(self, database):
        return self.sql("SHOW TABLES", database).splitlines()

    def dump(self, database, path):
        with path.open("wb") as output:
            result = subprocess.run(["mysqldump"] + self.connection + ["--single-transaction",
                    "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", database],
                    env=self.env, stdout=output, stderr=subprocess.PIPE)
        if result.returncode or path.stat().st_size == 0:
            raise RuntimeError("Backup failed")


def source_expression(db, schema, table):
    columns = db.rows("SELECT JSON_OBJECT('name',COLUMN_NAME,'type',DATA_TYPE,'pk',COLUMN_KEY) "
                      "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=" + literal(schema)
                      + " AND TABLE_NAME=" + literal(table) + " ORDER BY ORDINAL_POSITION")
    expressions = []
    for column in columns:
        field = identifier(column["name"])
        if column["type"] == "bit":
            field = "CAST(" + field + " AS UNSIGNED)"
        expressions += [literal(column["name"]), field]
    payload = "JSON_OBJECT(" + ",".join(expressions) + ")"
    primary = [identifier(column["name"]) for column in columns if column["pk"] == "PRI"]
    key = "JSON_ARRAY(" + ",".join(primary) + ")" if primary else payload
    return payload, "SHA2(CAST(" + key + " AS CHAR),256)"


def fingerprint(db, schema, table):
    payload, key = source_expression(db, schema, table)
    rows = db.sql("SELECT " + key + ",SHA2(CAST(" + payload + " AS CHAR),256) FROM "
                  + identifier(schema) + "." + identifier(table) + " ORDER BY 1,2")
    return hashlib.sha256(rows.encode()).hexdigest(), len(rows.splitlines()) if rows else 0


def inventory_plan(db, target="dmw", source="hy_economy"):
    props = db.rows("SELECT JSON_OBJECT('user_id',CAST(SUBSTRING(b.userId,2) AS UNSIGNED),"
        "'code',b.propCode,'prop_id',p.id,'column_code',p.code,'column_num',p.num,"
        "'json_num',IF(JSON_VALID(p.data),JSON_EXTRACT(p.data,'$.num'),NULL)) FROM "
        + identifier(source) + ".userbackpack b LEFT JOIN " + identifier(source)
        + ".propsdata p ON p.id=b.propId WHERE b.userId REGEXP '^u[0-9]+$' AND b.propCode<>''")
    stacks = {}
    for prop in props:
        key = (prop["user_id"], mapped_code(prop["code"]))
        stack = stacks.setdefault(key, {"imported": 0, "corrected": 0, "orphan_refs": 0})
        stack["imported"] += imported_quantity(prop)
        stack["corrected"] += corrected_quantity(prop)
        stack["orphan_refs"] += prop["prop_id"] is None
    current = {(row["user_id"], row["code"]): row["quantity"] for row in db.rows(
        "SELECT JSON_OBJECT('user_id',user_id,'code',item_code,'quantity',quantity) FROM "
        + identifier(target) + ".economy_user_item")}
    changes = []
    for (user, code), stack in sorted(stacks.items()):
        before = current.get((user, code), 0)
        after, consumed_excess = adjustment(before, stack["imported"], stack["corrected"])
        if stack["imported"] != stack["corrected"]:
            changes.append(dict(stack, user_id=user, code=code, before=before,
                                after=after, consumed_excess=consumed_excess))
    return changes, sum(stack["orphan_refs"] for stack in stacks.values())


def create_archive(db, target):
    prefix = identifier(target) + "."
    db.sql("CREATE TABLE IF NOT EXISTS " + prefix + "economy_history_record ("
        "source_schema VARCHAR(32) NOT NULL,source_table VARCHAR(64) NOT NULL,"
        "record_key CHAR(64) NOT NULL,payload JSON NOT NULL,row_hash CHAR(64) NOT NULL,"
        "archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
        "PRIMARY KEY(source_schema,source_table,record_key)) ENGINE=InnoDB")
    db.sql("CREATE TABLE IF NOT EXISTS " + prefix + "economy_history_source ("
        "source_schema VARCHAR(32) NOT NULL,source_table VARCHAR(64) NOT NULL,"
        "original_ddl LONGTEXT NOT NULL,row_count BIGINT NOT NULL,fingerprint CHAR(64) NOT NULL,"
        "PRIMARY KEY(source_schema,source_table)) ENGINE=InnoDB")
    db.sql("CREATE TABLE IF NOT EXISTS " + prefix + "economy_history_inventory ("
        "migration VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,item_code VARCHAR(64) NOT NULL,"
        "imported_quantity INT NOT NULL,correct_quantity INT NOT NULL,before_quantity INT NOT NULL,"
        "after_quantity INT NOT NULL,consumed_excess INT NOT NULL,"
        "PRIMARY KEY(migration,user_id,item_code)) ENGINE=InnoDB")


def verify_coverage(db, target, sources):
    dst = identifier(target) + "."
    economy = identifier(sources["economy"]) + "."
    hy = identifier(sources["hy_economy"]) + "."
    checks = {
        "missing_user_accounts": "SELECT COUNT(*) FROM " + economy + "economy_balance_record s LEFT JOIN "
            + dst + "economy_account t ON t.account_key=s.uuid AND t.scope=IF(s.context='global-economy','MAIN_BANK','WALLET') "
            "WHERE s.context IN ('global-economy','cn.chahuyun.HuYanEconomy') AND s.currency='hy-gold' "
            "AND s.uuid REGEXP '^u[0-9]+$' AND t.id IS NULL",
        "missing_profiles": "SELECT COUNT(*) FROM " + hy + "userinfo s LEFT JOIN " + dst
            + "economy_user_profile t ON t.user_id=s.qq WHERE t.user_id IS NULL",
        "missing_titles": "SELECT COUNT(*) FROM " + hy + "titleinfo s LEFT JOIN " + dst
            + "economy_user_title t ON t.user_id=s.userId AND t.title_code=s.code WHERE s.code<>'' AND t.id IS NULL",
        "missing_fishing_history": "SELECT COUNT(*) FROM " + hy + "fishranking s WHERE NOT EXISTS(SELECT 1 FROM "
            + dst + "economy_fishing_catch t WHERE t.user_id=s.qq AND t.fish_id=COALESCE(s.FishId,0) "
            "AND t.size_cm=GREATEST(COALESCE(s.dimensions,0),0) AND t.caught_at=s.date AND t.reward=ROUND(s.money,2))",
        "duplicate_openings": "SELECT COUNT(*) FROM (SELECT operation_id FROM " + dst
            + "economy_ledger_entry WHERE reason='OPENING_BALANCE' GROUP BY operation_id HAVING COUNT(*)>1)x",
        "ledger_drift": "SELECT COUNT(*) FROM " + dst + "economy_account a LEFT JOIN (SELECT account_key,scope,SUM(delta) net FROM "
            + dst + "economy_ledger_entry GROUP BY account_key,scope)x ON x.account_key=a.account_key AND x.scope=a.scope "
            "WHERE ABS(a.balance-COALESCE(x.net,0))>0.001",
    }
    results = {name: int(db.sql(query)) for name, query in checks.items()}
    if any(results.values()):
        raise RuntimeError("Unresolved migration coverage: " + json.dumps(results))
    return results


def reconcile(db, target, sources, output):
    coverage = verify_coverage(db, target, sources)
    create_archive(db, target)
    prefix = identifier(target) + "."
    changes, orphan_refs = inventory_plan(db, target, sources["hy_economy"])
    existing = {(r["user_id"], r["code"]) for r in db.rows(
        "SELECT JSON_OBJECT('user_id',user_id,'code',item_code) FROM " + prefix
        + "economy_history_inventory WHERE migration=" + literal(MIGRATION))}
    statements = []
    manifests = {}
    for original, schema in sources.items():
        for table in db.tables(schema):
            digest, count = fingerprint(db, schema, table)
            manifests[original + "." + table] = {"rows": count, "sha256": digest}
            payload, key = source_expression(db, schema, table)
            ddl = db.sql("SHOW CREATE TABLE " + identifier(table), schema).split("\t", 1)[1]
            statements.append("INSERT IGNORE INTO " + prefix + "economy_history_source VALUES("
                + ",".join([literal(original), literal(table), literal(ddl), str(count), literal(digest)]) + ");")
            statements.append("INSERT IGNORE INTO " + prefix + "economy_history_record "
                "(source_schema,source_table,record_key,payload,row_hash) SELECT "
                + literal(original) + "," + literal(table) + "," + key + "," + payload
                + ",SHA2(CAST(" + payload + " AS CHAR),256) FROM "
                + identifier(schema) + "." + identifier(table) + ";")
    for change in changes:
        if (change["user_id"], change["code"]) in existing:
            continue
        where = "user_id=" + str(change["user_id"]) + " AND item_code=" + literal(change["code"])
        if change["before"] != change["after"]:
            statements.append("UPDATE " + prefix + "economy_user_item SET quantity="
                + str(change["after"]) + ",updated_at=NOW() WHERE " + where + " AND quantity="
                + str(change["before"]) + "; IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' "
                "SET MESSAGE_TEXT='Inventory changed or missing; refusing reconciliation'; END IF;")
        statements.append("INSERT INTO " + prefix + "economy_history_inventory VALUES("
            + ",".join([literal(MIGRATION), str(change["user_id"]), literal(change["code"]),
              str(change["imported"]), str(change["corrected"]), str(change["before"]),
              str(change["after"]), str(change["consumed_excess"])]) + ");")
    procedure = identifier(target) + ".history_reconcile_20261104"
    script = "DROP PROCEDURE IF EXISTS " + procedure + ";\nDELIMITER $$\nCREATE PROCEDURE " + procedure + "()\nBEGIN\n"
    script += "DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;\nSTART TRANSACTION;\n"
    script += "\n".join(statements) + "\nCOMMIT;\nEND$$\nDELIMITER ;\nCALL " + procedure + "();\nDROP PROCEDURE " + procedure + ";\n"
    output.write_text(script)
    db.sql("", source=output)
    # Source and archived payload hashes must match exactly, including unsupported fields.
    for original, schema in sources.items():
        for table in db.tables(schema):
            expected = manifests[original + "." + table]
            archived = db.sql("SELECT record_key,row_hash FROM " + prefix + "economy_history_record "
                "WHERE source_schema=" + literal(original) + " AND source_table=" + literal(table)
                + " ORDER BY record_key,row_hash")
            if hashlib.sha256(archived.encode()).hexdigest() != expected["sha256"]:
                raise RuntimeError("Archive/source mismatch: " + original + "." + table)
            if fingerprint(db, schema, table)[0] != expected["sha256"]:
                raise RuntimeError("Source changed during reconciliation")
    if int(db.sql("SELECT COUNT(*) FROM " + prefix + "economy_history_record "
                  "WHERE row_hash<>SHA2(CAST(payload AS CHAR),256)")):
        raise RuntimeError("Archived payload hash mismatch")
    summary = {"source_tables": len(manifests), "source_rows": sum(m["rows"] for m in manifests.values()),
               "inventory_stacks_adjusted": sum((c["user_id"], c["code"]) not in existing for c in changes),
               "orphan_refs_preserved": orphan_refs,
               "consumed_excess": sum(c["consumed_excess"] for c in changes),
               "coverage": coverage, "manifests": manifests}
    return summary


def backup_and_rehearse(db, directory):
    clones = {}
    try:
        for original in ("dmw",) + SOURCES:
            print("BACKUP_RESTORE=" + original, flush=True)
            backup = directory / (original + ".sql")
            db.dump(original, backup)
            clone = "dmws_hist_" + str(int(time.time())) + "_" + original
            db.sql("CREATE DATABASE " + identifier(clone) + " CHARACTER SET utf8mb4")
            clones[original] = clone
            db.sql("", clone, backup)
            if set(db.tables(original)) != set(db.tables(clone)):
                raise RuntimeError("Backup table inventory mismatch")
            for table in db.tables(original):
                if fingerprint(db, original, table) != fingerprint(db, clone, table):
                    raise RuntimeError("Backup row/hash mismatch: " + original + "." + table)
        before = fingerprint(db, clones["dmw"], "economy_account")
        source_map = {source: clones[source] for source in SOURCES}
        summary = reconcile(db, clones["dmw"], source_map, directory / "rehearsal.sql")
        first = fingerprint(db, clones["dmw"], "economy_user_item")
        again = reconcile(db, clones["dmw"], source_map, directory / "rehearsal-rerun.sql")
        if first != fingerprint(db, clones["dmw"], "economy_user_item") or again["inventory_stacks_adjusted"]:
            raise RuntimeError("Reconciliation is not idempotent")
        if before != fingerprint(db, clones["dmw"], "economy_account"):
            raise RuntimeError("User/account balances changed during rehearsal")
        (directory / "rehearsal.json").write_text(json.dumps(summary, indent=2))
        return summary
    finally:
        for clone in reversed(list(clones.values())):
            db.sql("DROP DATABASE " + identifier(clone))


def verify_retired_archive(db):
    sources = db.rows("SELECT JSON_OBJECT('schema',source_schema,'table',source_table,"
                      "'rows',row_count,'hash',fingerprint) FROM dmw.economy_history_source")
    for source in sources:
        rows = db.sql("SELECT record_key,row_hash FROM dmw.economy_history_record WHERE source_schema="
            + literal(source["schema"]) + " AND source_table=" + literal(source["table"])
            + " ORDER BY record_key,row_hash")
        count = len(rows.splitlines()) if rows else 0
        if count != source["rows"] or hashlib.sha256(rows.encode()).hexdigest() != source["hash"]:
            raise RuntimeError("Retired archive manifest mismatch")
    if int(db.sql("SELECT COUNT(*) FROM dmw.economy_history_record WHERE row_hash<>SHA2(CAST(payload AS CHAR),256)")):
        raise RuntimeError("Retired archive payload mismatch")
    return sources


def finish_recovery_placeholder(db, manifests):
    recovery = "recover_your_data_info"
    if recovery not in db.tables("economy"):
        return
    columns = db.sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                     "WHERE TABLE_SCHEMA='economy' AND TABLE_NAME='recover_your_data_info' ORDER BY ORDINAL_POSITION")
    if columns != "READ_ME":
        raise RuntimeError("Unexpected business columns in recovery placeholder")
    source = next(row for row in manifests if row["schema"] == "economy" and row["table"] == recovery)
    if fingerprint(db, "economy", recovery) != (source["hash"], source["rows"]):
        raise RuntimeError("Recovery placeholder changed since verified backup")
    if int(db.sql("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                  "WHERE REFERENCED_TABLE_SCHEMA='economy' AND REFERENCED_TABLE_NAME='recover_your_data_info'")):
        raise RuntimeError("Recovery placeholder has dependent tables")
    # It has only a READ_ME field, no business fields; preserve even its explanatory
    # text in the verified archive instead of relying on approximate table row counts.
    db.sql("DROP TABLE economy.recover_your_data_info")


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    db = Database()
    if not db.tables("hy_economy"):
        if "economy_history_source" in db.tables("dmw") and int(db.sql(
                "SELECT COUNT(*) FROM dmw.economy_history_source WHERE source_schema='hy_economy'")):
            sources = verify_retired_archive(db)
            if args.apply:
                finish_recovery_placeholder(db, sources)
            print(json.dumps({"already_reconciled": True, "archive_tables": len(sources),
                "archive_rows": sum(row["rows"] for row in sources),
                "retained_economy_tables": db.tables("economy")}), flush=True)
            return
        raise RuntimeError("History sources missing without a verified archive")
    plan, orphans = inventory_plan(db)
    print(json.dumps({"inventory_stacks": len(plan), "quantity_delta": sum(c["after"]-c["before"] for c in plan),
                      "orphan_refs": orphans, "consumed_excess": sum(c["consumed_excess"] for c in plan)}), flush=True)
    if not args.prepare and not args.apply:
        return
    if int(db.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='hy_economy'")):
        raise RuntimeError("hy_economy still has connected clients")
    for schema in SOURCES:
        for catalog, field in (("TRIGGERS", "TRIGGER_SCHEMA"), ("ROUTINES", "ROUTINE_SCHEMA"),
                               ("EVENTS", "EVENT_SCHEMA"), ("VIEWS", "TABLE_SCHEMA")):
            if int(db.sql("SELECT COUNT(*) FROM information_schema." + catalog + " WHERE " + field + "=" + literal(schema))):
                raise RuntimeError("Database objects require separate review")
    directory = Path("/root/dmws-backups") / ("history-reconcile-" + time.strftime("%Y%m%d-%H%M%S"))
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    shutil.copy2("/www/luqing/bot/dmw/application.yaml", directory / "application.yaml")
    stopped = False
    try:
        # Freeze current game state even for the exact backup rehearsal.
        subprocess.run(["systemctl", "stop", "dmws"], check=True)
        stopped = True
        summary = backup_and_rehearse(db, directory)
        print("VERIFIED_BACKUP=" + str(directory), flush=True)
        if args.apply:
            before = {table: fingerprint(db, "dmw", table) for table in db.tables("dmw")}
            summary = reconcile(db, "dmw", {source: source for source in SOURCES}, directory / "apply.sql")
            for table, original in before.items():
                if table != "economy_user_item" and original != fingerprint(db, "dmw", table):
                    raise RuntimeError("Unexpected mutation of existing table: " + table)
            # Every legacy table is archived before any table is retired.
            pending = set(db.tables("hy_economy"))
            links = db.rows("SELECT JSON_OBJECT('child',TABLE_NAME,'parent',REFERENCED_TABLE_NAME,"
                "'child_schema',TABLE_SCHEMA) FROM information_schema.KEY_COLUMN_USAGE "
                "WHERE REFERENCED_TABLE_SCHEMA='hy_economy'")
            if any(link["child_schema"] != "hy_economy" for link in links):
                raise RuntimeError("External foreign keys prevent cleanup")
            drop_order = []
            while pending:
                ready = [table for table in sorted(pending) if not any(
                    link["parent"] == table and link["child"] in pending and link["child"] != table for link in links)]
                if not ready:
                    raise RuntimeError("Foreign key cycle prevents cleanup")
                for table in ready:
                    drop_order.append(table)
                    pending.remove(table)
            for table in drop_order:
                expected = summary["manifests"]["hy_economy." + table]["sha256"]
                if fingerprint(db, "hy_economy", table)[0] != expected:
                    raise RuntimeError("Source changed before drop")
            # Use one multi-table DDL, ordered children first, rather than leaving
            # a half-retired schema if an individual DROP fails.
            db.sql("DROP TABLE " + ",".join("hy_economy." + identifier(table) for table in drop_order))
            # The Mirai core still owns the other three economy tables. Only this
            # unrelated, fully archived recovery note can be retired without unloading it.
            recovery = "recover_your_data_info"
            finish_recovery_placeholder(db, verify_retired_archive(db))
            summary["retained_core_tables"] = db.tables("economy")
            summary["retired_tables"] = [name for name in summary["manifests"]
                if name.startswith("hy_economy.") or name == "economy." + recovery]
            (directory / "completed.json").write_text(json.dumps(summary, indent=2))
            print("COMPLETED=" + str(directory), flush=True)
    finally:
        if stopped:
            subprocess.run(["systemctl", "start", "dmws"], check=True)
    port = db.config.get("server", {}).get("port", 8080)
    for attempt in range(60):
        try:
            with urllib.request.urlopen("http://127.0.0.1:" + str(port) + "/actuator/health", timeout=2) as response:
                if json.load(response).get("status") == "UP":
                    print("HEALTH=UP", flush=True)
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Service health verification failed; backups retained")


if __name__ == "__main__":
    main()
