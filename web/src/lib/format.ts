/**
 * Presentation-only formatting helpers for the gallery UI.
 *
 * Kept separate from the data layer so views stay declarative: date headings in the
 * Google Photos house style ("Today", "Yesterday", "Wednesday", then a full date),
 * capture times, human byte sizes, and the trash purge countdown.
 */

const DAY_MS = 86_400_000

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

function parse(iso: string | null): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * Grouping key for one local calendar day ("2026-09-27"), or 'unknown' when the
 * capture time is missing. Stable and sortable as a plain string.
 */
export function dayKey(iso: string | null): string {
  const d = parse(iso)
  if (!d) return 'unknown'
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/** Heading shown above a day's tiles: "Today", "Yesterday", weekday, else full date. */
export function dayLabel(iso: string | null): string {
  const d = parse(iso)
  if (!d) return 'Date unknown'
  const diff = Math.round((startOfDay(new Date()) - startOfDay(d)) / DAY_MS)
  if (diff <= 0) return 'Today'
  if (diff === 1) return 'Yesterday'
  if (diff < 7) return d.toLocaleDateString(undefined, { weekday: 'long' })
  return d.toLocaleDateString(
    undefined,
    d.getFullYear() === new Date().getFullYear()
      ? { month: 'long', day: 'numeric' }
      : { month: 'long', day: 'numeric', year: 'numeric' },
  )
}

/** Short clock time for a viewer header ("2:14 PM"), empty when unknown. */
export function timeLabel(iso: string | null): string {
  const d = parse(iso)
  return d ? d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }) : ''
}

/** Long date + time for the info panel ("Sunday, September 27, 2026 · 2:14 PM"). */
export function fullDateLabel(iso: string | null): string {
  const d = parse(iso)
  if (!d) return 'Date unknown'
  const date = d.toLocaleDateString(undefined, {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
  const time = timeLabel(iso)
  return time ? `${date} · ${time}` : date
}

/** Date the trash purges an item, `days` after it was deleted. */
export function purgeLabel(deletedAt: string | null, days = 30): string {
  const d = parse(deletedAt)
  if (!d) return 'unknown'
  d.setDate(d.getDate() + days)
  return d.toLocaleDateString(undefined, { month: 'long', day: 'numeric', year: 'numeric' })
}

/** SI-ish binary size ("1.4 MB"); em dash for a missing/zero size. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${i === 0 || n >= 10 ? Math.round(n) : n.toFixed(1)} ${units[i]}`
}
