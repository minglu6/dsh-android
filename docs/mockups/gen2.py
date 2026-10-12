exec(open('gen_base.py').read())
CSS2='''
.ico{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round;flex:none}
.cbar{display:flex;align-items:center;gap:2px;margin-top:6px}
.cbar .ico{width:14px;height:14px}
.ctl{height:32px;border-radius:10px;padding:0 6px;white-space:nowrap;flex:none;display:flex;align-items:center;gap:5px;font-size:13px;color:#61666B}
.ctl .dim{color:#ADB2B8}
.plus{width:32px;height:32px;border-radius:50%;background:#F1F3F5;display:flex;align-items:center;justify-content:center;color:#0F1115}
.comp2{border:1px solid rgba(0,0,0,.10);border-radius:20px;padding:12px 10px 8px 14px;box-shadow:0 2px 12px rgba(0,0,0,.04);background:#fff}
.ph{color:#ADB2B8;font-size:15px;line-height:22px;min-height:22px}
.sendc{width:36px;height:36px;border-radius:50%;background:#0F1115;color:#fff;display:flex;align-items:center;justify-content:center;margin-left:auto}
.sendc.dis{background:#B4C4F4}
.row{display:flex;align-items:center;gap:12px;padding:12px 4px}
.row .t{font-size:15px}.row .d{font-size:12px;color:#81858C;line-height:18px}
.chk{color:#0F1115;margin-left:auto}
.sec{font-size:12px;color:#81858C;margin:14px 4px 4px}
.card{border:1px solid rgba(0,0,0,.08);border-radius:16px;padding:0 14px;margin:0 16px 12px}
.srow{display:flex;align-items:center;gap:12px;padding:13px 0;border-bottom:1px solid rgba(0,0,0,.06)}
.srow:last-child{border-bottom:0}
.srow .t{font-size:15px}.srow .d{font-size:12px;color:#81858C;line-height:17px;margin-top:1px}
.val{margin-left:auto;font-size:14px;color:#61666B;display:flex;align-items:center;gap:4px;white-space:nowrap}
.seg{display:flex;background:#F1F3F5;border-radius:10px;padding:3px;gap:2px}
.seg span{padding:4px 10px;border-radius:8px;font-size:13px;color:#61666B}.seg .on{background:#fff;color:#0F1115;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.sw{width:40px;height:24px;border-radius:12px;background:#D4D4D4;position:relative;flex:none;margin-left:auto}.sw:after{content:"";position:absolute;width:20px;height:20px;border-radius:50%;background:#fff;top:2px;left:2px}
.sw.on{background:#0F1115}.sw.on:after{left:18px}
.foot .ico{width:12px;height:12px}
.kbd{font-size:12px;color:#ADB2B8}
.stitle{font-size:17px;font-weight:500;margin:0 4px 6px;display:flex;align-items:center}
'''
CSS=CSS+CSS2
def I(name):
    P={'folder':'<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v1H7.5a2 2 0 0 0-1.9 1.4L3 19z"/><path d="M3 19l2.6-7.6A2 2 0 0 1 7.5 10H21l-2.7 7.6a2 2 0 0 1-1.9 1.4H3"/>',
     'mode':'<path d="M7 19H4.8a1.8 1.8 0 0 1-1.5-2.8l3-5"/><path d="M11 19h8.2a1.8 1.8 0 0 0 1.5-2.8l-1.4-2.4"/><path d="M14 16l-3 3 3 3"/><path d="M8.3 13.6 7.2 9.4 3 10.5"/><path d="M9.3 5.5a1.8 1.8 0 0 1 3.1 0l3.8 6.6"/><path d="M12.4 11.4l3.8.7-.7 3.8"/>',
     'shield':'<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/>',
     'shieldc':'<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
     'shielde':'<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M12 8v5"/><path d="M12 16h.01"/>',
     'data':'<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
     'down':'<path d="M6 9l6 6 6-6"/>','right':'<path d="M9 6l6 6-6 6"/>','check':'<path d="M5 12l5 5 9-10"/>',
     'plus':'<path d="M12 5v14M5 12h14"/>','up':'<path d="M12 19V5M6 11l6-6 6 6"/>','stop':'<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor"/>',
     'clip':'<path d="M21 11l-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 7"/>',
     'goal':'<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
     'plan':'<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h3"/>',
     'send':'<path d="M21 3L10 14"/><path d="M21 3l-7 18-4-7-7-4z"/>',
     'compact':'<path d="M21 12a9 9 0 1 1-9-9"/>','dl':'<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
     'menu':'<path d="M4 7h16M4 12h16M4 17h16"/>','more':'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
     'back':'<path d="M15 6l-6 6 6 6"/>','gear':'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
     'puzzle':'<path d="M10 3h4v3a2 2 0 1 0 4 0h0v4h-3a2 2 0 1 0 0 4h3v4h-4v-3a2 2 0 1 0-4 0v3H6v-4h3a2 2 0 1 0 0-4H6V6h4z"/>',
     'clock':'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>','search':'<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
     'sliders':'<path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/>',
     'newf':'<path d="M12 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7"/><path d="M16 3h5v5M21 3l-7 7"/>',
     'file':'<path d="M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8z"/><path d="M14 3v5h5"/>','link':'<path d="M7 17L17 7M8 7h9v9"/>',
     'preset':'<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8M8 13h5"/>','newc':'<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/>','team':'<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6 6 0 0 1 3.5 6"/>','ring':'<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 4.7 1.5" stroke-width="3"/>','bolt':'<path d="M13 3L5 14h6l-1 7 8-11h-6z"/>','term':'<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>','globe':'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>','app':'<rect x="4" y="4" width="16" height="16" rx="4"/><path d="M9 15l3-6 3 6"/>','sidebar':'<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M15 4v16"/>'}
    return f'<svg class="ico" viewBox="0 0 24 24">{P[name]}</svg>'
