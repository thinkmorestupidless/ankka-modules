// The brand's players (T074; P2, FR-009 to FR-012): players' view query, a page at a time with its total,
// filtered by status, country, currency, potential VIP, online, identity and birthday, each filter's
// values counted by players (`facets=true`) so an operator sees how many each would leave. The place
// (filters, page, scroll) is in the URL; a row opens the player.
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useId } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Status } from "../../components/status.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";

const PageSize = 50;

export interface PlayerRow {
  playerId: string;
  username: string;
  email: string;
  accountStatus: string;
  country: string;
  currency: string;
  registeredAt: string;
  vipTier?: string | null;
  potVip: boolean;
  suspectedDuplicate: boolean;
}

type Facets = Record<string, Record<string, number>>;

interface Answer {
  items: PlayerRow[];
  total: number;
  page: number;
  pageSize: number;
  facets?: Facets;
}

/** The filters players takes, in the order shown; the values offered for those with a fixed set. */
export const Filters: { name: string; label: MessageKey; values?: string[] }[] = [
  { name: "status", label: "players.filter.status", values: ["active", "suspended", "self_excluded", "blocked", "closed", "pending_email_verification"] },
  { name: "country", label: "players.filter.country" },
  { name: "currency", label: "players.filter.currency" },
  { name: "identity", label: "players.filter.identity", values: ["verified", "unverified"] },
  { name: "online", label: "players.filter.online", values: ["true", "false"] },
  { name: "potVip", label: "players.filter.potVip", values: ["true", "false"] },
  { name: "birthdayWithinDays", label: "players.filter.birthday", values: ["0", "7", "30"] },
];

const valueWord = (filter: string, v: string) => (filter === "status" ? word("status", v) : word(`players.filter.${filter}`, v));

function FilterSelect({ name, label, values, facets, value, onChange }: { name: string; label: MessageKey; values?: string[]; facets?: Facets; value: string; onChange(v: string): void }) {
  const id = useId();
  const counted = facets?.[name] ?? {};
  const offered = values ?? Object.keys(counted).sort();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(label)}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} data-filter={name}>
        <option value="">{t("players.filter.any")}</option>
        {offered.map((v) => (
          <option key={v} value={v}>
            {counted[v] !== undefined ? t("players.filter.counted", { value: valueWord(name, v), count: counted[v] }) : valueWord(name, v)}
          </option>
        ))}
      </select>
    </div>
  );
}

export function PlayersListPage() {
  const tz = useZone();
  const list = useListState(useRouterListStore());
  const navigate = useNavigate();
  const freshness = useFreshness(["players"]);
  const searchId = useId();
  const query: Record<string, string> = { ...list.state.filters, page: String(list.state.page), pageSize: String(PageSize), facets: "true" };
  const q = useQuery({ queryKey: ["players", "list", query], queryFn: () => readJson<Answer>(players.getPlayers({ query })), placeholderData: (prev) => prev });
  const columns: Column<PlayerRow>[] = [
    { id: "username", header: "players.column.username", cell: (r) => r.username || r.playerId },
    { id: "email", header: "players.column.email", cell: (r) => r.email },
    { id: "status", header: "players.column.status", cell: (r) => <Status value={r.accountStatus} /> },
    { id: "country", header: "players.column.country", cell: (r) => r.country },
    { id: "currency", header: "players.column.currency", cell: (r) => r.currency },
    { id: "registered", header: "players.column.registered", cell: (r) => <DateTime instant={r.registeredAt} zone={tz} /> },
    { id: "marks", header: "players.column.marks", cell: (r) => [r.vipTier ? t("players.vipTier", { tier: r.vipTier }) : null, r.potVip ? t("players.potVip") : null, r.suspectedDuplicate ? t("players.flags.duplicateShort") : null].filter(Boolean).join(" · ") },
  ];
  return (
    <section>
      <h1>{t("area.players")}</h1>
      <p>
        <Link className="bo-link" to="/players/sessions">
          {t("sessions.brand.link")}
        </Link>
      </p>
      <form role="search" className="bo-players-filters" onSubmit={(e) => {
        e.preventDefault();
        const v = new FormData(e.currentTarget).get("search");
        list.setFilter("search", typeof v === "string" ? v.trim() : undefined);
      }}>
        <div className="bo-field">
          <label htmlFor={searchId}>{t("players.filter.search")}</label>
          <input id={searchId} name="search" type="search" className="bo-input" defaultValue={list.state.filters.search ?? ""} />
        </div>
        {Filters.map((f) => (
          <FilterSelect key={f.name} {...f} facets={q.data?.facets} value={list.state.filters[f.name] ?? ""} onChange={(v) => list.setFilter(f.name, v)} />
        ))}
      </form>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="players.caption"
        columns={columns}
        rows={q.data?.items}
        rowKey={(r) => r.playerId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={(r) => void navigate({ to: `/players/${encodeURIComponent(r.playerId)}` } as never)}
      />
    </section>
  );
}
