#!/usr/bin/env python3
"""Rebuild the pinned Lucide vector subset. No package dependency at runtime."""
import concurrent.futures, pathlib, urllib.request, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
base='https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/'
groups={
'常用':'code:代码,play:运行,star:星标,heart:爱心,bookmark:书签,check:完成,plus:添加,search:搜索,settings:设置,house:首页,bell:提醒,zap:闪电',
'生活':'sun:晴天,moon:夜晚,cloud:多云,cloud-rain:下雨,umbrella:雨伞,coffee:咖啡,cup-soda:饮料,utensils:餐食,droplet:喝水,bed:睡眠,alarm-clock:闹钟,calendar:日历,clock:时间,gift:礼物,shopping-bag:购物,shopping-cart:购物车,shirt:衣服,flower:花朵',
'工具':'notebook-pen:笔记,pencil:编辑,type:文字,languages:翻译,calculator:计算器,qr-code:二维码,scan-line:扫描,arrow-left-right:转换,repeat:重复,scissors:剪切,clipboard:剪贴板,list-checks:待办,timer:计时,hash:数字,percent:百分比,braces:对象,terminal:终端,bug:调试,wrench:扳手,wand-sparkles:魔法',
'文件':'folder:文件夹,file:文件,file-text:文档,file-code:代码文件,image:图片,music:音乐,video:视频,camera:相机,mic:录音,archive:归档,download:下载,upload:上传,save:保存,trash-2:删除,copy:复制,hard-drive:磁盘',
'网络':'globe:网络,link:链接,wifi:无线网络,send:发送,mail:邮件,message-circle:消息,rss:订阅,cloud-download:云下载,cloud-upload:云上传,server:服务器,database:数据库,radio:广播',
'设备':'smartphone:手机,watch:手表,laptop:电脑,monitor:显示器,bluetooth:蓝牙,battery:电池,volume-2:音量,headphones:耳机,keyboard:键盘,mouse:鼠标,printer:打印机,lightbulb:灯光,power:电源,lock:锁定,key-round:钥匙,shield:保护,map-pin:位置,navigation:导航,compass:指南针,car:汽车,train-front:火车,plane:飞机,rocket:火箭,gamepad-2:游戏'
}
groups.update({
'应用':'app-window:应用窗口,layout-grid:应用列表,contact:联系人,phone:电话,phone-call:通话,messages-square:聊天,message-square-text:短信,video:视频,circle-play:播放器,tv:电视,newspaper:新闻,book-open:阅读,library:书库,podcast:播客,clapperboard:电影,film:胶片,cassette-tape:磁带,disc-3:唱片,audio-lines:音频波形,radio-tower:电台',
'出行':'bus:公交车,tram-front:有轨电车,bike:自行车,footprints:步行,ship:轮船,sailboat:帆船,truck:货车,ambulance:救护车,route:路线,map:地图,signpost:路标,tickets:车票,luggage:行李,backpack:背包,fuel:加油,circle-parking:停车,traffic-cone:交通锥,mountain:山峰,tent:露营',
'健康':'activity:活动,heart-pulse:心率,stethoscope:听诊器,pill:药片,syringe:注射,thermometer:体温,dumbbell:健身,weight:体重,apple:苹果,carrot:胡萝卜,salad:沙拉,brain:大脑,eye:眼睛,ear:耳朵,hand:手掌,accessibility:无障碍,smile:笑脸,person-standing:站立',
'办公':'briefcase-business:工作,building-2:办公楼,graduation-cap:学习,school:学校,presentation:演示,chart-column:柱状图,chart-line:折线图,chart-pie:饼图,table:表格,sheet:工作表,clipboard-check:检查清单,calendar-check:日程完成,calendar-days:日期,notebook:笔记本,sticky-note:便签,stamp:印章,paperclip:附件,folder-open:打开文件夹',
'财务':'wallet:钱包,credit-card:银行卡,banknote:现金,coins:硬币,receipt:收据,piggy-bank:存钱罐,landmark:银行,hand-coins:付款,badge-dollar-sign:金额,trending-up:上涨,trending-down:下跌',
'智能家居':'fan:风扇,air-vent:空调,heater:取暖器,refrigerator:冰箱,washing-machine:洗衣机,door-open:开门,door-closed:关门,lamp:台灯,sofa:沙发,bath:浴缸,shower-head:淋浴,plug:插头,plug-zap:充电,cctv:监控,router:路由器,usb:USB,nfc:NFC,fingerprint:指纹',
})
# Keep existing identifiers and categories stable when expanding the catalog.
seen=set()
entries=[]
for group,items in groups.items():
    for item in items.split(','):
        name,label=item.split(':')
        if name not in seen:
            entries.append((name,label,group))
            seen.add(name)
