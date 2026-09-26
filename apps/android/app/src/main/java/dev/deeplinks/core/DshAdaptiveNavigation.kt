package dev.deeplinks.core

/**
 * 自适应导航动作（对照 t3code lib/adaptive-navigation.ts）：
 * - 宽屏常驻侧栏时原地切换会话（UpdateArgs / Replace），不堆返回栈；
 * - 窄屏 drill-in 用 Push，保留系统返回。
 *
 * 全部是纯函数、可表驱动单测；Navigation Compose 迁移到位后由 NavHost 消费。
 * 迁移尚未落地前，这些契约先锁住「宽屏/窄屏返回栈行为必须不同」这一设计意图。
 */
enum class DshNavAction { Push, Replace, UpdateArgs }

/**
 * 选中会话时的导航动作。
 * @param usesPersistentSidebar 当前外壳是否常驻侧栏（来自 [DshLayout.persistentSidebar]）
 * @param atHome 是否处于首页（首页之上永远需要可返回）
 */
fun resolveThreadSelectionAction(usesPersistentSidebar: Boolean, atHome: Boolean): DshNavAction =
    if (usesPersistentSidebar && !atHome) DshNavAction.UpdateArgs else DshNavAction.Push

/** 选中文件时的导航动作：常驻检查器时原地替换，否则压栈。 */
fun resolveFileSelectionAction(hasPersistentInspector: Boolean): DshNavAction =
    if (hasPersistentInspector) DshNavAction.Replace else DshNavAction.Push
