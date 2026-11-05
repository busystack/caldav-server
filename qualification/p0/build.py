#!/usr/bin/env python3
"""Local qualification build with committed runtime inputs; leaves unrelated qualification edits intact.

The official release builder still requires a completely clean tree. This local helper
checks every runtime/build input against HEAD and records that distinction explicitly.
"""
import argparse,hashlib,importlib.util,json,shutil,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True);args=parser.parse_args()
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    dirty=subprocess.check_output(['git','status','--porcelain=v1','--untracked-files=all'],cwd=ROOT,text=True).splitlines()
    assert all(line[3:].startswith('qualification/') for line in dirty),'Commit implementation and documentation before a local qualification image build'
    names=subprocess.check_output(['git','ls-files','src/main','pom.xml','Dockerfile','.mvn','mvnw','LICENSE','NOTICE','THIRD-PARTY-NOTICES','licenses'],cwd=ROOT,text=True).splitlines()
    for name in names:assert (ROOT/name).read_bytes()==subprocess.check_output(['git','show',revision+':'+name],cwd=ROOT),'Uncommitted runtime input: '+name
    spec=importlib.util.spec_from_file_location('builder',ROOT/'scripts/build-image.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
    version=builder.versions.resolve(ROOT);jar=ROOT/'target'/builder.versions.jar_name(version);checksum,sbom=builder.inspect_jar(jar,version)
    context=ROOT/'target/p0-image-context'
    if context.exists():shutil.rmtree(context)
    inputs=context/'.image-input';inputs.mkdir(parents=True)
    shutil.copyfile(ROOT/'Dockerfile',context/'Dockerfile');shutil.copyfile(jar,inputs/'application.jar');(inputs/'runtime-dependency-inventory.json').write_bytes(sbom)
    for name in ('LICENSE','NOTICE','THIRD-PARTY-NOTICES'):shutil.copyfile(ROOT/name,inputs/name)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/licenses/') and not name.endswith('/'):
                target=inputs/'third-party'/name.removeprefix('META-INF/licenses/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
    (inputs/'version').write_text(version+'\n');(inputs/'revision').write_text(revision+'\n');(inputs/'SHA256SUMS').write_text(checksum+'  application.jar\n')
    subprocess.run(['docker','build','--platform','linux/amd64','--build-arg','APPLICATION_VERSION='+version,'--build-arg','SOURCE_REVISION='+revision,'--tag',args.image,str(context)],check=True)
    config=builder.identity(args.image,version,revision,checksum)
    proof={'qualificationKind':'local-unpublished','sourceCommit':revision,'imageId':config['Id'],'descriptorDigest':config['Descriptor']['digest'],'jarSha256':checksum,'runtimeInputs':{name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in names},'uncommittedQualificationPaths':[line[3:] for line in dirty]}
    (ROOT/'target/p0-image-inputs.json').write_text(json.dumps(proof,indent=2,sort_keys=True)+'\n');print(json.dumps({k:v for k,v in proof.items() if k!='runtimeInputs'},indent=2))
if __name__=='__main__':main()
