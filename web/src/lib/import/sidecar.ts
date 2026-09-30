/**
 * Google Photos Takeout sidecar JSON ↔ media pairing.
 *
 * Every media file in a Takeout export gets a sibling JSON file with the same name:
 *
 *   IMG_1234.jpg   +   IMG_1234.jpg.json
 *
 * Newer exports can also emit `IMG_1234.jpg.supplemental-metadata.json`, and long
 * filenames are sometimes truncated by Google, so a naive `file + '.json'` lookup loses
 * metadata silently. This module pairs them deterministically and reports anything it
 * could not match, so the importer can guarantee "never silently drop".
 *
 * Pure and dependency-free — exercised directly by tests/takeout-foundations.test.cjs.
 */
import type { TakeoutSidecar } from './types'

const SUPPLEMENTAL_SUFFIX = '.supplemental-metadata.json'

/** Minimum basename length for a truncation-tolerant prefix match (avoids false hits). */
const MIN_PREFIX = 8

export interface SidecarMatch {
  media: string
  sidecars: string[]
}

export interface PairResult {
  matches: SidecarMatch[]
  /** sidecars that could not be tied to any media file */
  unmatchedSidecars: string[]
  /** media files with no sidecar (they still import — just without JSON metadata) */
  mediaWithoutSidecar: string[]
}

function dirname(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? '' : path.slice(0, i)
}

function basename(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? path : path.slice(i + 1)
}

/** Strip the JSON suffix Google appended, leaving the media name the sidecar refers to. */
export function baseOfSidecar(path: string): string {
  if (path.endsWith(SUPPLEMENTAL_SUFFIX)) return path.slice(0, -SUPPLEMENTAL_SUFFIX.length)
  if (path.endsWith('.json')) return path.slice(0, -'.json'.length)
  return path
}

/** True when a path is a Takeout metadata sidecar rather than media. */
export function isSidecarJson(path: string): boolean {
  return path.endsWith('.json')
}

/**
 * Pair media files with their JSON sidecars.
 *
 * Pass 1 (exact): a sidecar `X.json` pairs with media `X` in the same directory.
 * Pass 2 (truncated): Google may shorten long basenames, so pair when one basename is a
 * prefix of the other (same directory). Ambiguity resolves to the closest length, then
 * lexicographically, so the result is deterministic and stable across runs.
 */
export function pairSidecars(mediaPaths: string[], sidecarPaths: string[]): PairResult {
  const mediaByDir = new Map<string, string[]>()
  for (const m of mediaPaths) {
    const list = mediaByDir.get(dirname(m))
    if (list) list.push(m)
    else mediaByDir.set(dirname(m), [m])
  }

  const matches = new Map<string, string[]>()
  const matched = new Set<string>()
  const unmatched: string[] = []

  const addMatch = (media: string, sidecar: string) => {
    const list = matches.get(media)
    if (list) list.push(sidecar)
    else matches.set(media, [sidecar])
    matched.add(sidecar)
  }

  // Pass 1 — exact basename match within the same directory.
  for (const s of sidecarPaths) {
    const target = baseOfSidecar(s)
    const sameDir = mediaByDir.get(dirname(s)) ?? []
    const exact = sameDir.filter((m) => m === target)
    if (exact.length === 1) addMatch(exact[0], s)
  }

  // Pass 2 — truncation-tolerant prefix match for anything still unpaired.
  for (const s of sidecarPaths) {
    if (matched.has(s)) continue
    const base = basename(baseOfSidecar(s))
    if (base.length < MIN_PREFIX) {
      unmatched.push(s)
      continue
    }
    const sameDir = mediaByDir.get(dirname(s)) ?? []
    const candidates = sameDir.filter((m) => {
      const b = basename(m)
      return b.length >= MIN_PREFIX && (b.startsWith(base) || base.startsWith(b))
    })
    if (candidates.length === 0) {
      unmatched.push(s)
      continue
    }
    const pick = candidates.slice().sort((a, b) => {
      const da = Math.abs(basename(a).length - base.length)
      const db = Math.abs(basename(b).length - base.length)
      if (da !== db) return da - db
      return a < b ? -1 : a > b ? 1 : 0
    })[0]
    addMatch(pick, s)
  }

  const withSidecar = new Set(matches.keys())
  return {
    matches: [...matches.entries()].map(([media, sidecars]) => ({ media, sidecars })),
    unmatchedSidecars: unmatched,
    mediaWithoutSidecar: mediaPaths.filter((m) => !withSidecar.has(m)),
  }
}

/**
 * Merge several sidecar objects for one media file (exact + supplemental). Later entries
 * win, and empty/undefined values never overwrite a populated one — so a
 * `.supplemental-metadata.json` refines rather than blanks the base sidecar.
 */
export function mergeSidecars(objs: TakeoutSidecar[]): TakeoutSidecar {
  const out: Record<string, unknown> = {}
  for (const o of objs) {
    for (const [k, v] of Object.entries(o)) {
      if (v === undefined || v === null || v === '') continue
      out[k] = v
    }
  }
  return out as TakeoutSidecar
}
