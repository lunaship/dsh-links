/**
 * 可空字符串：空白、JSON null、以及 Android JSONObject.optString 会当成真值的
 * 字面量 "null" / "undefined" 一律视为没有值。
 */
export function optionalString(value) {
  if (typeof value !== "string") return null
  const trimmed = value.trim()
  if (!trimmed) return null
  const lower = trimmed.toLowerCase()
  if (lower === "null" || lower === "undefined") return null
  return trimmed
}

/** JSON.stringify 会保留 null；缺键时旧 App 的 optString 才不会变成字面量 "null"。 */
export function omitNullFields(obj) {
  const out = {}
  for (const [key, value] of Object.entries(obj)) {
    if (value !== null && value !== undefined) out[key] = value
  }
  return out
}
