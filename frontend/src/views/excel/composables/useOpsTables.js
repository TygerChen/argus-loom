/**
 * useOpsTables：运营中心三个同构表格（审计日志 / 协同日志 / 在线会话）的
 * 数据加载与筛选。
 *
 * <p>三段 load 的形状完全一样（loading → 组 params → 解包 → records/total），
 * 原来在组件里复制三份；收口到这里后，改分页/筛选行为只动这一处。
 * total 与 pageNum 由调用方共享（切换 tab 复用同一分页状态），因此作为
 * 参数注入而非模块内自建。</p>
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAuditLogs, listCollabLogs, listOnlineRooms } from '@/api/operations'

export function useOpsTables({ pageNum, pageSize }) {
  const auditFilter = ref({ username: '', action: '', targetType: '' })
  const collabFilter = ref({ documentId: '', username: '', merged: undefined, conflict: undefined })
  const roomFilter = ref({ documentId: '', username: '' })
  const auditRecords = ref([])
  const collabRecords = ref([])
  const roomRecords = ref([])
  const auditLoading = ref(false)
  const collabLoading = ref(false)
  const roomLoading = ref(false)
  const total = ref(0)

  /** 审计日志：谁在什么时候对哪个文档做了什么 */
  async function loadAudit() {
    auditLoading.value = true
    try {
      const data = await listAuditLogs({
        pageNum: pageNum.value,
        pageSize,
        username: auditFilter.value.username || undefined,
        action: auditFilter.value.action || undefined,
        targetType: auditFilter.value.targetType || undefined
      })
      auditRecords.value = data?.records || []
      total.value = data?.total || 0
    } catch (error) {
      ElMessage.error(error.message || '加载审计日志失败')
    } finally {
      auditLoading.value = false
    }
  }

  /** 协同日志：含 baseRev→finalRev、合并/冲突标记 */
  async function loadCollab() {
    collabLoading.value = true
    try {
      const data = await listCollabLogs({
        pageNum: pageNum.value,
        pageSize,
        documentId: collabFilter.value.documentId || undefined,
        username: collabFilter.value.username || undefined,
        merged: collabFilter.value.merged,
        conflict: collabFilter.value.conflict
      })
      collabRecords.value = data?.records || []
      total.value = data?.total || 0
    } catch (error) {
      ElMessage.error(error.message || '加载协同日志失败')
    } finally {
      collabLoading.value = false
    }
  }

  /** 在线会话：当前哪些用户在哪些文档的协同房间里 */
  async function loadRooms() {
    roomLoading.value = true
    try {
      const data = await listOnlineRooms({
        pageNum: pageNum.value,
        pageSize,
        documentId: roomFilter.value.documentId || undefined,
        username: roomFilter.value.username || undefined
      })
      roomRecords.value = data?.records || []
      total.value = data?.total || 0
    } catch (error) {
      ElMessage.error(error.message || '加载在线会话失败')
    } finally {
      roomLoading.value = false
    }
  }

  return {
    auditFilter,
    collabFilter,
    roomFilter,
    auditRecords,
    collabRecords,
    roomRecords,
    auditLoading,
    collabLoading,
    roomLoading,
    total,
    loadAudit,
    loadCollab,
    loadRooms
  }
}
