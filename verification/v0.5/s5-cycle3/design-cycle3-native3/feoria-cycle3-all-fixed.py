import subprocess
r='/private/tmp/feoria-cycle3-native.py'
def call(*a):subprocess.run(['python3',r,*a],check=True)
call('init');call('scene','end-of-order');call('capture','end-of-order')
subprocess.run(['python3','-u','/private/tmp/feoria-cycle3-wait-fixed.py'],check=True)
subprocess.run(['python3','-u','/private/tmp/feoria-cycle3-scenes-first.py'],check=True)
subprocess.run(['python3','-u','/private/tmp/feoria-cycle3-scenes-next.py'],check=True)
print('All 8 final cycle3 captures completed; visual reviews still required',flush=True)
