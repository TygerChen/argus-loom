import { useAuthStore } from '@/stores/auth'

export function getUserProfile() {
  try {
    const store = useAuthStore()
    if (store.currentUser) return store.currentUser
  } catch {
    // fall through to localStorage for legacy profile storage
  }
  try {
    const s = localStorage.getItem('userProfile')
    return s ? JSON.parse(s) : null
  } catch { return null }
}

export function getAccessToken() {
  try {
    return useAuthStore().accessToken
  } catch {
    return null
  }
}

export function isAdminUser() {
  try {
    return useAuthStore().isAdmin
  } catch {
    const profile = getUserProfile()
    return profile?.systemRole === 'ADMIN' || profile?.role === 'ADMIN'
  }
}

export function hasPermission() {
  return true
}
