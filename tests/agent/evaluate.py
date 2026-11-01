#!/usr/bin/env python3
"""Compare JSON evidence annotations with pipeline JSON; no model or private audio upload."""
import argparse
import json
from pathlib import Path


def metrics(expected, actual):
    def facts(data):
        return {(f['unit_id'], f['type'], f['quote'].strip()) for f in data['facts']}
    truth, predicted = facts(expected), facts(actual)
    matched = truth & predicted
    def boundaries(data):
        return {p['unit_ids'][-1] for p in data['paragraphs'][:-1]}
    wanted, found = boundaries(expected), boundaries(actual)
    tasks = {f for f in truth if f[1] == 'task'}
    proposed = {f for f in predicted if f[1] == 'task'}
    def ratio(n, d, empty=1.0):
        return n / d if d else empty
    return {
        'fact_recall': ratio(len(matched), len(truth)),
        'unsupported_fact_ratio': ratio(len(predicted - truth), len(predicted), 0.0),
        'boundary_precision': ratio(len(wanted & found), len(found), float(not wanted)),
        'boundary_recall': ratio(len(wanted & found), len(wanted)),
        'task_precision': ratio(len(tasks & proposed), len(proposed), float(not tasks)),
        'task_recall': ratio(len(tasks & proposed), len(tasks)),
        'note': 'Exact unit/type/quote matching; human review is still required for entailment and paraphrase quality.',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--expected', required=True, type=Path)
    parser.add_argument('--actual', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(metrics(json.loads(args.expected.read_text()), json.loads(args.actual.read_text())), ensure_ascii=False, indent=2))
