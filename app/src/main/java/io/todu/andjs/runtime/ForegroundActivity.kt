package io.todu.andjs.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

internal object ForegroundActivity:Application.ActivityLifecycleCallbacks {
    @Volatile private var current=WeakReference<Activity>(null)
    fun get():Activity?=current.get()?.takeUnless {it.isFinishing || it.isDestroyed}
    override fun onActivityResumed(activity:Activity) {current=WeakReference(activity)}
    override fun onActivityPaused(activity:Activity) {if(current.get()===activity) current.clear()}
    override fun onActivityCreated(activity:Activity,state:Bundle?) {}
    override fun onActivityStarted(activity:Activity) {}
    override fun onActivityStopped(activity:Activity) {}
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
    override fun onActivityDestroyed(activity:Activity) {if(current.get()===activity) current.clear()}
}
