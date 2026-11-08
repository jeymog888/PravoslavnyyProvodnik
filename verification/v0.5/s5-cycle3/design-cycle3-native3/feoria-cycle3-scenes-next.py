import subprocess
runner='/private/tmp/feoria-cycle3-native.py'
def call(*args):subprocess.run(['python3',runner,*args],check=True)
def tap(case,kind,value,ek,ev):call('tap',case,kind,value,ek,ev)
c='compact-reading-details'
tap(c,'content-desc','Действия со службой','text','Открытое чтение')
tap(c,'text','Открытое чтение','text','Тексты на 8 ноября 2026')
call('capture',c)
tap(c,'text','Закрыть','content-desc','Включить плеер')
c='narrow-russian-light'
tap(c,'text','По-русски','content-desc','Включить плеер')
tap(c,'content-desc','Действия со службой','text','Светлый экран')
tap(c,'text','Светлый экран','content-desc','Включить плеер')
call('capture',c)
c='landscape-original'
call('scene',c);call('capture',c)
c='landscape-meaning'
tap(c,'text','Смысл','text','Что происходит');call('capture',c)
