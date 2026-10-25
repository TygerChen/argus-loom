/* eslint-disable @typescript-eslint/no-explicit-any -- declarations for legacy JS utility modules */

declare module '@/utils/export' {
  export function exportExcel(sheets: any[], fileName?: string): Promise<void>
}

declare module '@/utils/export.js' {
  export function exportExcel(sheets: any[], fileName?: string): Promise<void>
}

declare module '@/utils/collabClient' {
  export interface CollabClient {
    connect: () => void
    disconnect: () => void
    send: (message: Record<string, any>) => boolean
    dispose: () => void
  }
  export function createCollabClient(options: Record<string, any>): CollabClient
}

declare module '@/utils/collabClient.js' {
  export interface CollabClient {
    connect: () => void
    disconnect: () => void
    send: (message: Record<string, any>) => boolean
    dispose: () => void
  }
  export function createCollabClient(options: Record<string, any>): CollabClient
}

declare module '@/utils/collabHelpers' {
  export const READONLY_COMMAND_MARKERS: string[]
  export const COLLAB_COLORS: string[]
  export function isEditCommand(commandId?: string): boolean
  export function normalizeSelectionRange(range?: Record<string, any> | null): Record<string, number> | null
  export function avatarColor(user?: Record<string, any> | null): string
  export function remoteLuckyToUniver(value?: any): any
}

declare module '@/utils/collabHelpers.js' {
  export const READONLY_COMMAND_MARKERS: string[]
  export const COLLAB_COLORS: string[]
  export function isEditCommand(commandId?: string): boolean
  export function normalizeSelectionRange(range?: Record<string, any> | null): Record<string, number> | null
  export function avatarColor(user?: Record<string, any> | null): string
  export function remoteLuckyToUniver(value?: any): any
}

declare module '@/utils/univerAdapter' {
  export function createUniverWorkbookAdapter(deps: Record<string, any>): any
}

declare module '@/utils/univerAdapter.js' {
  export function createUniverWorkbookAdapter(deps: Record<string, any>): any
}

declare module '@/utils/auth' {
  export function getUserProfile(): Record<string, any> | null
  export function getAccessToken(): string | null
  export function isAdminUser(): boolean
  export function hasPermission(): boolean
}

declare module '@/views/excel/composables/useOpsTables' {
  export function useOpsTables(deps: Record<string, any>): Record<string, any>
}
