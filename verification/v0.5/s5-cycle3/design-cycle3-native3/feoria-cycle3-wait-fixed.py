import importlib.util, subprocess, time, json
sp=importlib.util.spec_from_file_location('d','/private/tmp/feoria-native-design-v05.py');d=importlib.util.module_from_spec(sp);sp.loader.exec_module(d)
run=d.BASE/'design-cycle3-native3';case='waiting-context';T=d.label
subprocess.run(['python3','/private/tmp/feoria-cycle3-native.py','scene',case],check=True)
def tap(kind,value,expected):return d.h.tap(run,d.adb,case,{kind:value},expected,timeout=35)
tap('content-desc','Порядок службы',[T('Порядок службы')])
for i in range(8):
 v=d.view()
 try:v.target(T('Благодарение и завершение'))
 except d.h.GateError:
  if i==7:raise
  w,h=v.size;d.setup(run,'shell','input','swipe',str(w//2),str(int(h*.77)),str(w//2),str(int(h*.34)),'400')
 else:break
tap('text','Благодарение и завершение',[T('Благодарение и завершение'),d.paused()])
# Select the final available text through the real chooser, then let real time reach Wait.
tap('content-desc','Выбрать текст: Да исполнятся уста наша',[T('Тексты этого этапа')])
tap('text','С миром изыдем',[T('С миром изыдем'),d.paused()])
tap('content-desc','Включить плеер',[T('Остановить плеер','content-desc')])
d.h.wait_for(d.adb,d.PACKAGE,[T('Дальше слушаем службу')],d.ALLOW,timeout=40)
tap('content-desc','Остановить плеер',[d.paused(),T('Дальше слушаем службу')])
d.h.capture_case(run,d.adb,case,timeout=40)
print('waiting-context PASS',flush=True)
