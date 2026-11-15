import importlib.util
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("create_release_metadata.py")
SPEC = importlib.util.spec_from_file_location("create_release_metadata", SCRIPT_PATH)
METADATA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(METADATA)

EXPECTED = "30e9d23fb1a37a9d9dc1f117e2366d23a17186cf745b0570b7b909f75602fcd6"


class SignerSha256Tests(unittest.TestCase):
    def test_reads_standard_apksigner_stdout(self):
        output = f"Signer #1 certificate SHA-256 digest: {EXPECTED}\n"
        self.assertEqual(METADATA.signer_sha256(output, ""), EXPECTED)

    def test_reads_digest_from_stderr(self):
        output = f"Signer #1 certificate SHA-256 digest: {EXPECTED}\n"
        self.assertEqual(METADATA.signer_sha256("", output), EXPECTED)

    def test_accepts_colon_separated_hex_without_weakening_identity_check(self):
        colon_digest = ":".join(EXPECTED[index:index + 2] for index in range(0, len(EXPECTED), 2))
        output = f"Signer #1 certificate SHA-256 digest: {colon_digest}\n"
        self.assertEqual(METADATA.signer_sha256(output, ""), EXPECTED)

    def test_rejects_missing_or_malformed_digest(self):
        with self.assertRaises(ValueError):
            METADATA.signer_sha256("APK verified", "")
        with self.assertRaises(ValueError):
            METADATA.signer_sha256("Signer #1 certificate SHA-256 digest: not-a-digest", "")

    def test_rejects_multiple_signers(self):
        output = (
            f"Signer #1 certificate SHA-256 digest: {EXPECTED}\n"
            f"Signer #2 certificate SHA-256 digest: {EXPECTED}\n"
        )
        with self.assertRaises(ValueError):
            METADATA.signer_sha256(output, "")


if __name__ == "__main__":
    unittest.main()
