#!/usr/bin/env python3
"""Stage an explicitly unpublished local release in the production asset format."""
import argparse,gzip,hashlib,importlib.util,json,shutil,subprocess,sys,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts'))
import release_source,release_version
spec=importlib.util.spec_from_file_location('bundle',ROOT/'scripts/release-bundle.py');bundle=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundle)

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip();source=release_source.Snapshot(revision,ROOT);version=source.version
    config=json.loads(subprocess.check_output(['docker','image','inspect',args.image],text=True))[0]
    manifest_digest=config['Descriptor']['digest'];repository='ghcr.io/'+release_version.REPOSITORY
    # An owned local alias exercises the official image-reference shape without a push.
    alias=repository+':p0-local-'+revision[:12]
    subprocess.run(['docker','image','tag',args.image,alias],check=True)
    image=repository+':'+version+'@'+manifest_digest
    resolved=json.loads(subprocess.check_output(['docker','image','inspect',image],text=True))[0]
    assert resolved['Id']==config['Id'],'Local canonical digest must resolve to the tested image'
    runtime=config['Config']['Labels']['org.opencontainers.image.revision']
    for name in source.files:
        if name.startswith(('src/main/','licenses/')) or name in ('pom.xml','Dockerfile','LICENSE','NOTICE','THIRD-PARTY-NOTICES'):
            assert source.read(name)==subprocess.check_output(['git','show',runtime+':'+name],cwd=ROOT),'Runtime differs from image source: '+name
    jar=ROOT/'target'/release_version.jar_name(version);checksum,sbom=bundle.candidate.builder.inspect_jar(jar,version)
    actual=subprocess.check_output(['docker','run','--rm','--network','none','--entrypoint','sha256sum',args.image,'/opt/busystack/application.jar'],text=True).split()[0];assert actual==checksum
    args.output.mkdir(parents=True,exist_ok=False);assets=args.output/'assets';assets.mkdir()
    sbom_path=assets/'sbom.cdx.json';sbom_path.write_bytes(sbom)
    identity={'qualificationKind':'local-unpublished','version':version,'sourceCommit':revision,'runtimeCommit':runtime,'image':image,'imageId':config['Id'],'jarSha256':checksum,'publicDownloadAcceptance':'not-performed; no publication authorized'}
    compose=source.read('compose.yaml').decode();a=compose.index('    build:\n');b=compose.index('\n',compose.index('    image: ${BUSYSTACK_APPLICATION_IMAGE:',a))+1
    compose=compose[:a]+f'    image: {image}\n    platform: linux/amd64\n'+compose[b:]
    files,used,notices=bundle.deployment_files(source,version,compose,identity,sbom_path)
    name=release_version.deployment_name(version);archive=assets/name;archive.write_bytes(bundle.tar_bytes(files,f'caldav-server-{version}-deploy/'))
    (assets/'notices.tar.gz').write_bytes(bundle.tar_bytes(notices));shutil.copyfile(jar,assets/jar.name)
    asset_hashes={p.name:digest(p) for p in assets.iterdir()}
    manifest={**identity,'schemaVersion':2,'sourceTree':subprocess.check_output(['git','rev-parse',revision+'^{tree}'],text=True).strip(),
      'sourceSnapshotSha256':hashlib.sha256(gzip.compress(release_source.archive(revision,ROOT),mtime=0)).hexdigest(),
      'sourceInputs':{name:hashlib.sha256(source.read(name)).hexdigest() for name in sorted(used)},
      'deploymentFiles':{name:hashlib.sha256(data).hexdigest() for name,data in files.items()},
      'repository':'https://github.com/'+release_version.REPOSITORY,
      'verification':{'status':'local-unpublished','run':None,'attempt':None,'url':None},
      'container':{'image':image,'registryManifestDigest':manifest_digest,'localTestedImageId':config['Id'],'platform':'linux/amd64','published':False},
      'jar':{'name':jar.name,'sha256':checksum},'deployment':{'name':name,'sha256':digest(archive)},'assets':asset_hashes}
    manifest_file=assets/'release-manifest.json';manifest_file.write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    sums={**asset_hashes,'release-manifest.json':digest(manifest_file)}
    (assets/'SHA256SUMS').write_text(''.join(sha+'  '+name+'\n' for name,sha in sorted(sums.items())))
    bundle.validate_assets(assets,version,revision)
    with tarfile.open(archive) as packed:packed.extractall(args.output,filter='data')
    extracted=args.output/f'caldav-server-{version}-deploy'
    bundle.validate_guide({str(p.relative_to(extracted)):p.read_bytes() for p in extracted.rglob('*') if p.is_file()})
    print(json.dumps({'status':'validated-local-unpublished','source':revision,'runtime':runtime,'image':image,'deploymentSha256':digest(archive),'assets':str(assets),'directory':str(extracted)},indent=2))
if __name__=='__main__':main()
