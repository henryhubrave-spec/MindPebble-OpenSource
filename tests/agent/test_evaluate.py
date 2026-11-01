import unittest
from evaluate import metrics


class EvaluationTests(unittest.TestCase):
    def test_missing_task_and_false_boundary_are_visible(self):
        expected = {'facts': [{'unit_id': 'a:0', 'type': 'task', 'quote': '明天交方案'}], 'paragraphs': [{'unit_ids': ['a:0', 'b:0']}]}
        actual = {'facts': [{'unit_id': 'b:0', 'type': 'decision', 'quote': '只是建议'}], 'paragraphs': [{'unit_ids': ['a:0']}, {'unit_ids': ['b:0']}]}
        result = metrics(expected, actual)
        self.assertEqual(result['fact_recall'], 0)
        self.assertEqual(result['unsupported_fact_ratio'], 1)
        self.assertEqual(result['task_recall'], 0)
        self.assertEqual(result['boundary_precision'], 0)

    def test_perfect_annotations(self):
        fixture = {'facts': [], 'paragraphs': [{'unit_ids': ['a:0']}]}
        self.assertEqual(metrics(fixture, fixture)['boundary_precision'], 1)
