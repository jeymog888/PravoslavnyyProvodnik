import subprocess
runner='/private/tmp/feoria-cycle3-native.py'
for scene in ['epistle-full-header','narrow-original-dark']:
 subprocess.run(['python3',runner,'scene',scene],check=True)
 subprocess.run(['python3',runner,'capture',scene],check=True)
 print('Finished',scene,flush=True)
