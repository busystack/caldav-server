"""The image ID and optional OCI descriptor are distinct identities."""
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('p0_build',Path(__file__).with_name('build.py'))
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)

class LocalImageIdentityTest(unittest.TestCase):
    def test_missing_descriptor_does_not_invent_registry_identity(self):
        self.assertIsNone(builder.descriptor_digest({'Id':'sha256:'+'1'*64}))

    def test_supplied_descriptor_is_retained(self):
        digest='sha256:'+'2'*64
        self.assertEqual(builder.descriptor_digest({'Descriptor':{'digest':digest}}),digest)

    def test_supplied_malformed_descriptor_is_not_silently_ignored(self):
        for value in ({}, {'digest':'invented'}, 'invalid'):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                builder.descriptor_digest({'Descriptor':value})
