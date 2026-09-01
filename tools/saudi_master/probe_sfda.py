from __future__ import annotations

import json
import ssl
import urllib.parse
import urllib.request

KEY = "dZRGUbnQeaDndOXk9hX9GFCxXzCSraEz"
UA = "MoqarinAlasaarSaudiMasterProbe/2.0"

PROBES = [
    {
        "name": "developer_food_plain",
        "url": "https://developer.sfda.gov.sa/products/registered-food-product/registered-food-service",
        "params": {},
    },
    {
        "name": "developer_food_key",
        "url": "https://developer.sfda.gov.sa/products/registered-food-product/registered-food-service",
        "params": {"apikey": KEY},
    },
    {
        "name": "legacy_food",
        "url": "https://api.sfda.gov.sa:9001/v2/FIRS/food/list",
        "params": {"apikey": KEY, "limit": 10},
    },
    {
        "name": "legacy_cosmetics",
        "url": "https://api.sfda.gov.sa:9001/v3/cosmetics/list-Active",
        "params": {"apikey": KEY, "limit": 10},
    },
]


def summarize_json(parsed):
    if isinstance(parsed, list):
        first = parsed[0] if parsed and isinstance(parsed[0], dict) else None
        return {
            "top_type": "list",
            "array_length": len(parsed),
            "first_record_keys": list(first.keys())[:60] if first else [],
        }
    if isinstance(parsed, dict):
        arrays = []

        def walk(value, path="$"):
            if isinstance(value, list):
                arrays.append((path, value))
                for i, child in enumerate(value[:2]):
                    walk(child, f"{path}[{i}]")
            elif isinstance(value, dict):
                for key, child in value.items():
                    walk(child, f"{path}.{key}")

        walk(parsed)
        biggest_path, biggest = max(arrays, key=lambda item: len(item[1])) if arrays else (None, [])
        first = biggest[0] if biggest and isinstance(biggest[0], dict) else None
        return {
            "top_type": "dict",
            "top_keys": list(parsed.keys())[:50],
            "largest_array_path": biggest_path,
            "largest_array_length": len(biggest),
            "first_record_keys": list(first.keys())[:60] if first else [],
        }
    return {"top_type": type(parsed).__name__}


def request_probe(probe):
    query = urllib.parse.urlencode(probe["params"])
    url = probe["url"] + (f"?{query}" if query else "")
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "application/json,text/plain,*/*"},
    )
    with urllib.request.urlopen(req, timeout=20, context=ssl.create_default_context()) as response:
        body = response.read()
        content_type = response.headers.get("content-type")
        status = response.status
        final_url = response.geturl()
    text = body.decode("utf-8", errors="replace")
    result = {
        "name": probe["name"],
        "status": status,
        "content_type": content_type,
        "bytes": len(body),
        "final_url": final_url,
        "preview": text[:500],
    }
    try:
        parsed = json.loads(text)
    except Exception:
        return result
    result.update(summarize_json(parsed))
    return result


def main():
    results = []
    for probe in PROBES:
        try:
            result = request_probe(probe)
        except Exception as exc:
            result = {"name": probe["name"], "error": repr(exc)}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False))
    with open("sfda-probe.json", "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
