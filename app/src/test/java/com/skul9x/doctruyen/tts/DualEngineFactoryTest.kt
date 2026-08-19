package com.skul9x.doctruyen.tts

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class DualEngineFactoryTest {

    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setup() {
        preferencesMap.clear()

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "apply", "commit" -> null
                    else -> null
                }
            }
        ) as SharedPreferences.Editor

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getString" -> {
                        val key = args[0] as String
                        val def = args[1] as? String
                        preferencesMap[key] as? String ?: def
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as SharedPreferences

        UserConfig.customPrefsProvider = { prefsProxy }
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = null
    }

    private fun createMockContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context {
                return this
            }
        }
    }

    @Test
    fun testFactoryReturnsVieNeuTTSManagerWhenConfigIsVieNeu() {
        val mockContext = createMockContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.VIENEU)

        val manager = TTSManagerFactory.create(
            context = mockContext,
            onStateChange = {},
            onProgressChange = {}
        )

        assertNotNull(manager)
        assertEquals(ITTSManager.EngineType.VIENEU, manager.engineType)
        assertTrue(manager is VieNeuTTSManager)
    }

    @Test
    fun testFactoryReturnsGoogleTTSManagerWhenConfigIsGoogleTts() {
        val mockContext = createMockContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.GOOGLE_TTS)

        val manager = TTSManagerFactory.create(
            context = mockContext,
            onStateChange = {},
            onProgressChange = {}
        )

        assertNotNull(manager)
        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, manager.engineType)
        assertTrue(manager is GoogleTTSManager)
    }

    @Test
    fun testFactoryForceEngineType() {
        val mockContext = createMockContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.VIENEU)

        val manager = TTSManagerFactory.create(
            context = mockContext,
            onStateChange = {},
            onProgressChange = {},
            forceEngineType = ITTSManager.EngineType.GOOGLE_TTS
        )

        assertNotNull(manager)
        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, manager.engineType)
        assertTrue(manager is GoogleTTSManager)
    }
}
