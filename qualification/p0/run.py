#!/usr/bin/env python3
"""Real HTTPS protected initialization and screenshot qualification; owned disposable projects only."""
import argparse, importlib.util, json, os
from pathlib import Path
import re, secrets, socket, subprocess, sys, tempfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'qualification/passkeys'))
import run as established
operations=established.operations

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-image',required=True)
    parser.add_argument('--browser-image',default='busystack-passkey-browser:corrective')
    parser.add_argument('--deployment-directory',type=Path,default=ROOT)
    parser.add_argument('--context-path',default='',choices=('', '/team'))
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
        environment.update(COMPOSE_FILE=str(operations.ROOT/'compose.yaml')+':'+str(override),COMPOSE_PROJECT_NAME='busystack-p0-'+secrets.token_hex(6),COMPOSE_PATH_SEPARATOR=':',BUSYSTACK_DATABASE_PASSWORD_FILE=str(fixture/'database-password'),BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=str(fixture/'admin-password'),BUSYSTACK_PUBLIC_BASE_URL=base,BUSYSTACK_ISSUER=base)
        try:
            print('P0_CONFIG_VALIDATION',flush=True)
            operations.compose(environment,'config','--quiet')
            operations.compose(environment,'up','-d','--no-build','--wait','--wait-timeout','120','database')
            operations.compose(environment,'up','-d','--no-build','application')
            established.ready(base,fixture/'ca.pem')
            print('P0_HTTPS_READY',flush=True)
            print('P0_LOCAL_ISSUANCE',flush=True)
            result=operations.execute(['bash','scripts/initialize.sh'],environment)
            codes=re.findall(r'(?m)^[A-Za-z0-9_-]{43}$',result);assert len(codes)==1,'Expected exactly one operator-only code'
            code=fixture/'initialization-code';code.write_text(codes[0]);code.chmod(0o600)
            command=['docker','run','--rm','--network','host','--volume',str(fixture/'ca.pem')+':/fixture/ca.pem:ro','--volume',str(fixture/'password')+':/fixture/password:ro','--volume',str(code)+':/fixture/initialization-code:ro','--volume',str(args.output.resolve())+':/evidence','--volume',str(ROOT/'qualification/p0/browser.mjs')+':/qualification/passkeys.mjs:ro','--volume',str(ROOT/'qualification/passkeys/portal-helpers.mjs')+':/qualification/portal-helpers.mjs:ro','--env','BUSYSTACK_BROWSER_CA_FILE=/fixture/ca.pem','--env','BUSYSTACK_BROWSER_URL='+base,args.browser_image]
            print('P0_BROWSER_START',flush=True)
            subprocess.run(command,check=True,timeout=600)
            logs=operations.compose(environment,'logs','--no-log-prefix','application')
            assert codes[0] not in logs and (fixture/'password').read_text().strip() not in logs,'Secret appeared in application logs'
            assert operations.sql(environment,'SELECT initialized AND code_verifier IS NULL FROM installation_state;')=='t'
            print('P0_LOCAL_CODE_AND_SECRET_LOG_CHECK_PASSED',flush=True)
        finally:
            operations.compose(environment,'down','--volumes','--remove-orphans','--timeout','10')
if __name__=='__main__':main()
