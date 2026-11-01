#!/usr/bin/env python3
"""Explicit tested-artifact promotion. Stage first; publish only the verified staged assets."""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPO = 'busystack/caldav-server'
REGISTRY = 'ghcr.io/' + REPO
REQUIRED = {'release-tools', 'application', 'dependency-audit'}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


candidate = module('candidate', 'release-candidate.py')
bundle = module('bundle', 'release-bundle.py')


def command(*args):
    return subprocess.check_output(list(args), text=True).strip()


def api(path, missing=False, method='GET', fields=None):
    arguments = ['gh', 'api', '--method', method, path]
    for key, value in (fields or {}).items():
        arguments += ['-f', f'{key}={value}']
    result = subprocess.run(arguments, capture_output=True, text=True)
    if result.returncode:
        if missing and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError('GitHub API failed: ' + path + ': ' + result.stderr[:500])
    return json.loads(result.stdout) if result.stdout else None


def verification(run, jobs, sha):
    assert run['repository']['full_name'] == REPO and run['head_repository']['full_name'] == REPO, 'Wrong repository'
    assert run['path'] == '.github/workflows/ci.yml' and run['name'] == 'verify', 'Wrong verification workflow'
    assert run['status'] == 'completed' and run['conclusion'] == 'success', 'Verification not successful'
    assert run['head_sha'] == sha, 'Wrong verification SHA'
    assert run['head_branch'] == 'main' and run['event'] in ('push', 'workflow_dispatch'), 'Untrusted verification source'
    by_name = {job['name']: job for job in jobs}
    assert REQUIRED <= by_name.keys(), 'Missing required job'
    assert all(by_name[name]['status'] == 'completed' and by_name[name]['conclusion'] == 'success' for name in REQUIRED), 'Required job failed/incomplete'


def verify_run(run_id, sha):
    run = api(f'repos/{REPO}/actions/runs/{run_id}')
    jobs = []
    page = 1
    while True:
        data = api(f'repos/{REPO}/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs?per_page=100&page={page}')
        jobs.extend(data['jobs'])
        if len(data['jobs']) < 100:
            break
        page += 1
    verification(run, jobs, sha)
    return run


def download(run_id, name, destination):
    data = api(f'repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100')
    matches = [item for item in data['artifacts'] if item['name'] == name and not item['expired']]
    assert len(matches) == 1, 'Missing/ambiguous artifact: ' + name
    subprocess.run(['gh', 'run', 'download', str(run_id), '--repo', REPO, '--name', name, '--dir', str(destination)], check=True)


