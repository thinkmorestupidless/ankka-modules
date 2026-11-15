// An operator action: offered only in the states its record allows (FR-026); shown disabled, with a
// role that would permit it, to an operator who lacks its permission, or not at all when no role of the
// brand gives it (FR-005); confirmed in a dialog that states its effect first and takes a reason where
// the owner records one (FR-025); and a refusal shown with its reason and next step (FR-006).
import { useId, useState, type ReactNode } from "react";
import { Dialog } from "radix-ui";
import "../components.css";
import { holds, rolesGiving, type Me, type RoleBags } from "../../../shared/access.ts";
import { t, type Message } from "../../../shared/messages/index.ts";
import { explain } from "../../../shared/refusals.ts";
import { offeredIn, reasonGiven, reasonOf, type ActionDescription } from "../../actions/index.ts";
import { Glyph } from "../glyph.tsx";
import { RefusalNotice } from "./Refusal.tsx";

export interface ActionProps {
  action: ActionDescription;
  me: Pick<Me, "permissions">;
  /** The brand's roles as `/api/permissions` answers them; `undefined` while not yet read. */
  bags: RoleBags | undefined;
  /** The record's state, for an action offered only in some. */
  state?: string;
  /** What the action is taken on (the amount, the player), shown with the effect. */
  subject?: ReactNode;
  /** Takes the action with the reason given; the owner's answer as it came. */
  run(reason: string | undefined): Promise<Response>;
  onDone?(answer: Response): void;
  /** Whether what the subject asks for (an amount, a choice) is given; confirming waits for it. */
  ready?: boolean;
}

type Shown = { message: Message; next?: Message };

export function Action({ action, me, bags, state, subject, run, onDone, ready }: ActionProps) {
  const needsId = useId();
  if (!offeredIn(action, state)) return null;

  if (!holds(me, action.permission)) {
    const roles = bags ? rolesGiving(action.permission, bags) : undefined;
    if (roles && roles.length === 0) return null;
    return (
      <span className="bo-action-disabled" data-action-disabled>
        <button type="button" className="bo-button" disabled aria-describedby={needsId}>
          {t(action.label)}
        </button>
        <span id={needsId} className="bo-needs">
          <Glyph name="lock" />
          <span>{roles ? t("action.needsRole", { roles: roles.join(", ") }) : t("action.needsPermission", { permission: action.permission })}</span>
        </span>
      </span>
    );
  }
  return <ActionDialog action={action} bags={bags} subject={subject} run={run} onDone={onDone} ready={ready} />;
}

export interface ActionDialogProps {
  action: ActionDescription;
  bags: RoleBags | undefined;
  subject?: ReactNode;
  run(reason: string | undefined): Promise<Response>;
  onDone?(answer: Response): void;
  ready?: boolean;
}

/** The button that opens the confirmation, and the confirmation. */
export function ActionDialog({ action, bags, subject, run, onDone, ready = true }: ActionDialogProps) {
  const reasonId = useId();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [pending, setPending] = useState(false);
  const [refusal, setRefusal] = useState<Shown | undefined>(undefined);

  const reset = (next: boolean) => {
    setOpen(next);
    if (!next) {
      setReason("");
      setRefusal(undefined);
    }
  };

  const confirm = async () => {
    setPending(true);
    setRefusal(undefined);
    try {
      const answer = await run(action.reason === "none" ? undefined : reasonOf(reason));
      if (answer.ok) {
        reset(false);
        onDone?.(answer);
      } else {
        setRefusal(explain(answer.status, await answer.text(), bags));
      }
    } catch {
      setRefusal({ message: { key: "action.unreachable" }, next: { key: "action.unreachable.next" } });
    } finally {
      setPending(false);
    }
  };

  const canConfirm = !pending && ready && reasonGiven(action, reason);

  return (
    <Dialog.Root open={open} onOpenChange={(next) => !pending && reset(next)}>
      <Dialog.Trigger asChild>
        <button type="button" className="bo-button">
          {t(action.label)}
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="bo-overlay" />
        <Dialog.Content className="bo-dialog" data-action-dialog>
          <Dialog.Title>{t(action.label)}</Dialog.Title>
          <Dialog.Description data-effect>{t(action.effect)}</Dialog.Description>
          {subject && <div data-subject>{subject}</div>}
          {action.reason !== "none" && (
            <div className="bo-field">
              <label htmlFor={reasonId}>{action.reason === "required" ? t("action.reason") : t("action.note")}</label>
              <textarea id={reasonId} rows={3} value={reason} required={action.reason === "required"} aria-required={action.reason === "required"} onChange={(e) => setReason(e.target.value)} />
            </div>
          )}
          {refusal && <RefusalNotice refusal={refusal} />}
          <div className="bo-dialog-buttons">
            <Dialog.Close asChild>
              <button type="button" className="bo-button" disabled={pending}>
                {t("action.cancel")}
              </button>
            </Dialog.Close>
            <button type="button" className="bo-button" data-variant="primary" disabled={!canConfirm} onClick={() => void confirm()}>
              {pending ? t("action.working") : t("action.confirm")}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
