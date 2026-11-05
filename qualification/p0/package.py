#!/usr/bin/env python3
"""Build an explicitly unpublished local package using production payload selection."""
import argparse,hashlib,importlib.util,json,subprocess,sys,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts'))
import release_source,release_version
spec=importlib.util.spec_from_file_location('bundle',ROOT/'scripts/release-bundle.py');bundle=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundle)
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip();source=release_source.Snapshot(revision,ROOT);version=source.version
    config=json.loads(subprocess.check_output(['docker','image','inspect',args.image],text=True))[0]
    digest=config['Descriptor']['digest'];repo=args.image.split('@')[0].rsplit(':',1)[0]
    image=repo+':'+version+'@'+digest
    runtime=config['Config']['Labels']['org.opencontainers.image.revision']
    for name in source.files:
        if name.startswith(('src/main/','licenses/')) or name in ('pom.xml','Dockerfile','LICENSE','NOTICE','THIRD-PARTY-NOTICES'):
            assert source.read(name)==subprocess.check_output(['git','show',runtime+':'+name],cwd=ROOT), 'Runtime differs from image source: '+name
    jar=ROOT/'target'/release_version.jar_name(version);checksum,sbom=bundle.candidate.builder.inspect_jar(jar,version)
    actual=subprocess.check_output(['docker','run','--rm','--entrypoint','sha256sum',args.image,'/opt/busystack/application.jar'],text=True).split()[0];assert actual==checksum
    args.output.mkdir(parents=True,exist_ok=False);sbom_path=args.output/'sbom.cdx.json';sbom_path.write_bytes(sbom)
    identity={'qualificationKind':'local-unpublished','version':version,'sourceCommit':revision,'runtimeCommit':runtime,'image':image,'imageId':config['Id'],'jarSha256':checksum,'publicDownloadAcceptance':'not-performed; no publication authorized'}
    compose=source.read('compose.yaml').decode();a=compose.index('    build:\n');b=compose.index('\n',compose.index('    image: ${BUSYSTACK_APPLICATION_IMAGE:',a))+1
    compose=compose[:a]+f'    image: {image}\n    platform: linux/amd64\n'+compose[b:]
    files,_,_=bundle.deployment_files(source,version,compose,identity,sbom_path)
    name=release_version.deployment_name(version);archive=args.output/name;archive.write_bytes(bundle.tar_bytes(files,f'caldav-server-{version}-deploy/'))
    identity['deploymentFiles']={name:hashlib.sha256(data).hexdigest() for name,data in files.items()}
    manifest=args.output/'release-manifest.json';manifest.write_text(json.dumps(identity,indent=2,sort_keys=True)+'\n')
    (args.output/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in (archive,manifest)))
    with tarfile.open(archive) as packed:packed.extractall(args.output,filter='data')
    extracted=args.output/f'caldav-server-{version}-deploy'
    bundle.validate_guide({str(p.relative_to(extracted)):p.read_bytes() for p in extracted.rglob('*') if p.is_file()})
    print(json.dumps({'status':'created-local-unpublished','source':revision,'runtime':runtime,'image':image,'deploymentSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'directory':str(extracted)},indent=2))
if __name__=='__main__':main()
