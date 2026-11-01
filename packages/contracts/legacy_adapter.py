"""Reference v1→v2 metadata adapter; no network, media decoding or in-place writes.

This does NOT establish media validity, receiver durability, or authenticate receipts.
Actual ingestion must probe/decode media and commit it before issuing a receipt.
"""
import hashlib
import re
from uuid import UUID, uuid5

SAFE_AUDIO_NAME = re.compile(r'^(?!.*\.\.)[A-Za-z0-9][A-Za-z0-9._+\-]*\.m4a$')

def migrate_legacy_metadata(meta, audio_bytes):
    if type(meta.get('schema_version')) is not int or meta['schema_version'] != 1:
        raise ValueError('Unsupported legacy schema')
    name = meta.get('audio_file', '')
    if not isinstance(name, str) or len(name) > 180 or not SAFE_AUDIO_NAME.fullmatch(name):
        raise ValueError('Unsafe or incomplete audio filename')
    if type(meta.get('bytes')) is not int or meta['bytes'] != len(audio_bytes) or not audio_bytes:
        raise ValueError('Byte length mismatch')
    for key in ('sequence','started_at_epoch_ms','ended_at_epoch_ms','capture_elapsed_ms'):
        if type(meta.get(key)) is not int or meta[key] < (1 if key == 'sequence' else 0):
            raise ValueError('Invalid ' + key)
    if meta.get('requested_codec') != 'aac' or meta.get('channels') != 1:
        raise ValueError('Unsupported legacy recording configuration')
    if meta.get('reason') not in ('segment','pause','stop','error','service_destroyed'):
        raise ValueError('Unsupported finalization reason')
    rate, bitrate = meta.get('requested_sample_rate_hz'), meta.get('requested_bit_rate')
    if type(rate) is not int or not 8000 <= rate <= 192000:
        raise ValueError('Unsupported sample rate')
    if type(bitrate) is not int or not 8000 <= bitrate <= 512000:
        raise ValueError('Unsupported bitrate')
    session = UUID(meta['session_id'])
    sequence = meta['sequence']
    duration = meta['capture_elapsed_ms']
    wall_duration = meta['ended_at_epoch_ms'] - meta['started_at_epoch_ms']
    return {
        'schema_version': 2,
        'session_id': str(session),
        'segment_id': str(uuid5(session, 'legacy-segment:' + str(sequence))),
        'sequence': sequence,
        'capture': {
            'started_at_epoch_ms': meta['started_at_epoch_ms'],
            'ended_at_epoch_ms': meta['ended_at_epoch_ms'],
            'elapsed_ms': duration,
            'clock_discontinuity': abs(wall_duration-duration) > 2000,
        },
        'audio': {
            'file': name,
            'byte_length': len(audio_bytes),
            'sha256': hashlib.sha256(audio_bytes).hexdigest(),
            'declared_codec': 'aac',
            'declared_container': 'mp4',
            'channels': 1,
            'requested_sample_rate_hz': rate,
            'requested_bit_rate': bitrate,
        },
        'finalization_reason': meta['reason'],
    }
