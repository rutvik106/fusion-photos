/**
 * Compile the pure importer modules (src/lib/import/*.ts) to CommonJS so the plain
 * node tests can require them. run from `npm run test:import`.
 *
 * They must land in tests/.build as CJS, but the package is `"type": "module"`, so we
 * drop a `{"type":"commonjs"}` marker into the output directory. tsc is invoked via the
 * local devDependency (no npx / no platform shell quirks).
 */
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..')
const outDir = path.join(__dirname, '.build')

execFileSync(
  process.execPath,
  [
    require.resolve('typescript/bin/tsc'),
    'src/lib/import/types.ts',
    'src/lib/import/sidecar.ts',
    'src/lib/import/mapping.ts',
    'src/lib/import/takeout.ts',
    'src/lib/import/byteSource.ts',
    'src/lib/import/zipCentralDirectory.ts',
    'src/lib/import/tauriByteSource.ts',
    'src/lib/api.ts',
    'src/lib/storage.ts',
    '--outDir',
    'tests/.build',
    '--module',
    'commonjs',
    '--target',
    'es2022',
    '--moduleResolution',
    'node',
    '--skipLibCheck',
    '--strict',
  ],
  { cwd: root, stdio: 'inherit' },
)

fs.mkdirSync(outDir, { recursive: true })
fs.writeFileSync(path.join(outDir, 'package.json'), JSON.stringify({ type: 'commonjs' }))
