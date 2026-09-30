/**
 * Local derivative generation (thumbnails + previews) and metadata extraction.
 *
 * Per plan: derivatives are generated locally from the original bytes before upload.
 * If the browser cannot decode a format (HEIC in Chrome, some RAWs), we do NOT reject
 * the asset — the original is still backed up and the asset carries an explicit
 * "preview unavailable" state (never a silent drop).
 */
import exifr from 'exifr'

export const THUMBNAIL_MAX_PX = 512
export const PREVIEW_MAX_PX = 1600

export interface Derivatives {
  thumbnail: Blob | null
  preview: Blob | null
  width: number | null
  height: number | null
  previewAvailable: boolean
}

async function resizeToJpeg(src: ImageBitmap, maxPx: number): Promise<Blob> {
  const scale = Math.min(1, maxPx / Math.max(src.width, src.height))
  const w = Math.max(1, Math.round(src.width * scale))
  const h = Math.max(1, Math.round(src.height * scale))
  let canvas: OffscreenCanvas | HTMLCanvasElement
  if (typeof OffscreenCanvas !== 'undefined') {
    canvas = new OffscreenCanvas(w, h)
  } else {
    canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
  }
  const ctx = (canvas as OffscreenCanvas).getContext('2d') as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
  ctx.drawImage(src, 0, 0, w, h)
  src.close()
  const blob = await (canvas as OffscreenCanvas).convertToBlob
    ? (canvas as OffscreenCanvas).convertToBlob({ type: 'image/jpeg', quality: 0.82 })
    : new Promise<Blob>((resolve, reject) =>
        (canvas as HTMLCanvasElement).toBlob(
          (b) => (b ? resolve(b) : reject(new Error('toBlob failed'))),
          'image/jpeg',
          0.82,
        ),
      )
  return blob
}

export async function generateDerivatives(file: File): Promise<Derivatives> {
  try {
    // 'from-image' bakes EXIF orientation into the bitmap
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' })
    const width = bmp.width
    const height = bmp.height
    const thumbnail = await resizeToJpeg(bmp, THUMBNAIL_MAX_PX)
    const preview = await resizeToJpeg(
      await createImageBitmap(file, { imageOrientation: 'from-image' }),
      PREVIEW_MAX_PX,
    )
    return { thumbnail, preview, width, height, previewAvailable: true }
  } catch {
    // Browser can't decode (e.g. HEIC in Chrome, some RAWs): keep the original,
    // surface preview-unavailable in the UI. Never silently drop the asset.
    return { thumbnail: null, preview: null, width: null, height: null, previewAvailable: false }
  }
}

export interface ExifData {
  capturedAt: string | null
  capturedTzOffsetSeconds: number | null
  latitude: number | null
  longitude: number | null
}

export async function extractExif(file: File): Promise<ExifData> {
  try {
    const d = await exifr.parse(file, ['DateTimeOriginal', 'CreateDate', 'tzOffsetSeconds', 'latitude', 'longitude'])
    const date: Date | undefined = d?.DateTimeOriginal ?? d?.CreateDate
    return {
      capturedAt: date ? date.toISOString() : null,
      capturedTzOffsetSeconds: typeof d?.tzOffsetSeconds === 'number' ? d.tzOffsetSeconds : null,
      latitude: typeof d?.latitude === 'number' ? d.latitude : null,
      longitude: typeof d?.longitude === 'number' ? d.longitude : null,
    }
  } catch {
    return { capturedAt: null, capturedTzOffsetSeconds: null, latitude: null, longitude: null }
  }
}
