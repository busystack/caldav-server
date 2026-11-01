"""Release identity comes only from the selected source's Maven project.version."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'busystack/caldav-server'
STABLE = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'


def stable(value):
    if not isinstance(value, str) or not re.fullmatch(STABLE, value):
        raise ValueError('Expected a stable Maven project.version X.Y.Z without leading zeros: ' + repr(value))
    return value


def from_pom(data):
    project = ET.fromstring(data)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    if (project.findtext('m:groupId', namespaces=ns), project.findtext('m:artifactId', namespaces=ns)) != ('org.busystack', 'caldav-server'):
        raise ValueError('Unexpected Maven project coordinates')
    return stable(project.findtext('m:version', namespaces=ns))


def revision(value='HEAD', root=ROOT):
    sha = subprocess.check_output(['git', '-C', str(root), 'rev-parse', '--verify', value + '^{commit}'], text=True).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected an exact Git source revision')
    return sha


def resolve(root=ROOT, source=None, requested=None):
    data = (Path(root) / 'pom.xml').read_bytes() if source is None else subprocess.check_output(
        ['git', '-C', str(root), 'show', revision(source, root) + ':pom.xml'])
    version = from_pom(data)
    if requested is not None and stable(requested) != version:
        raise ValueError(f'Requested version {requested} differs from qualified Maven project.version {version}')
    return version


def tag(version):
    return 'v' + stable(version)


def candidate_tag(version, value):
    stable(version)
    if not value or len(value) > 128 or not re.fullmatch(re.escape(version) + r'-rc\.[1-9][0-9]*', value):
        raise ValueError('Expected a release candidate tag such as ' + version + '-rc.1')
    return value


def jar_name(version):
    return 'caldav-server-' + stable(version) + '.jar'


def deployment_name(version):
    return 'caldav-server-' + stable(version) + '-deploy.tar.gz'


def receipt_path(version):
    return 'qualification/evidence/releases/' + stable(version) + '/verification.json'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-sha')
    parser.add_argument('--requested')
    parser.add_argument('--candidate')
    parser.add_argument('--format', choices=('version', 'tag', 'jar', 'deployment', 'receipt'), default='version')
    args = parser.parse_args()
    version = resolve(source=args.source_sha, requested=args.requested)
    print(candidate_tag(version, args.candidate) if args.candidate else {
        'version': lambda v: v, 'tag': tag, 'jar': jar_name,
        'deployment': deployment_name, 'receipt': receipt_path}[args.format](version))
