package com.labteto.dshmobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.labteto.dshmobile.core.wire.WireJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_mobile")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(policy: com.labteto.dshmobile.local.security.LocalManagedExecutionPolicy): OkHttpClient = OkHttpClient.Builder()
        // Application checks also cover cache hits and requests before any connection is opened.
        .addInterceptor { chain ->
            val target = chain.request().url
            policy.current().requireNetworkDestination(target.scheme, target.host)
            chain.proceed(chain.request())
        }
        // OkHttp follows redirects and performs route retries inside the application interceptor.
        // The network interceptor rechecks every *actual destination* before sending credentials.
        .addNetworkInterceptor { chain ->
            val target = chain.request().url
            policy.current().requireNetworkDestination(target.scheme, target.host)
            chain.proceed(chain.request())
        }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWireJson(): Json = WireJson

    @Provides
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}
