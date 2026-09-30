/**
 * Google Photos Takeout discovery.
 *
 * Turns the files from a folder picker (an extracted Takeout tree) into ImportItems:
 * media files joined with their JSON sidecars, album folders, and Live/Motion Photo
 * pairings. It never reads or uploads anything itself — the engine does that — and it
 * reports everything it could not classify so nothing is dropped silently.
 *
 * Pure apart from `File.text()`/`Blob` reads, so the classification helpers are testable.
 */
import { pairSidecars, mergeSidecars, isSidecarJson } from './sidecar'
import type { ImportItem, ScanReport, TakeoutSidecar } from './types'

const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'heic', 'heif', 'webp', 'gif', 'avif', 'tif', 'tiff', 'bmp'])
const RAW_EXT = new Set(['dng', 'cr2', 'cr3', 'nef', 'arw', 'raf', 'orf', 'rw2', 'sr2', 'pef', 'srw'])
const VIDEO_EXT = new Set(['mp4', 'mov', 'm4v', '3gp', '3g2', 'avi', 'mkv', 'webm', 'mpg', 'mpeg'])

/** Best-effort MIME for extensions whose File.type is often empty (HEIC, RAW, …). */
const MIME: Record<string, string> = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', heic: 'image/heic', heif: 'image/heif',
  webp: 'image/webp', gif: 'image/gif', avif: 'image/avif', tif: 'image/tiff', tiff: 'image/tiff',
  bmp: 'image/bmp', dng: 'image/x-adobe-dng', cr2: 'image/x-canon-cr2', cr3: 'image/x-canon-cr3',
  nef: 'image/x-nikon-nef', arw: 'image/x-sony-arw', raf: 'image/x-fujifilm-raf', orf: 'image/x-olympus-orf',
  rw2: 'image/x-panasonic-rw2', mp4: 'video/mp4', mov: 'video/quicktime', m4v: 'video/x-m4v',
  '3gp': 'video/3gpp', avi: 'video/x-msvideo', mkv: 'video/x-matroska', webm: 'video/webm',
}

const GOOGLE_PHOTOS = 'google photos'

export function extOf(path: string): string {
  const base = path.slice(path.lastIndexOf('/') + 1)
  const dot = base.lastIndexOf('.')
  return dot < 0 ? '' : base.slice(dot + 1).toLowerCase()
}

function stemOf(path: string): string {
  const base = path.slice(path.lastIndexOf('/') + 1)
  const dot = base.lastIndexOf('.')
  return (dot < 0 ? base : base.slice(0, dot)).toLowerCase()
}

function dirOf(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? '' : path.slice(0, i)
}

/**
 * The album folder under `Google Photos/`, or null when the media sits at the root.
 * Handles selecting either the `Takeout` folder or the `Google Photos` folder; when no
 * marker is present it falls back to the immediate parent directory.
 */
export function albumPathOf(relPath: string): string | null {
  const parts = relPath.split('/')
  const idx = parts.findIndex((p) => p.toLowerCase() === GOOGLE_PHOTOS)
  const dirs = idx >= 0 ? parts.slice(idx + 1, -1) : parts.slice(-2, -1)
  const album = dirs.filter((d) => d && !/^takeout$/i.test(d))
  return album.length ? album.join('/') : null
}

function withMime(file: File, mime?: string): File {
  if (file.type || !mime) return file
  // Re-wrap so the encrypted manifest records a real MIME even when the browser
  // reports none for HEIC/RAW/video extensions.
  return new File([file], file.name, { type: mime, lastModified: file.lastModified })
}

export interface TakeoutScan {
  items: ImportItem[]
  report: ScanReport
}

export async function discoverTakeout(files: File[]): Promise<TakeoutScan> {
  const byPath = new Map<string, File>()
  const mediaPaths: string[] = []
  const sidecarPaths: string[] = []
  const unsupported: string[] = []

  for (const f of files) {
    const rel = (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name
    if (!byPath.has(rel)) byPath.set(rel, f)
    if (isSidecarJson(rel)) {
      sidecarPaths.push(rel)
      continue
    }
    const ext = extOf(rel)
    if (IMAGE_EXT.has(ext) || RAW_EXT.has(ext) || VIDEO_EXT.has(ext)) mediaPaths.push(rel)
    else unsupported.push(rel)
  }

  const { matches, unmatchedSidecars, mediaWithoutSidecar } = pairSidecars(mediaPaths, sidecarPaths)
  const sidecarsByMedia = new Map(matches.map((m) => [m.media, m.sidecars]))

  const parsed = new Map<string, TakeoutSidecar>()
  for (const s of sidecarPaths) {
    const f = byPath.get(s)
    if (!f) continue
    try {
      parsed.set(s, JSON.parse(await f.text()) as TakeoutSidecar)
    } catch {
      /* malformed sidecar -> surfaces via mediaWithoutSidecar/unmatched reporting */
    }
  }

  // Live/Motion Photo pairing: same folder + same stem, one image + one video. The
  // motion file is folded into the still as a companion (never imported on its own),
  // so a Live Photo is always a single compound asset.
  const motionByKey = new Map<string, string>()
  for (const p of mediaPaths) {
    if (VIDEO_EXT.has(extOf(p))) motionByKey.set(`${dirOf(p)}\u0000${stemOf(p)}`, p)
  }
  const pairedMotion = new Set<string>()
  for (const p of mediaPaths) {
    if (!VIDEO_EXT.has(extOf(p))) continue
    const still = mediaPaths.find(
      (q) => !VIDEO_EXT.has(extOf(q)) && dirOf(q) === dirOf(p) && stemOf(q) === stemOf(p),
    )
    if (still) pairedMotion.add(p)
  }

  const albumSet = new Set<string>()
  const items: ImportItem[] = []
  let livePhotoPairs = 0

  for (const p of mediaPaths) {
    const ext = extOf(p)
    const isMotion = VIDEO_EXT.has(ext)
    if (isMotion && pairedMotion.has(p)) continue // folded into its still as a companion

    const list = sidecarsByMedia.get(p) ?? []
    const objs = list.map((s) => parsed.get(s)).filter((o): o is TakeoutSidecar => !!o)
    const sidecar = objs.length ? mergeSidecars(objs) : null

    const albumPath = albumPathOf(p)
    if (albumPath) albumSet.add(albumPath)

    let companionFile: File | undefined
    let companionMime: string | null = null
    if (!isMotion) {
      const motion = motionByKey.get(`${dirOf(p)}\u0000${stemOf(p)}`)
      if (motion && pairedMotion.has(motion)) {
        const motionExt = extOf(motion)
        companionFile = withMime(byPath.get(motion)!, MIME[motionExt])
        companionMime = MIME[motionExt] ?? 'video/quicktime'
        livePhotoPairs += 1
      }
    }

    items.push({
      file: withMime(byPath.get(p)!, MIME[ext]),
      relativePath: p,
      sidecar,
      albumPath,
      companionMime,
      companionFile,
    })
  }

  return {
    items,
    report: {
      mediaCount: mediaPaths.length,
      albumCount: albumSet.size,
      livePhotoPairs,
      unmatchedSidecars,
      mediaWithoutSidecar: mediaWithoutSidecar.length,
      unsupported,
    },
  }
}
