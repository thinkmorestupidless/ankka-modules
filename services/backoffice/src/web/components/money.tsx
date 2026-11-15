// An amount from minor units by the one rule (FR-013): `formatMoney`, the currency's own minor-unit
// count and symbol, never converted. A currency the contract's table lacks is shown as its minor units
// and code, never guessed. A negative amount carries its sign as well as its colour (FR-040).
import "./components.css";
import { formatMoney, UnknownCurrency, type Money as Amount } from "../../shared/money.ts";
import { t } from "../../shared/messages/index.ts";

export function moneyText(value: Amount): string {
  try {
    return formatMoney(value);
  } catch (e) {
    if (e instanceof UnknownCurrency) return t("money.unknownCurrency", { minor: String(value.minor), currency: value.currency });
    throw e;
  }
}

export function Money({ value }: { value: Amount }) {
  const negative = String(value.minor).startsWith("-");
  return (
    <span className="bo-money" data-figure data-sign={negative ? "negative" : undefined} data-currency={value.currency}>
      {moneyText(value)}
    </span>
  );
}
