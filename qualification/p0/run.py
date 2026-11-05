#!/usr/bin/env python3
"""Real HTTPS protected initialization and screenshot qualification; owned disposable projects only."""
import argparse, importlib.util, json, os
from pathlib import Path
import re, secrets, socket, subprocess, sys, tempfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'qualification/passkeys'))
import run as established
operations=established.operations

def proxy_override(deployment, fixture, base, port):
    from urllib.parse import urlsplit
    public = urlsplit(base)
    name = 'nginx-subpath.conf.example' if public.path else 'nginx-root.conf.example'
    example = deployment / name
    if not example.exists(): example = deployment / 'deployment' / name
    template = example.read_text().replace('listen 443 ssl;', 'listen 8443 ssl;')
    template = template.replace('server_name calendar.example.org;', 'server_name localhost;')
    template = template.replace('calendar.example.org', public.netloc)
    if public.path: template = template.replace('/calendar', public.path)
    template = template.replace('/etc/ssl/caldav/fullchain.pem', '/tls/server.pem').replace('/etc/ssl/caldav/privkey.pem', '/tls/server.key')
    template = template.replace('http://127.0.0.1:8080', 'http://application:8080')
    (fixture / 'server.key').chmod(0o444)
    (fixture / 'nginx.conf').write_text('worker_processes 1;\npid /tmp/nginx.pid;\nerror_log /dev/stderr warn;\nevents {worker_connections 128;}\nhttp {access_log off;client_body_temp_path /tmp/client_temp;proxy_temp_path /tmp/proxy_temp;fastcgi_temp_path /tmp/fastcgi_temp;uwsgi_temp_path /tmp/uwsgi_temp;scgi_temp_path /tmp/scgi_temp;\n' + template + '}\n')
    proxy = {'image': established.proxy.IMAGE, 'user': '101:101', 'entrypoint': ['nginx', '-g', 'daemon off;'],
        'ports': [f'127.0.0.1:{port}:8443'], 'depends_on': {'application': {'condition': 'service_healthy'}},
        'volumes': [str(fixture / 'nginx.conf') + ':/etc/nginx/nginx.conf:ro', str(fixture / 'server.pem') + ':/tls/server.pem:ro', str(fixture / 'server.key') + ':/tls/server.key:ro'],
        'read_only': True, 'tmpfs': ['/tmp:rw,nosuid,nodev,noexec,size=32m'], 'mem_limit': '128m',
        'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true']}
    return 'services:\n  application:\n    ports: !override []\n  reverse-proxy: ' + json.dumps(proxy) + '\n'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-image',required=True)
    parser.add_argument('--browser-image',default='busystack-passkey-browser:corrective')
    parser.add_argument('--deployment-directory',type=Path,default=ROOT)
    parser.add_argument('--context-path',default='',choices=('', '/team'))
    parser.add_argument('--clients',action='store_true',help='Run real Python and native Thunderbird profiles using the browser-issued application password')
    parser.add_argument('--client-python',default=str(ROOT/'.client-venv/bin/python'))
    parser.add_argument('--thunderbird-binary',default=str(ROOT/'.client-tools/thunderbird/thunderbird'))
    parser.add_argument('--proxy',action='store_true',help='Use the package HTTPS proxy example, preserving root or /team paths')
    parser.add_argument('--output',type=Path,default=ROOT/'target/qualification/p0')
    args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
    operations.ROOT=args.deployment_directory.resolve()
    environment=os.environ.copy();environment.update(operations.development_environment(args.application_image))
    with tempfile.TemporaryDirectory(prefix='busystack-p0-') as temporary:
        fixture=Path(temporary);fixture.chmod(0o700)
        for name in ('database-password','admin-password','password'):
            f=fixture/name;f.write_text(secrets.token_urlsafe(32)+'\n');f.chmod(0o600 if name=='password' else 0o444)
        established.certificates(fixture,environment)
        with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
        base=f'https://localhost:{port}'+args.context_path
        override=fixture/'override.yaml'
        override.write_text('services:\n  application:\n    ports: !override\n      - "127.0.0.1:'+str(port)+':8443"\n    environment:\n      SERVER_PORT: "8443"\n      SERVER_SSL_ENABLED: "true"\n      SERVER_SSL_KEY_STORE: file:/tls/server.p12\n      SERVER_SSL_KEY_STORE_TYPE: PKCS12\n    volumes:\n      - '+json.dumps(str(fixture/'server.p12')+':/tls/server.p12:ro')+'\n    secrets:\n      - source: tls_password\n        target: server.ssl.key-store-password\n    healthcheck:\n      disable: true\nsecrets:\n  tls_password:\n    file: '+json.dumps(str(fixture/'server-password'))+'\n')
        if args.proxy: override.write_text(proxy_override(operations.ROOT,fixture,base,port))
        environment.update(COMPOSE_FILE=str(operations.ROOT/'compose.yaml')+':'+str(override),COMPOSE_PROJECT_NAME='busystack-p0-'+secrets.token_hex(6),COMPOSE_PATH_SEPARATOR=':',BUSYSTACK_DATABASE_PASSWORD_FILE=str(fixture/'database-password'),BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=str(fixture/'admin-password'),BUSYSTACK_PUBLIC_BASE_URL=base,BUSYSTACK_ISSUER=base)
        try:
            print('P0_CONFIG_VALIDATION',flush=True)
            operations.compose(environment,'config','--quiet')
            configuration=json.loads(operations.execute(['docker','compose','--profile','operator','config','--format','json'],environment))
            assert configuration['services']['initialization']['logging']['driver']=='none'
            assert configuration['services']['application'].get('logging',{}).get('driver')!='none'
            if args.proxy: operations.compose(environment,'pull','--policy','missing')
            operations.compose(environment,'up','-d','--no-build','--wait','--wait-timeout','120','database')
            operations.compose(environment,'up','-d','--no-build','application')
            if args.proxy: operations.compose(environment,'up','-d','--no-build','--wait','--wait-timeout','240','reverse-proxy')
            established.ready(base,fixture/'ca.pem',args.proxy)
            print('P0_HTTPS_READY',flush=True)
            print('P0_LOCAL_ISSUANCE',flush=True)
            result=operations.execute(['bash','scripts/initialize.sh'],environment)
            codes=re.findall(r'(?m)^[A-Za-z0-9_-]{43}$',result);assert len(codes)==1,'Expected exactly one operator-only code'
            code=fixture/'initialization-code';code.write_text(codes[0]);code.chmod(0o600)
            issued=fixture/'issued-password';issued.touch(mode=0o600)
            command=['docker','run','--rm','--network','host','--volume',str(fixture/'ca.pem')+':/fixture/ca.pem:ro','--volume',str(fixture/'password')+':/fixture/password:ro','--volume',str(code)+':/fixture/initialization-code:ro','--volume',str(issued)+':/fixture/issued-password:rw','--volume',str(args.output.resolve())+':/evidence','--volume',str(ROOT/'qualification/p0/browser.mjs')+':/qualification/passkeys.mjs:ro','--volume',str(ROOT/'qualification/passkeys/portal-helpers.mjs')+':/qualification/portal-helpers.mjs:ro','--env','BUSYSTACK_BROWSER_CA_FILE=/fixture/ca.pem','--env','BUSYSTACK_BROWSER_URL='+base,args.browser_image]
            print('P0_BROWSER_START',flush=True)
            subprocess.run(command,check=True,timeout=600)
            if args.clients:
                client_environment=os.environ.copy()
                client_environment.update(BUSYSTACK_CLIENT_URL=base+'/dav/',BUSYSTACK_CLIENT_USER='demo-admin',
                    BUSYSTACK_CLIENT_SECRET_FILE=str(issued),BUSYSTACK_CLIENT_CA=str(fixture/'ca.pem'),
                    BUSYSTACK_CLIENT_SYNC_DEPTH_INPUT='setting omitted; packaged application default',BUSYSTACK_THUNDERBIRD_BINARY=args.thunderbird_binary)
                client_results=[]
                for script,deadline in [('python_caldav.py',180),('thunderbird.py',360)]:
                    result=subprocess.run([args.client_python,str(ROOT/'qualification/clients'/script)],cwd=ROOT,env=client_environment,text=True,capture_output=True,timeout=deadline)
                    if result.returncode:
                        raise RuntimeError('P0 independent client failed: '+operations.sanitized_diagnostic(result.stdout+result.stderr,[issued.read_text()]))
                    value=json.loads(result.stdout.strip().splitlines()[-1]);assert value['status']=='passed'
                    client_results.append(value)
                    print('P0_INDEPENDENT_CLIENT_PASSED='+script,flush=True)
                (args.output/'clients-result.json').write_text(json.dumps({'image':args.application_image,'clients':client_results,'credentialOrigin':'application password issued after protected browser initialization and real virtual-authenticator MFA'},indent=2)+'\n')
            logs=operations.compose(environment,'logs','--no-log-prefix','application')
            assert codes[0] not in logs and (fixture/'password').read_text().strip() not in logs and issued.read_text() not in logs,'Secret appeared in application logs'
            assert operations.sql(environment,'SELECT initialized AND code_verifier IS NULL FROM installation_state;')=='t'
            print('P0_LOCAL_CODE_AND_SECRET_LOG_CHECK_PASSED',flush=True)
        finally:
            operations.compose(environment,'down','--volumes','--remove-orphans','--timeout','10')
if __name__=='__main__':main()
