package lk.lalana.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.json.JSONObject
import java.util.Locale

/** Native speech recognition and text-to-speech (WebView has neither). */
class Speech(private val act: MainActivity) : TextToSpeech.OnInitListener {
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        act.runOnUiThread { tts = TextToSpeech(act, this) }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale.UK
        val v = pickVoice(t)
        if (v != null) t.voice = v
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                done(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                done(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                done(utteranceId)
            }
        })
        ttsReady = true
    }

    /** Prefer an offline British male voice (Google's gbd / rjs / gbb). */
    private fun pickVoice(t: TextToSpeech): Voice? {
        val all: List<Voice> = try {
            t.voices?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val gb = all.filter { it.locale.language == "en" && it.locale.country == "GB" }
            .sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
        val male = listOf("gbd", "rjs", "gbb", "male")
        val female = listOf("gba", "gbc", "gbg", "female")
        fun n(v: Voice) = v.name.lowercase(Locale.ROOT)
        return gb.firstOrNull { v -> male.any { n(v).contains(it) } && female.none { n(v).contains(it) } }
            ?: gb.firstOrNull { v -> female.none { n(v).contains(it) } }
            ?: gb.firstOrNull()
    }

    private fun done(id: String?) {
        if (id != null) act.js("window.jarvisNative&&window.jarvisNative.onSpeakDone(${JSONObject.quote(id)})")
    }

    fun speak(text: String, rate: Double, pitch: Double, id: String) {
        act.runOnUiThread {
            val t = tts
            if (t == null || !ttsReady) {
                done(id)
            } else {
                t.setSpeechRate(rate.toFloat())
                t.setPitch(pitch.toFloat())
                t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            }
        }
    }

    fun stopSpeak() {
        act.runOnUiThread { tts?.stop() }
    }

    fun listen(lang: String) {
        act.runOnUiThread { startListening(lang) }
    }

    private fun startListening(lang: String) {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MainActivity.REQ_MIC)
            emit("error", "mic-permission")
            emit("end", "")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            emit("error", "unavailable")
            emit("end", "")
            return
        }
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            var ended = false
            fun end() {
                if (!ended) {
                    ended = true
                    emit("end", "")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                emit("error", error.toString())
                end()
            }

            override fun onResults(results: Bundle?) {
                emit("final", first(results))
                end()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val p = first(partialResults)
                if (p.isNotBlank()) emit("partial", p)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (lang.isNotBlank()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        r.startListening(i)
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    fun stopListen() {
        act.runOnUiThread { rec?.stopListening() }
    }

    private fun emit(type: String, text: String) {
        act.js("window.jarvisNative&&window.jarvisNative.onSpeech(${JSONObject.quote(type)}, ${JSONObject.quote(text)})")
    }

    fun shutdown() {
        act.runOnUiThread {
            rec?.destroy()
            tts?.shutdown()
        }
    }
}
