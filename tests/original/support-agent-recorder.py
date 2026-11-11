"""Records the original's casino agent (spec 017, T055) twice on fresh players.

usage: python3 -I support-agent-recorder.py <out dir> <original commit>

The agent runs on the host with the scripted model (`MOTOKO_SCRIPTED`, tests/port/support-agent/README.md),
at AGENT_URL; casino-service at CASINO_URL and the wallet's internal port at WALLET_URL. The hand-off
secret signs the player's token (HANDOFF_SHARED_SECRET, the run's own value) and WALLET_INTERNAL_SECRET
the deposits; neither is written to a recording.
"""
import datetime, hashlib, hmac, json, os, re, sys, urllib.error, urllib.request, uuid

import jwt

out, commit = sys.argv[1], sys.argv[2]
AGENT = os.environ.get("AGENT_URL", "http://localhost:18080")
CASINO = os.environ.get("CASINO_URL", "http://localhost:18091")
WALLET = os.environ.get("WALLET_URL", "http://localhost:19102")
HANDOFF = os.environ["HANDOFF_SHARED_SECRET"]
WALLET_SECRET = os.environ["WALLET_INTERNAL_SECRET"]
BRAND, CURRENCY = "spinvibe", "EUR"
PASSWORD = "Support-Recorder-1!"


def frames(text):
    """An SSE body as its frames, each {event, data}."""
    got = []
    for block in text.split("\n\n"):
        event, data = None, None
        for line in block.splitlines():
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
        if event:
            got.append({"event": event, "data": data})
    return got


def send(base, method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method, headers=dict(headers or {}))
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        return urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        return e


def parsed(resp):
    text = resp.read().decode()
    if resp.headers.get("Content-Type", "").startswith("text/event-stream"):
        return frames(text)
    try:
        return json.loads(text)
    except ValueError:
        return text


class Run:
    def __init__(self):
        self.steps, self.values = [], {}

    def token(self, session, player=None):
        claims = {"player_id": player or self.values["playerId"], "session_id": session, "brand_id": BRAND,
                  "currency": CURRENCY, "exp": datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)}
        return {"Authorization": "Bearer " + jwt.encode(claims, HANDOFF, algorithm="HS256")}

    def step(self, name, service, method, path, body=None, auth=None, shown=None):
        base = {"agent": AGENT, "casino": CASINO, "wallet": WALLET}[service]
        headers = dict(auth or {})
        if service == "wallet":
            # The wallet's internal routes take the brand and HMAC(secret, brand) (WalletRoutes.requireInternalAuth).
            headers["X-Internal-Brand-Id"] = BRAND
            headers["X-Internal-Secret"] = hmac.new(WALLET_SECRET.encode(), BRAND.encode(), hashlib.sha256).hexdigest()
        resp = send(base, method, path, body, headers)
        answer = {"status": resp.status if hasattr(resp, "status") else resp.code, "body": parsed(resp)}
        request = {"method": method, "path": path, "service": service}
        if auth:
            request["headers"] = {"Authorization": "Bearer {token}"}
        if body is not None:
            request["body"] = shown if shown is not None else body
        self.steps.append({"name": name, "request": request, "response": answer})
        return answer

    def player(self):
        email = f"support-rec-{uuid.uuid4().hex[:12]}@example.com"
        r = self.step("setup: register the player", "casino", "POST", f"/v1/casino/players/{BRAND}/register",
                      {"email": email, "password": PASSWORD, "currency": CURRENCY, "country": "GB", "language": "en",
                       "dateOfBirth": "1990-01-01"},
                      shown={"email": "{email}", "password": "<redacted>", "currency": CURRENCY, "country": "GB",
                             "language": "en", "dateOfBirth": "1990-01-01"})
        self.values["playerId"], self.values["email"] = r["body"]["playerId"], email
        self.step("setup: deposit 50.00 to the player", "wallet", "POST",
                  f"/internal/v1/wallet/{BRAND}/{self.values['playerId']}/{CURRENCY}/deposit",
                  {"paymentRef": f"support-rec-{uuid.uuid4()}", "amount": "50.00", "terminalId": "support-recorder",
                   "pspId": "support-recorder-psp", "scheme": "card"},
                  shown={"paymentRef": "{paymentRef}", "amount": "50.00", "terminalId": "support-recorder",
                         "pspId": "support-recorder-psp", "scheme": "card"})

    def chat(self, session, script, name=None):
        message = "script:" + json.dumps(script)
        return self.step(name or f"chat: {script}", "agent", "POST", "/run/chat_with_player",
                         {"message": message, "session_id": session}, auth=self.token(session))

    def decided(self, session, tool, args, action, feedback=None, extra=None):
        """A write tool asked for, the stream held open to its confirmation, the decision, then the rest."""
        message = "script:" + json.dumps({"tool": tool, "args": args})
        resp = send(AGENT, "POST", "/run/chat_with_player", {"message": message, "session_id": session},
                    {**self.token(session), "Content-Type": "application/json"})
        head = ""
        while "event: confirm_action" not in head and "event: error" not in head and "event: done" not in head:
            line = resp.readline().decode()
            if not line:
                break
            head += line
        head += resp.readline().decode() + resp.readline().decode()  # its data line and the blank line
        first = {"name": f"chat: {tool}", "request": {"method": "POST", "path": "/run/chat_with_player",
                 "service": "agent", "headers": {"Authorization": "Bearer {token}"},
                 "body": {"message": message, "session_id": session}}, "response": {"status": resp.status}}
        self.steps.append(first)
        if extra:
            extra(session)
        body = {"action": action} if feedback is None else {"action": action, "feedback": feedback}
        self.step(f"resume: {action}", "agent", "POST", f"/resume/{session}", body, auth=self.token(session))
        first["response"]["body"] = frames(head + resp.read().decode())


