package dev.deeplinks.native

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KaTeX 渲染链路验证（共享离屏 WebView → 位图）：
 * 合法公式产出非空且含不透明像素的位图；非法 LaTeX 返回 null（回退路径）。
 * 需要设备/模拟器（WebView 不可在 JVM 单测中运行）。
 */
@RunWith(AndroidJUnit4::class)
class MathRendererInstrumentedTest {
    private val colorArgb = 0xFF202020.toInt()

    /**
     * 共享离屏 WebView 常驻进程生命周期，App 内无需释放；但同一 Instrumentation
     * 进程内后续测试类会复用这个进程，残留的 WebView/Chromium 沙箱进程会卡死
     * 下一个 Compose UI 测试的主线程空闲检测（真机表现为白屏无响应）。
     */
    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        MathRenderer.resetForTests()
    }

    @Test
    fun rendersFormulaToBitmapWithVisiblePixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MathRenderer.attach(context.applicationContext)
        val rendered = withTimeout(30_000) {
            MathRenderer.render("c = \\pm\\sqrt{a^2 + b^2}", MathRenderer.DISPLAY_FONT_CSS_PX, colorArgb, displayMode = true)
        }
        assertNotNull("合法公式应渲染成功", rendered)
        rendered!!
        assertTrue("位图应有宽度", rendered.bitmap.width > 0)
        assertTrue("位图应有高度", rendered.bitmap.height > 0)
        assertTrue("位图应含不透明像素", hasOpaquePixels(rendered))
    }

    @Test
    fun invalidLatexFailsWithoutRendering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MathRenderer.attach(context.applicationContext)
        val rendered = withTimeout(30_000) {
            MathRenderer.render("\\notARealCommand{x}", MathRenderer.INLINE_FONT_CSS_PX, colorArgb, displayMode = false)
        }
        assertNull("非法 LaTeX 应回退 null", rendered)
    }

    private fun hasOpaquePixels(rendered: MathRenderer.Rendered): Boolean {
        val bitmap = rendered.bitmap.asAndroidBitmap()
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 32)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) and 0xFF000000.toInt() != 0) return true
                x += step
            }
            y += step
        }
        return false
    }
}
