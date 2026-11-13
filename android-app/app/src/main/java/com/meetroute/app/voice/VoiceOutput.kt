package com.meetroute.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

interface VoiceOutput {
    val isAvailable: Boolean
    fun speak(message: String)
    fun release()
}

/**
 * 플랫폼 TTS 구현. Pleos TextToSpeech SDK는 이 인터페이스의 새 구현으로 붙인다.
 */
class AndroidVoiceOutput(context: Context) : VoiceOutput {

    @Volatile
    private var ready = false

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    override val isAvailable: Boolean
        get() = ready && engine.isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE

    override fun speak(message: String) {
        if (!isAvailable) return
        engine.language = Locale.KOREAN
        engine.speak(message, TextToSpeech.QUEUE_ADD, null, message)
    }

    override fun release() {
        engine.shutdown()
    }
}
