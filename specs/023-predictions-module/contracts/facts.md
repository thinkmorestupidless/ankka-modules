# Facts

| Topic | Key | Families | Personal fields |
|---|---|---|---|
| `markets.events` | market | `market_drafted`, `market_changed`, `market_approved`, `market_opened`, `market_suspended`, `market_resumed`, `market_closed`, `market_close_moved`, `market_resolved`, `market_corrected`, `market_voided`, `market_final` | none (operators are staff) |
| `exchange.orders` | market | `order_placed`, `order_filled`, `order_cancelled`, `order_expired` | none |
| `exchange.trades` | market | `trade_made` (`legs`, a nested object per owner; `prices`, one per outcome), `position_paid`, `remainder_kept` | none |
| `exchange.prices` | market | `prices_changed` (`prices`, a nested object per outcome; `sequence`) | none |

`player_id` is a plain id on every family that names a player (spec 001 FR-024). Every family carries `market_id`, `occurred_at` and, where money moves, amounts in minor units with
the market's `currency`. Schemas by `just schemas`, version 1.
