package dev.deeplinks.core

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/** 应用入口：深色模式跟随系统；界面语言从本地缓存初始化。 */
class DshApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        LocaleManager.init(this)
        ThemeManager.init(this)
        FontScaleManager.init(this)
    }

    /** Coil3 全局 ImageLoader：Markdown 图片只允许 https 公网（DNS 层 + 拦截器双保险）。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = OkHttpClient.Builder()
                            .dns(MarkdownMedia.publicOnlyDns)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .addNetworkInterceptor { chain ->
                                MarkdownMedia.assertPublicHttps(chain.request().url.toString())
                                val resp = chain.proceed(chain.request())
                                MarkdownMedia.assertPublicHttps(resp.request.url.toString())
                                resp
                            }
                            .build(),
                    ),
                )
            }
            .build()
}
