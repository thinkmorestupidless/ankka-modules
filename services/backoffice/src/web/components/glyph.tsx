// The small marks beside a word (FR-040: a state is a word and an icon, never colour alone). Strokes in
// currentColor, hidden from screen readers: the word beside them is what is read.

export type GlyphName = "check" | "cross" | "clock" | "alert" | "pause" | "lock" | "dot" | "changed" | "minus";

const paths: Record<GlyphName, string> = {
  check: "M5 12l5 5 9-10",
  cross: "M6 6l12 12M18 6L6 18",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
  alert: "M12 3l10 18H2zM12 10v5M12 18h.01",
  pause: "M9 5v14M15 5v14",
  lock: "M6 11h12v10H6zM8 11V8a4 4 0 0 1 8 0v3",
  dot: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
  changed: "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5",
  minus: "M5 12h14",
};

export function Glyph({ name, size = 14 }: { name: GlyphName; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      data-glyph={name}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={paths[name]} />
    </svg>
  );
}
