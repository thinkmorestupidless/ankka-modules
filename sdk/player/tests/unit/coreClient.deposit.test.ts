import { describe, expect, it } from "vitest";
import { amount } from "../../src/amount.ts";
import { coreClient } from "../../src/core/client.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

async function setup() {
  const d = doubles();
  const t = doubleTransport(d);
  await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
  return { d, core: coreClient(t, () => "p-ana") };
}
const fifty = amount(5000n, "EUR");

describe("core's client: the deposit rows", () => {
  it("startDeposit sends the minor units, the currency and the request id, and answers the ticket and the terminals", async () => {
    const { d, core } = await setup();
    const r = await core.startDeposit(fifty, "req-1");
    expect(d.askedOf("startDeposit")[0]).toMatchObject({ path: { brand: "guyanabet", player: "p-ana" }, body: { amount: 5000, currency: "EUR", requestId: "req-1" } });
    if (!r.ok) throw new Error("refused");
    expect(r.value.requestId).toBe("req-1");
    expect(r.value.cashierTicket).toMatch(/^ticket-/);
    expect(r.value.terminals[0]).toEqual({ id: "t-card", name: "Carte", scheme: "VISA", gateway: "stub", currencies: ["EUR"] });
    expect(r.value.terminals.find((t) => t.scheme === "CRYPTO")?.currencies).toEqual(["BTC", "USDT"]);
  });

  it("a start under a request id used for another amount is the cashier's REQUEST_ID_REUSED", async () => {
    const { core } = await setup();
    await core.startDeposit(fifty, "req-1");
    expect(await core.startDeposit(amount(100n, "EUR"), "req-1")).toEqual({ ok: false, refusal: { status: 409, code: "REQUEST_ID_REUSED", fields: { requestId: "req-1" }, service: "ingest" } });
  });

  it("a start answer with no ticket is malformed", async () => {
    const { d, core } = await setup();
    d.script("startDeposit", { status: 200, body: { redirectUrl: "", paymentId: "x" } });
    expect(await core.startDeposit(fifty, "req-1")).toMatchObject({ ok: false, refusal: { code: "malformed_answer", service: "players" } });
  });

  it("balance reads the wallet's major text by the currency's exponent", async () => {
    const { d, core } = await setup();
    d.brand.balance = { real: "1234.50", bonus: "10.00" };
    expect(await core.balance("EUR")).toEqual({ ok: true, value: { real: amount(123450n, "EUR"), restricted: amount(1000n, "EUR"), available: amount(123450n, "EUR") } });
    d.brand.balance = { real: "100", bonus: "0" };
    expect(await core.balance("JPY")).toMatchObject({ ok: true, value: { real: amount(100n, "JPY") } });
    d.brand.balance = { real: "12.345", bonus: "0" };
    expect(await core.balance("EUR")).toMatchObject({ ok: false, refusal: { code: "malformed_answer", service: "wallet" } });
  });

  it("submitCashier sends the ticket in its header alone, the terminal, the coin and the details as the cashier names them", async () => {
    const { d, core } = await setup();
    const started = await core.startDeposit(fifty, "req-1");
    if (!started.ok) throw new Error("refused");
    const r = await core.submitCashier(started.value.cashierTicket, "t-crypto", { coin: "USDT", details: { firstName: "Ana", phone: "+594694000000", city: "" } });
    expect(r).toMatchObject({ ok: true, value: { status: "AWAITING_CRYPTO" } });
    const asked = d.askedOf("submitCashier")[0]!;
    expect(asked.headers.cashierTicket).toBe(started.value.cashierTicket);
    expect(asked.body).toEqual({ selectedTerminalEntityId: "t-crypto", cryptoCurrency: "USDT", customerFirstName: "Ana", customerPhone: "+594694000000" });
    expect(JSON.stringify(asked.body)).not.toContain(started.value.cashierTicket);
  });

  it("payment answers the read without failureReason, and a provider's rejection keeps its words", async () => {
    const { d, core } = await setup();
    d.script("payment", { status: 200, body: { status: "FAILED", failureCode: "05", failureReason: "Do not honour", amount: "50.00", amountCents: 5000 } });
    expect(await core.payment("ticket-1", "pay-1")).toEqual({ ok: true, value: { status: "FAILED", failureCode: "05" } });
    expect(d.askedOf("payment")[0]).toMatchObject({ path: { paymentId: "pay-1", ticket: "ticket-1" }, headers: { cashierTicket: "ticket-1" } });
    d.script("submitCashier", { status: 400, body: { error: "PAYMENT_REJECTED", message: "minimum deposit amount is 15.00" } });
    const rejected = await core.submitCashier("ticket-1", "t-card");
    expect(rejected).toMatchObject({ ok: false, refusal: { code: "PAYMENT_REJECTED" } });
  });

  it("profile and changeProfile name the signed-in player", async () => {
    const { d, core } = await setup();
    expect(await core.profile()).toMatchObject({ ok: true, value: { firstName: "Ana", lastName: "Silva" } });
    await core.changeProfile({ phone: "+594694000000" });
    expect(d.askedOf("changeProfile")[0]).toMatchObject({ path: { player: "p-ana" }, body: { phone: "+594694000000" } });
    expect(d.brand.players[0]!.profile.phone).toBe("+594694000000");
  });

  it("abandonCashier is sent so it outlives the page", async () => {
    const { d, core } = await setup();
    await core.abandonCashier("ticket-1");
    expect(d.askedOf("abandonCashier")[0]?.headers.cashierTicket).toBe("ticket-1");
  });
});
