// The rail's icons: simple strokes in currentColor, hidden from screen readers (the label is read).
import type { Icon } from "./areas.ts";

const paths: Record<Icon, string> = {
  inbox: "M4 13h4l2 3h4l2-3h4M4 13l2-7h12l2 7v6H4z",
  users: "M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 20c0-3 3-5 6-5s6 2 6 5M16 5a3 3 0 0 1 0 6M18 15c2 .5 3 2.5 3 5",
  out: "M12 3v12M7 10l5 5 5-5M4 21h16",
  scale: "M12 4v16M5 8h14M5 8l-3 6h6zM19 8l-3 6h6z",
  gift: "M4 10h16v10H4zM3 7h18v3H3zM12 7v13M12 7c-2-4-6-3-5 0M12 7c2-4 6-3 5 0",
  tag: "M3 12V3h9l9 9-9 9zM7.5 7.5h.01",
  id: "M3 5h18v14H3zM8 11a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM5 16c.5-2 2-3 3-3s2.5 1 3 3M14 9h4M14 13h4",
  shield: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
  alert: "M12 3l10 18H2zM12 10v5M12 18h.01",
  chart: "M4 20V10M10 20V4M16 20v-7M22 20H2",
  table: "M3 5h18v14H3zM3 10h18M3 15h18M9 5v14",
  cog: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12l2-1-1-3-2 .5-1.5-1.5.5-2-3-1-1 2h-2l-1-2-3 1 .5 2L6 7.5 4 7 3 10l2 1v2l-2 1 1 3 2-.5 1.5 1.5-.5 2 3 1 1-2h2l1 2 3-1-.5-2 1.5-1.5 2 .5 1-3-2-1z",
  card: "M2 6h20v12H2zM2 10h20M6 15h4",
  team: "M8 8a3 3 0 1 0 0-.01M16 8a3 3 0 1 0 0-.01M2 20c0-3 3-5 6-5s6 2 6 5M14 15c1-.6 1.8-1 2-1 3 0 6 2 6 5",
  link: "M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1",
};

export function AreaIcon({ icon }: { icon: Icon }) {
  return (
    <svg aria-hidden="true" focusable="false" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[icon]} />
    </svg>
  );
}
