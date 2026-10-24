"""Source-bound review recommendations must never become synthetic coverage."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("classification_review",TOOL / "review_classifications.py")
review = importlib.util.module_from_spec(spec);spec.loader.exec_module(review)


class ClassificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="classification-",dir=TOOL.parents[1] / ".local-private")
        self.root = Path(self.temp.name)
        self.source = self.root / "Sample.swift"
        self.source.write_text("let a: Int? = 1\nlet b = a!\n")
        start = self.source.read_bytes().index(b"a!\n")
        item = {"source":str(self.source),"sourceSHA256":review.sha(self.source),"kind":"forceUnwrapExpr",
                "startUTF8":start,"endUTF8":start+2,"sourceText":"a!","line":2,"reviewStatus":"pending"}
        identity = {"sourceSHA256":item["sourceSHA256"],"kind":item["kind"],"start":start,"end":start+2}
        item["classificationID"] = hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        self.inventory = self.root / "inventory.json"
        self.inventory.write_text(json.dumps({"registryDigest":"registry","sites":[item]}))
        self.report = self.root / "review.md"
        self.report.write_text(f'| 1 `{item["classificationID"][:12]}` | `Sample.swift:2` | Intrinsic candidate | Requires exact invariant/compiler proof. |\n')

    def tearDown(self):
        self.temp.cleanup()

    def test_binding_preserves_pending_proof_and_never_adds_hits(self):
        result = review.bind(self.inventory,self.report)
        self.assertEqual(result["status"],"classified-pending-required-proofs")
        self.assertFalse(result["sites"][0]["inventedHits"])
        self.assertFalse(result["sites"][0]["applicationGateApproved"])
        self.assertEqual(result["sites"][0]["sourceSHA256"],review.sha(self.source))

    def test_changed_source_duplicate_review_and_wrong_identity_fail(self):
        raw = self.source.read_bytes()
        self.source.write_bytes(raw+b" ")
        with self.assertRaisesRegex(ValueError,"source changed"):review.bind(self.inventory,self.report)
        self.source.write_bytes(raw)
        original = self.report.read_text();self.report.write_text(original+original)
        with self.assertRaisesRegex(ValueError,"Duplicate"):review.bind(self.inventory,self.report)
        self.report.write_text(original)
        inventory = json.loads(self.inventory.read_text());inventory["sites"][0]["endUTF8"] += 1
        self.inventory.write_text(json.dumps(inventory))
        with self.assertRaisesRegex(ValueError,"identity"):review.bind(self.inventory,self.report)


if __name__ == "__main__":unittest.main()
