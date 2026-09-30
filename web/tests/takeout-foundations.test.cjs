/**
 * Foundation tests for the Google Photos Takeout importer's pure modules.
 *
 * These exercise the REAL code — src/lib/import/*.ts compiled to tests/.build by
 * `npm run test:import` — rather than a re-implementation, so pairing and mapping
 * behaviour cannot silently drift from the app.
 *
 * Run: npm run test:import
 */
const assert = require('node:assert')
const { pairSidecars, mergeSidecars, baseOfSidecar, isSidecarJson } = require('./.build/sidecar.js')
const { overlayFromTakeout, takeoutTimestampToIso } = require('./.build/mapping.js')
const { extOf, albumPathOf } = require('./.build/takeout.js')

let count = 0
function test(name, fn) {
  fn()
  count += 1
  console.log('  \u2713', name)
}

const EXIF_EMPTY = { capturedAt: null, capturedTzOffsetSeconds: null, latitude: null, longitude: null }

console.log('takeout: sidecar path helpers')
test('baseOfSidecar strips .json and .supplemental-metadata.json', () => {
  assert.strictEqual(baseOfSidecar('a/IMG_1.jpg.json'), 'a/IMG_1.jpg')
  assert.strictEqual(baseOfSidecar('a/IMG_1.jpg.supplemental-metadata.json'), 'a/IMG_1.jpg')
  assert.strictEqual(baseOfSidecar('a/plain'), 'a/plain')
})
test('isSidecarJson recognises sidecars', () => {
  assert.strictEqual(isSidecarJson('a/IMG_1.jpg.json'), true)
  assert.strictEqual(isSidecarJson('a/IMG_1.jpg'), false)
})

console.log('takeout: sidecar pairing')
test('exact name pairs', () => {
  const r = pairSidecars(['p/IMG_1.jpg'], ['p/IMG_1.jpg.json'])
  assert.strictEqual(r.matches.length, 1)
  assert.strictEqual(r.matches[0].media, 'p/IMG_1.jpg')
  assert.deepStrictEqual(r.matches[0].sidecars, ['p/IMG_1.jpg.json'])
  assert.deepStrictEqual(r.unmatchedSidecars, [])
  assert.deepStrictEqual(r.mediaWithoutSidecar, [])
})
test('supplemental-metadata variant pairs with the media', () => {
  const r = pairSidecars(['p/IMG_2.heic'], ['p/IMG_2.heic.supplemental-metadata.json'])
  assert.deepStrictEqual(r.matches[0].sidecars, ['p/IMG_2.heic.supplemental-metadata.json'])
})
test('multiple sidecars for one media are all attached', () => {
  const r = pairSidecars(
    ['p/IMG_3.jpg'],
    ['p/IMG_3.jpg.json', 'p/IMG_3.jpg.supplemental-metadata.json'],
  )
  assert.strictEqual(r.matches.length, 1)
  assert.strictEqual(r.matches[0].sidecars.length, 2)
})
test('truncated (prefix) name still pairs', () => {
  const r = pairSidecars(
    ['p/this_is_a_really_long_original_filename_2024.jpg'],
    ['p/this_is_a_really_long_original.json'],
  )
  assert.strictEqual(r.matches.length, 1)
  assert.strictEqual(r.matches[0].media, 'p/this_is_a_really_long_original_filename_2024.jpg')
})
test('ambiguous prefix resolves deterministically to the closest length', () => {
  const media = ['p/abcdefgh_short.jpg', 'p/abcdefgh_short_EXTRA_LONG.jpg']
  const r = pairSidecars(media, ['p/abcdefgh_short.json'])
  assert.strictEqual(r.matches[0].media, 'p/abcdefgh_short.jpg')
})
test('different directories never cross-pair', () => {
  const r = pairSidecars(['albums/Trip/IMG_9.jpg'], ['albums/Other/IMG_9.jpg.json'])
  assert.deepStrictEqual(r.matches, [])
  assert.deepStrictEqual(r.unmatchedSidecars, ['albums/Other/IMG_9.jpg.json'])
  assert.deepStrictEqual(r.mediaWithoutSidecar, ['albums/Trip/IMG_9.jpg'])
})
test('unmatched sidecar and media-without-sidecar are reported', () => {
  const r = pairSidecars(['p/IMG_A.jpg'], ['p/IMG_B.jpg.json'])
  assert.deepStrictEqual(r.unmatchedSidecars, ['p/IMG_B.jpg.json'])
  assert.deepStrictEqual(r.mediaWithoutSidecar, ['p/IMG_A.jpg'])
})

