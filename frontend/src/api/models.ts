import http from './http'

export interface ModelInfo {
  id: string
  name: string
  description: string
  category: string
  isDefault: boolean
  enabled: boolean
}

interface ModelOptionPayload {
  id: string
  displayName: string
  description: string
  category: string
  isDefault: boolean
}

export async function fetchModels(): Promise<ModelInfo[]> {
  try {
    const res = await http.get('/models')
    const payload = (res.data?.data ?? []) as ModelOptionPayload[]
    return payload.map((model) => ({
      id: model.id,
      name: model.displayName,
      description: model.description,
      category: model.category,
      isDefault: model.isDefault,
      enabled: true,
    }))
  } catch {
    return []
  }
}
