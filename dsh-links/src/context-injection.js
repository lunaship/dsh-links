/**
 * 识别 DSH 注入到会话里的上下文快照（system-reminder、runtime context 等），
 * 与 App ContextInjection.kt 规则对齐。
 */
export function isContextInjectionText(text) {
  if (!text || !String(text).trim()) return false
  const t = String(text)
  return (
    /<system-reminder>/i.test(t) ||
    /&lt;system-reminder&gt;/i.test(t) ||
    /<available_skills>/i.test(t) ||
    /&lt;available_skills&gt;/i.test(t) ||
    /available skill catalog/i.test(t) ||
    /available-skills/i.test(t) ||
    /Current runtime context/i.test(t) ||
    /Current DSH file policy/i.test(t) ||
    /Approval prompts are disabled/i.test(t) ||
    /Instructions from:/i.test(t) ||
    /\bAGENTS\.md\b/.test(t) ||
    /\bCLAUDE\.md\b/.test(t)
  )
}

/** 注入来源短标签（Web：上下文注入 · skill-catalog）。 */
export function contextInjectionLabels(text) {
  const labels = new Set()
  if (
    /available_skills/i.test(text) ||
    /skill catalog/i.test(text) ||
    /available-skills/i.test(text)
  ) {
    labels.add("skill-catalog")
  }
  if (/Current runtime context/i.test(text)) labels.add("runtime")
  if (/Current DSH file policy/i.test(text)) labels.add("file-policy")
  if (/Approval prompts are disabled/i.test(text)) labels.add("approval-policy")
  for (const m of text.matchAll(/Instructions from:\s*(.+)/g)) {
    const name = m[1]?.trim()
    if (name) labels.add(name)
  }
  for (const m of text.matchAll(/(?:AGENTS\.md|CLAUDE\.md|\.zcode\/[^\s,]+)/g)) {
    labels.add(m[0])
  }
  return labels.size ? [...labels] : ["workspace"]
}
