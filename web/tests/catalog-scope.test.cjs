/**
 * Account-scoping tests: a cached catalog belonging to a different account must be
 * dropped, never displayed. This is the fix for "signed in as B but seeing A's photos
 * as Missing resource".
 *
 * Run: npm run test:lib
 */
const assert = require('node:assert')
const { catalogBelongsToOtherAccount } = require('./.build/storage.js')

let count = 0
function check(name, got, want) {
  assert.strictEqual(got, want, `${name}: got ${got}, want ${want}`)
  count += 1
  console.log('  \u2713', name)
}

console.log('account scoping: cached catalog ownership')
check('same account keeps its cache', catalogBelongsToOtherAccount('u1', 'u1', 5), false)
check('different account drops the cache', catalogBelongsToOtherAccount('u1', 'u2', 5), true)
check('different account with nothing cached still resets', catalogBelongsToOtherAccount('u1', 'u2', 0), true)
check('legacy catalog (no owner) is rebuilt once', catalogBelongsToOtherAccount('u1', null, 5), true)
check('empty catalog with no owner is fine', catalogBelongsToOtherAccount('u1', null, 0), false)
check('unknown current account never wipes', catalogBelongsToOtherAccount('', 'u2', 5), false)
check('unknown current account, no owner: no wipe', catalogBelongsToOtherAccount('', null, 5), false)

console.log(`\ncatalog scoping: ${count} checks passed`)
