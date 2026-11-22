import http from './http'
import type { ApiResponse } from './http'

/**
 * 模型设置（BYOK，配置方式参考 Smart Draw 的"连接"分区并服务端化）：
 * 用户自带 API 地址/密钥/模型 ID，服务端加密落库；聊天时优先生效，
 * 否则回落平台默认（ADMIN 维护）→ .env 全局配置。
 * 密钥永不回传明文——GET 只给掩码，PUT apiKey 留空 = 沿用已存密钥。
 */

export interface ModelConfigView {
  baseUrl: string
  modelId: string
  enabled: boolean
  /** 掩码（如 sk-abcd…wxyz）；null = 未配置 */
  maskedKey: string | null
  updatedAt: string | null
}

export interface SaveModelConfigRequest {
  baseUrl: string
  /** 留空 = 沿用已存密钥 */
  apiKey?: string
  modelId: string
  enabled: boolean
}

export interface TestConnectionResult {
  ok: boolean
  message: string
  reply: string | null
  latencyMs: number | null
}

function unwrap<T>(payload: ApiResponse<T>, fallback: string): T {
  if (!payload.success) {
    throw new Error(payload.message ?? fallback)
  }
  return payload.data
}

export async function getModelConfig(): Promise<ModelConfigView> {
  const { data } = await http.get<ApiResponse<ModelConfigView>>('/settings/model')
  return unwrap(data, '加载模型配置失败')
}

export async function saveModelConfig(payload: SaveModelConfigRequest): Promise<void> {
  const { data } = await http.put<ApiResponse<null>>('/settings/model', payload)
  unwrap(data, '保存模型配置失败')
}

export async function clearModelConfig(): Promise<void> {
  const { data } = await http.delete<ApiResponse<null>>('/settings/model')
  unwrap(data, '清除模型配置失败')
}

export async function testModelConnection(payload: SaveModelConfigRequest): Promise<TestConnectionResult> {
  const { data } = await http.post<ApiResponse<TestConnectionResult>>('/settings/model/test', payload)
  return unwrap(data, '测试请求失败')
}