console.log('takeout: mergeSidecars')
test('later objects win and empty values never overwrite', () => {
  const merged = mergeSidecars([
    { title: 'old.jpg', description: 'kept', favorited: false },
    { title: 'new.jpg', description: '', favorited: true, archived: true },
  ])
  assert.strictEqual(merged.title, 'new.jpg')
  assert.strictEqual(merged.description, 'kept')
  assert.strictEqual(merged.favorited, true)
  assert.strictEqual(merged.archived, true)
})

console.log('takeout: timestamps')
test('unix seconds string -> ISO, invalid rejected', () => {
  assert.strictEqual(takeoutTimestampToIso({ timestamp: '1609459200' }), '2021-01-01T00:00:00.000Z')
  assert.strictEqual(takeoutTimestampToIso({ timestamp: '0' }), null)
  assert.strictEqual(takeoutTimestampToIso({ timestamp: 'nope' }), null)
  assert.strictEqual(takeoutTimestampToIso(null), null)
})

console.log('takeout: manifest overlay mapping')
test('photoTakenTime wins over creationTime and EXIF', () => {
  const o = overlayFromTakeout({
    sidecar: {
      photoTakenTime: { timestamp: '1609459200' },
      creationTime: { timestamp: '1700000000' },
    },
    exif: { ...EXIF_EMPTY, capturedAt: '1999-01-01T00:00:00.000Z' },
    fileName: 'a.jpg',
  })
  assert.strictEqual(o.capturedAt, '2021-01-01T00:00:00.000Z')
})
test('creationTime is fallback, then EXIF, then null', () => {
  const creation = overlayFromTakeout({
    sidecar: { creationTime: { timestamp: '1700000000' } },
    exif: { ...EXIF_EMPTY, capturedAt: '1999-01-01T00:00:00.000Z' },
    fileName: 'a.jpg',
  })
  assert.strictEqual(creation.capturedAt, new Date(1700000000 * 1000).toISOString())

  const viaExif = overlayFromTakeout({
    sidecar: null,
    exif: { ...EXIF_EMPTY, capturedAt: '1999-01-01T00:00:00.000Z' },
    fileName: 'a.jpg',
  })
  assert.strictEqual(viaExif.capturedAt, '1999-01-01T00:00:00.000Z')

  const none = overlayFromTakeout({ sidecar: null, exif: EXIF_EMPTY, fileName: 'a.jpg' })
  assert.strictEqual(none.capturedAt, null)
})
test('GPS prefers geoDataExif, treats (0,0) as none, falls back to EXIF', () => {
  const exifBacked = overlayFromTakeout({
    sidecar: {
      geoData: { latitude: 1, longitude: 2 },
      geoDataExif: { latitude: 10, longitude: 20 },
    },
    exif: EXIF_EMPTY,
    fileName: 'a.jpg',
  })
  assert.strictEqual(exifBacked.latitude, 10)
  assert.strictEqual(exifBacked.longitude, 20)

  const zero = overlayFromTakeout({
    sidecar: { geoData: { latitude: 0, longitude: 0 } },
    exif: { ...EXIF_EMPTY, latitude: 5, longitude: 6 },
    fileName: 'a.jpg',
  })
  assert.strictEqual(zero.latitude, 5)
  assert.strictEqual(zero.longitude, 6)
})
test('flags, caption, album, people and companion map through', () => {
  const o = overlayFromTakeout({
    sidecar: {
      favorited: true,
      archived: true,
      trashed: true,
      description: 'sunset',
      title: 'ORIGINAL.HEIC',
      people: [{ name: 'Ada' }, { name: '' }, {}],
    },
    exif: EXIF_EMPTY,
    fileName: 'renamed.heic',
    albumPath: 'Photos from 2023',
    companionMime: 'video/quicktime',
  })
  assert.strictEqual(o.source, 'takeout')
  assert.strictEqual(o.favorite, true)
  assert.strictEqual(o.archived, true)
  assert.strictEqual(o.trashed, true)
  assert.strictEqual(o.caption, 'sunset')
  assert.strictEqual(o.originalFileName, 'ORIGINAL.HEIC')
  assert.deepStrictEqual(o.albums, ['Photos from 2023'])
  assert.deepStrictEqual(o.people, ['Ada'])
  assert.deepStrictEqual(o.companion, { motionMime: 'video/quicktime', originalSize: 0 })
})
test('missing sidecar yields safe defaults (no flags, filename used)', () => {
  const o = overlayFromTakeout({ sidecar: null, exif: EXIF_EMPTY, fileName: 'plain.jpg' })
  assert.strictEqual(o.favorite, false)
  assert.strictEqual(o.archived, false)
  assert.strictEqual(o.trashed, false)
  assert.strictEqual(o.originalFileName, 'plain.jpg')
  assert.strictEqual(o.caption, undefined)
  assert.strictEqual(o.albums, undefined)
  assert.strictEqual(o.companion, null)
})

