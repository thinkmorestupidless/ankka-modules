// The top bar (contracts/design.md): the brand, always visible; the one search; freshness; jobs and
// notices; the operator, and signing out. The brand is this backoffice's: there is no switch (FR-001).
import { useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { t } from "../../shared/messages/index.ts";
import type { Me } from "../../shared/access.ts";

export async function signOut(fetchFn: typeof fetch = fetch, go: (href: string) => void = (h) => window.location.assign(h)): Promise<void> {
  await fetchFn("/auth/sign-out", { method: "POST", headers: { "X-Backoffice": "1" } }).catch(() => undefined);
  go("/auth/sign-in");
}

export function TopBar({ me, freshness, notices = 0 }: { me: Me; freshness?: ReactNode; notices?: number }) {
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const jobs = ((me.preferences as { jobs?: unknown[] } | null)?.jobs ?? []).length;
  const search = (e: FormEvent) => {
    e.preventDefault();
    if (q.trim()) void navigate({ to: "/search", search: { q: q.trim() } });
  };
  return (
    <header className="bo-top">
      <div className="bo-brand">
        <span className="bo-visually-hidden">{t("frame.brand")}</span>
        <strong>{me.brand.name}</strong>
      </div>
      <form role="search" className="bo-search" onSubmit={search}>
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("frame.search")} placeholder={t("frame.search")} />
      </form>
      <div className="bo-top-status">{freshness}</div>
      <Link to="/jobs" className="bo-top-item">
        {t("frame.jobs")}
        {jobs > 0 ? <span className="bo-count figures">{jobs}</span> : null}
      </Link>
      <span className="bo-top-item">
        {t("frame.notices")}
        {notices > 0 ? <span className="bo-count figures">{notices}</span> : null}
      </span>
      <span className="bo-operator">{t("frame.operator", { name: me.name ?? me.subject ?? "" })}</span>
      <button type="button" className="bo-button" onClick={() => void signOut()}>
        {t("frame.signOut")}
      </button>
    </header>
  );
}
