package dev.deeplinks.core

import android.app.Activity
import android.view.WindowManager
import dev.deeplinks.BuildConfig

/**
 * 敏感窗口统一禁止截图、录屏和最近任务预览。
 *
 * 为什么抽成 helper：主宿主、设置页、扫码页是三个独立窗口，历史上只在
 * `MainActivity` 设置过 `FLAG_SECURE`，另外两个窗口漏掉了；新增敏感 Activity 时
 * 只要调用这一个函数，就不会再各自复制一遍 flag 设置。
 *
 * 注意：这是「窗口级」防护，只对有敏感内容的 Activity 生效；不要全局加到
 * 非敏感页面上，否则用户失去正常截图能力。
 *
 * debug 变体（`dev.deeplinks.debug`）不设这个 flag：真机测试需要 adb screencap
 * 取画面来核对 UI，被 FLAG_SECURE 挡住只能截到黑屏。release 不受影响。
 */
fun Activity.applyDshSecureWindow() {
    if (BuildConfig.DEBUG) return
    window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE,
    )
}
