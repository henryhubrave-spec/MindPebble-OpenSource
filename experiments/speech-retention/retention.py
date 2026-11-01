"""Conservative interval builder for an offline VAD experiment.

Input is per-frame speech decisions, NOT an amplitude threshold or a VAD model.
None means uncertainty and is retained. Never modifies source audio.
"""
import math

def retained_intervals(decisions, frame_ms=20, pre_ms=500, post_ms=800,
                       merge_ms=700, total_ms=None, protected_intervals=()):
    if frame_ms not in (10,20,30):
        raise ValueError('Expected 10/20/30ms frames')
    if any(type(v) is not int or v < 0 for v in (pre_ms,post_ms,merge_ms)):
        raise ValueError('Margins must be non-negative integer milliseconds')
    if any(v is not None and type(v) is not bool for v in decisions):
        raise ValueError('Frame decisions must be bool or None')
    max_duration=len(decisions)*frame_ms
    duration=max_duration if total_ms is None else total_ms
    if not isinstance(duration,(int,float)) or not math.isfinite(duration) or not 0 <= duration <= max_duration:
        raise ValueError('Invalid duration')
    spans=[]
    start=None
    for i,value in enumerate(list(decisions)+[False]):
        keep=i<len(decisions) and value is not False
        if keep and start is None:start=i*frame_ms
        if not keep and start is not None:
            a=max(0,start-pre_ms);b=min(duration,i*frame_ms+post_ms)
            if b>a:spans.append((a,b))
            start=None
    for a,b in protected_intervals:
        if not all(isinstance(x,(int,float)) and math.isfinite(x) for x in (a,b)) or b<a:
            raise ValueError('Invalid protected interval')
        a,b=max(0,a),min(duration,b)
        if b>a:spans.append((a,b))
    merged=[]
    for a,b in sorted(spans):
        if merged and a<=merged[-1][1]+merge_ms:
            merged[-1]=(merged[-1][0],max(merged[-1][1],b))
        else:merged.append((a,b))
    return merged
