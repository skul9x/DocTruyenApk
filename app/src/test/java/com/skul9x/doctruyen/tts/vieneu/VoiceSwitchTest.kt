package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class VoiceSwitchTest {

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

    @Test
    fun testVoiceSwitchingInManagerAndUserConfig() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context {
                return this
            }
        }

        UserConfig.setTtsVoice(mockContext, "Trúc Ly")
        assertEquals("Trúc Ly", UserConfig.getTtsVoice(mockContext))

        var stateChanges = mutableListOf<com.skul9x.doctruyen.tts.ITTSManager.TTSState>()
        val manager = VieNeuTTSManager(
            context = mockContext,
            onStateChange = { stateChanges.add(it) }
        )

        assertEquals("Trúc Ly", manager.voiceName)

        // Change voice to Minh Đức
        manager.setVoice("Minh Đức")
        assertEquals("Minh Đức", manager.voiceName)
        assertEquals("Minh Đức", UserConfig.getTtsVoice(mockContext))

        // Change voice to Phạm Tuyên
        manager.setVoice("Phạm Tuyên")
        assertEquals("Phạm Tuyên", manager.voiceName)
        assertEquals("Phạm Tuyên", UserConfig.getTtsVoice(mockContext))
    }
}
