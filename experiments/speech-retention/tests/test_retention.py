import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from retention import retained_intervals

class RetentionTests(unittest.TestCase):
    def test_silence_has_no_media(self):self.assertEqual(retained_intervals([False]*100),[])
    def test_continuous_speech_is_complete(self):self.assertEqual(retained_intervals([True]*100),[(0,2000)])
    def test_pre_and_post_roll(self):
        self.assertEqual(retained_intervals([False]*50+[True]*10+[False]*100),[(500,2000)])
    def test_first_syllable_and_tail_clamped(self):
        self.assertEqual(retained_intervals([True]+[False]*200+ [True]),[(0,820),(3520,4040)])
    def test_short_pause_is_merged(self):
        self.assertEqual(retained_intervals([True]*10+[False]*50+[True]*10),[(0,1400)])
    def test_long_pause_is_removed(self):
        self.assertEqual(retained_intervals([True]*10+[False]*200+[True]*10),[(0,1000),(3700,4400)])
    def test_mark_protected_even_without_detected_speech(self):
        self.assertEqual(retained_intervals([False]*100,protected_intervals=[(500,1500)]),[(500,1500)])
    def test_uncertainty_is_retained(self):self.assertEqual(retained_intervals([None]*10),[(0,200)])
    def test_last_partial_frame_never_exceeds_source(self):
        self.assertEqual(retained_intervals([True]*10,total_ms=181.5),[(0,181.5)])
    def test_invalid_inputs_rejected(self):
        for kw in ({'frame_ms':15},{'pre_ms':-1},{'total_ms':999},{'protected_intervals':[(4,1)]}):
            with self.subTest(kw=kw),self.assertRaises(ValueError):retained_intervals([True],**kw)
    def test_output_stays_ordered_and_bounded(self):
        import random
        rng=random.Random(42)
        for _ in range(100):
            flags=[rng.random()<.03 for _ in range(1000)]
            spans=retained_intervals(flags)
            self.assertTrue(all(0<=a<b<=20000 for a,b in spans))
            self.assertTrue(all(spans[i][1]<spans[i+1][0] for i in range(len(spans)-1)))

if __name__=='__main__':unittest.main()
