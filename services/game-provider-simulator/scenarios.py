import json, os, sys

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

H = "tests/e2e/scenario15_st8_wallet_e2e_test.go"
PROVIDER = {"wait": "5s", "intervals": [{"every": "30s", "for": "10m"}, {"every": "10m"}], "cap": "8h"}
ONCE = {"wait": "5s", "intervals": [], "cap": "0s"}

def send(callback, **kw):
    d = {"kind": "send", "callback": callback}
    d.update(kw)
    return d

def expect(of, **kw):
    d = {"kind": "expect", "of": of}
    d.update(kw)
    return d

balance = send("balance")

scenarios = {
    "default": dict(
        description="The stand-in exactly: one callback per instruction, never retried, the brand's answer returned verbatim (FR-016).",
        source="tests/fake-st8/main.go (the original's stand-in)", shape="stub", schedule=ONCE,
        steps=[{"kind": "pass-through"}]),
    "slow-operator": dict(
        description="A debit answered slower than the provider waits is abandoned and sent again under the same transaction id; passes only if the balance moved once.",
        source=f"{H}:778 (TestScenario15_St8Wallet_IdempotentDebit); spec 003 FR-011", shape="harness", schedule=PROVIDER,
        steps=[balance, send("debit", amount="1.50", round="new"),
               expect(1, status=["ok"], balance="moved_once", attempts={"min": 2})]),
    "cancel-after-credit": dict(
        description="A round is debited and credited, then its bet is cancelled; passes only if the brand answers as the original does for a cancel of a settled round and leaves the balance the original would.",
        source=f"{H}:969 (TestScenario15_St8Wallet_Resettlement), {H}:1015 (CancelEverythingWithinRound)", shape="harness", schedule=ONCE,
        steps=[balance, send("debit", amount="10.00", round="new"), expect(1, status=["ok"]),
               send("credit", amount="20.00", round="same"), expect(3, status=["ok"]),
               send("cancel", transaction="same-as 1", round="same"), expect(5, status=["ok"])]),
    "out-of-order": dict(
        description="A round's credit arrives before its debit; passes only if the brand answers the credit as for an unknown round and later accepts the debit.",
        source=f"{H}:644 (TestScenario15_St8Wallet_StandaloneCredit)", shape="harness", schedule=ONCE,
        steps=[balance, send("credit", amount="15.00", round="new"), expect(1, status=["ok"]),
               send("debit", amount="10.00", round="same"), expect(3, status=["ok"])]),
    "retry-storm": dict(
        description="A debit the brand answers with error is sent again on the provider's schedule until it answers otherwise or the cap is reached; the run reports how many attempts and how long.",
        source="docs/incidents/2026-08-19-st8-open-rounds-property-truncation.md (86 retries over 8 hours for one win)", shape="harness", schedule=PROVIDER,
        steps=[send("debit", amount="1.00", round="new"), expect(0, status=["ok"])]),
    "rotated-key": dict(
        description="The next key is published, the brand's bound waited, then calls are signed with it; passes only if the brand admits the new key and refuses the withdrawn one.",
        source=f"spec 003 FR-026 (the key document); {H}:884 (TestScenario15_St8Wallet_WrongSignature)", shape="harness", schedule=ONCE,
        steps=[{"kind": "rotate-key"}, send("balance", key="current"), expect(1, status=["ok"]),
               send("balance", key="withdrawn"), expect(3, status=["auth_failed"], attempts={"max": 1})]),
    "wrong-key": dict(
        description="A call signed with a key the brand never saw; passes only if the brand answers auth_failed, which is never retried.",
        source=f"{H}:884 (TestScenario15_St8Wallet_WrongSignature)", shape="harness", schedule=PROVIDER,
        steps=[send("balance", key="wrong"), expect(0, status=["auth_failed"], attempts={"max": 1})]),
    "unknown-token": dict(
        description="A debit names a session ticket the brand never issued; passes only if the brand answers session_expired and no balance moves.",
        source="spec 003 FR-012 (a debit requires a registered session ticket); St8Routes.scala debit", shape="harness", schedule=ONCE,
        steps=[balance, send("debit", amount="1.00", round="new", token="unknown"), expect(1, status=["session_expired"]),
               balance, expect(3, status=["ok"], balance="unchanged")]),
    "win-larger-than-any-bet": dict(
        description="A win of 1,000,000.00 on a stake of 0.10; passes only if the brand credits the exact amount, as the original does since #662.",
        source=f"{H}:500 (TestScenario15_St8Wallet_LargeWinOnLargeStake); spec 002 SC-004", shape="harness", schedule=ONCE,
        steps=[balance, send("debit", amount="0.10", round="new"), expect(1, status=["ok"], balance="moved_once"),
               send("credit", amount="1000000.00", round="same"), expect(3, status=["ok"], balance="moved_once")]),
    "orphaned-bet": dict(
        description="A bet with no credit, cancel or loss within the scenario's window is settled by a credit of 0.00; passes only if the brand settles the round as a loss.",
        source=f"{H}:628 (TestScenario15_St8Wallet_ZeroCredit); spec 003 FR-014", shape="harness", schedule=ONCE,
        steps=[balance, send("debit", amount="1.00", round="new"), expect(1, status=["ok"]),
               {"kind": "wait", "for": "30s"},
               send("credit", amount="0.00", round="same"), expect(4, status=["ok"], balance="unchanged", roundSettledAs="lost")]),
    "malformed-body": dict(
        description="A body that is not a JSON object; passes only if the brand answers HTTP 400, which is never retried.",
        source="St8Routes.scala (a malformed body is HTTP 400 session_expired); spec 003 FR-004", shape="harness", schedule=PROVIDER,
        steps=[send("debit", malformed=True), expect(0, httpStatus=400, status=["session_expired"], attempts={"max": 1})]),
    "harness-basic": dict(
        description="The harness's basic calls: balance, debit, credit, cancel, buyin, payout, a zero debit and a zero credit.",
        source=f"{H}:450-644 (BasicBalance, BasicDebit, BasicCredit, BasicCancel, BasicBuyin, BasicPayout, ZeroDebit, ZeroCredit)", shape="harness", schedule=ONCE,
        steps=[balance, expect(0, status=["ok"]),
               send("debit", amount="10.00", round="new"), expect(2, status=["ok"], balance="moved_once"),
               send("credit", amount="20.00", round="same"), expect(4, status=["ok"], balance="moved_once"),
               send("debit", amount="10.00", round="new"), send("cancel", transaction="same-as 6", round="same"), expect(7, status=["ok"]),
               send("buyin", amount="25.00", round="none", token="none"), expect(9, status=["ok"]),
               send("payout", amount="50.00", round="none", token="none"), expect(11, status=["ok"]),
               send("debit", amount="0.00", round="new"), expect(13, status=["ok"], balance="unchanged"),
               send("debit", amount="10.00", round="new"), send("credit", amount="0.00", round="same"), expect(16, status=["ok"], balance="unchanged")]),
    "harness-rounds": dict(
        description="The harness's multi-call rounds: two debits, two credits, cancels of each debit, a credit after a failed debit, a credit under another game code.",
        source=f"{H}:665-756, {H}:919 (BigRoundMultipleDebits, BigRoundMultipleCredits, BigRoundMultipleCancels1, BigRoundFailedDebit, TableSwitch)", shape="harness", schedule=ONCE,
        steps=[balance,
               send("debit", amount="10.00", round="new"), send("debit", amount="5.00", round="same"), expect(2, status=["ok"]),
               send("credit", amount="8.00", round="new"), expect(4, status=["ok"]),
               send("debit", amount="10.00", round="new"), send("debit", amount="5.00", round="same"),
               send("cancel", transaction="same-as 6", round="same"), send("cancel", transaction="same-as 7", round="same"), expect(9, status=["ok"]),
               send("debit", amount="10.00", round="new"), send("cancel", transaction="same-as 11", round="same"),
               send("credit", amount="15.00", round="same"), expect(13, status=["ok"]),
               send("debit", amount="10.00", round="new"), send("credit", amount="12.00", round="same", gameCode="fake-slot-002"), expect(16, status=["ok"])]),
    "harness-idempotent": dict(
        description="The harness's repeats: a debit, a credit and a cancel each sent twice under the same id; the second answer equals the first.",
        source=f"{H}:778-844 (IdempotentDebit, IdempotentCredit, IdempotentCancel)", shape="harness", schedule=ONCE,
        steps=[balance,
               send("debit", amount="10.00", round="new"), send("debit", amount="10.00", round="same", transaction="same-as 1"), expect(2, status=["ok"], sameAnswerAs=1),
               send("credit", amount="20.00", round="same"), send("credit", amount="20.00", round="same", transaction="same-as 4"), expect(5, status=["ok"], sameAnswerAs=4),
               send("debit", amount="10.00", round="new"), send("cancel", transaction="same-as 7", round="same"), expect(8, status=["ok"])]),
    "harness-edges": dict(
        description="The harness's edges: not enough money, a balance with no ticket, a cancel with only a player, unknown fields, an unknown cancel, a correction below zero, a bad signature.",
        source=f"{H}:582-1015 (NotEnoughMoney, BalanceWithoutToken, LimitedCancel, NewParams, StandaloneCancel, CorrectionDebitNegativeBalance, WrongSignature)", shape="harness", schedule=ONCE,
        steps=[balance,
               send("debit", amount="1000000000.00", round="new"), expect(1, status=["not_enough_money"]),
               send("balance", token="none"), expect(3, status=["ok"]),
               send("debit", amount="1.00", round="new"), send("cancel", transaction="same-as 5", round="none", token="none"), expect(6, status=["ok"]),
               send("balance", extra={"new_future_field": "some_value", "another_field": 42}), expect(8, status=["ok"]),
               send("cancel", transaction="unknown", round="none"), expect(10, status=["transaction_not_found", "ok"]),
               send("buyin", amount="0.10", round="none", token="none", providerKind="correction_debit"), expect(12, status=["ok"]),
               send("balance", key="wrong"), expect(14, status=["auth_failed"])]),
}

names = []
for name, s in scenarios.items():
    doc = {"name": name, "version": 1, "description": s["description"], "source": s["source"],
           "shape": s["shape"], "schedule": s["schedule"], "steps": s["steps"]}
    with open(os.path.join(out, f"{name}.json"), "w") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")
    names.append(f"{name}.json")

with open(os.path.join(out, "index.txt"), "w") as f:
    f.write("# The shipped scenarios, seeded into the library at start (research R10). Each cites its source (FR-018).\n")
    f.write("\n".join(names) + "\n")
print(len(names), "scenarios")
