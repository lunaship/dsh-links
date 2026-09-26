package dev.deeplinks.devices

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import dev.deeplinks.native.MainActivity

/**
 * 启动入口：只做路由，不再自绘启动海报。
 *
 * 品牌露出由 API 31+ 的系统 SplashScreen 承担（背景与图标见 themes 的
 * `windowSplashScreen*`）；API 26–30 由 `@drawable/splash_brand` 承接。
 * 本 Activity 只挂退出动画：图标放大淡出，与首屏的淡入上移接力。
 *
 * 自绘海报会在系统启动页之后再出现第二张启动画面，造成双启动页与停顿，
 * 因此这里不做 Compose 启动页。
 *
 * reduce-motion：系统「移除动画」开启时 `animator_duration_scale = 0`，
 * ObjectAnimator 会瞬时结束并立即回调 `SplashScreenViewProvider.remove()`，
 * 无需在此额外判断。
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView ->
                // SDK 37：回调参数即 SplashScreenView（FrameLayout），图标在 iconView。
                val icon = splashView.iconView
                if (icon == null) {
                    splashView.remove()
                    return@setOnExitAnimationListener
                }
                ObjectAnimator.ofPropertyValuesHolder(
                    icon,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, EXIT_SCALE),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, EXIT_SCALE),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                ).apply {
                    duration = EXIT_DURATION_MS
                    // 与 Compose 侧 DshEasing.out 同族的 FastOutSlowIn
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            splashView.remove()
                        }
                    })
                }.start()
            }
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        /** 与首屏入场时长对齐，形成一次连续交接而非两段动画。 */
        const val EXIT_DURATION_MS = 220L

        /** 放大而非缩小：图标「让位」给内容，视觉上是推走而不是缩没。 */
        const val EXIT_SCALE = 1.12f
    }
}
