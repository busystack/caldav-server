#!/usr/bin/env python3
"""Pinned Trivy OS-package scan, with current feed metadata and no advisory suppression."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

VERSION = '0.76.1'
ARCHIVE_SHA256 = 'c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0'
URL = f'https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/trivy_{VERSION}_Linux-64bit.tar.gz'
ROOT = Path(__file__).resolve().parents[1]


def scanner_environment():
    # Operator shells must not silently narrow this required qualification scan.
    return {key:value for key,value in os.environ.items() if not key.startswith('TRIVY_')}


def analyze(document, feed, now):
    updated = datetime.fromisoformat(feed['UpdatedAt'].replace('Z', '+00:00'))
    if not now - timedelta(hours=48) <= updated <= now + timedelta(hours=2):
        raise ValueError('Vulnerability database is stale or future dated')
    if not document.get('Metadata', {}).get('OS', {}).get('Family'):
        raise ValueError('Scanner did not identify the image operating system')
    results = [value for value in document.get('Results', []) if value.get('Class') == 'os-pkgs']
    if not results or not any(value.get('Packages') for value in results):
        raise ValueError('OS package inventory is missing; empty output is not a clean scan')
    findings = []
    for result in results:
        for item in result.get('Vulnerabilities') or []:
            if item.get('Severity') not in ('UNKNOWN','LOW','MEDIUM','HIGH','CRITICAL'):
                raise ValueError('Scanner advisory severity is missing or unrecognized')
            findings.append({key: item.get(key) for key in ('VulnerabilityID','PkgName','InstalledVersion','FixedVersion','Severity','Status','PrimaryURL','Title')})
    for finding in findings:
        finding['disposition'] = ('blocking; update or individually justified resolution required' if finding['Severity'] in ('HIGH','CRITICAL','UNKNOWN') else 'reported below the documented HIGH/CRITICAL/UNKNOWN gate; ' + ('upstream fixed version is recorded' if finding.get('FixedVersion') else 'no fixed version reported by this feed'))
    blocking = [value for value in findings if value['Severity'] in ('HIGH','CRITICAL','UNKNOWN')]
    return {'os': document['Metadata']['OS'], 'packageCount': sum(len(r['Packages']) for r in results),
            'findings': findings, 'blockingFindings': len(blocking),
            'gate': 'HIGH, CRITICAL or UNKNOWN fail, including unfixed findings; all other severities remain reported',
            'suppressions': [], 'database': feed, 'status': 'advisories-returned' if blocking else 'passed'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image',required=True)
    parser.add_argument('--output',default=str(ROOT/'target/container-os-advisories.json'))
    parser.add_argument('--cache-dir',default=str(ROOT/'target/trivy-cache'))
    args=parser.parse_args()
    output=Path(args.output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
    cache_dir=Path(args.cache_dir).resolve()
    report={'status':'error','recordedAt':datetime.now(timezone.utc).isoformat(),'scanner':{'name':'Trivy','version':VERSION,'archiveSha256':ARCHIVE_SHA256},'coverage':'final-container OS packages; separate from Maven/JAR advisories'}
    try:
        image=json.loads(subprocess.check_output(['docker','image','inspect',args.image],text=True))[0]
        report['image']={'id':image['Id'],'repoDigests':image.get('RepoDigests',[]),'labels':image['Config'].get('Labels')}
        with tempfile.TemporaryDirectory(prefix='busystack-os-scan-') as temporary:
            directory=Path(temporary); archive=directory/'scanner.tar.gz'
            with urllib.request.urlopen(URL,timeout=60) as response, archive.open('wb') as destination:
                while chunk:=response.read(1024*1024): destination.write(chunk)
            if hashlib.sha256(archive.read_bytes()).hexdigest()!=ARCHIVE_SHA256:
                raise ValueError('Scanner download checksum mismatch')
            with tarfile.open(archive) as source: binary=source.extractfile('trivy').read()
            scanner=directory/'trivy';scanner.write_bytes(binary);scanner.chmod(0o700)
            scanner_env=scanner_environment()
            empty_config=directory/'scan-config.yaml';empty_config.write_text('{}\n')
            empty_ignore=directory/'scan.ignore';empty_ignore.write_text('')
            actual=json.loads(subprocess.check_output([str(scanner),'version','--format','json'],text=True,env=scanner_env,cwd=directory))
            if actual.get('Version')!=VERSION: raise ValueError('Scanner version mismatch')
            raw=output.with_name(output.stem+'-raw.json')
            subprocess.run([str(scanner),'image','--image-src','docker','--scanners','vuln','--pkg-types','os','--config',str(empty_config),'--ignorefile',str(empty_ignore),'--ignore-unfixed=false','--severity','UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL','--list-all-pkgs','--no-progress','--format','json','--output',str(raw),'--cache-dir',str(cache_dir),'--timeout','10m',image['Id']],check=True,timeout=660,env=scanner_env,cwd=directory)
            feed_path=cache_dir/'db/metadata.json'
            feed=json.loads(feed_path.read_text())
            db=feed_path.with_name('trivy.db')
            report.update(analyze(json.loads(raw.read_text()),feed,datetime.now(timezone.utc)))
            report['scannerConfiguration']={'environment':'TRIVY_* overrides removed','config':'empty private config','ignoreFile':'empty private ignore file','ignoreUnfixed':False,'includedSeverities':['UNKNOWN','LOW','MEDIUM','HIGH','CRITICAL']}
            with db.open('rb') as source:
                report['databaseSha256']=hashlib.file_digest(source,'sha256').hexdigest()
            report['rawReportSha256']=hashlib.sha256(raw.read_bytes()).hexdigest()
    except (OSError,ValueError,KeyError,subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
        report['status']='error';report['error']=str(error)
    output.write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='findings'},sort_keys=True))
    return 2 if report['status']=='error' else 1 if report['status']=='advisories-returned' else 0

if __name__=='__main__': sys.exit(main())
