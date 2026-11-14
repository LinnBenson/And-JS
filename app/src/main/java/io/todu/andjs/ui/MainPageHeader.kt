package io.todu.andjs.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.R

@Composable fun MainPageHeader(title:String,modifier:Modifier=Modifier,onBack:(()->Unit)?=null,actions:@Composable RowScope.()->Unit = {}) {
    Row(modifier.fillMaxWidth().height(68.dp),verticalAlignment=Alignment.CenterVertically) {
        if(onBack==null) {
            Icon(painterResource(R.drawable.app_symbol),null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        } else IconButton(onClick=onBack) {Glyph("back",MaterialTheme.colorScheme.onSurface,Modifier.size(22.dp),"返回")}
        Text(title,fontSize=if(onBack==null) 22.sp else 18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
        actions()
    }
}
