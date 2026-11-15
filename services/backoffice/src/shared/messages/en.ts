// Every string the backoffice shows (FR-040a), in English at delivery: ICU message syntax, so another
// language is a translation of this file. A screen names a key, never prose.
export const en = {
  // the frame
  "frame.signIn": "Sign in",
  "frame.signOut": "Sign out",
  "frame.search": "Search players, withdrawals, rounds…",
  "frame.readOnly": "This window is narrower than 1280 pixels, so the backoffice is read-only here.",
  "frame.moduleOff": "{module} is not run by this brand.",

  // refusals by the backoffice itself
  "refusal.signed_out": "You are signed out.",
  "refusal.signed_out.next": "Sign in again.",
  "refusal.bad_origin": "This request did not come from the backoffice's own page.",
  "refusal.bad_origin.next": "Reload the page and try again.",
  "refusal.no_such_route": "The backoffice does not ask for this.",
  "refusal.source_unavailable": "{source} is not answering.",
  "refusal.source_unavailable.next": "Try again in a moment; what is shown may be out of date.",
  "refusal.brand_not_in_roles": "Your roles do not name this brand.",
  "refusal.brand_not_in_roles.next": "Ask the brand's admin for a role in it.",

  // refusals by the owners
  "refusal.forbidden": "You need the {permission} permission for this.",
  "refusal.forbidden.unnamed": "Your roles do not allow this.",
  "refusal.forbidden.next": "A role that gives it: {roles}.",
  "refusal.forbidden.none": "No role of this brand gives it; ask the brand's admin.",
  "refusal.roles_unread": "The brand's roles have not been read yet.",
  "refusal.roles_unread.next": "Try again in a few seconds.",
  "refusal.authentication_required": "Your sign-in was not accepted.",
  "refusal.authentication_required.next": "Sign in again.",
  "refusal.player_not_found": "No such player in this brand.",
  "refusal.not_found": "Not found.",
  "refusal.invalid_body": "The request was not understood: {detail}",
  "refusal.reserved_role": "That role name is the realm's, not the brand's.",
  "refusal.reserved_role.next": "Choose another name.",
  "refusal.invalid_role_name": "A role's name is lower case letters, digits and underscores.",
  "refusal.reason_required": "A reason is needed.",
  "refusal.reason_required.next": "Write why, then confirm again.",
  "refusal.unknown_preference": "That preference cannot be kept.",
  "refusal.service_unavailable": "The service could not finish this in time.",
  "refusal.service_unavailable.next": "Try again; nothing was done twice.",
  "refusal.unknown": "{code}: {given}",

  // money and dates
  "money.unknownCurrency": "{minor} minor units of {currency}",
  "date.zone": "Times are in {zone}.",
} as const satisfies Record<string, string>;

export type MessageKey = keyof typeof en;
