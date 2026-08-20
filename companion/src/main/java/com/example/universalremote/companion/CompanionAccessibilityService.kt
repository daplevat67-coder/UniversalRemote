package com.example.universalremote.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CompanionAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { active = this }
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: CompanionAccessibilityService? = null
        fun isEnabled(): Boolean = active != null
        fun home(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_HOME) == true
        fun back(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        fun recents(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true
    }
}
