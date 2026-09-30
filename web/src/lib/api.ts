/**
 * Thin API client. All control-plane calls go through the local Go API. Media bytes are
 * fetched directly from S3/MinIO via short-lived presigned URLs issued by the API after
 * an ownership check — the client never chooses object keys.
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message)
  }
}

const TOKEN_KEY = 'fp.sessionToken'
const USER_KEY = 'fp.userId'

/**
 * API base URL. Empty in the browser (requests are same-origin and Vite's dev proxy
 * forwards `/v1`). A native host (the desktop importer) injects an absolute base via
 * `window.__FUSION_API_BASE__` before the app boots, since a packaged app has no proxy.
 */
declare global {
  interface Window {
    __FUSION_API_BASE__?: string
  }
}
const API_BASE =
  (typeof window !== 'undefined' ? window.__FUSION_API_BASE__ : undefined)?.replace(/\/$/, '') ?? ''

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(t: string | null) {
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

/**
 * The signed-in account id, persisted so a reload can still tell WHICH account the
 * locally cached catalog belongs to (the in-memory store only knows it after login).
 * This is what lets a cached catalog be dropped the moment the account changes.
 */
export function getUserId(): string | null {
  return localStorage.getItem(USER_KEY)
}
export function setUserId(id: string | null) {
  if (id) localStorage.setItem(USER_KEY, id)
  else localStorage.removeItem(USER_KEY)
}

/** Forget the session entirely (expired, revoked, or explicit sign-out). */
export function clearSession() {
  setToken(null)
  setUserId(null)
}

export async function api<T>(
  path: string,
  opts: { method?: string; body?: unknown } = {},
): Promise<T> {
  const token = getToken()
  const res = await fetch(`${API_BASE}/v1${path}`, {
    method: opts.method ?? (opts.body ? 'POST' : 'GET'),
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? 'unknown', data?.error ?? `HTTP ${res.status}`)
  }
  return data as T
}

/** A presigned URL is a short-lived bearer token: once it expires S3/MinIO answers
 * 400/403 (and 404 if the object was replaced). Never reuse or cache it — mint a fresh
 * one and retry once. This is what lets a long-running export survive the 15-minute
 * signing window. */
const SIGNATURE_FAILURE = new Set([400, 403, 404])

export async function fetchSigned(
  sign: () => Promise<{ url: string }>,
  init?: RequestInit,
): Promise<Response> {
  const first = await fetch((await sign()).url, init)
  if (first.ok || !SIGNATURE_FAILURE.has(first.status)) return first
  return fetch((await sign()).url, init)
}

// ---------- typed endpoints ----------

export interface OtpRequestResponse {
  ok: true
  /** Only present when the server runs in dev mode — never in production. */
  devCode?: string
}

export interface OtpVerifyResponse {
  sessionToken: string
  userId: string
  hasVault: boolean
  /** Present on first login when no vault exists yet. */
  wrappedVault?: { kdfSalt: string; wrapped: string; v: number } | null
}

/** One key-wrapping envelope (base64). */
export interface WrappedVault {
  /** base64 argon2 salt (empty for the recovery envelope, which has no KDF) */
  kdfSalt: string
  /** base64 nonce(24) || AEAD ciphertext || tag(16) */
  wrapped: string
  /** crypto format version, for future migration */
  v: number
}

export interface VaultPutBody {
  wrapped: VaultBlob
}

/**
 * Server-side vault blob (opaque to the server). Holds two envelopes:
 * passphrase-wrapped and recovery-key-wrapped. Legacy vaults (pre-recovery-key)
 * are the flat single-envelope form; the client handles both shapes.
 */
export interface VaultBlob {
  passphrase?: WrappedVault
  recovery?: WrappedVault | null
  // legacy flat form
  kdfSalt?: string
  wrapped?: string
  v?: number
}

export interface UploadItemRequest {
  originalSize: number
  /** ciphertext sizes (payload = header + frames); original_motion is the Live Photo companion */
  encryptedSizes: {
    original: number
    preview: number
    thumbnail: number
    manifest: number
    original_motion?: number
  }
  /** opaque metadata the client encrypted; server stores as opaque ID only */
  assetIdClient: string
}

export interface UploadItemPlan {
  /** echoes the client's dedupe reference */
  assetIdClient: string
  assetId: string
  /** server-chosen immutable object keys, one per resource */
  keys: Record<ResourceVariant, string>
  /** presigned PUT URLs, one per resource */
  putUrls: Record<ResourceVariant, string>
  maxSizeBytes: number
  /**
   * Idempotent replay: this client ref already has a committed asset, so there is
   * nothing to upload (the server returned no URLs). Re-uploading would re-encrypt
   * under a new key and corrupt the stored object.
   */
  committed?: boolean
}

export interface UploadCompleteBody {
  items: Array<{
    assetId: string
    keyEnvelope: string
    /** encrypted manifest blob (b64) — contains filename, capture time, gps, digests */
    manifest: { headerB64: string; blobB64: string }
    resources: Array<{
      variant: ResourceVariant
      objectKey: string
      ciphertextSize: number
    }>
  }>
}

export interface UploadCompleteResponse {
  assets: Array<{ assetId: string; seq: number }>
}

export type ResourceVariant = 'original' | 'preview' | 'thumbnail' | 'manifest' | 'original_motion'

export interface SyncAsset {
  assetId: string
  seq: number
  keyEnvelope: string
  manifest: { headerB64: string; blobB64: string }
  resources: Record<ResourceVariant, { objectKey: string; ciphertextSize: number }>
  capturedAt: string | null
  deleted: boolean
  /** RFC3339 timestamp when the asset moved to cloud trash, if trashed */
  deletedAt: string | null
  /**
   * True only after the server verified every resource of this asset in the
   * independent replica bucket. Never treat an asset as safe to remove from a device
   * until this is true.
   */
  protected: boolean
}

/** Honest picture of what is backed up and what is safe (GET /v1/backup/status). */
export interface BackupStatus {
  totalAssets: number
  protectedAssets: number
  unreplicatedAssets: number
  trashedAssets: number
  pendingUploads: number
  expiredUploads: number
  lastCommittedAt: string | null
  lastReplicationCheck: string | null
  /** False means this deployment has no second copy, so nothing can be "protected". */
  replicaConfigured: boolean
}

/** A point-in-time, immutable selection of the library (export snapshots). */
export interface ExportSnapshot {
  snapshotId: string
  createdAt: string
  expiresAt: string
  maxSeq: number
  assetCount: number
  byteTotal: number
}

export interface ExportSnapshotsResponse {
  snapshots: ExportSnapshot[]
  /** Disclosed retention window: how long a snapshot stays available to resume. */
  retentionSeconds: number
}

export interface MultipartInitResponse {
  size: number
  parts: Array<{ number: number; offset: number; length: number; url: string }>
}

export interface MultipartStatusResponse {
  started: boolean
  parts: Array<{ number: number; etag: string; size: number }>
}

export interface MultipartPartsResponse {
  parts: Array<{ number: number; url: string; size?: number }>
}

export interface SyncResponse {
  cursor: number
  assets: SyncAsset[]
}

export const API = {
  requestOtp: (email: string) =>
    api<OtpRequestResponse>('/auth/otp/request', { body: { email } }),
  /** Which account this session belongs to (used to scope the local catalog). */
  me: () => api<{ userId: string; email: string }>('/me'),
  verifyOtp: (email: string, code: string) =>
    api<OtpVerifyResponse>('/auth/otp/verify', { body: { email, code } }),
  putVault: (body: VaultPutBody) => api<{ ok: true }>('/vault', { method: 'PUT', body }),
  getVault: () => api<{ wrapped: VaultBlob | null }>('/vault'),
  createUploadSession: (items: UploadItemRequest[]) =>
    api<{ items: UploadItemPlan[] }>('/upload-session', { body: { items } }),
  completeUploadSession: (body: UploadCompleteBody) =>
    api<UploadCompleteResponse>('/upload-session/complete', { body }),
  sync: (cursor: number) => api<SyncResponse>(`/sync/changes?cursor=${cursor}`),
  /** Authoritative backup/protection counts for the signed-in account. */
  backupStatus: () => api<BackupStatus>('/backup/status'),
  /** Create an immutable, resumable export snapshot of the current library. */
  createExportSnapshot: () => api<ExportSnapshot>('/export/snapshot', { body: {} }),
  listExportSnapshots: () => api<ExportSnapshotsResponse>('/export/snapshots'),
  /** Page a snapshot's assets (oldest first) to build the export manifest. */
  exportAssets: (snapshotId: string, cursor: number, limit = 200) =>
    api<SyncResponse>(
      `/export/snapshot/${encodeURIComponent(snapshotId)}/assets?cursor=${cursor}&limit=${limit}`,
    ),
  /** Returns a presigned GET URL for one resource of one owned asset. When snapshotId
   * is given the lookup is snapshot-scoped, so an asset trashed after the snapshot was
   * created is still exportable (snapshot immutability). */
  resourceUrl: (assetId: string, variant: ResourceVariant, snapshotId?: string) =>
    api<{ url: string; objectKey: string; ciphertextSize: number }>(
      `/resource/${encodeURIComponent(assetId)}/${variant}` +
        (snapshotId ? `?snapshot=${encodeURIComponent(snapshotId)}` : ''),
    ),
  trashAsset: (assetId: string) =>
    api<{ purgeAfter: string }>(`/assets/${encodeURIComponent(assetId)}/trash`, { body: {} }),
  restoreAsset: (assetId: string) =>
    api<{ ok: true }>(`/assets/${encodeURIComponent(assetId)}/restore`, { body: {} }),
  putUrlsFor: (assetId: string, variants: ResourceVariant[]) =>
    api<{ items: Array<{ variant: ResourceVariant; objectKey: string; url: string }> }>(
      `/upload-session/${encodeURIComponent(assetId)}/put-urls`,
      { body: { variants } },
    ),
  multipartInit: (assetId: string, variant: ResourceVariant, partCount: number) =>
    api<MultipartInitResponse>(
      `/upload-session/${encodeURIComponent(assetId)}/multipart/init`,
      { body: { variant, partCount } },
    ),
  multipartParts: (assetId: string, variant: ResourceVariant, numbers: number[]) =>
    api<MultipartPartsResponse>(
      `/upload-session/${encodeURIComponent(assetId)}/multipart/parts`,
      { body: { variant, numbers } },
    ),
  multipartStatus: (assetId: string, variant: ResourceVariant) =>
    api<MultipartStatusResponse>(
      `/upload-session/${encodeURIComponent(assetId)}/multipart/status`,
      { body: { variant } },
    ),
  multipartComplete: (assetId: string, variant: ResourceVariant, parts: Array<{ number: number; etag: string }>) =>
    api<{ size: number }>(
      `/upload-session/${encodeURIComponent(assetId)}/multipart/complete`,
      { body: { variant, parts } },
    ),
  multipartAbort: (assetId: string, variant: ResourceVariant) =>
    api<{ ok: true }>(
      `/upload-session/${encodeURIComponent(assetId)}/multipart/abort`,
      { body: { variant } },
    ),
}
