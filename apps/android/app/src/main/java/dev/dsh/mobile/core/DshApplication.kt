package dev.dsh.mobile.core

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.Coil
import okhttp3.OkHttpClient

/** 应用入口：深色模式跟随系统；界面语言从本地缓存初始化。 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        LocaleManager.init(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .addNetworkInterceptor { chain ->
                            MarkdownMedia.assertPublicHttps(chain.request().url.toString())
                            val resp = chain.proceed(chain.request())
                            MarkdownMedia.assertPublicHttps(resp.request.url.toString())
                            resp
                        }
                        .build()
                }
                .build(),
        )
    }
}
