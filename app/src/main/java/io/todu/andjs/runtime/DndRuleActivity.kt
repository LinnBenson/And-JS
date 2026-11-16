package io.todu.andjs.runtime

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Configuration page required by app-managed automatic DND rules. */
class DndRuleActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32)}
        layout.addView(TextView(this).apply {text="And JS 临时勿扰";textSize=20f})
        layout.addView(TextView(this).apply {text="由前台脚本开启，脚本结束时撤销。";setPadding(0,24,0,24)})
        layout.addView(Button(this).apply {text="关闭此规则";setOnClickListener {
            val manager=getSystemService(NotificationManager::class.java)
            val id=intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
            if(id!=null) runCatching {
                val rule=manager.getAutomaticZenRule(id)
                if(rule?.conditionId?.authority==packageName && rule.conditionId?.path?.startsWith("/script-dnd/")==true) manager.removeAutomaticZenRule(id)
            }
            finish()
        }})
        setContentView(layout)
    }
}
