import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// Session hydration: if a session token exists, the gallery still requires an unlock
// (vault key is never persisted), so no automatic redirect is needed here.
app.mount('#app')
