#!/usr/bin/env python3
"""Remove retired configuration keys, backing up exact original bytes first.

The historical <application.yaml> <operator-id> CLI remains supported.
Omit operator-id to leave the current MCP operator untouched; --dry-run is read-only.
"""

import argparse
from copy import deepcopy
from datetime import datetime
import os
from pathlib import Path
import shutil
import stat
import tempfile
import uuid

import yaml


def clean_config(config, operator_id=None):
    result = deepcopy(config)
    removed = []
    datasource = result.get("spring", {}).get("datasource", {})
    for key in ("economy", "hy-economy", "hy_economy", "hyEconomy"):
        if key in datasource:
            datasource.pop(key)
            removed.append("spring.datasource." + key)
    dmws = result.get("dmws", {})
    for key in ("ws-url", "http-url", "max-messages", "wsUrl", "httpUrl", "maxMessages"):
        if key in dmws:
            dmws.pop(key)
            removed.append("dmws." + key)
    if operator_id is not None:
        if operator_id <= 0:
            raise ValueError("operator-id must be a positive QQ user ID")
        result.setdefault("dmws", {}).setdefault("mcp", {}).setdefault("economy", {})["operator-id"] = operator_id
    return result, removed


def update_file(path, operator_id=None, dry_run=False, backup_dir=None):
    path = Path(path)
    original = path.read_bytes()
    config = yaml.safe_load(original.decode("utf-8-sig")) or {}
    if not isinstance(config, dict):
        raise ValueError("Configuration root must be a YAML mapping")
    updated, removed = clean_config(config, operator_id)
    if dry_run or updated == config:
        return {"changed": updated != config, "removed": removed, "backup": None}
    directory = Path(backup_dir) if backup_dir else Path.home() / ".codex/backups/dmws" / (
        "config-cleanup-" + datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    backup = directory / (path.name + "." + uuid.uuid4().hex[:8] + ".bak")
    shutil.copy2(path, backup)
    if backup.read_bytes() != original:
        raise RuntimeError("Backup verification failed; original configuration unchanged")
    pending = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=path.name + ".", suffix=".tmp", delete=False) as stream:
            pending = Path(stream.name)
            yaml.safe_dump(updated, stream, allow_unicode=True, sort_keys=False)
        os.chmod(pending, stat.S_IMODE(path.stat().st_mode))
        os.replace(pending, path)
    finally:
        if pending is not None:
            pending.unlink(missing_ok=True)
    return {"changed": True, "removed": removed, "backup": str(backup)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("operator_id", nargs="?", type=int)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    report = update_file(args.config, args.operator_id, args.dry_run)
    print("changed=" + str(report["changed"]))
    print("removed=" + ",".join(report["removed"]))
    if report["backup"]:
        print("verified_backup=" + report["backup"])


if __name__ == "__main__":
    main()
