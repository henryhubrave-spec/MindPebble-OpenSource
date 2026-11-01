import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from legacy_adapter import migrate_legacy_metadata

# Explicitly NOT audio. Used only for protocol/schema tests, never media-validity tests.
PAYLOAD=b'SYNTHETIC-CONTRACT-FIXTURE-NOT-AUDIO'

def load(path): return json.loads((ROOT/path).read_text(encoding='utf-8'))
def validate(value,schema): Draft202012Validator(load(schema),format_checker=FormatChecker()).validate(value)

class ContractTests(unittest.TestCase):
    def setUp(self): self.legacy=load('examples/legacy-metadata.synthetic.json')
    def test_schemas_are_valid(self):
        for p in ROOT.glob('*.schema.json'):Draft202012Validator.check_schema(json.loads(p.read_text()))
    def test_valid_migration_preserves_source_and_identity(self):
        before=copy.deepcopy(self.legacy)
        a=migrate_legacy_metadata(self.legacy,PAYLOAD)
        validate(a,'segment-manifest.v2.schema.json')
        self.assertEqual(before,self.legacy)
        self.assertEqual(a['segment_id'],migrate_legacy_metadata(self.legacy,PAYLOAD)['segment_id'])
        self.assertEqual(a['audio']['sha256'],hashlib.sha256(PAYLOAD).hexdigest())
        self.assertEqual(a['capture']['elapsed_ms'],3000)
    def test_different_payload_changes_hash_without_changing_segment_identity(self):
        first=migrate_legacy_metadata(self.legacy,PAYLOAD)
        second=migrate_legacy_metadata(self.legacy,b'X'*len(PAYLOAD))
        self.assertEqual(first['segment_id'],second['segment_id'])
        self.assertNotEqual(first['audio']['sha256'],second['audio']['sha256'])
        # Ingest must treat this pair as a conflict, not a retry overwrite.
    def test_truncation_rejected(self):
        with self.assertRaises(ValueError):migrate_legacy_metadata(self.legacy,PAYLOAD[:-1])
    def test_unsafe_and_pending_names_rejected(self):
        for name in ('../secret.m4a','/tmp/a.m4a','a.m4a.part','a/clip.m4a','a\\clip.m4a','a..m4a'):
            m=copy.deepcopy(self.legacy);m['audio_file']=name
            with self.subTest(name=name),self.assertRaises(ValueError):migrate_legacy_metadata(m,PAYLOAD)
    def test_unknown_legacy_version_rejected(self):
        self.legacy['schema_version']=99
        with self.assertRaises(ValueError):migrate_legacy_metadata(self.legacy,PAYLOAD)
    def test_wall_clock_rollback_does_not_rewrite_monotonic_duration(self):
        self.legacy['ended_at_epoch_ms']=self.legacy['started_at_epoch_ms']-30000
        value=migrate_legacy_metadata(self.legacy,PAYLOAD)
        validate(value,'segment-manifest.v2.schema.json')
        self.assertTrue(value['capture']['clock_discontinuity'])
        self.assertEqual(value['capture']['elapsed_ms'],3000)
    def test_unknown_v2_and_missing_digest_rejected(self):
        for field in ('schema_version','audio_hash'):
            v=migrate_legacy_metadata(self.legacy,PAYLOAD)
            if field=='schema_version':v[field]=99
            else:del v['audio']['sha256']
            with self.subTest(field=field),self.assertRaises(ValidationError):validate(v,'segment-manifest.v2.schema.json')
    def test_uuid_and_hash_formats_rejected(self):
        v=migrate_legacy_metadata(self.legacy,PAYLOAD);v['segment_id']='not-a-uuid'
        with self.assertRaises(ValidationError):validate(v,'segment-manifest.v2.schema.json')
        v=migrate_legacy_metadata(self.legacy,PAYLOAD);v['audio']['sha256']='not-a-hash'
        with self.assertRaises(ValidationError):validate(v,'segment-manifest.v2.schema.json')
    def test_analysis_sources_required(self):
        result=load('examples/analysis-result.synthetic.json')
        validate(result,'analysis-result.v1.schema.json')
        result['claims'][0]['sources']=[]
        with self.assertRaises(ValidationError):validate(result,'analysis-result.v1.schema.json')
    def test_schema_does_not_accept_boolean_as_capture_duration(self):
        self.legacy['capture_elapsed_ms']=True
        with self.assertRaises(ValueError):migrate_legacy_metadata(self.legacy,PAYLOAD)
    def test_receipt_names_actual_durability_level(self):
        manifest=migrate_legacy_metadata(self.legacy,PAYLOAD)
        receipt={'schema_version':1,'receipt_id':'55555555-5555-4555-8555-555555555555','receiver_id':'66666666-6666-4666-8666-666666666666','segment_id':manifest['segment_id'],'audio_sha256':manifest['audio']['sha256'],'level':'phone_durable','stored_at':'2026-01-01T00:00:00Z'}
        validate(receipt,'durability-receipt.v1.schema.json')
        receipt['level']='http_200'
        with self.assertRaises(ValidationError):validate(receipt,'durability-receipt.v1.schema.json')

if __name__=='__main__':unittest.main()
