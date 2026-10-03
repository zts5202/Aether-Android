package com.zhousl.aether.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One recognized text run from the Agent Mode display, positioned in the pixel space of the exact
 * bitmap that was analyzed. Callers pass that bitmap's width/height when converting to normalized
 * 0..1000 coordinates, so element boxes always line up with the screenshot handed back to the model.
 */
internal data class AgentModeTextElement(
    val text: String,
    val boundingBox: Rect,
)

/**
 * Offline text recognition for Agent Mode element lookup.
 *
 * Backed by the bundled ML Kit Chinese recognizer, so the model ships inside the APK and nothing is
 * downloaded or sent anywhere at runtime.
 */
internal object AgentModeTextRecognizer {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognizes every text line in [bitmap].
     *
     * Lines are used rather than ML Kit's word-level elements because a UI label ("立即购买",
     * "登录") is normally a single line, which makes both query matching and the resulting tap
     * target more reliable than assembling individual words.
     */
    suspend fun recognize(bitmap: Bitmap): List<AgentModeTextElement> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val text = line.text.trim()
                val box = line.boundingBox
                if (text.isEmpty() || box == null) null else AgentModeTextElement(text, box)
            }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