PERM={'read-only':('shield','仅可查看'),'workspace-write':('shieldc','工作区内修改'),'danger-full-access':('shielde','完全权限')}
def composer(busy=False,hero=False,perm='workspace-write',text=None):
    ph=text if text else ('描述你想要构建的内容, / 调用指令, @ 文件或对话' if hero else '发消息或创建任务, / 调用指令, @ 文件或对话')
    ic,lab=PERM[perm]
    btn=f'<div class="sendc" style="background:#0F1115">{I("stop")}</div>' if busy and not text else f'<div class="sendc{"" if text else " dis"}">{I("up")}</div>'
    return f'''<div class="comp2"><div class="ph" style="{'color:#0F1115' if text else ''}">{ph}</div>
<div class="cbar"><div class="plus">{I('plus')}</div><div class="ctl">{I(ic)}{lab}{I('down')}</div>
<div class="ctl" style="margin-left:auto;max-width:200px;flex:0 1 auto;min-width:0;font-size:12.5px;gap:3px"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">DeepSeek-V4.1-Flash</span><span class="dim">High</span>{I('down')}</div>{btn}</div></div>'''
FOOT=lambda: f'<div class="foot" style="display:flex;justify-content:center;gap:14px;font-size:11.5px;color:#81858C;margin-top:6px;align-items:center"><span style="display:flex;gap:4px;align-items:center">{I("bolt")}3 轮 11 步</span><span>缓存命中 96%</span><span style="display:flex;gap:4px;align-items:center">{I("ring")}14%</span></div>'
def bottom(busy=False,perm='workspace-write',text=None,extra=''):
    return f'<div class="bottom" style="padding-bottom:10px">{extra}{composer(busy,perm=perm,text=text)}{FOOT()}</div>'
