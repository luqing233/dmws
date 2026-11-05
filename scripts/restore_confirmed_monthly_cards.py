"""Restore provable monthly-card terms; default is a read-only plan, never mint inventory.

Requires the existing migration archives and a backup before --apply. Historical
random rewards alone are not enough: reject invalid baseline stock, ambiguous
earlier monthly-sized rewards, and accounts with an already active term.
"""
import argparse
import datetime as dt
import json
from collections import defaultdict
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from reconcile_legacy_economy import Database, corrected_quantity, identifier, literal


def build_plan(signs, stock, baseline, existing, today, sign_max=Decimal("500")):
    grouped = defaultdict(list)
    for row in signs:
        grouped[int(row["user_id"])].append(row)
    plans, excluded = [], []
    for user_id, rows in sorted(grouped.items()):
        proofs = [r for r in rows if Decimal(str(r["reward"])) > sign_max * 3
                  and Decimal(str(r["reward"])) % 5 == 0]
        if not proofs:
            continue
        reason = None
        if baseline.get(user_id, 0) < stock.get(user_id, 0) + 1:
            reason = "no_valid_consumed_baseline_card"
        elif existing.get(user_id):
            reason = "term_already_present"
        elif len(proofs) != 1:
            reason = "multiple_consumptions_need_manual_review"
        elif any(r not in proofs and Decimal(str(r["reward"])) >= 250
                 and Decimal(str(r["reward"])) % 5 == 0 for r in rows):
            reason = "ambiguous_other_sign_rewards"
        if reason:
            excluded.append({"user_id": user_id, "reason": reason})
            continue
        proof = proofs[0]
        activation = dt.date.fromisoformat(proof["created_at"][:10])
        expiry = activation + dt.timedelta(days=30)
        if proof["last_sign_date"][:10] != activation.isoformat():
            reason = "sign_date_needs_timezone_or_history_review"
        elif not activation <= today < expiry:
            reason = "outside_active_window"
        if reason:
            excluded.append({"user_id": user_id, "reason": reason})
            continue
        plans.append({"user_id": user_id, "activated_on": activation.isoformat(), "expires_on": expiry.isoformat(),
                      "evidence_operation": proof["operation_id"], "baseline_quantity": baseline[user_id],
                      "inventory_unchanged": stock.get(user_id, 0)})
    return plans, excluded


def audit(db, database, today, sign_max):
    prefix = identifier(database) + "."
    has_expiry = db.rows("SELECT JSON_OBJECT('count',COUNT(*)) FROM information_schema.columns WHERE table_schema="
                         + literal(database) + " AND table_name='economy_user_profile' AND column_name='monthly_sign_expires_on'")[0]["count"]
    expiry = "p.monthly_sign_expires_on" if has_expiry else "NULL"
    signs = db.rows("SELECT JSON_OBJECT('user_id',p.user_id,'created_at',e.created_at,'reward',e.delta,"
                    "'last_sign_date',p.last_sign_date,'operation_id',e.operation_id,'expiry'," + expiry + ") FROM "
                    + prefix + "economy_ledger_entry e JOIN " + prefix + "economy_user_profile p ON e.account_key=CONCAT('u',p.user_id) "
                    "WHERE e.reason='SIGN_REWARD' AND e.created_at>=" + literal((today-dt.timedelta(days=30)).isoformat()))
    stock = {int(r["user_id"]): int(r["quantity"]) for r in db.rows("SELECT JSON_OBJECT('user_id',user_id,'quantity',quantity) FROM "
              + prefix + "economy_user_item WHERE item_code='sign-monthly-card'")}
    props = {str(r["id"]): r for r in db.rows("SELECT payload FROM " + prefix
             + "economy_history_record WHERE source_schema='hy_economy' AND source_table='propsdata'")}
    packs = db.rows("SELECT payload FROM " + prefix + "economy_history_record WHERE source_schema='hy_economy' "
                    "AND source_table='userbackpack' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.propCode'))='sign-monthly'")
    baseline = defaultdict(int)
    for pack in packs:
        owner = str(pack.get("userId", ""))
        if not owner.startswith("u") or not owner[1:].isdigit():
            continue
        prop = props.get(str(pack.get("propId")), {})
        if not prop or prop.get("code") not in (None, "", "sign-monthly"):
            continue  # An orphaned/mismatched reference cannot prove an entitlement.
        try:
            embedded = json.loads(prop.get("data") or "{}")
        except (TypeError, ValueError):
            embedded = {}
        if not isinstance(embedded, dict):
            embedded = {}
        baseline[int(owner[1:])] += corrected_quantity({"prop_id": prop.get("id"), "column_code": prop.get("code"),
            "column_num": prop.get("num"), "json_num": embedded.get("num")})
    # Native purchases or item grants require individual reconciliation, not an inferred archive baseline.
    purchase_users = {int(r["actor_id"]) for r in db.rows("SELECT JSON_OBJECT('actor_id',actor_id) FROM " + prefix
                      + "economy_operation WHERE operation_type='SHOP_PURCHASE' AND description LIKE '%sign-monthly-card%'")}
    for user in purchase_users:
        baseline.pop(user, None)
    existing = {int(r["user_id"]): r["expiry"] for r in signs}
    plan, excluded = build_plan(signs, stock, baseline, existing, today, sign_max)
    return {"database": database, "today": today.isoformat(), "plan": plan, "excluded": excluded}


def apply(db, report):
    prefix = identifier(report["database"]) + "."
    statements = ["START TRANSACTION"]
    for row in report["plan"]:
        user = int(row["user_id"])
        key = "monthly-repair:" + row["activated_on"] + ":" + str(user)
        statements += ["SELECT user_id FROM " + prefix + "economy_user_profile WHERE user_id=" + str(user) + " FOR UPDATE",
            "UPDATE " + prefix + "economy_user_profile SET monthly_sign_expires_on=" + literal(row["expires_on"])
            + " WHERE user_id=" + str(user) + " AND monthly_sign_expires_on IS NULL AND NOT EXISTS(SELECT 1 FROM "
            + prefix + "economy_operation WHERE operation_id=" + literal(key) + ")",
            "SET @dmw_monthly_changed=ROW_COUNT()",
            "INSERT INTO " + prefix + "economy_operation(operation_id,operation_type,actor_id,description,created_at) SELECT "
            + literal(key) + ",'MONTHLY_CARD_REPAIR'," + str(user) + ","
            + literal("按已核实库存和签到流水恢复月卡至 " + row["expires_on"] + "（不含），不补发道具或金币")
            + ",NOW() WHERE @dmw_monthly_changed=1"]
    statements.append("COMMIT")
    db.sql(";\n".join(statements) + ";")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="dmw")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--today", default=dt.datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat())
    parser.add_argument("--sign-max", type=Decimal, default=Decimal("500"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    db = Database()
    report = audit(db, args.database, dt.date.fromisoformat(args.today), args.sign_max)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.apply:
        apply(db, report)
    print(json.dumps({"planned": len(report["plan"]), "excluded": len(report["excluded"]), "applied": args.apply}))


if __name__ == "__main__":
    main()
