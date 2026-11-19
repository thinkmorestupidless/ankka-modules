// The countries the reference site's registration offers when the visitor's own cannot be found by
// their address: French Guiana's region and the brands' South American markets (identity.md,
// 2026-11-07), named in the page's language. players checks the code against the brand's list.
export const COUNTRIES = ["GF", "FR", "SR", "GY", "BR", "AR", "CL", "CO", "EC", "PE", "PY", "UY", "VE", "BO", "MX"] as const;

export function countryName(code: string, language: string): string {
  try {
    return new Intl.DisplayNames([language], { type: "region" }).of(code) ?? code;
  } catch {
    return code;
  }
}
