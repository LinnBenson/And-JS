package io.todu.andjs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun PermissionCard(
    title:String, description:String, icon:String, granted:Boolean,
    compact:Boolean=false, status:String=if(granted) "已授权" else "去授权", onClick:(()->Unit)?=null
) {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(if(compact) 20.dp else 22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow)
            .then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)
            .padding(horizontal=if(compact) 12.dp else 16.dp,vertical=if(compact) 12.dp else 17.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(compact) 10.dp else 14.dp)
    ) {
        Box(Modifier.size(if(compact) 30.dp else 42.dp).background(colors.primary.copy(alpha=.09f),RoundedCornerShape(if(compact) 10.dp else 14.dp)),contentAlignment=Alignment.Center) {
            Glyph(icon,colors.primary,Modifier.size(if(compact) 18.dp else 22.dp))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize=if(compact) 13.sp else 16.sp,fontWeight=FontWeight.Medium,color=colors.onSurface)
            Text(if(compact) status else description,fontSize=if(compact) 11.sp else 12.sp,lineHeight=if(compact) 15.sp else 18.sp,color=colors.onSurfaceVariant)
        }
        if(compact) {
            if(granted || onClick!=null) Glyph(if(granted) "check" else "forward",if(granted) colors.primary else colors.onSurfaceVariant,Modifier.size(15.dp))
        } else {
            Row(Modifier.background(if(granted) colors.primary.copy(alpha=.08f) else colors.background,RoundedCornerShape(50)).padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                if(granted) Glyph("check",colors.primary,Modifier.size(12.dp))
                Text(status,fontSize=11.sp,fontWeight=FontWeight.Medium,color=colors.primary)
            }
        }
    }
}

@Composable internal fun PermissionSection(title:String) {
    Text(title,modifier=Modifier.padding(start=6.dp,top=12.dp,bottom=2.dp),fontSize=12.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
