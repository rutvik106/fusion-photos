import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../lib/api'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** Signed-in shell route: needs a session token AND an unlocked vault key. */
    auth?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/gallery' },
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/unlock', component: () => import('../views/UnlockView.vue') },
    { path: '/gallery', component: () => import('../views/GalleryView.vue'), meta: { auth: true } },
    { path: '/import', component: () => import('../views/ImportView.vue'), meta: { auth: true } },
    { path: '/export', component: () => import('../views/ExportView.vue'), meta: { auth: true } },
    { path: '/explore', component: () => import('../views/PlaceholderView.vue'), meta: { auth: true } },
    { path: '/sharing', component: () => import('../views/PlaceholderView.vue'), meta: { auth: true } },
    { path: '/library', component: () => import('../views/PlaceholderView.vue'), meta: { auth: true } },
    { path: '/trash', component: () => import('../views/TrashView.vue'), meta: { auth: true } },
  ],
})

/**
 * Navigation guards. The three states are:
 *   no session token        -> /login (email OTP)
 *   session, vault locked   -> /unlock (passphrase)
 *   session + unlocked key  -> the signed-in shell (/gallery and friends)
 * A page reload always drops back to /unlock (the vault key is memory-only by design);
 * an expired/invalid session drops to /login.
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  const hasSession = Boolean(getToken())

  if (to.meta.auth) {
    if (!hasSession) return '/login'
    if (!auth.vaultKey) return '/unlock'
  }
  if (to.path === '/unlock') {
    if (!hasSession) return '/login'
    if (auth.vaultKey) return '/gallery'
  }
  if (to.path === '/login' && hasSession && auth.vaultKey) {
    return '/gallery'
  }
})

export default router