def scenarios():
    future = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=3)).replace(microsecond=0)
    writes = {
        "request_withdrawal": {"amount": 2500, "currency": CURRENCY},
        "close_active_session": {"game_session_id": str(uuid.uuid4())},
        "set_deposit_limit": {"amount": 10000, "period": "weekly"},
        "apply_cool_off": {"cool_off_until": future.isoformat().replace("+00:00", "Z")},
        "apply_self_exclusion": {},
    }
    got = {}
    for tool in ["get_player_state", "get_wallet_balance", "get_active_bonuses", "get_transaction_history",
                 "get_session_history", "get_rg_status"]:
        def read(r, tool=tool):
            r.player()
            r.chat(f"sess-{uuid.uuid4()}", {"tool": "tool_" + tool, "args": {}})
        got[f"a read tool answers the model [{tool}]"] = ("017/FR-010", read)
    got["a reply is sent as one delta and done"] = ("017/FR-003", lambda r: (r.player(), r.chat(f"sess-{uuid.uuid4()}", {"text": "Hello there."})))
    for tool, args in writes.items():
        for action in ["approve", "reject"]:
            def write(r, tool=tool, args=args, action=action):
                r.player()
                r.decided(f"sess-{uuid.uuid4()}", "tool_" + tool, args, action,
                          feedback="not now" if action == "reject" else None)
            got[f"a write tool waits for the player's decision [{tool}, {action}]"] = ("017/FR-013", write)

    def busy(r):
        r.player()
        session = f"sess-{uuid.uuid4()}"
        def while_waiting(s):
            r.step("chat again while awaiting", "agent", "POST", "/run/chat_with_player",
                   {"message": "script:{\"text\": \"again\"}", "session_id": s}, auth=r.token(s))
            r.step("resume with another session's token", "agent", "POST", f"/resume/{s}", {"action": "approve"},
                   auth=r.token(f"sess-{uuid.uuid4()}"))
            r.step("resume with an unknown action", "agent", "POST", f"/resume/{s}", {"action": "maybe"}, auth=r.token(s))
        r.decided(session, "tool_request_withdrawal", writes["request_withdrawal"], "reject", extra=while_waiting)
        r.step("resume after the decision", "agent", "POST", f"/resume/{session}", {"action": "approve"}, auth=r.token(session))
    got["what the routes answer around a pending decision"] = ("017/FR-017", busy)

    def unauthenticated(r):
        r.step("chat with no token", "agent", "POST", "/run/chat_with_player", {"message": "hi", "session_id": "s"})
        r.step("chat with a token signed by another secret", "agent", "POST", "/run/chat_with_player",
               {"message": "hi", "session_id": "s"},
               auth={"Authorization": "Bearer " + jwt.encode({"player_id": "p", "session_id": "s"}, "other", algorithm="HS256")})
    got["the routes refuse a player with no valid token"] = ("017/FR-002", unauthenticated)
    return got


def tokenise(value, values):
    text = json.dumps(value)
    for k in ["playerId", "email"]:
        if k in values:
            text = text.replace(values[k], "{" + k + "}")
    text = re.sub(r"sess-[0-9a-f-]{36}", "{session}", text)
    return json.loads(text)


def differing(a, b, path="$"):
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            yield from differing(a.get(k), b.get(k), f"{path}.{k}")
    elif isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
        for i, (x, y) in enumerate(zip(a, b)):
            yield from differing(x, y, f"{path}[{i}]")
    elif a != b:
        yield path


os.makedirs(out, exist_ok=True)
for name, (requirement, drive) in scenarios().items():
    runs = []
    for _ in range(2):
        r = Run()
        drive(r)
        runs.append([tokenise(s, r.values) for s in r.steps])
    first, second = runs
    if [s["name"] for s in first] != [s["name"] for s in second]:
        print(f"{name}: the two runs took different steps", file=sys.stderr)
        sys.exit(1)
    for a, b in zip(first, second):
        vary = {p: "differs between two runs of the original" for p in differing(a["response"].get("body"), b["response"].get("body"))}
        if vary:
            a["vary"] = vary
    record = {"scenario": name, "requirement": requirement,
              "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
              "original": {"commit": commit}, "runs": 2, "service": "support-agent", "steps": first}
    with open(os.path.join(out, name + ".json"), "w") as f:
        json.dump(record, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(name, [s["response"]["status"] for s in first])
