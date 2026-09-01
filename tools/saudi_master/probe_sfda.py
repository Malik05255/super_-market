from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request

KEY = "dZRGUbnQeaDndOXk9hX9GFCxXzCSraEz"
UA = "MoqarinAlasaarSaudiMasterProbe/1.0"

SOURCES = {
    "food": "https://api.sfda.gov.sa:9001/v2/FIRS/food/list",
    "cosmetics": "https://api.sfda.gov.sa:9001/v3/cosmetics/list-Active",
}


def walk_arrays(value, path="$"):
    found = []
    if isinstance(value, list):
        found.append((path, value))
        for i, child in enumerate(value[:3]):
            found.extend(walk_arrays(child, f"{path}[{i}]"))
    elif isinstance(value, dict):
        for key, child in value.items():
            found.extend(walk_arrays(child, f"{path}.{key}"))
    return found


def request(source: str, limit: int):
    base = SOURCES[source]
    query = urllib.parse.urlencode({"apikey": KEY, "limit": limit})
    req = urllib.request.Request(
        f"{base}?{query}",
        headers={"User-Agent": UA, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        body = response.read()
        content_type = response.headers.get("content-type")
        status = response.status
    parsed = json.loads(body)
    arrays = walk_arrays(parsed)
    biggest_path, biggest = max(arrays, key=lambda item: len(item[1])) if arrays else (None, [])
    top_keys = list(parsed.keys())[:30] if isinstance(parsed, dict) else []
    first = biggest[0] if biggest and isinstance(biggest[0], dict) else None
    return {
        "source": source,
        "requested_limit": limit,
        "status": status,
        "content_type": content_type,
        "bytes": len(body),
        "top_type": type(parsed).__name__,
        "top_keys": top_keys,
        "largest_array_path": biggest_path,
        "largest_array_length": len(biggest),
        "first_record_keys": list(first.keys())[:50] if first else [],
    }


def main():
    results = []
    failed = False
    for source in SOURCES:
        for limit in (10, 100, 1000):
            try:
                result = request(source, limit)
                results.append(result)
                print(json.dumps(result, ensure_ascii=False))
            except Exception as exc:
                failed = True
                result = {"source": source, "requested_limit": limit, "error": repr(exc)}
                results.append(result)
                print(json.dumps(result, ensure_ascii=False))
                break
    with open("sfda-probe.json", "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    # The workflow must still upload diagnostics when the endpoint is unreachable.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
