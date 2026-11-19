import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from "react";

export function Button({ tone = "plain", className = "", ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: "money" | "plain" | "quiet" }) {
  const tones = {
    money: "bg-cayenne text-white hover:bg-cayenne/90",
    plain: "bg-canopy text-white hover:bg-canopy/90",
    quiet: "bg-transparent text-leaf underline-offset-4 hover:underline",
  };
  return <button className={`rounded-md px-4 py-2 font-semibold disabled:opacity-50 ${tones[tone]} ${className}`} {...rest} />;
}

export function Field({ label, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium">{label}</span>
      <input className="w-full rounded-md border border-river bg-white px-3 py-2 aria-invalid:border-cayenne" {...rest} />
    </label>
  );
}

export function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mx-auto w-full max-w-md px-4 py-10">
      <h1 className="mb-6 text-3xl font-extrabold tracking-tight">{title}</h1>
      {children}
    </section>
  );
}

export const Busy = ({ text }: { text: string }) => (
  <p role="status" className="text-ink/70">
    {text}
  </p>
);