def TOPF(tab='对话'):
    g='style="font-size:12.5px;color:#81858C;display:flex;align-items:center;gap:3px;white-space:nowrap"'
    return f'''<div class="top" style="height:48px;border:0;gap:8px;padding:0 6px"><div class="ib">{I('menu')}</div><div style="font-size:16px;font-weight:600;white-space:nowrap">问候</div><span {g}>{I('team')}智能体团队</span><span {g}>{I('mode')}标准模式</span><span style="flex:1"></span><span class="dot g"></span><div class="ib" style="width:34px">{I('more')}</div><div class="ib" style="width:34px">{I('sidebar')}</div></div>
<div style="display:flex;gap:24px;padding:0 16px;border-bottom:1px solid rgba(0,0,0,.06);font-size:14px">{''.join(f'<div style="padding:6px 0 8px;{"color:#3964FE;border-bottom:2px solid #3964FE;font-weight:500" if t==tab else "color:#61666B"}">{t}</div>' for t in ['对话','轨迹'])}</div>'''
TOP=TOPF()
MSGS='''<div class="msgs">
<div class="user">给 pi-rpc-bridge 加上 token 轮换，旧 token 宽限 5 分钟</div>
<div class="asst">
<div class="reason">已深度思考 8 秒 ›</div>
<div class="tool"><span class="ok">✓</span><b>read</b><span class="arg mono">bridge/pi-rpc-bridge.mjs</span><span>›</span></div>
<div class="tool"><span class="ok">✓</span><b>grep</b><span class="arg mono">"auth" · 6 处匹配</span><span>›</span></div>
<p>握手在 <code>handleAuth()</code> 里一次性比对。我会改成同时接受当前 token 和宽限期内的上一个：</p>
<pre>const valid = [current, previous]
  .filter(t =&gt; t &amp;&amp; t.expiresAt &gt; now)
  .some(t =&gt; safeEqual(t.value, got))</pre>
<div class="tool"><span class="run">◌</span><b>edit</b><span class="arg mono">bridge/pi-rpc-bridge.mjs</span><span>›</span></div>
<p style="color:#81858C;font-size:13px">深度求索中，用时 42s ···</p>
</div></div>'''
page('01-chat-streaming',TOP+MSGS+bottom(busy=True))

# hero / new chat
HERO=f'''<div class="top"><div class="ib">{I('menu')}</div><div class="title"></div><div class="ib">{I('more')}</div></div>
<div style="position:absolute;left:0;right:0;top:300px;text-align:center"><div style="display:inline-flex;align-items:center;gap:10px">{logo(34,'#0F1115')}<span style="font-size:24px">探索未至之境</span><span style="font-size:11px;background:#E4EDFD;color:#3964FE;border-radius:6px;padding:1px 6px">预览版</span></div></div>
<div class="bottom"><div class="cbar" style="margin:0 2px 6px"><div class="ctl" style="color:#0F1115">{I('folder')}pi-android{I('down')}</div><div class="ctl" style="color:#0F1115">{I('mode')}标准模式{I('down')}</div></div>{composer(hero=True)}</div>'''
page('06-new-chat',HERO)

def sheet(body): return f'<div class="scrim"></div><div class="sheet"><div class="grab"></div>{body}</div>'
BASE=TOP+MSGS+bottom()
# model picker
MODEL=sheet(f'''<div class="stitle">模型与推理等级</div>
<div style="height:40px;border-radius:12px;background:#F5F6F7;color:#ADB2B8;display:flex;align-items:center;gap:8px;padding:0 12px;font-size:14px;margin:6px 0">{I('search')}搜索模型…</div>
<div class="sec">模型</div>
<div class="sec" style="margin-top:4px">DeepSeek</div>
<div class="row">{I('data')}<div><div class="t">DeepSeek-V4.1-Flash</div></div><span class="chk">{I('check')}</span></div>
<div class="row">{I('data')}<div><div class="t">DeepSeek-V4-Pro</div><div class="d">Stronger agentic coding, knowledge, and difficult reasoning; suited to complex or quality-critical tasks at higher cost.</div></div></div>
<div class="sec">推理等级</div>
<div class="row"><div><div class="t">Off</div><div class="d">Use for simple tasks that do not need reasoning.</div></div></div>
<div class="row"><div><div class="t">Low</div><div class="d">Prefer for routine or latency-sensitive tasks.</div></div></div>
<div class="row" style="background:#F5F6F7;border-radius:12px;padding-left:10px;padding-right:10px"><div><div class="t">High</div><div class="d">The default balance for most tasks.</div></div><span class="chk">{I('check')}</span></div>
<div class="row"><div><div class="t">Max</div><div class="d">Reserve for the hardest quality-first tasks.</div></div></div>''')
page('07-model-sheet',BASE+MODEL)
# permission
def prow(k,on=False,desc=''):
    ic,l=PERM[k]; return f'<div class="row" style="{"background:#F5F6F7;border-radius:12px;padding:12px 10px" if on else ""}">{I(ic)}<div><div class="t">{l}</div>{f"<div class=d>{desc}</div>" if desc else ""}</div>{f"<span class=chk>{I(chr(99)+chr(104)+chr(101)+chr(99)+chr(107))}</span>" if on else ""}</div>'
