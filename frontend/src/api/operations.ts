import http from '@/api/http'

export function getOpsSummary() {
  return http.get('/admin/ops/summary')
}

export async function listAuditLogs(params: Record<string, unknown>) {
  const res = await http.get('/admin/ops/audit', { params })
  return res.data?.data ?? {}
}

export async function listCollabLogs(params: Record<string, unknown>) {
  const res = await http.get('/admin/ops/collab', { params })
  return res.data?.data ?? {}
}

export async function listOnlineRooms(params: Record<string, unknown>) {
  const res = await http.get('/admin/ops/rooms', { params })
  return res.data?.data ?? {}
}

export function kickOnlineRoom(roomId: number) {
  return http.post(`/admin/ops/rooms/${roomId}/kick`)
}

export function getVersionPolicy() {
  return http.get('/admin/ops/version-policy')
}

export function updateVersionPolicy(body: { retentionCount: number; autoCellIntervalMinutes: number }) {
  return http.put('/admin/ops/version-policy', body)
}

export function getVersionStats() {
  return http.get('/admin/ops/version-stats')
}
