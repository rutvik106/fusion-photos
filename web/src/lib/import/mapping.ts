/**
 * Google Photos Takeout JSON → encrypted AssetManifest overlay.
 *
 * Takeout is authoritative for capture time and GPS when present: the Library API strips
 * GPS, and older Takeouts lose EXIF, so sidecar values win over EXIF and EXIF is only the
 * fallback. Pure and dependency-free — exercised by tests/takeout-foundations.test.cjs.
 */
import type { ExifLike, GeoPoint, ManifestOverlay, TakeoutSidecar } from './types'

/** Takeout timestamps are unix *seconds* encoded as strings. */
export function takeoutTimestampToIso(t?: { timestamp?: string } | null): string | null {
  const raw = t?.timestamp
  if (!raw) return null
  const secs = Number(raw)
  if (!Number.isFinite(secs) || secs <= 0) return null
  const d = new Date(secs * 1000)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function gpsPoint(g?: GeoPoint | null): { latitude: number; longitude: number } | null {
  if (!g || typeof g.latitude !== 'number' || typeof g.longitude !== 'number') return null
  // Google uses (0, 0) to mean "no location" — never place a photo in the Gulf of Guinea.
  if (g.latitude === 0 && g.longitude === 0) return null
  return { latitude: g.latitude, longitude: g.longitude }
}

export interface TakeoutMappingInput {
  sidecar: TakeoutSidecar | null
  exif: ExifLike
  /** filename on disk (Takeout may have renamed it on a name collision) */
  fileName: string
  /** enclosing album folder, when the file lives under one */
  albumPath?: string | null
  /** MIME of a paired Live/Motion Photo motion part, if any */
  companionMime?: string | null
}

export function overlayFromTakeout(input: TakeoutMappingInput): ManifestOverlay {
  const { sidecar, exif, fileName, albumPath, companionMime } = input

  const capturedAt =
    takeoutTimestampToIso(sidecar?.photoTakenTime) ??
    takeoutTimestampToIso(sidecar?.creationTime) ??
    exif.capturedAt

  const point = gpsPoint(sidecar?.geoDataExif) ?? gpsPoint(sidecar?.geoData)

  const people = sidecar?.people
    ?.map((p) => p?.name)
    .filter((n): n is string => typeof n === 'string' && n.length > 0)

  return {
    source: 'takeout',
    capturedAt,
    capturedTzOffsetSeconds: exif.capturedTzOffsetSeconds,
    latitude: point?.latitude ?? exif.latitude,
    longitude: point?.longitude ?? exif.longitude,
    caption: sidecar?.description || undefined,
    favorite: sidecar?.favorited === true,
    archived: sidecar?.archived === true,
    trashed: sidecar?.trashed === true,
    originalFileName: sidecar?.title || fileName,
    albums: albumPath ? [albumPath] : undefined,
    people: people && people.length ? people : undefined,
    companion: companionMime ? { motionMime: companionMime, originalSize: 0 } : null,
  }
}
