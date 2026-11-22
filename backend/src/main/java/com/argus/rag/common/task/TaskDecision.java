package com.argus.rag.common.task;

/**
 * 入口裁决结果 —— 挂号台的"收下 / 拒收"两个动作。
 * <p>
 * 注意语义边界：ACCEPTED 表示<b>入口把请求收下了</b>（通过了认证、授权与参数校验），
 * 不代表后续业务一定成功（业务失败有自己的记录体系：qa_records、operation_log 等）。
 * 挂号台只对"能不能进门"负责，不对"病治没治好"负责——两层记录混在一起
 * 就再也分不清是门禁问题还是业务问题。
 * </p>
 */
public enum TaskDecision {

    /** 请求通过入口校验，已交给业务流程 */
    ACCEPTED,

    /** 请求被入口规则拒绝（认证/授权/参数/限流） */
    REJECTED
}