PERMS=sheet(f'''<div class="stitle">访问模式</div>
{prow('read-only')}{prow('workspace-write',True)}{prow('danger-full-access')}
<div class="row" style="opacity:.55">{I('shield')}<div><div class="t">自动审查 <span style="font-size:10px;border:1px solid #ADB2B8;border-radius:4px;padding:0 4px;color:#81858C">EXP</span></div><div class="d">无沙箱运行；每次原生工具调用和 PTC 内层调用前由同一模型进行实验性审查。</div></div></div>
<div class="d" style="font-size:11px;color:#ADB2B8;margin:2px 4px">（自动审查仅在 host 目录提供 auto 预设时出现）</div>''')
page('08-permission-sheet',BASE+PERMS)
CONF=f'''<div class="scrim"></div><div style="position:absolute;left:24px;right:24px;top:280px;background:#fff;border-radius:24px;padding:22px 20px 16px">
<div style="font-size:18px;font-weight:500;margin-bottom:10px">确认启用完全权限？</div>
<div style="font-size:14px;line-height:22px;color:#61666B">启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。</div>
<div style="display:flex;gap:10px;align-items:center;margin:16px 0;font-size:14px"><span style="width:18px;height:18px;border:1.5px solid #0F1115;border-radius:4px;display:inline-block"></span>我已了解风险，并愿意继续</div>
<div style="display:flex;gap:8px"><div class="btn out">取消</div><div class="btn pri" style="opacity:.35">启用完全权限</div></div></div>'''
page('09-full-access-confirm',BASE+CONF)
# agent preset / mode
def mrow(t,d,on=False): return f'<div class="row" style="align-items:flex-start;{"background:#F5F6F7;border-radius:12px;padding:12px 10px" if on else ""}"><div><div class="t">{t}</div><div class="d">{d}</div></div>{f"<span class=chk>{I(chr(99)+chr(104)+chr(101)+chr(99)+chr(107))}</span>" if on else ""}</div>'
MODE=sheet(f'''<div class="stitle">Agent 预设</div>
{mrow('标准模式','处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。',True)}
{mrow('PTC 模式','包含标准模式的所有能力，更适合批量调用工具，并对结果进行筛选、整理、去重、统计或汇总的任务。')}
{mrow('极简模式','Agent 仅使用终端工具完成任务，适合测试和对比其基础表现。')}
{mrow('创造模式','用对话定制 DSH：让 Agent 编写插件，添加新功能或界面；也能组合工具和提示词，创建自己的模式。')}''')
page('10-mode-sheet',HERO+MODE)
# plus sheet
def crow(ic,t,k,d=''): return f'<div class="row" style="padding:11px 4px">{I(ic)}<span class="t">{t}</span><span class="kbd mono">{k}</span><span class="d" style="margin-left:auto;text-align:right">{d}</span></div>'
PLUS=sheet(f'''<div class="sec" style="margin-top:0">添加</div>
{crow('clip','文件','file')}{crow('goal','目标','goal','设置或查看长期任务目标')}{crow('plan','计划','plan','进入或退出计划模式')}{crow('send','反馈','feedback','发送关于当前会话的反馈')}
<div class="sec">指令</div>
{crow('compact','压缩','compact','压缩以上对话内容')}{crow('shield','权限','permission','切换权限预设（沙箱模式与审批策略）')}{crow('data','模型','model','选择本会话使用的模型')}{crow('dl','下载日志','export','将当前会话内容导出为 ZIP')}''')
page('11-plus-sheet',BASE+PLUS)
# settings index
def nav(ic,t,v=''): return f'<div class="srow">{I(ic)}<span class="t">{t}</span><span class="val">{v}{I("right")}</span></div>'
SBAR=lambda t: f'<div class="top" style="border:0"><div class="ib">{I("back")}</div><div class="title" style="font-size:18px">{t}</div></div>'
SIDX=SBAR('设置')+f'''<div style="height:8px"></div><div class="card">{nav('gear','通用设置')}{nav('data','模型','DeepSeek')}{nav('puzzle','内置插件','28 个')}{nav('preset','Agent 预设','标准模式')}</div>
<div class="card">{nav('file','打开配置文件')}</div>
<div class="sec" style="margin:4px 20px">dsh-android</div>
<div class="card">{nav('link','连接','Mac mini')}<div class="srow"><span class="t">审批通知</span><span class="sw on"></span></div></div>
<div style="text-align:center;font-size:12px;color:#ADB2B8;margin-top:8px">当前版本：0.2.1-alpha.2 · Mac mini</div>'''
page('12-settings-index',SIDX)
def srow(t,d,ctl): return f'<div class="srow"><div style="flex:1;min-width:0"><div class="t">{t}</div><div class="d">{d}</div></div>{ctl}</div>'
def dd(v): return f'<span class="val">{v}{I("down")}</span>'
GEN=SBAR('通用设置')+f'''<div style="overflow:hidden;height:830px">
<div class="card">{srow('权限','选择新会话的默认权限模式',f'<span class="val">{I("shieldc")}工作区内修改{I("down")}</span>')}
{srow('语言','',dd('中文'))}
<div class="srow" style="flex-wrap:wrap"><div class="t" style="flex:1">外观</div><div class="seg"><span class="on">浅色</span><span>深色</span><span>跟随系统</span></div></div>
{srow('字号大小','仅影响会话内容的字号','<span class="val"><span class="seg"><span>−</span><span class="on mono">14</span><span>+</span></span> px</span>')}</div>
<div class="card">{srow('工作步骤展示','选择希望看到多少工具调用细节',dd('详细'))}
{srow('工作步骤收起时机','选择何时自动收起工作步骤',dd('回答结束后'))}
{srow('显示代码工作视图','开启后，显示轨迹、本轮代码差异，可选择完整的 Agent 预设切换','<span class="sw"></span>')}</div>
<div class="card">{srow('快捷键','查看和编辑当前可用的快捷键和输入操作','<span class="val" style="color:#ADB2B8">桌面端</span>')}
{srow('繁忙时的发送行为','智能体运行时 Enter 键和发送按钮的行为；Cmd/Ctrl+Enter 使用另一行为',dd('排队发送'))}
{srow('性能与用量','选择性能与用量信息展示的详细程度',dd('详细'))}
{srow('在使用官方模型 API 时上传 Session Log','帮助改进 DeepSeek 模型与产品','<span class="sw"></span>')}</div>
<div style="text-align:center;font-size:12px;color:#ADB2B8">当前版本：0.2.1-alpha.2</div></div>'''
page('13-settings-general',GEN)
# general dropdown as sheet example
DDS=sheet('<div class="stitle">工作步骤展示</div><div class="d" style="font-size:12px;color:#81858C;margin:0 4px 6px">选择希望看到多少工具调用细节</div>'+''.join(mrow(t,'',t=='详细').replace('<div class="d"></div>','') for t in ['简洁','标准','详细','完全展开']))
page('14-settings-dropdown',GEN+DDS)

