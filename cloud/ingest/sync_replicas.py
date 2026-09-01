from __future__ import annotations

import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

import requests

from supabase_store import SupabaseStore

HERE = Path(__file__).resolve().parent
GENERATED = HERE.parent / ".generated"
D1_SQL = GENERATED / "d1-sync.sql"


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def build_d1_sql(rows: list[dict[str, Any]], refresh_state: dict[str, Any] | None) -> str:
    lines = ["BEGIN TRANSACTION;"]
    for row in rows:
        barcode = str(row["barcode"])
        payload = compact_json(row["payload"])
        payload_hash = str(row["payload_hash"])
        updated_at = str(row["updated_at"])
        lines.append(
            "INSERT INTO product_snapshots (barcode,payload,payload_hash,updated_at) VALUES ("
            f"{sql_quote(barcode)},{sql_quote(payload)},{sql_quote(payload_hash)},{sql_quote(updated_at)}) "
            "ON CONFLICT(barcode) DO UPDATE SET "
            "payload=excluded.payload,payload_hash=excluded.payload_hash,updated_at=excluded.updated_at "
            "WHERE product_snapshots.payload_hash <> excluded.payload_hash;"
        )

    if refresh_state:
        state_value = compact_json(refresh_state.get("value") or {})
        state_updated = str(refresh_state.get("updated_at") or datetime.now(timezone.utc).isoformat())
        lines.append(
            "INSERT INTO system_state (key,value,updated_at) VALUES ("
            f"'last_price_refresh',{sql_quote(state_value)},{sql_quote(state_updated)}) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at;"
        )
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def chunks_by_count(items: list[dict[str, Any]], count: int = 350) -> Iterable[list[dict[str, Any]]]:
    for index in range(0, len(items), count):
        yield items[index : index + count]


def sync_firebase(rows: list[dict[str, Any]], refresh_state: dict[str, Any] | None) -> bool:
    database_url = os.environ.get("FIREBASE_DATABASE_URL", "").rstrip("/")
    service_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "")
    if not database_url or not service_json:
        print("Firebase replica skipped: FIREBASE_DATABASE_URL/FIREBASE_SERVICE_ACCOUNT_JSON not configured")
        return False

    try:
        from google.auth.transport.requests import Request
        from google.oauth2 import service_account
    except ImportError as exc:
        raise RuntimeError("google-auth is required for Firebase replica sync") from exc

    info = json.loads(service_json)
    credentials = service_account.Credentials.from_service_account_info(
        info,
        scopes=[
            "https://www.googleapis.com/auth/firebase.database",
            "https://www.googleapis.com/auth/userinfo.email",
        ],
    )
    credentials.refresh(Request())
    token = credentials.token
    session = requests.Session()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    for chunk in chunks_by_count(rows):
        patch = {str(row["barcode"]): row["payload"] for row in chunk}
        if not patch:
            continue
        response = session.patch(
            f"{database_url}/product_snapshots.json",
            params={"print": "silent"},
            headers=headers,
            data=compact_json(patch).encode("utf-8"),
            timeout=90,
        )
        response.raise_for_status()

    if refresh_state:
        response = session.put(
            f"{database_url}/system_state/last_price_refresh.json",
            params={"print": "silent"},
            headers=headers,
            data=compact_json(
                {
                    "value": refresh_state.get("value") or {},
                    "updated_at": refresh_state.get("updated_at"),
                }
            ).encode("utf-8"),
            timeout=30,
        )
        response.raise_for_status()

    print(f"Firebase replica synced: {len(rows)} changed snapshots")
    return True


def main() -> int:
    # A small overlap makes the sync idempotent if a prior run stopped after Supabase rebuild.
    overlap_hours = int(os.environ.get("REPLICA_SYNC_LOOKBACK_HOURS", "14"))
    since = datetime.now(timezone.utc) - timedelta(hours=overlap_hours)
    since_iso = since.isoformat().replace("+00:00", "Z")

    store = SupabaseStore()
    rows = store.changed_snapshots(since_iso)
    refresh_state = store.system_state("last_price_refresh")

    GENERATED.mkdir(parents=True, exist_ok=True)
    D1_SQL.write_text(build_d1_sql(rows, refresh_state), encoding="utf-8")
    print(f"D1 delta generated: {D1_SQL} ({len(rows)} changed snapshots)")

    sync_firebase(rows, refresh_state)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
