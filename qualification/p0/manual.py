#!/usr/bin/env python3
"""Prepare a fresh operator-controlled browser and passkey acceptance deployment; no account seeding."""
import argparse,json,os,secrets,shutil,socket,subprocess,sys
from pathlib import Path
from ipaddress import IPv4Network
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'qualification/passkeys'));import run as established
ops=established.operations

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--deployment-directory',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--network-subnet',type=IPv4Network,help='Optional disposable fixture subnet when Docker default pools are exhausted');args=parser.parse_args()
    directory=args.output.resolve();directory.mkdir(mode=0o700,parents=True,exist_ok=False);install=directory/'install';shutil.copytree(args.deployment_directory,install)
    environment=os.environ.copy()
    for name in ('database-password','admin-password'):
        path=directory/name;path.write_text(secrets.token_urlsafe(32)+'\n');path.chmod(0o444)
    established.certificates(directory,environment)
    with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
    base=f'https://localhost:{port}'
    template=(install/'nginx-root.conf.example').read_text().replace('listen 443 ssl;','listen 8443 ssl;').replace('server_name calendar.example.org;','server_name localhost;').replace('calendar.example.org',f'localhost:{port}').replace('/etc/ssl/caldav/fullchain.pem','/tls/server.pem').replace('/etc/ssl/caldav/privkey.pem','/tls/server.key').replace('http://127.0.0.1:8080','http://application:8080')
    config='worker_processes 1;\npid /tmp/nginx.pid;\nerror_log /dev/stderr warn;\nevents {worker_connections 128;}\nhttp {access_log off;client_body_temp_path /tmp/client_temp;proxy_temp_path /tmp/proxy_temp;fastcgi_temp_path /tmp/fastcgi_temp;uwsgi_temp_path /tmp/uwsgi_temp;scgi_temp_path /tmp/scgi_temp;\n'+template+'}\n';(directory/'nginx.conf').write_text(config);(directory/'server.key').chmod(0o444)
    proxy={'image':established.proxy.IMAGE,'user':'101:101','entrypoint':['nginx','-g','daemon off;'],'ports':[f'127.0.0.1:{port}:8443'],'depends_on':{'application':{'condition':'service_healthy'}},'volumes':[f'{directory}/nginx.conf:/etc/nginx/nginx.conf:ro',f'{directory}/server.pem:/tls/server.pem:ro',f'{directory}/server.key:/tls/server.key:ro'],'read_only':True,'tmpfs':['/tmp:rw,nosuid,nodev,noexec,size=32m'],'mem_limit':'128m','cap_drop':['ALL'],'security_opt':['no-new-privileges:true']}
    (install/'compose.manual.yaml').write_text('services:\n  application:\n    ports: !override []\n  reverse-proxy: '+json.dumps(proxy)+'\n')
    if args.network_subnet:
        assert args.network_subnet.is_private, 'Choose a private unallocated fixture subnet'
        with (install/'compose.manual.yaml').open('a') as settings:
            settings.write('networks:\n  default:\n    ipam:\n      config:\n        - subnet: '+str(args.network_subnet)+'\n')
    values={'COMPOSE_PROJECT_NAME':'busystack-p0-manual-'+secrets.token_hex(6),'COMPOSE_FILE':'compose.yaml:compose.manual.yaml','BUSYSTACK_DATABASE_PASSWORD_FILE':str(directory/'database-password'),'BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE':str(directory/'admin-password'),'BUSYSTACK_PUBLIC_BASE_URL':base,'BUSYSTACK_ISSUER':base}
    (install/'.env').write_text(''.join(name+'='+value+'\n' for name,value in values.items()));(install/'.env').chmod(0o600)
    def command(*args):subprocess.run(args,cwd=install,check=True)
    command('docker','compose','config','--quiet');command('docker','compose','pull','--policy','missing');command('docker','compose','up','-d','--no-build','--wait','--wait-timeout','240')
    established.ready(base,directory/'ca.pem',True)
    subprocess.run(['openssl','x509','-checkend','60','-noout','-in',str(directory/'server.pem')],check=True)
    with established.tls.opener(directory/'ca.pem').open(base+'/setup',timeout=10) as setup:
        assert setup.status==200 and b'Set up BusyStack' in setup.read(), 'Protected setup did not render'
    (directory/'acceptance.json').write_text(json.dumps({'status':'ready-manual-acceptance-pending','url':base,'directory':str(install),'ca':str(directory/'ca.pem'),'noAccountCreated':True,'setupVerifiedOverTrustedTls':True,'initializationCommand':'bash scripts/initialize.sh','cleanup':'docker compose down --volumes --remove-orphans'},indent=2)+'\n')
    print((directory/'acceptance.json').read_text())
if __name__=='__main__':main()
