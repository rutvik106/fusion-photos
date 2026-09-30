/**
 * Shared types for the migration importers (Google Photos Takeout first).
 *
 * These describe the *source* formats only. The app's own on-disk/catalog types live in
 * db.ts (LocalAsset, ImportJob) and upload.ts (AssetManifest); this module has no runtime
 * dependencies so it can be compiled and tested in isolation (tests/takeout-foundations).
 */

export interface GeoPoint {
  latitude: number
  longitude: number
  altitude?: number
  latitudeSpan?: number
  longitudeSpan?: number
}

/**
 * A Google Photos Takeout sidecar JSON. Only the fields we map are modelled; unknown
 * fields are ignored. Timestamps are unix *seconds* encoded as strings.
 */
export interface TakeoutSidecar {
  title?: string
  description?: string
  photoTakenTime?: { timestamp?: string; formatted?: string } | null
  creationTime?: { timestamp?: string; formatted?: string } | null
  geoData?: GeoPoint | null
  geoDataExif?: GeoPoint | null
  favorited?: boolean
  archived?: boolean
  trashed?: boolean
  people?: Array<{ name?: string }>
  video?: { fps?: number; status?: string }
  googlePhotosOrigin?: Record<string, unknown>
  imageViews?: string
  url?: string
}

/**
 * Structural mirror of images.ts `ExifData`. Kept local (rather than importing images.ts)
 * so the importer stays free of the `exifr` runtime dependency and remains unit-testable
 * without a browser.
 */
export interface ExifLike {
  capturedAt: string | null
  capturedTzOffsetSeconds: number | null
  latitude: number | null
  longitude: number | null
}

/** The subset of AssetManifest an importer contributes, before encryption. */
export interface ManifestOverlay {
  source: 'takeout'
  capturedAt: string | null
  capturedTzOffsetSeconds: number | null
  latitude: number | null
  longitude: number | null
  caption?: string
  favorite: boolean
  archived: boolean
  trashed: boolean
  originalFileName?: string
  albums?: string[]
  people?: string[]
  companion: { motionMime: string; originalSize: number } | null
}

/** One media file discovered by a source scanner, ready for the import engine. */
export interface ImportItem {
  /** the media file (original bytes — never re-encoded) */
  file: File
  /** path relative to the selected folder root (album derivation + reporting) */
  relativePath: string
  /** merged Takeout sidecar metadata, when one was paired */
  sidecar: TakeoutSidecar | null
  /** enclosing album folder under Google Photos/, or null when at the root */
  albumPath: string | null
  /** when this still has a paired motion part, that part's MIME type */
  companionMime?: string | null
  /** the paired motion file itself (Live Photo video), uploaded as `original_motion` */
  companionFile?: File
}

/** A summary of what a source scan found — shown before the user commits to an import. */
export interface ScanReport {
  mediaCount: number
  albumCount: number
  livePhotoPairs: number
  unmatchedSidecars: string[]
  mediaWithoutSidecar: number
  /** files with extensions outside the supported set (reported, never imported silently) */
  unsupported: string[]
}

/** The outcome of an import run — the plan's "integrity report". */
export interface ImportRunResult {
  committed: number
  skipped: number
  failed: number
  bytes: number
  failures: Array<{ fileName: string; error: string }>
}

