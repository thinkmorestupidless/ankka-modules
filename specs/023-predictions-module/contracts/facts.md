# Facts

| Topic | Key | Families | Personal fields |
|---|---|---|---|
| `markets.events` | market | `market_drafted`, `market_changed`, `market_approved`, `market_opened`, `market_suspended`, `market_resumed`, `market_closed`, `market_close_moved`, `market_resolved`, `market_corrected`, `market_voided`, `market_final` | none (operators are staff) |
| `exchange.orders` | player | `order_placed`, `order_filled`, `order_cancelled`, `order_expired` | `player_id` |
| `exchange.trades` | market | `trade_made` (a leg per owner), `position_paid`, `remainder_kept` | each leg's `player_id` |
| `exchange.prices` | market | `prices_changed` (last price per outcome, `sequence`) | none |

Every family carries `market_id`, `occurred_at` and, where money moves, amounts in minor units with
the market's `currency`. Schemas by `just schemas`, version 1.
