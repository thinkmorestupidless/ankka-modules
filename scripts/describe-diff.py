#!/usr/bin/env python3
"""One line per operation added, removed or changed between two service descriptions (spec 031 FR-011).

Usage: describe-diff.py <kept openapi.player.json> <fresh openapi.player.json>
Prints `added GET /path`, `removed GET /path` or `changed GET /path`, by method and path, sorted;
exits 1 when there is any, else 0. An operation is changed when anything under it differs, or a
component schema it reaches does.
"""
import json
import sys

METHODS = ("get", "put", "post", "delete", "patch")


def refs(node, found):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str) and v.startswith("#/components/schemas/"):
                found.add(v.rsplit("/", 1)[1])
            else:
                refs(v, found)
    elif isinstance(node, list):
        for v in node:
            refs(v, found)


def reached(op, schemas):
    found, todo = set(), set()
    refs(op, todo)
    while todo:
        name = todo.pop()
        if name in found:
            continue
        found.add(name)
        refs(schemas.get(name, {}), todo)
    return {n: schemas.get(n) for n in sorted(found)}


def operations(doc):
    schemas = doc.get("components", {}).get("schemas", {})
    out = {}
    for path, item in doc.get("paths", {}).items():
        for method in METHODS:
            if method in item:
                out[f"{method.upper()} {path}"] = (item[method], reached(item[method], schemas))
    return out


def diff(kept, fresh):
    a, b = operations(kept), operations(fresh)
    lines = [f"added {k}" for k in b.keys() - a.keys()]
    lines += [f"removed {k}" for k in a.keys() - b.keys()]
    lines += [f"changed {k}" for k in a.keys() & b.keys() if a[k] != b[k]]
    return sorted(lines, key=lambda l: (l.split(" ", 1)[1], l))


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    with open(argv[1]) as f:
        kept = json.load(f)
    with open(argv[2]) as f:
        fresh = json.load(f)
    lines = diff(kept, fresh)
    for line in lines:
        print(line)
    return 1 if lines else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
