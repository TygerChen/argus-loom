package com.argus.rag.assistant.memory.longterm;

/**
 * 记忆生命周期状态枚举。
 */
public enum MemoryStatus {
    CANDIDATE,
    PENDING_CONFIRMATION,
    ACTIVE,
    SUPERSEDED,
    EXPIRED,
    REVOKED,
    DELETED
}