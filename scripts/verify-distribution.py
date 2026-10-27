#!/usr/bin/env python3
"""Inspect source, executable JAR and final image license delivery; no feed or network bypass."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APACHE_SHA = 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
DOCUMENTS = ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES')


def inspect_jar(path):
    with zipfile.ZipFile(path) as jar:
        for name in DOCUMENTS:
            assert jar.read('META-INF/' + name) == (ROOT / name).read_bytes(), 'JAR missing/different ' + name
        assert jar.read('META-INF/licenses/ical4j-parser-BSD-3-Clause.txt') == (ROOT / 'src/main/resources/META-INF/licenses/ical4j-parser-BSD-3-Clause.txt').read_bytes()
        assert b'Spring' in jar.read('META-INF/licenses/spring-derived-schemas.txt')
        assert b'allow-sync-depth-one: true' in jar.read('BOOT-INF/classes/application.yaml'), 'Executable JAR default differs from approved interoperability default'
        for name in ('asm-9.7.1-copyright-and-license.txt','logback-LICENSE.txt','EPL-2.0.txt','LGPL-2.1.txt'):
            assert jar.read('META-INF/licenses/'+name)==(ROOT/'licenses'/name).read_bytes()
        bom = json.loads(jar.read('META-INF/sbom/application.cdx.json'))
        project_licenses = bom['metadata']['component']['licenses']
        assert isinstance(project_licenses,list) and len(project_licenses)==1 and set(project_licenses[0])=={'license'}, 'Project SBOM license is incorrect'
        project_license = project_licenses[0]['license']
        assert isinstance(project_license,dict) and set(project_license) in ({'id'},{'id','url'}) and project_license.get('id')=='Apache-2.0', 'Project SBOM license is incorrect'
        if 'url' in project_license:
            assert project_license['url'] in ('https://www.apache.org/licenses/LICENSE-2.0','https://www.apache.org/licenses/LICENSE-2.0.txt'), 'Project SBOM license URL is incorrect'
        session=[c for c in bom['components'] if c.get('group')=='org.springframework.session']
        assert len(session)==2 and all(c['version']=='4.1.1' for c in session)
        assert all(any(l.get('license',{}).get('name')=='Broadcom Foundation License' for l in c['licenses']) for c in session), 'Original upstream POM metadata must remain traceable; do not silently relabel components'
        dependencies = []
        for name in jar.namelist():
            if not name.startswith('BOOT-INF/lib/') or not name.endswith('.jar'):
                continue
            with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                notices = {n: hashlib.sha256(nested.read(n)).hexdigest() for n in nested.namelist() if not n.endswith('/') and n.upper().split('/')[-1].startswith(('LICENSE','NOTICE','COPYING','COPYRIGHT'))}
            dependencies.append({'artifact': name.split('/')[-1], 'deliveredLicenseAndNoticeFiles': notices})
        assert any(d['artifact'] == 'spring-session-core-4.1.1.jar' and d['deliveredLicenseAndNoticeFiles'] for d in dependencies)
        assert any(d['artifact'] == 'spring-session-jdbc-4.1.1.jar' and d['deliveredLicenseAndNoticeFiles'] for d in dependencies)
        return {'projectLicense': project_licenses, 'jarSha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(), 'runtimeComponents': len(bom['components']), 'nestedArtifacts': dependencies}


def inspect_image(image, expected_jar_sha, revision):
    config = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image], text=True))[0]
    labels = config['Config']['Labels'] or {}
    assert labels.get('org.opencontainers.image.licenses') == 'Apache-2.0'
    assert labels.get('org.opencontainers.image.source') == 'https://github.com/busystack/caldav-server'
    assert labels.get('org.opencontainers.image.revision') == revision, 'Image source revision differs from qualified revision'
    for name in DOCUMENTS:
        data = subprocess.check_output(['docker','run','--rm','--entrypoint','cat',image,'/opt/busystack/licenses/'+name])
        assert data == (ROOT/name).read_bytes(), 'Image missing/different ' + name
    checksum = subprocess.check_output(['docker','run','--rm','--entrypoint','sha256sum',image,'/opt/busystack/application.jar'],text=True).split()[0]
    assert checksum == expected_jar_sha, 'Host/container executable JAR bytes differ'
    subprocess.run(['docker','run','--rm','--entrypoint','sh',image,'-ec', 'test -r /opt/java/openjdk/legal/java.base/LICENSE; test -r /opt/java/openjdk/legal/java.base/ASSEMBLY_EXCEPTION; test -r /usr/share/doc/base-files/copyright; test -r /opt/busystack/licenses/runtime-dependency-inventory.json'],check=True)
    return {'id':config['Id'], 'labels':labels,'jarSha256':checksum, 'jdkLegalDirectory':'/opt/java/openjdk/legal','osLicenseDirectory':'/usr/share/doc'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',default=str(ROOT/'target/caldav-server-0.0.1-SNAPSHOT.jar'))
    parser.add_argument('--image',required=True)
    parser.add_argument('--revision',default=None)
    parser.add_argument('--output',default=str(ROOT/'target/qualification/distribution.json'))
    args=parser.parse_args()
    revision=args.revision or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    report={'recordedAt':datetime.now(timezone.utc).isoformat(), 'sourceRevision':revision,'status':'error'}
    try:
        assert hashlib.sha256((ROOT/'LICENSE').read_bytes()).hexdigest()==APACHE_SHA, 'Project LICENSE must be the unmodified official Apache text'
        source=subprocess.check_output(['git','archive','--format=tar',revision],cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(source)) as archive:
            for name in DOCUMENTS:
                assert archive.extractfile(name).read()==(ROOT/name).read_bytes(), 'Source archive missing/different '+name
        report['sourceArchiveSha256']=hashlib.sha256(source).hexdigest()
        report.update(inspect_jar(args.jar))
        report['image']=inspect_image(args.image,report['jarSha256'],revision)
        report['status']='passed'
    except (AssertionError,OSError,KeyError,ValueError,subprocess.CalledProcessError) as error:
        report['error']=str(error)
    destination=Path(args.output); destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='nestedArtifacts'},sort_keys=True))
    return 0 if report['status']=='passed' else 1

if __name__=='__main__':
    sys.exit(main())