def get(path):
    with urllib.request.urlopen(base+path,timeout=25) as response:return response.read()
def number(e,k,default='0'):return float(e.get(k,default))
def convert(entry):
    name,label,group=entry
    if (root/"app/src/main/res/drawable"/("ic_"+name.replace("-","_")+".xml")).exists(): return
    try: svg=ET.fromstring(get('icons/'+name+'.svg'))
    except Exception as e: raise RuntimeError(name) from e
    paths=[]
    for e in svg:
        tag=e.tag.split('}')[-1];d=None
        if tag=='path':d=e.get('d')
        elif tag=='line':d=f'M {e.get("x1")} {e.get("y1")} L {e.get("x2")} {e.get("y2")}'
        elif tag in ('polyline','polygon'):d='M '+e.get('points')+(' Z' if tag=='polygon' else '')
        elif tag in ('circle','ellipse'):
            x=number(e,'cx');y=number(e,'cy');rx=number(e,'r') if tag=='circle' else number(e,'rx');ry=number(e,'r') if tag=='circle' else number(e,'ry')
            d=f'M {x-rx} {y} a {rx} {ry} 0 1 0 {2*rx} 0 a {rx} {ry} 0 1 0 {-2*rx} 0'
        elif tag=='rect':
            x=number(e,'x');y=number(e,'y');w=number(e,'width');h=number(e,'height');r=number(e,'rx')
            d=f'M {x+r} {y} H {x+w-r} Q {x+w} {y} {x+w} {y+r} V {y+h-r} Q {x+w} {y+h} {x+w-r} {y+h} H {x+r} Q {x} {y+h} {x} {y+h-r} V {y+r} Q {x} {y} {x+r} {y} Z'
        else:raise ValueError((name,tag))
        paths.append(f'    <path android:pathData="{d}" android:fillColor="@android:color/transparent" android:strokeColor="#FF000000" android:strokeWidth="1.7" android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    target=root/'app/src/main/res/drawable'/('ic_'+name.replace('-','_')+'.xml')
    target.write_text('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'+'\n'.join(paths)+'\n</vector>\n')
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(convert,entries))
license_dir=root/'docs/third-party';license_dir.mkdir(parents=True,exist_ok=True)
(license_dir/'LUCIDE-LICENSE.txt').write_bytes(get('LICENSE'))
with urllib.request.urlopen('https://raw.githubusercontent.com/feathericons/feather/v4.29.2/LICENSE',timeout=25) as response:
    (license_dir/'FEATHER-LICENSE.txt').write_bytes(response.read())
(license_dir/'LUCIDE.md').write_text('# Lucide\n\nSource: https://github.com/lucide-icons/lucide/tree/0.468.0\n\nSelected icons converted to Android VectorDrawable with a 1.7 viewport-unit stroke. ISC license, with Feather portions under MIT; original notices retained in LUCIDE-LICENSE.txt.\n')
lines=['package io.todu.andjs.ui','import io.todu.andjs.R','data class AppIcon(val id:String,val label:String,val category:String,val resource:Int)','object AppIcons {','    val all=listOf(']
lines += [f'        AppIcon("{n}","{l}","{g}",R.drawable.ic_{n.replace("-","_")}),' for n,l,g in entries]
lines+=['    )','    private val aliases=mapOf("note" to "notebook-pen","text" to "type","swap" to "arrow-left-right","global" to "globe","bolt" to "zap")','    fun resource(id:String):Int?=all.find {it.id==(aliases[id] ?: id)}?.resource','}']
(root/'app/src/main/java/io/todu/andjs/ui/AppIcons.kt').write_text('\n'.join(lines)+'\n')
print('Imported',len(entries),'Lucide icons')
