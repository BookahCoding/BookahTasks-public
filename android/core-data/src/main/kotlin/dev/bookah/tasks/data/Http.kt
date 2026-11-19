package dev.bookah.tasks.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /** One shared client. OkHttp drops the Authorization header when a redirect goes to another host. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** The only thing the app says about itself. No names, no addresses. */
    const val USER_AGENT = "BookahTasks (Android)"
}