console.log('takeout: path classification')
test('extOf lowercases and handles a missing extension', () => {
  assert.strictEqual(extOf('a/b/IMG.JPEG'), 'jpeg')
  assert.strictEqual(extOf('a/b/README'), '')
})
test('albumPathOf reads the folder under Google Photos', () => {
  assert.strictEqual(albumPathOf('Takeout/Google Photos/Photos from 2023/IMG.jpg'), 'Photos from 2023')
  assert.strictEqual(albumPathOf('Google Photos/Our Trip/IMG.jpg'), 'Our Trip')
  assert.strictEqual(albumPathOf('Google Photos/IMG.jpg'), null)
})
test('albumPathOf falls back to the immediate parent when no marker', () => {
  assert.strictEqual(albumPathOf('Backup/2019/IMG.jpg'), '2019')
})

// --- async: full discovery (reads File bodies) ---

function withRelPath(file, relPath) {
  Object.defineProperty(file, 'webkitRelativePath', { value: relPath })
  return file
}

;(async () => {
  console.log('takeout: discovery (async)')

  const { discoverTakeout } = require('./.build/takeout.js')

  const still = withRelPath(
    new File([new Uint8Array([1, 2, 3])], 'IMG_1.HEIC', { type: 'image/heic' }),
    'Google Photos/Trip/IMG_1.HEIC',
  )
  const motion = withRelPath(
    new File([new Uint8Array([4, 5])], 'IMG_1.MOV', { type: 'video/quicktime' }),
    'Google Photos/Trip/IMG_1.MOV',
  )
  const sidecar = withRelPath(
    new File([JSON.stringify({ title: 'IMG_1.HEIC', favorited: true, photoTakenTime: { timestamp: '1609459200' } })], 'IMG_1.HEIC.json'),
    'Google Photos/Trip/IMG_1.HEIC.json',
  )

  const scan = await discoverTakeout([still, motion, sidecar])

  console.log('takeout: live photo companion pairing')
  test('a paired motion file folds into the still (one item, not two)', () => {
    assert.strictEqual(scan.items.length, 1)
    assert.strictEqual(scan.items[0].file.name, 'IMG_1.HEIC')
    assert.strictEqual(scan.items[0].companionFile.name, 'IMG_1.MOV')
    assert.strictEqual(scan.report.livePhotoPairs, 1)
  })
  test('the still keeps its album and merged sidecar metadata', () => {
    assert.strictEqual(scan.items[0].albumPath, 'Trip')
    assert.strictEqual(scan.items[0].sidecar.favorited, true)
    assert.strictEqual(scan.items[0].sidecar.title, 'IMG_1.HEIC')
  })

  console.log(`\ntakeout foundations: ${count} checks passed`)
})().catch((err) => {
  console.error(err)
  process.exit(1)
})

