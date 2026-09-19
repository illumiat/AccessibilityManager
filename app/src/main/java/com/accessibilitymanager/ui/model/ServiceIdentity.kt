package com.accessibilitymanager.ui.model

import com.accessibilitymanager.IconCache
import java.util.Locale

/**
 * 服务身份的**展示规则**——全应用唯一实现。
 *
 * ## 为什么必须收口在这里
 *
 * 同一个服务会在**两个地方**露出名字：主页列表卡片、详情弹卡头部。
 * 早期版本两处各写一套规则，结果**同一个服务在两页显示不同的名字**：
 *
 * | 位置 | 旧规则 | 效果 |
 * |---|---|---|
 * | 列表卡片 | `app != svc` 时 `"app/svc"` | 「Scene/SCENE-辅助服务」 |
 * | 详情头部 | `svc ?: app` | 「SCENE-辅助服务」（丢掉应用名） |
 *
 * 用户看到的是「点进去名字变了」。这不是迁移引入的（旧 Java 实现同样如此），
 * 但**UI 呈现没有"必须与旧版逐字一致"的约束** —— 保真原则保的是业务逻辑
 * （写入路径、镜像纪律、锁身份、线程模型），不是界面文案。故此处统一为列表规则。
 *
 * @param app 应用名（`IconCache.Entry.appLabel`），未加载时为 `null`
 * @param svc 服务名（`IconCache.Entry.serviceLabel`），未加载时为 `null`
 * @param serviceId 服务 id 原文（`包名/类名`），作为最终兜底
 */
fun serviceTitle(app: String?, svc: String?, serviceId: String): String {
    val resolvedApp = app
    val resolvedSvc = svc ?: resolvedApp ?: IconCache.shortClassName(serviceId)
    return if (resolvedApp != null && resolvedApp != resolvedSvc) {
        "$resolvedApp/$resolvedSvc"
    } else {
        resolvedSvc.orEmpty()
    }
}

/**
 * 占位首字：服务类短名的首字符（大写）。
 *
 * 与 [serviceTitle] 同处一个文件：两者都是「服务身份如何呈现」的规则，
 * 且都被列表与详情两个消费方共用。
 */
fun placeholderInitial(serviceId: String): String {
    val short = IconCache.shortClassName(serviceId)
    return if (short.isNullOrEmpty()) "?" else short.substring(0, 1).uppercase(Locale.ROOT)
}
