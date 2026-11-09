"""A local image ID cannot become a registry digest or bypass public validation."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('local_package',Path(__file__).with_name('package.py'))
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)

class PackageIdentityTest(unittest.TestCase):
    def manifest(self):
        image='sha256:'+'1'*64
        return {'qualificationKind':'local-unpublished',
            'verification':{'status':'local-unpublished','run':None,'attempt':None,'url':None},
            'container':{'registryManifestDigest':None,'image':image,'localTestedImageId':image,'published':False}}

    def test_explicit_local_mode_retains_actual_image_identity(self):
        value=self.manifest()
        package.bundle.validate_container_identity(value,'0.2.0',allow_local_unpublished=True)
        self.assertIsNone(value['container']['registryManifestDigest'])

    def test_public_validator_never_accepts_missing_registry_digest(self):
        with self.assertRaises(AssertionError):
            package.bundle.validate_container_identity(self.manifest(),'0.2.0')

    def test_local_mode_rejects_false_publication_provenance_and_wrong_image(self):
        original=self.manifest()
        for field,value in [('qualificationKind','release'),('verification',{'status':'passed','run':1,'attempt':1,'url':'https://example.invalid'})]:
            manifest=copy.deepcopy(original);manifest[field]=value
            with self.subTest(field=field),self.assertRaises(AssertionError):
                package.bundle.validate_container_identity(manifest,'0.2.0',allow_local_unpublished=True)
        for field,value in [('published',True),('image','sha256:'+'2'*64),('localTestedImageId','fabricated')]:
            manifest=copy.deepcopy(original);manifest['container'][field]=value
            with self.subTest(field=field),self.assertRaises(AssertionError):
                package.bundle.validate_container_identity(manifest,'0.2.0',allow_local_unpublished=True)
