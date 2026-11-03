#!/usr/bin/env python3
"""Update the server config for the single-database 1.1.0 deployment."""

from pathlib import Path
import sys

import yaml


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: update_server_config.py <application.yaml> <operator-id>")

    path = Path(sys.argv[1])
    operator_id = int(sys.argv[2])
    config = yaml.safe_load(path.read_text(encoding="utf-8"))

    datasource = config.setdefault("spring", {}).setdefault("datasource", {})
    datasource.pop("economy", None)
    datasource.pop("hy-economy", None)

    economy = (
        config.setdefault("dmws", {})
        .setdefault("mcp", {})
        .setdefault("economy", {})
    )
    economy["operator-id"] = operator_id

    path.write_text(
        yaml.safe_dump(config, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    print("datasources=" + ",".join(sorted(datasource)))
    print(f"mcp.economy.operator-id={operator_id}")


if __name__ == "__main__":
    main()
