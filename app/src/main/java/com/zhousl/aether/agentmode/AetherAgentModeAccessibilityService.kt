package com.zhousl.aether.agentmode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.Keep
import com.zhousl.aether.data.AgentModeSourceAccessibility

/**
 * Fallback window reader for the Agent Mode virtual display.
 * Used only when the privileged UiAutomation connection cannot see that display.
 * The user has to enable this service once in system accessibility settings.
 */
@Keep
class AetherAgentModeAccessibilityService : AccessibilityService() {
    private val session = AgentModeUiTreeSession(
        source = AgentModeSourceAccessibility,
        windowsOnDisplay = ::windowsForDisplay,
        injector = null,
    )

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        session.close()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun interact(displayId: Int, requestJson: String): String = session.handle(displayId, requestJson)

    private fun windowsForDisplay(displayId: Int): List<AccessibilityWindowInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return windowsOnAllDisplays.get(displayId)?.toList().orEmpty()
    }

    companion object {
        @Volatile
        private var instance: AetherAgentModeAccessibilityService? = null

        fun interact(displayId: Int, requestJson: String): String? =
            instance?.interact(displayId, requestJson)
    }
}
