"""Records the original stand-in's operator API twice on fresh ids (spec 001 contracts/recording.md).

usage: python3 -I record_operator_api.py <stand-in base url> <out dir> <original commit>
"""
import datetime, json, os, sys, urllib.request, uuid

base, out, commit = sys.argv[1], sys.argv[2], sys.argv[3]


def call(method, path, body=None):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    try:
        parsed = json.loads(text)
    except ValueError:
        parsed = text
    return status, parsed


def scenarios(run):
    """Each scenario: a name and its steps, built with ids fresh to this run."""
    token = f"launch-{uuid.uuid4()}"
    bonus = f"bonus-{uuid.uuid4()}"
    player = f"player-{run}-{uuid.uuid4().hex[:8]}"
    return {
        "OperatorApi_Launch": [
            ("launch", "POST", "/api/operator/v1/launch",
             {"game_code": "book-of-ra", "currency": "EUR", "token": token, "player": player, "fun_mode": False, "site": "goldvibe"}),
        ],
        "OperatorApi_Games": [
            ("games", "GET", "/api/operator/v1/games?site=goldvibe", None),
        ],
        "OperatorApi_BonusCreateUnknownGame": [
            ("create on a game the catalogue lacks", "POST", "/api/operator/v1/bonus/create",
             {"bonus_id": bonus, "players": [player], "count": 3, "value": "0.10", "type": "free_bets", "currency": "EUR", "game_codes": ["no-such-game"], "site": "goldvibe"}),
        ],
        "OperatorApi_BonusLifecycle": [
            ("create", "POST", "/api/operator/v1/bonus/create",
             {"bonus_id": bonus, "players": [player, player + "-2"], "count": "3", "value": "0.10", "type": "free_bets", "currency": "EUR", "game_codes": ["book-of-ra"], "site": "goldvibe"}),
            ("get", "GET", f"/api/operator/v1/bonus/get?bonus_id={bonus}&site=goldvibe", None),
            ("cancel one player's instance", "POST", "/api/operator/v1/bonus/cancel", {"bonus_id": bonus, "player": player, "site": "goldvibe"}),
            ("get an unknown bonus", "GET", "/api/operator/v1/bonus/get?bonus_id=no-such-bonus&site=goldvibe", None),
            ("cancel an unknown player", "POST", "/api/operator/v1/bonus/cancel", {"bonus_id": bonus, "player": "nobody", "site": "goldvibe"}),
        ],
    }


def differences(a, b, path="$"):
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            out += differences(a.get(k), b.get(k), f"{path}.{k}")
        return out
    if isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
        out = []
        for i, (x, y) in enumerate(zip(a, b)):
            out += differences(x, y, f"{path}[{i}]")
        return out
    return [] if a == b else [path]


runs = [scenarios(1), scenarios(2)]
answers = [{name: [call(m, p, b) for (_, m, p, b) in steps] for name, steps in run.items()} for run in runs]

os.makedirs(out, exist_ok=True)
for name, steps in runs[0].items():
    vary = {}
    recorded = []
    for i, (label, method, path, body) in enumerate(steps):
        (s1, r1), (s2, r2) = answers[0][name][i], answers[1][name][i]
        for p in differences(r1, r2):
            vary[p] = "an id fresh to each run (the launch ticket, the bonus id, the player)"
        if name == "OperatorApi_Games":
            # Go map order in the stand-in (main.go:618-626): whether or not the two runs happened to differ.
            reason = "Go map order in the stand-in (main.go:618-626): the order changes between calls; research R7"
            for field, keys in (("categories", ("id", "name")), ("developers", ("developer_id", "name"))):
                for j in range(len(r1[field])):
                    for k in keys:
                        vary[f"$.{field}[{j}].{k}"] = reason
        request = {"method": method, "path": path, "headers": {"Content-Type": "application/json"} if body is not None else {}}
        if body is not None:
            request["body"] = body
        recorded.append({"name": label, "request": request, "response": {"status": s1, "body": r1}})
    document = {
        "scenario": name,
        "requirement": "020/FR-007",
        "recorded_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "original": {"commit": commit, "stub": "tests/fake-st8", "how": "run locally from source; two runs on fresh ids"},
        "runs": 2,
        "steps": recorded,
        "vary": dict(sorted(vary.items())),
    }
    with open(os.path.join(out, f"{name}.json"), "w") as f:
        json.dump(document, f, indent=2)
        f.write("\n")
    print(name, len(recorded), "steps; vary:", sorted(vary))
