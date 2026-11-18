import type { PlayerSite } from "@eitheror/player-sdk";
import type { ChatStep } from "@eitheror/player-sdk/support";
import { useBehaviour, usePlayer } from "@eitheror/player-sdk/react";
import { useState } from "react";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy, Button } from "./ui.tsx";

/** The chat with the support agent: the agent's words as it sent them, and only the actions the step allows. */
export function ChatOverlay({ site }: { site: PlayerSite }) {
  const { t } = useLanguage();
  const player = usePlayer(site);
  const step = useBehaviour(site.chat);
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  if (!site.chat || !step || !player) return null;
  const chat = site.chat;
  const can = (name: string) => (step.allowed as readonly string[]).includes(name);
  const turns = "turns" in step ? step.turns : [];
  if (!open)
    return (
      <Button className="fixed right-4 bottom-4 z-10 shadow-lg" onClick={() => setOpen(true)}>
        {t("nav.chat")}
      </Button>
    );
  return (
    <section role="dialog" aria-labelledby="chat-title" className="fixed right-0 bottom-0 z-10 flex h-[min(36rem,100dvh)] w-full max-w-sm flex-col border border-river bg-white sm:right-4 sm:bottom-4 sm:rounded-lg">
      <header className="flex items-center justify-between border-b border-river px-4 py-3">
        <h2 id="chat-title" className="font-bold">
          {t("chat.title")}
        </h2>
        <Button tone="quiet" onClick={() => setOpen(false)}>
          {t("action.close")}
        </Button>
      </header>
      <ol className="flex-1 space-y-2 overflow-y-auto p-4" aria-live="polite">
        {turns.map((turn, i) => (
          <li key={i} className={turn.from === "player" ? "ml-8 rounded-lg bg-mist px-3 py-2" : "mr-8"}>
            <p className="whitespace-pre-wrap">{turn.text}</p>
            {turn.result ? <p className="mt-1 text-sm text-leaf">{t(turn.result.success ? "chat.resultOk" : "chat.resultFailed", { action: turn.result.action })}</p> : null}
            {turn.interrupted ? <p className="mt-1 text-sm text-ink/60">{t("chat.interrupted")}</p> : null}
          </li>
        ))}
      </ol>
      <footer className="border-t border-river p-3">
        {footer(step)}
        {can("send") ? (
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (chat.actions.send(text).ok) setText("");
            }}
          >
            <label className="flex-1">
              <span className="sr-only">{t("chat.placeholder")}</span>
              <input autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder={t("chat.placeholder")} className="w-full rounded-md border border-river px-3 py-2" />
            </label>
            <Button type="submit" disabled={!text.trim()}>
              {t("chat.send")}
            </Button>
          </form>
        ) : null}
      </footer>
    </section>
  );

  function footer(step: ChatStep) {
    switch (step.kind) {
      case "answering":
        return <Busy text={t("chat.answering")} />;
      case "deciding":
        return <Busy text={t("chat.deciding")} />;
      case "awaiting_approval":
        return (
          <div className="space-y-2">
            <p className="font-semibold">{t("chat.approval")}</p>
            {step.request.effect ? <p>{step.request.effect}</p> : <p>{step.request.action}</p>}
            <div className="flex gap-2">
              <Button tone="money" onClick={() => chat.actions.approve()}>
                {t("chat.approve")}
              </Button>
              <Button tone="quiet" onClick={() => chat.actions.cancel()}>
                {t("chat.cancel")}
              </Button>
            </div>
          </div>
        );
      case "refused":
        return (
          <div className="mb-2 space-y-2">
            <Refused refusal={step.refusal} />
            <Button tone="quiet" onClick={() => chat.actions.dismiss()}>
              {t("action.dismiss")}
            </Button>
          </div>
        );
      case "ended":
        return <p className="mb-2 text-ink/70">{step.words || t("chat.ended")}</p>;
      case "unavailable":
        return <p>{step.words}</p>;
      default:
        return null;
    }
  }
}
