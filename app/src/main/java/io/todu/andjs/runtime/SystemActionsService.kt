package io.todu.andjs.runtime

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** No event subscription or screen content access; only explicit global actions. */
class SystemActionsService:AccessibilityService() {
    companion object {
        @Volatile private var connected:SystemActionsService?=null
        fun available()=connected!=null
        fun perform(action:Int):Boolean {
            val service=connected ?: throw IllegalStateException("请先在权限授权中开启系统操作无障碍服务")
            return service.performGlobalAction(action)
        }
    }
    override fun onServiceConnected() {connected=this}
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent:android.content.Intent?):Boolean {if(connected===this) connected=null;return super.onUnbind(intent)}
    override fun onDestroy() {if(connected===this) connected=null;super.onDestroy()}
}
