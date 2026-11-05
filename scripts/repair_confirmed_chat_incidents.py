"""Bounded repair of two confirmed 2026-11-04 incidents. Default: read-only.

Run only on the HK host with its existing Database helper. Original ledger and robbery
records are never deleted. --apply requires an existing full backup and stopped DMWS
(except an explicitly named dmw_guard_rehearsal_* database).
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
from reconcile_legacy_economy import Database

REFUND = "repair:20261104:duplicate-bait-1329158513"
WRONG_ACTOR = "repair:20261104:wrong-actor-2209"
ORIGINAL = "item-buy:-1295435687"
DUPLICATE = "mcp:buy:buy-20261104-2216-bait-xy"
WRONG_ROB = "mcp:rob:rob-20261104-2209-mont1s"


def select_snapshot(db, database):
    queries = {
        "operations": "SELECT JSON_OBJECT('id',operation_id,'actor',actor_id,'type',operation_type,'description',description) FROM economy_operation WHERE operation_id IN ('" + "','".join([ORIGINAL, DUPLICATE, WRONG_ROB, REFUND, WRONG_ACTOR]) + "') ORDER BY operation_id",
        "ledger": "SELECT JSON_OBJECT('id',id,'op',operation_id,'key',account_key,'scope',scope,'delta',delta,'after',balance_after,'reason',reason,'description',description) FROM economy_ledger_entry WHERE operation_id IN ('" + "','".join([ORIGINAL, DUPLICATE, REFUND]) + "') ORDER BY id",
        "inventory": "SELECT JSON_OBJECT('id',id,'user',user_id,'code',item_code,'quantity',quantity) FROM economy_user_item WHERE user_id=1329158513 AND item_code='fishing-luck-bait'",
        "account": "SELECT JSON_OBJECT('id',id,'key',account_key,'scope',scope,'balance',balance,'version',version) FROM economy_account WHERE account_key='u1329158513' AND scope='WALLET'",
        "condition": "SELECT JSON_OBJECT('user',user_id,'crime',crime,'injury',injury,'since',hospital_since,'until',hospital_until,'session',hospital_session,'diagnosis',diagnosis_fee,'hourly',hourly_fee) FROM economy_player_condition WHERE user_id=3253912136",
        "drift": "SELECT JSON_OBJECT('count',COUNT(*)) FROM economy_account a WHERE a.balance<>COALESCE((SELECT SUM(e.delta) FROM economy_ledger_entry e WHERE e.account_key=a.account_key AND e.scope=a.scope),0)",
    }
    return {label: [json.loads(line) for line in db.sql(q, database=database).splitlines() if line]
            for label, q in queries.items()}


def validate(snapshot):
    done = {row["id"] for row in snapshot["operations"]}
    if snapshot["drift"] != [{"count": 0}]:
        raise RuntimeError("Ledger drift prevents repair")
    refund_done, actor_done = REFUND in done, WRONG_ACTOR in done
    for op in [ORIGINAL, DUPLICATE]:
        rows = [r for r in snapshot["ledger"] if r["op"] == op]
        if len(rows) != 1 or any(rows[0][k] != v for k, v in {
            "key": "u1329158513", "scope": "WALLET", "delta": -300,
            "reason": "SHOP_PURCHASE", "description": "购买 幸运鱼饵 x1"}.items()):
            raise RuntimeError("Original purchase evidence does not match")
    if refund_done:
        refunds = [r for r in snapshot["ledger"] if r["op"] == REFUND]
        if len(refunds) != 1 or refunds[0]["delta"] != 300 or refunds[0]["key"] != "u1329158513" or refunds[0]["scope"] != "WALLET":
            raise RuntimeError("Existing refund receipt is inconsistent")
    if not refund_done and (len(snapshot["inventory"]) != 1 or snapshot["inventory"][0]["quantity"] < 1):
        raise RuntimeError("Cannot safely reclaim the duplicate item")
    if not actor_done:
        rows = snapshot["condition"]
        if len(rows) != 1 or rows[0]["crime"] != 20 or rows[0]["injury"] != 84 or rows[0]["session"] != "c5f3b2e1-4008-429b-9d6d-f4a1323655ef":
            raise RuntimeError("Wrong-actor stay changed; manual reconciliation required")
    return refund_done, actor_done


def transaction_sql(refund_done, actor_done):
    def require(predicate):
        return "INSERT INTO dmw_incident_assert(ok) SELECT IF((" + predicate + "),1,0);\n"
    sql = "CREATE TEMPORARY TABLE dmw_incident_assert(ok INT NOT NULL CHECK(ok=1));\nSTART TRANSACTION;\n"
    sql += "SELECT user_id FROM economy_user_profile WHERE user_id IN (1329158513,3253912136) ORDER BY user_id FOR UPDATE;\n"
    if not refund_done:
        sql += "SELECT id FROM economy_user_item WHERE user_id=1329158513 AND item_code='fishing-luck-bait' FOR UPDATE;\n"
        sql += "SELECT id FROM economy_account WHERE account_key='u1329158513' AND scope='WALLET' FOR UPDATE;\n"
        sql += require("SELECT COUNT(*)=2 FROM economy_ledger_entry WHERE operation_id IN ('" + ORIGINAL + "','" + DUPLICATE + "') AND account_key='u1329158513' AND scope='WALLET' AND reason='SHOP_PURCHASE' AND delta=-300 AND description='购买 幸运鱼饵 x1'")
        sql += require("SELECT COUNT(*)=1 FROM economy_user_item WHERE user_id=1329158513 AND item_code='fishing-luck-bait' AND quantity>=1")
        sql += "INSERT INTO economy_operation(operation_id,operation_type,actor_id,description,created_at) VALUES ('" + REFUND + "','INCIDENT_REFUND',NULL,'撤销同一消息原生与MCP重复购买：退300，回收鱼饵1；保留原流水',NOW(6));\n"
        sql += "UPDATE economy_user_item SET quantity=quantity-1,updated_at=NOW(6) WHERE user_id=1329158513 AND item_code='fishing-luck-bait';\n"
        sql += "UPDATE economy_account SET balance=balance+300,version=version+1,updated_at=NOW(6) WHERE account_key='u1329158513' AND scope='WALLET';\n"
        sql += require("ROW_COUNT()=1")
        sql += "INSERT INTO economy_ledger_entry(account_key,scope,actor_id,balance_after,created_at,delta,description,operation_id,reason) SELECT account_key,scope,NULL,balance,NOW(6),300,'退回重复购买幸运鱼饵款；原MCP请求 buy-20261104-2216-bait-xy','" + REFUND + "','ADMIN_ADJUST' FROM economy_account WHERE account_key='u1329158513' AND scope='WALLET';\n"
    if not actor_done:
        sql += "SELECT user_id FROM economy_player_condition WHERE user_id=3253912136 FOR UPDATE;\n"
        sql += require("SELECT COUNT(*)=1 FROM economy_rob_record WHERE operation_id='" + WRONG_ROB + "' AND robber_id=3253912136 AND target_id=1437370109 AND outcome='COUNTERED' AND injury=84")
        sql += require("SELECT COUNT(*)=0 FROM economy_ledger_entry WHERE operation_id='medical:c5f3b2e1-4008-429b-9d6d-f4a1323655ef'")
        sql += require("SELECT COUNT(*)=1 FROM economy_player_condition WHERE user_id=3253912136 AND crime=20 AND injury=84 AND hospital_session='c5f3b2e1-4008-429b-9d6d-f4a1323655ef' AND hospital_since='2026-11-04 22:09:27.516117'")
        sql += "INSERT INTO economy_operation(operation_id,operation_type,actor_id,description,created_at) VALUES ('" + WRONG_ACTOR + "','INCIDENT_STATE_REPAIR',NULL,'撤销MCP错误使用管理员的22:09抢劫影响：取消未结算住院，犯罪值20恢复15；原抢劫记录保留',NOW(6));\n"
        sql += "UPDATE economy_player_condition SET crime=crime-5,injury=0,hospital_since=NULL,hospital_until=NULL,hospital_session=NULL,diagnosis_fee=NULL,hourly_fee=NULL WHERE user_id=3253912136;\n"
    sql += require("SELECT COUNT(*)=0 FROM economy_account a WHERE a.balance<>COALESCE((SELECT SUM(e.delta) FROM economy_ledger_entry e WHERE e.account_key=a.account_key AND e.scope=a.scope),0)")
    return sql + "COMMIT;\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--database", default="dmw")
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args()
    if args.database != "dmw" and not re.fullmatch(r"dmw_guard_rehearsal_[0-9]+", args.database):
        raise RuntimeError("Unexpected target database")
    db = Database(); before = select_snapshot(db, args.database)
    done = validate(before)
    print(json.dumps({"database": args.database, "before": before, "already_repaired": done}, ensure_ascii=False))
    if not args.apply or all(done):
        return
    if not args.backup_dir or not (args.backup_dir / "dmw.sql").is_file() or (args.backup_dir / "dmw.sql").stat().st_size == 0:
        raise RuntimeError("Full backup required")
    if args.database == "dmw" and subprocess.run(["systemctl", "is-active", "--quiet", "dmws"]).returncode == 0:
        raise RuntimeError("Stop DMWS before applying production repair")
    prefix = args.backup_dir / ("incident-" + args.database)
    prefix.with_suffix(".before.json").write_text(json.dumps(before, ensure_ascii=False, indent=2))
    sql = transaction_sql(*done)
    prefix.with_suffix(".sql").write_text(sql)
    db.sql(sql, database=args.database)  # one connection; any assertion error exits and rolls back
    after = select_snapshot(db, args.database)
    if validate(after) != (True, True):
        raise RuntimeError("Repair verification failed")
    if not done[0]:
        from decimal import Decimal
        if Decimal(str(after["account"][0]["balance"])) != Decimal(str(before["account"][0]["balance"])) + 300:
            raise RuntimeError("Refund balance mismatch")
        if after["inventory"][0]["quantity"] != before["inventory"][0]["quantity"] - 1:
            raise RuntimeError("Reclaimed inventory mismatch")
    if not done[1] and (after["condition"][0]["injury"] != 0 or after["condition"][0]["crime"] != 15 or after["condition"][0]["session"] is not None):
        raise RuntimeError("Wrong-actor state mismatch")
    prefix.with_suffix(".after.json").write_text(json.dumps(after, ensure_ascii=False, indent=2))
    print(json.dumps({"after": after}, ensure_ascii=False))


if __name__ == "__main__": main()