# tool sheet / approval updated with new composer
SHEET=sheet(f'''<div style="display:flex;align-items:center;gap:10px"><span class="ok" style="font-size:18px">✓</span><b style="font-size:17px;font-weight:500">grep</b><span style="color:#81858C;font-size:13px;margin-left:auto">0.12 s</span></div>
<div class="h">参数</div><pre>{{"pattern": "auth", "path": "bridge/"}}</pre>
<div class="h">结果 · 6 处匹配</div>
<pre>pi-rpc-bridge.mjs:41   if (msg.auth) {{
pi-rpc-bridge.mjs:42     handleAuth(ws, msg.auth)
pi-rpc-bridge.mjs:88 function handleAuth(ws, got) {{
pi-rpc-bridge.mjs:90   if (!safeEqual(token, got))
token-store.mjs:12   export function loadAuth()</pre>
<div style="display:flex;gap:8px;margin-top:12px"><div class="btn out">复制结果</div><div class="btn out">复制参数</div></div>''')
page('02-tool-sheet',TOP+MSGS+bottom(busy=True)+SHEET)
APPROVAL=f'''<div style="border:1px solid #F7AD31;border-radius:20px;overflow:hidden;box-shadow:0 4px 16px rgba(0,0,0,.08);margin-bottom:8px;background:#fff">
<div style="background:#FEF5E7;color:#B7791F;font-size:13px;line-height:18px;padding:8px 16px;display:flex;gap:8px;align-items:center"><span class="dot y"></span>等待审批</div>
<div style="padding:10px 16px 0"><div style="font-size:15px;font-weight:500">工具 bash 请求越权执行</div>
<div class="mono" style="font-size:13px;line-height:20px;color:#81858C;word-break:break-all;margin-top:4px">npm install --save-dev vitest@latest &amp;&amp; npx vitest run bridge/</div></div>
<div style="display:flex;gap:8px;padding:12px 16px"><div class="btn out">拒绝</div><div class="btn pri">允许一次</div></div></div>'''
NOTIF=f'''<div style="position:absolute;left:8px;right:8px;top:34px;background:#fff;border-radius:20px;box-shadow:0 6px 24px rgba(0,0,0,.18);padding:12px 14px;display:flex;gap:10px;z-index:5">
<div style="width:36px;height:36px;border-radius:10px;background:#F1F3F5;display:flex;align-items:center;justify-content:center">{logo(20,'#0F1115')}</div>
<div style="flex:1;font-size:13px;line-height:18px"><b style="font-weight:500">dsh · 等待审批</b><span style="color:#81858C;float:right">现在</span><br>工具 bash 请求越权执行<div style="display:flex;gap:16px;margin-top:6px;color:#0F1115;font-weight:500"><span>拒绝</span><span>允许一次</span></div></div></div>'''
page('03-approval',TOP+MSGS.replace('<p style="color:#81858C;font-size:13px">深度求索中','<div class="tool"><span style="color:#F59E0B">!</span><b>bash</b><span class="arg mono">npm install --save-dev vitest…</span><span>›</span></div><p style="color:#81858C;font-size:13px">深度求索中')+bottom(busy=True,extra=APPROVAL)+NOTIF)

