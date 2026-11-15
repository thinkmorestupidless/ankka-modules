// A birth date typed (FR-015): `YYYY-MM-DD` or day, month and year, read by `parseBirthDate`. A day
// that does not exist or is still to come is refused, said in words beside the field once the operator
// leaves it; the value given on is always `YYYY-MM-DD` or nothing.
import { useId, useState } from "react";
import "./components.css";
import { parseBirthDate } from "../../shared/dates.ts";
import { t, type MessageKey } from "../../shared/messages/index.ts";
import { Glyph } from "./glyph.tsx";

export interface BirthDateInputProps {
  label: MessageKey;
  /** The day it is in the brand's zone, `YYYY-MM-DD`: a birth date after it is refused. */
  today: string;
  /** The date as `YYYY-MM-DD`, when there is one already. */
  initial?: string;
  onChange(value: string | undefined): void;
  required?: boolean;
}

export function BirthDateInput({ label, today, initial, onChange, required }: BirthDateInputProps) {
  const id = useId();
  const [text, setText] = useState(initial ?? "");
  const [left, setLeft] = useState(false);
  const parsed = parseBirthDate(text, today);
  const invalid = text.trim() !== "" && parsed === undefined;
  const shownInvalid = invalid && left;

  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(label)}</label>
      <input
        id={id}
        className="bo-input"
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={text}
        required={required}
        aria-invalid={shownInvalid}
        aria-describedby={`${id}-hint${shownInvalid ? ` ${id}-error` : ""}`}
        onChange={(ev) => {
          const next = ev.target.value;
          setText(next);
          onChange(parseBirthDate(next, today));
        }}
        onBlur={() => setLeft(true)}
      />
      <span id={`${id}-hint`} className="bo-field-hint">
        {t("dateInput.hint")}
      </span>
      {shownInvalid && (
        <span id={`${id}-error`} className="bo-field-error" role="alert">
          <Glyph name="alert" />
          <span>{t("dateInput.invalid")}</span>
        </span>
      )}
    </div>
  );
}