def manifest_digest(tag, anonymous=False):
    # Documented OCI distribution authentication. Never print the registry bearer token.
    query = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': 'repository:' + REPO + ':pull'})
    headers = {}
    if not anonymous:
        credential = os.environ['GITHUB_ACTOR'] + ':' + os.environ['GH_TOKEN']
        headers['Authorization'] = 'Basic ' + base64.b64encode(credential.encode()).decode()
    token_request = urllib.request.Request('https://ghcr.io/token?' + query, headers=headers)
    with urllib.request.urlopen(token_request, timeout=60) as response:
        token = json.load(response)['token']
    headers = {'Authorization': 'Bearer ' + token,
               'Accept': 'application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'}
    try:
        request = urllib.request.Request('https://ghcr.io/v2/' + REPO + '/manifests/' + tag, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            digest = response.headers['Docker-Content-Digest']
            assert re.fullmatch(r'sha256:[0-9a-f]{64}', digest)
            return digest
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def matching_version(existing, expected):
    assert existing is None or existing == expected, 'Conflicting existing version; refusing overwrite'


def journal(path, operation, details):
    record = {'operation': operation, **details}
    with Path(path).open('a') as output:
        output.write(json.dumps(record, sort_keys=True) + '\n')
    print(json.dumps(record, sort_keys=True), flush=True)


def check_release(sha, digest=None):
    tag = api(f'repos/{REPO}/git/ref/tags/v0.0.1', missing=True)
    if tag:
        target = tag['object']
        if target['type'] == 'tag':
            target = api(f'repos/{REPO}/git/tags/{target["sha"]}')['object']
        assert target['type'] == 'commit' and target['sha'] == sha, 'Conflicting tag'
    release = api(f'repos/{REPO}/releases/tags/v0.0.1', missing=True)
    if release:
        assert tag, 'Release without exact tag'
        if not release['draft']:
            assert release.get('immutable') is True, 'Existing release is not immutable'
    return tag, release


def check_client_receipt(jar_sha):
    path = ROOT / 'qualification/evidence/releases/0.0.1/official-dependencies/verification.json'
    receipt = json.loads(path.read_text())
    assert receipt['jarSha256'] == jar_sha, 'Client qualification belongs to another executable'
    assert receipt['status'] == 'passed'
    assert set(receipt['clients']) == {'thunderbird-system-vancouver', 'thunderbird-explicit-vancouver', 'thunderbird-explicit-berlin', 'gnome-vancouver'}
    assert all(result['status'] == 'passed' for result in receipt['clients'].values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True, choices=['0.0.1'])
    parser.add_argument('--verification-run', type=int, required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--phase', choices=['stage', 'publish'], required=True)
    parser.add_argument('--stage-run', type=int)
    parser.add_argument('--work', required=True)
    args = parser.parse_args()
    assert os.environ['GITHUB_REPOSITORY'] == REPO
    assert os.environ['GITHUB_REF'] == 'refs/heads/main', 'Dispatch publication from main'
    assert args.source_sha == os.environ['GITHUB_SHA'] == command('git', 'rev-parse', 'HEAD'), 'Dispatch/source mismatch'
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=False)
    log = work / 'operations.jsonl'
    run = verify_run(args.verification_run, args.source_sha)
    artifact_name = f'verified-candidate-{run["id"]}-{run["run_attempt"]}'
    source = work / 'candidate'
    download(run['id'], artifact_name, source)
    record = candidate.validate(source)
    assert record['ciRun'] == run['id'] and record['ciAttempt'] == run['run_attempt'] and record['sourceCommit'] == args.source_sha
    check_client_receipt(record['files']['caldav-server-0.0.1.jar'])
    check_release(args.source_sha)
    subprocess.run(['docker', 'image', 'load', '--input', str(source / 'container.tar.gz')], check=True)
    config = json.loads(command('docker', 'image', 'inspect', record['imageId']))[0]
    assert config['Id'] == record['imageId'] and config['Architecture'] == 'amd64' and config['Os'] == 'linux'
    assert config['Config']['Labels']['org.opencontainers.image.revision'] == args.source_sha
    assert command('docker', 'run', '--rm', '--entrypoint', 'sha256sum', record['imageId'], '/opt/busystack/application.jar').split()[0] == record['files']['caldav-server-0.0.1.jar']
    if args.phase == 'stage':
        staging = f'candidate-{run["id"]}-{run["run_attempt"]}'
        subprocess.run(['docker', 'tag', record['imageId'], REGISTRY + ':' + staging], check=True)
        # After the first push the linked package exists; retries never change staged content.
        package = api('orgs/busystack/packages/container/caldav-server', missing=True)
        if package:
            prior = manifest_digest(staging)
            if prior:
                remote = command('docker', 'manifest', 'inspect', REGISTRY + '@' + prior)
                assert json.loads(remote)['config']['digest'] == record['imageId'], 'Conflicting stage image'
        subprocess.run(['docker', 'push', REGISTRY + ':' + staging], check=True)
        digest = manifest_digest(staging)
        assert digest
        journal(log, 'staged-registry-image', {'digest': digest, 'imageId': record['imageId']})
        matching_version(manifest_digest(args.version), digest)
        payload = work / 'assets'
        manifest = bundle.build(source, digest, payload)
        # This helper extracts and exercises the exact archive, with no checkout mounts.
        subprocess.run(['python3', str(ROOT / 'scripts/verify-installation-bundle.py'), '--assets', str(payload), '--output', str(work / 'installation.json')], check=True)
        candidate.write_json(work / 'stage.json', {'sourceCommit': args.source_sha, 'verificationRun': run['id'],
                             'verificationAttempt': run['run_attempt'], 'registryDigest': digest,
                             'assets': {p.name: candidate.sha256(p) for p in payload.iterdir()},
                             'installationSha256': candidate.sha256(work / 'installation.json')})
        journal(log, 'staged-installation-passed', {'digest': digest, 'assets': manifest['assets']})
        return
    assert args.stage_run, 'Publish requires an exact successful staging run'
    stage_run = api(f'repos/{REPO}/actions/runs/{args.stage_run}')
    assert stage_run['repository']['full_name'] == REPO and stage_run['head_repository']['full_name'] == REPO
    assert stage_run['path'] == '.github/workflows/release.yml' and stage_run['event'] == 'workflow_dispatch'
    assert stage_run['head_sha'] == args.source_sha and stage_run['status'] == 'completed' and stage_run['conclusion'] == 'success'
    staged = work / 'staged'
    download(args.stage_run, f'release-stage-{args.stage_run}-{stage_run["run_attempt"]}', staged)
    stage = json.loads((staged / 'stage.json').read_text())
    assert stage['sourceCommit'] == args.source_sha and stage['verificationRun'] == run['id'] and stage['verificationAttempt'] == run['run_attempt']
    payload = staged / 'assets'
    assert {p.name: candidate.sha256(p) for p in payload.iterdir()} == stage['assets'], 'Staged asset checksum mismatch'
    assert candidate.sha256(staged / 'installation.json') == stage['installationSha256']
    assert json.loads((staged / 'installation.json').read_text())['status'] == 'passed'
    digest = stage['registryDigest']
    matching_version(manifest_digest(args.version), digest)
    assert manifest_digest(digest) == digest
    assert manifest_digest(digest, anonymous=True) == digest, 'Package must be public before version publication'
    immutable = api(f'repos/{REPO}/immutable-releases')
    assert immutable['enabled'], 'Enable immutable releases before publishing'
    tag, release = check_release(args.source_sha)
    if not tag:
        api(f'repos/{REPO}/git/refs', method='POST', fields={'ref': 'refs/tags/v0.0.1', 'sha': args.source_sha})
    journal(log, 'exact-tag-created-or-matched', {'sha': args.source_sha})
    subprocess.run(['docker', 'tag', record['imageId'], REGISTRY + ':' + args.version], check=True)
    subprocess.run(['docker', 'push', REGISTRY + ':' + args.version], check=True)
    assert manifest_digest(args.version) == digest
    journal(log, 'version-image-created-or-matched', {'digest': digest})
    if not release:
        subprocess.run(['gh', 'release', 'create', 'v0.0.1', '--repo', REPO, '--verify-tag', '--draft', '--title', 'BusyStack CalDAV Server 0.0.1', '--notes-file', str(ROOT / 'docs/releases/0.0.1.md')], check=True)
        release = api(f'repos/{REPO}/releases/tags/v0.0.1')
    expected = stage['assets']
    existing = {asset['name']: asset for asset in release['assets']}
    assert set(existing) <= set(expected), 'Unexpected release asset'
    for name, asset in existing.items():
        assert asset.get('digest') == 'sha256:' + expected[name], 'Conflicting release asset: ' + name
    if release['draft']:
        for name in sorted(set(expected) - set(existing)):
            subprocess.run(['gh', 'release', 'upload', 'v0.0.1', str(payload / name), '--repo', REPO], check=True)
    remote = api(f'repos/{REPO}/releases/tags/v0.0.1')
    assert {asset['name']: asset.get('digest') for asset in remote['assets']} == {name: 'sha256:' + sha for name, sha in expected.items()}
    journal(log, 'draft-assets-verified', {'assets': expected})
    if remote['draft']:
        subprocess.run(['gh', 'release', 'edit', 'v0.0.1', '--repo', REPO, '--draft=false', '--latest'], check=True)
    published = api(f'repos/{REPO}/releases/tags/v0.0.1')
    assert published['immutable'] and not published['draft']
    journal(log, 'immutable-release-published', {'url': published['html_url'], 'digest': digest})
    subprocess.run(['python3', str(ROOT / 'scripts/verify-installation-bundle.py'), '--public', '--output', str(work / 'anonymous-installation.json')], check=True)
    journal(log, 'anonymous-installation-passed', {'sha': args.source_sha, 'digest': digest})


if __name__ == '__main__':
    main()
