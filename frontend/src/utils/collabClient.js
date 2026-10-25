import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { getAccessToken } from '@/utils/auth'

/**
 * 创建 STOMP 协同客户端：通过原生 WebSocket 连接 /ws，加入文档房间，订阅编辑与在线名单。
 * 后端 CELL_EDIT 单格帧会被归一化成 useCollabSync 期待的 cells-updated 批次。
 *
 * <p><b>对照 05 篇读</b>。协同链路的前端半边：</p>
 * <ul>
 *   <li><b>STOMP over SockJS</b>：WebSocket 是"电话线"，STOMP 是上面的"通话协议"
 *       （发布/订阅语义），SockJS 是兜底包装（老浏览器没有 WebSocket 时自动降级
 *       成轮询等替代传输）；</li>
 *   <li><b>认证</b>：连接时把 accessToken 放进 connectHeaders——WebSocket 不是
 *       HTTP 请求，走不了常规拦截器，必须在握手时带上；</li>
 *   <li><b>心跳（10 秒）</b>：双方定期互发心跳帧，断网能被立刻发现而不是傻等
 *       TCP 超时；reconnectDelay=2000 断线 2 秒自动重连——协同编辑对"掉线感知"
 *       的要求是秒级的；</li>
 *   <li><b>房间隔离</b>：订阅地址是 /topic/excel/{documentId}——一个文档一个房间，
 *       广播只发给同房间的人（05 篇 Q1 的"按房间隔离主题"）。</li>
 * </ul>
 */
export function createCollabClient({ documentId, onStatus, onMessage }) {
  let client = null
  let disposed = false

  const buildWebSocketUrl = () => {
    // Backend exposes the STOMP endpoint via SockJS at /ws. SockJS negotiates
    // the first transport itself, so the http(s) origin is correct here.
    const scheme = window.location.protocol === 'https:' ? 'https' : 'http'
    return `${scheme}://${window.location.host}/ws`
  }

  const handleStatus = (status) => {
    if (!disposed && typeof onStatus === 'function') onStatus(status)
  }

  const connect = () => {
    disposed = false
    const token = getAccessToken()
    if (!token) {
      handleStatus('offline')
      return
    }

    client = new Client({
      webSocketFactory: () => new SockJS(buildWebSocketUrl()),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: () => {},
      onConnect: () => {
        if (disposed) return
        handleStatus('online')
        client.subscribe(`/topic/excel/${documentId}`, (frame) => {
          try {
            const message = JSON.parse(frame.body)
            if (message && message.type === 'CELL_EDIT') {
              onMessage({
                type: 'cells-updated',
                requestId: message.requestId || message.senderSessionId,
                cells: [{
                  sheetId: message.sheetId,
                  r: message.r,
                  c: message.c,
                  v: message.v,
                  rev: message.serverTimestamp,
                  revision: message.revision,
                  merged: 1,
                  conflict: 0
                }]
              })
            } else {
              onMessage(message)
            }
          } catch (error) {
            console.warn('解析协作消息失败:', error)
          }
        })
        client.subscribe(`/topic/excel/${documentId}/users`, (frame) => {
          try {
            const message = JSON.parse(frame.body)
            if (message && message.type === 'USER_LIST') {
              onMessage({
                type: 'presence',
                users: Array.isArray(message.users) ? message.users : [],
                count: message.count || 0
              })
            } else {
              onMessage(message)
            }
          } catch (error) {
            console.warn('解析在线名单失败:', error)
          }
        })
        client.publish({
          destination: `/app/excel/join/${documentId}`,
          body: '{}'
        })
      },
      onStompError: (frame) => {
        handleStatus('offline')
        onMessage({ type: 'error', code: 'stomp-error', message: frame.headers?.message || '连接错误' })
      },
      onWebSocketClose: () => handleStatus('offline'),
      onDisconnect: () => handleStatus('offline')
    })
    client.activate()
  }

  const disconnect = () => {
    disposed = true
    try {
      client?.publish({ destination: `/app/excel/leave/${documentId}`, body: '{}' })
    } catch {
      // ignore
    }
    try {
      client?.deactivate()
    } catch {
      // ignore
    }
    client = null
    handleStatus('offline')
  }

  const send = (message) => {
    if (!client || !client.connected) return false
    const payload = {
      ...message,
      documentId: message.documentId || documentId,
      timestamp: Date.now()
    }
    try {
      client.publish({
        destination: '/app/excel/edit',
        body: JSON.stringify(payload)
      })
      return true
    } catch (error) {
      console.warn('协同消息发送失败:', error)
      return false
    }
  }

  return {
    connect,
    disconnect,
    send,
    dispose: disconnect
  }
}