def prof(dot,name,sub,kind,extra=''):
    return f'''<div class="srow"><span class="dot {dot}"></span><div style="flex:1;min-width:0"><div class="t">{name}{extra}</div><div class="mono d">{sub}</div></div><span style="font-size:12px;color:#61666B;border:1px solid rgba(0,0,0,.12);border-radius:8px;padding:0 8px">{kind}</span>{I('right')}</div>'''
CONN=SBAR('连接')+f'''<div class="card">{prof('g','Mac mini','直连 · 18 ms','iroh',' <span style="font-size:11px;color:#3964FE;background:#E4EDFD;border-radius:6px;padding:0 6px;margin-left:4px">当前</span>')}
{prof('y','MacBook Pro','中继 · 142 ms','iroh')}{prof('g','Studio','100.106.128.87:7681 · 31 ms','Tailscale')}{prof('r','书房 NAS','192.168.1.20:7681 · 无法连接','LAN')}{prof('gr','本机','dsh 0.2.1-alpha.2 · 未启动','本地')}</div>
<div style="margin:0 16px;display:flex;gap:8px"><div class="btn out" style="font-size:14px">输入配对码</div><div class="btn out" style="font-size:14px">扫码配对</div></div>
<div class="sheet" style="box-shadow:0 -4px 24px rgba(0,0,0,.08)"><div class="grab"></div><div class="stitle">配对</div>
<div style="display:flex;gap:8px;justify-content:center;margin:8px 0 16px">{''.join(f'<div class="mono" style="width:46px;height:56px;border:1px solid {"#0F1115" if i==4 else "rgba(0,0,0,.16)"};border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:24px">{d}</div>' for i,d in enumerate("4829  "))}</div>
<div class="btn pri" style="opacity:.35">配对</div></div>'''
page('04-connections',CONN)

