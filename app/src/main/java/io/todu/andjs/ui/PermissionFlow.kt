package io.todu.andjs.ui
internal inline fun permissionFlow(context:android.content.Context,launch:()->Unit) {
    val app=context.applicationContext as io.todu.andjs.AndJsApplication
    app.externalFlow=true
    try {launch()} catch(e:Exception) {app.externalFlow=false;throw e}
}
