import tempfile
import unittest
from pathlib import Path

from plugin_runtime.errors import MetadataError
from plugin_runtime.metadata import parse_metadata_source, read_metadata


class MetadataTests(unittest.TestCase):
    def test_scan_does_not_execute_source(self):
        source = '__id__ = "safe_id"\n__name__ = "Safe"\nraise RuntimeError("executed")\n'
        metadata = parse_metadata_source(source)
        self.assertEqual("safe_id", metadata.id)

    def test_all_fields_and_legacy_min_version(self):
        metadata = parse_metadata_source(
            '__id__="plug_1"\n__name__="Имя"\n__description__="Описание"\n'
            '__author__="@author"\n__version__="2.3"\n__icon__="Pack/1"\n'
            '__min_version__="12.5.1"\n__sdk_version__=">=1.4"\n'
            '__requirements__=["tinydb>=4", "mpmath"]\n'
        )
        self.assertEqual(">=12.5.1", metadata.app_version)
        self.assertEqual(("tinydb>=4", "mpmath"), metadata.requirements)

    def test_app_version_wins_over_min_version(self):
        value = parse_metadata_source(
            '__id__="plug"\n__name__="Plug"\n__app_version__=">=13"\n__min_version__="10"'
        )
        self.assertEqual(">=13", value.app_version)

    def test_legacy_min_version_keeps_existing_operator(self):
        value = parse_metadata_source(
            '__id__="plug"\n__name__="Plug"\n__min_version__=">=11.12.0"'
        )
        self.assertEqual(">=11.12.0", value.app_version)
        self.assertEqual(">=11.12.0", value.min_version)

    def test_dynamic_and_nonlegacy_ast_are_rejected(self):
        cases = [
            '__id__ = "a" + "b"\n__name__="X"',
            '__id__: str = "ab"\n__name__="X"',
            '__id__="ab"\n__name__="X"\n__requirements__=("x",)',
        ]
        for source in cases:
            with self.subTest(source=source), self.assertRaises(MetadataError):
                parse_metadata_source(source)

    def test_utf8_bom_crlf_and_multiline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "bom.plugin")
            path.write_bytes(
                ('__id__="bom_plugin"\r\n__name__="Тест"\r\n'
                 '__description__="""строка\nдва"""\r\n').encode("utf-8-sig")
            )
            self.assertEqual("Тест", read_metadata(path).name)

    def test_id_validation(self):
        for value in ("1bad", "a", "bad id", "a" * 33):
            with self.subTest(value=value), self.assertRaises(MetadataError):
                parse_metadata_source(f'__id__={value!r}\n__name__="X"')


if __name__ == "__main__":
    unittest.main()