def sess(t,active=False,state=''): return f'<div style="padding:8px 12px 8px 32px;border-radius:10px;{"background:#EDEEF0;" if active else ""}display:flex;align-items:center;gap:8px;font-size:14px"><span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{t}</span>{state}</div>'
def ws(n): return f'<div style="display:flex;align-items:center;gap:8px;padding:8px 12px;font-size:14px;color:#3964FE">{I("folder")}<span style="color:#0F1115">{n}</span></div>'
DRAWER=f'''<div style="position:absolute;inset:0">{TOP}{MSGS}</div><div class="scrim"></div>
<div style="position:absolute;left:0;top:0;bottom:0;width:316px;background:#F8F9FB;border-radius:0 24px 24px 0;padding:36px 10px 12px;display:flex;flex-direction:column">
<div style="display:flex;align-items:center;gap:8px;padding:4px 8px 12px">{logo(22,'#0F1115')}<span style="font-size:18px;font-weight:600;letter-spacing:-.3px">deepseek</span><span style="font-size:8px;font-weight:700;background:#0F1115;color:#fff;border-radius:2px;padding:0 3px">HARNESS</span><span style="margin-left:auto;font-size:12px;color:#61666B;display:flex;align-items:center;gap:5px"><span class="dot g"></span>Mac mini</span></div>
<div style="height:40px;border-radius:10px;border:1px solid rgba(0,0,0,.10);background:#fff;display:flex;align-items:center;justify-content:center;gap:6px;font-size:14px;margin:0 2px 8px">{I('newf')}新会话</div>
<div style="display:flex;align-items:center;gap:8px;padding:8px 12px;font-size:14px">{I('puzzle')}插件</div>
<div style="display:flex;align-items:center;gap:8px;padding:8px 12px;font-size:14px">{I('clock')}自动化任务</div>
<div style="display:flex;align-items:center;padding:12px 12px 4px;font-size:12px;color:#81858C"><span style="flex:1">工作区</span><span style="display:flex;gap:12px">{I('search')}{I('sliders')}{I('newf')}</span></div>
{ws('pi-android')}{sess('重构 bridge 鉴权流程',True,'<span class="dot" style="background:#3964FE"></span>')}{sess('Termux RUN_COMMAND 失败排查')}{sess('空状态建议从历史生成',False,'<span class="dot y"></span>')}
{ws('dsh-android')}{sess('梳理 /api 与 remote.mux 协议')}{sess('设计 token 提取')}
<div style="margin-top:auto;display:flex;align-items:center;gap:8px;padding:10px 12px;font-size:14px">{I('gear')}设置</div></div>'''
page('05-drawer',DRAWER)

MORE=sheet(f'''<div class="stitle">问候</div>
<div class="row">{I('dl')}<span class="t">下载 Session 日志</span></div>
<div class="row">{I('send')}<span class="t">反馈</span></div>
<div class="row">{I('bolt')}<span class="t">会话统计</span><span class="d" style="margin-left:auto">3 轮 11 步</span></div>''')
page('15-more-sheet',BASE+MORE)
def trow(lv,kind,name,inp,out,tm,st='✓',hl=False):
    return f'<div style="display:flex;align-items:center;gap:6px;padding:7px 12px 7px {12+lv*14}px;font-size:13px;border-bottom:1px solid rgba(0,0,0,.05);{"background:#F5F7FF" if hl else ""}"><span class="{"ok" if st=="✓" else "run"}" style="width:12px">{st}</span><span style="font-size:11px;color:#81858C;width:28px">{kind}</span><span class="mono" style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{name}</span><span class="mono" style="color:#81858C;font-size:11px;width:44px;text-align:right">{inp}</span><span class="mono" style="color:#81858C;font-size:11px;width:40px;text-align:right">{out}</span><span class="mono" style="font-size:11px;width:40px;text-align:right">{tm}</span></div>'
