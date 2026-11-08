from pathlib import Path
import importlib.util, json, sys, xml.etree.ElementTree as ET
sp=importlib.util.spec_from_file_location('d','/private/tmp/feoria-native-design-v05.py'); d=importlib.util.module_from_spec(sp);sp.loader.exec_module(d)
run=d.BASE/'design-cycle3-native'
T=d.label
cases=[
 ('epistle-full-header',[T('Прокимен и чтение Апостола'),T('Текста чтения пока нет'),d.selected('По-русски'),d.paused(False)],'1.0','ordinary portrait 412dp; accepted 9 Nov missing Epistle, app font1.3 dark'),
 ('narrow-original-dark',[T('Символ веры'),d.selected('Оригинал'),d.paused(),T('Иже от Отца рожденнаго прежде всех век.')],'2.0','320x640dp; font2.0 x app1.3; accepted 8 Nov Creed phrase4; dark'),
 ('narrow-russian-light',[T('Символ веры'),d.selected('По-русски'),d.paused()],'2.0','320x640dp; font2.0 x app1.3; accepted 8 Nov Creed phrase4; light; translated paired phrase'),
 ('compact-reading-details',[T('Открытое чтение'),T('Тексты на 8 ноября 2026'),T('Этап'),T('Закрыть')],'2.0','same narrow reader; menu details pauses and reports accepted date; scrollable details'),
 ('landscape-original',[d.selected('Оригинал'),d.paused(),T('Иже от Отца рожденнаго прежде всех век.')],'1.3','640x360dp landscape; system1.3 x app1.3 dark; accepted 8 Nov Creed phrase4'),
 ('landscape-meaning',[d.selected('Смысл'),T('Что происходит')],'1.3','same landscape reading; Meaning switches and pauses'),
 ('waiting-context',[T('Дальше слушаем службу'),d.paused()],'1.0','ordinary portrait; native entry to last stage and real Play reaches Wait; manually paused for static review'),
 ('end-of-order',[T('Путеводитель завершен'),d.paused(False)],'1.0','ordinary portrait; real monotonic Play reaches End and pauses; no fake time'),
]
def init():
 manifest={'schema':1,'package':d.PACKAGE,'build_type':'debug','conditions':{'data':'explicit local visual fixtures, not live parish data','clock':'device wall and monotonic clocks, no fake time','network':'not used in reader corpus','geometry':'own API35 emulator only'},'cases':[]}
 for name,expected,font,note in cases:
  manifest['cases'].append({'id':name,'mode':'static','conditions':{'scene':note},'expected':expected,'human_review_required':True,'allowed_windows':d.ALLOW,'observed_requirements':{'sdk':'35','font_scale':font,'timezone':'Europe/Moscow','density':'Physical density: 420'}})
 p=d.BASE/'design-cycle3-native-manifest.json';d.h.write_json(p,manifest)
 d.h.begin_run(p,d.BASE/'design-cycle3-ready2-build/build.json',run,d.adb)
 print('initialized',run)
def env(size=None,font='1.0',rotate=0):
 d.setup(run,'shell','settings','put','system','accelerometer_rotation','0')
 d.setup(run,'shell','settings','put','system','user_rotation',str(rotate))
 d.setup(run,'shell','wm','size',size or 'reset')
 d.setup(run,'shell','settings','put','system','font_scale',font)
def fixture(name,dark=None,original=None):
 data=(d.BASE/name).read_bytes();root=ET.fromstring(data)
 for key,value in [('dark',dark),('lyric_original',original)]:
  if value is not None:
   node=root.find(f"boolean[@name='{key}']")
   if node is None: node=ET.SubElement(root,'boolean',name=key)
   node.set('value',str(value).lower())
 data=ET.tostring(root,encoding='utf-8',xml_declaration=True)
 (run/('fixture-'+sys.argv[2]+'.xml')).write_bytes(data)
 d.setup(run,'shell','am','force-stop',d.PACKAGE)
 d.setup(run,'shell','run-as',d.PACKAGE,'mkdir','-p','shared_prefs')
 d.setup(run,'shell','run-as',d.PACKAGE,'sh','-c',"'cat > shared_prefs/guide_preferences.xml'",input_bytes=data)
 d.setup(run,'shell','am','start','-W','-n',d.PACKAGE+'/.ui.MainActivity')
def resume(case):
 for i in range(12):
  v=d.view()
  try: v.target(T('Сохраненное чтение'))
  except d.h.GateError:
   if i==11: raise
   w,h=v.size;d.setup(run,'shell','input','swipe',str(w//2),str(int(h*.78)),str(w//2),str(int(h*.36)),'400')
  else:
   d.h.tap(run,d.adb,case,T('Сохраненное чтение'),[T('Действия со службой','content-desc')],timeout=30);return
cmd=sys.argv[1]
if cmd=='init':init()
elif cmd=='scene':
 case=sys.argv[2]
 if case=='epistle-full-header':env();fixture('design-missing-fixture.xml')
 elif case=='narrow-original-dark':env('840x1680','2.0');fixture('design-creed-fixture.xml',True,True)
 elif case=='narrow-russian-light':env('840x1680','2.0');fixture('design-creed-fixture.xml',False,False)
 elif case=='landscape-original':env('945x1680','1.3',1);fixture('design-creed-fixture.xml',True,True)
 elif case=='waiting-context':env();fixture('design-creed-fixture.xml',True,True)
 else:raise ValueError(case)
 resume(case);d.show()
elif cmd=='show':d.show()
elif cmd=='tap':
 case,kind,value,ek,ev=sys.argv[2:];print(d.h.tap(run,d.adb,case,{kind:value},[{ek:ev}],timeout=30))
elif cmd=='capture':print(d.h.capture_case(run,d.adb,sys.argv[2],timeout=40))
elif cmd=='raw':print(d.setup(run,*sys.argv[2:]).decode(errors='replace'))
elif cmd=='reset':env()
elif cmd=='finish':print(json.dumps(d.h.aggregate(run),ensure_ascii=False))
else:raise ValueError(cmd)
