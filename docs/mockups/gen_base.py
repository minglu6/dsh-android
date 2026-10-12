import pathlib
fish=open('fish.txt').read()
LOGO=f'<svg viewBox="0 0 23.16 17.04" width="{{w}}" height="{{h}}"><path fill="{{c}}" d="{fish}"/></svg>'
def logo(w=22,c='#4176E6'): return LOGO.format(w=w,h=w*17.04/23.16,c=c)
CSS='''
*{box-sizing:border-box;margin:0;padding:0}
body{width:412px;height:915px;overflow:hidden;font-family:-apple-system,"Segoe UI","PingFang SC","Noto Sans CJK SC",sans-serif;background:#fff;color:#0F1115;font-size:15px;line-height:24px;position:relative}
.mono{font-family:"JetBrains Mono","DejaVu Sans Mono",monospace}
.status{height:28px;display:flex;justify-content:space-between;padding:0 20px;font-size:13px;color:#0F1115;align-items:center}
.top{height:56px;display:flex;align-items:center;gap:12px;padding:0 12px;border-bottom:1px solid rgba(0,0,0,.06)}
.ib{width:40px;height:40px;border-radius:12px;display:flex;align-items:center;justify-content:center;color:#61666B;font-size:20px}
.title{flex:1;font-size:16px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block}
.g{background:#22C55E}.r{background:#EC1313}.y{background:#F59E0B}.gr{background:#D4D4D4}
.msgs{padding:16px 16px 0}
.user{margin-left:56px;background:#F1F3F5;border-radius:20px;padding:10px 16px;margin-bottom:16px}
.asst{margin-bottom:12px}
.reason{font-size:13px;color:#81858C;border-left:2px solid #E1E5EE;padding-left:10px;margin-bottom:10px;line-height:20px}
.tool{display:flex;align-items:center;gap:10px;border:1px solid rgba(0,0,0,.10);border-radius:12px;padding:8px 12px;margin:8px 0;font-size:14px;color:#61666B;background:#fff}
.tool b{color:#0F1115;font-weight:500}
.tool .arg{flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:13px;color:#81858C}
.ok{color:#22C55E}.run{color:#4176E6}
code{font-family:"JetBrains Mono","DejaVu Sans Mono",monospace;font-size:13px;background:#F1F3F5;border-radius:4px;padding:1px 5px}
pre{font-family:"JetBrains Mono","DejaVu Sans Mono",monospace;font-size:13px;line-height:20px;background:#F5F6F7;border-radius:12px;padding:12px;overflow:hidden;white-space:pre;margin:8px 0}
.caret{display:inline-block;width:8px;height:16px;background:#4176E6;vertical-align:-3px;border-radius:2px}
.bottom{position:absolute;left:0;right:0;bottom:0;background:#fff;padding:8px 12px 20px}
.chips{display:flex;gap:8px;margin-bottom:8px}
.chip{height:32px;border-radius:16px;border:1px solid rgba(0,0,0,.12);padding:0 12px;display:flex;align-items:center;gap:6px;font-size:13px;color:#61666B}
.chip.on{background:#E4EDFD;border-color:transparent;color:#4176E6}
.comp{border:1px solid rgba(0,0,0,.12);border-radius:20px;min-height:52px;display:flex;align-items:center;padding:6px 6px 6px 16px;gap:8px;color:#ADB2B8;box-shadow:0 2px 12px rgba(0,0,0,.04)}
.send{width:40px;height:40px;border-radius:50%;background:#0F1115;color:#fff;display:flex;align-items:center;justify-content:center;font-size:18px;margin-left:auto}
.stop{background:#0F1115}
.scrim{position:absolute;inset:0;background:rgba(0,0,0,.24)}
.sheet{position:absolute;left:0;right:0;bottom:0;background:#fff;border-radius:28px 28px 0 0;padding:8px 16px 24px}
.grab{width:36px;height:4px;border-radius:2px;background:#CFD3D6;margin:4px auto 12px}
.h{font-size:13px;color:#81858C;margin:12px 0 6px}
.btn{height:48px;border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:500;flex:1}
.pri{background:#0F1115;color:#fff}.out{border:1px solid rgba(0,0,0,.16);color:#0F1115}
.mock{position:absolute;right:8px;top:30px;font-size:10px;color:#ADB2B8;letter-spacing:1px}
'''
SB='<div class="status"><span>9:41</span><span>5G ▮▮▮ 87%</span></div><div class="mock">MOCKUP</div>'
def page(name,body):
    pathlib.Path(f'src/{name}.html').write_text(f'<!doctype html><meta charset=utf-8><style>{CSS}</style><body>{SB}{body}</body>')