def bar(w,c,off=0): return f'<div style="height:4px;margin:0 12px 0 {54+off}px;background:linear-gradient(90deg,transparent {off}%,{c} {off}%,{c} {off+w}%,transparent {off+w}%);border-radius:2px"></div>'
TRAJ=TOPF('轨迹')+f'''<div style="display:flex;align-items:center;gap:6px;padding:8px 12px;font-size:12px;color:#61666B">
<div class="seg"><span class="on">时长</span><span>实际时间</span></div><div class="ctl" style="border:1px solid rgba(0,0,0,.1);height:28px">轮次 ⇕</div><div class="ctl" style="border:1px solid rgba(0,0,0,.1);height:28px">调用 ⇕</div><div class="ib" style="margin-left:auto;width:32px;height:32px">{I('search')}</div></div>
<div style="display:flex;gap:6px;padding:4px 12px;font-size:11px;color:#ADB2B8;border-bottom:1px solid rgba(0,0,0,.08)"><span style="width:46px"></span><span style="flex:1"></span><span style="width:44px;text-align:right">输入</span><span style="width:40px;text-align:right">输出</span><span style="width:40px;text-align:right">时间</span></div>
<div style="padding:8px 12px 4px;font-size:12px;font-weight:500">第 1 轮</div>
{trow(0,'用户','给 pi-rpc-bridge 加上 token 轮换…','','','')}
{trow(0,'助手','第 1 步 · DeepSeek-V4.1-Flash','18.2k','612','8.1s')}
{trow(1,'思考','reasoning','','431','5.0s')}
{trow(1,'工具','read bridge/pi-rpc-bridge.mjs','','','0.04s')}
{trow(1,'工具','grep "auth"','','','0.12s')}
{trow(0,'助手','第 2 步 · DeepSeek-V4.1-Flash','21.9k','1.2k','14.6s',hl=True)}
{trow(1,'工具','edit bridge/pi-rpc-bridge.mjs','','','0.02s')}
{trow(0,'助手','第 3 步 · DeepSeek-V4.1-Flash','23.4k','—','…','◌')}
<div class="sheet" style="box-shadow:0 -4px 24px rgba(0,0,0,.1);padding-bottom:16px"><div class="grab"></div>
<div class="stitle" style="font-size:15px">第 2 步 · 助手</div>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px 16px;font-size:13px;margin:6px 4px 10px">
<span style="color:#81858C">开始时间</span><span class="mono">16:12:08</span><span style="color:#81858C">总时长</span><span class="mono">14.6s</span>
<span style="color:#81858C">首 token 延迟</span><span class="mono">1.3s</span><span style="color:#81858C">吞吐量</span><span class="mono">84 tok/s</span>
<span style="color:#81858C">模型</span><span class="mono">DeepSeek-V4.1-Flash</span><span style="color:#81858C">缓存命中</span><span class="mono">96%</span></div>
<div style="display:flex;gap:8px"><div class="btn out" style="height:40px;font-size:14px">输入</div><div class="btn out" style="height:40px;font-size:14px">输出</div><div class="btn out" style="height:40px;font-size:14px">思考</div></div></div>'''
page('16-trajectory',TRAJ)
RP=sheet(f'''<div style="display:flex;align-items:center;gap:8px;margin-bottom:6px"><div class="stitle" style="margin:0">右侧边栏</div><span class="d" style="margin-left:auto;font-size:12px;color:#81858C">新标签页</span></div>
<div style="display:flex;gap:6px;margin:6px 0 10px"><div class="chip on">开始</div><div class="chip">{I('folder')}工作区文件 ✕</div></div>
<div class="sec">开始</div>
<div class="row">{I('folder')}<div><div class="t">工作区文件</div><div class="d">浏览会话工作区的文件</div></div>{I('right')}</div>
<div class="row">{I('term')}<div><div class="t">终端</div><div class="d">在会话工作区运行命令</div></div>{I('right')}</div>
<div class="row">{I('globe')}<div><div class="t">浏览器</div><div class="d">浏览网页</div></div>{I('right')}</div>
<div class="row">{I('clock')}<div><div class="t">后台任务</div><div class="d">0 个后台任务</div></div>{I('right')}</div>
<div class="d" style="font-size:11px;color:#ADB2B8;margin:6px 4px">手机上每个页面全屏打开；桌面的分栏/浮动不提供</div>''')
page('17-right-panel-sheet',BASE+RP)
TEAM=sheet(f'''<div class="stitle">{I('team')}&nbsp;智能体团队</div>
<div class="sec">成员</div>
<div class="row"><span class="dot g"></span><div><div class="t">lead <span class="d">· 当前会话</span></div><div class="d">模型 DeepSeek-V4.1-Flash · 运行中</div></div></div>
<div class="row"><span class="dot gr"></span><div><div class="t">reviewer</div><div class="d">模型 DeepSeek-V4-Pro · 未运行</div></div><span class="d" style="margin-left:auto">打开成员会话 ›</span></div>
<div class="sec">共享任务</div>
<div style="text-align:center;color:#81858C;font-size:13px;padding:18px 0">暂无共享任务，可以通过对话创建</div>
<div class="d" style="font-size:11px;color:#ADB2B8;margin:0 4px">实验性功能（experimental/client-ui-agent-team）</div>''')
page('18-agent-team-sheet',BASE+TEAM)
