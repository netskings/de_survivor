import unittest

from extera_utils.text_formatting import (
    HTML,
    Markdown,
    RawEntity,
    TLEntityType,
    add_surrogates,
    remove_surrogates,
)
from plugin_runtime import services


class TextFormattingTests(unittest.TestCase):
    def tearDown(self):
        services.clear()

    def test_surrogate_helpers_expose_utf16_code_units(self):
        value = "a😀b"
        surrogate_value = add_surrogates(value)
        self.assertEqual(4, len(surrogate_value))
        self.assertEqual(value, remove_surrogates(surrogate_value))
        self.assertEqual("\U0001f600", remove_surrogates("\U0001f600"))

    def test_markdown_nested_link_code_and_utf16_offsets(self):
        parsed = Markdown.parse("😀 *bold _it_* [site](https://example.com) `x`")
        self.assertEqual("😀 bold it site x", parsed["text"])
        kinds = [entity.type for entity in parsed["entities"]]
        self.assertEqual(
            [
                TLEntityType.BOLD,
                TLEntityType.ITALIC,
                TLEntityType.TEXT_LINK,
                TLEntityType.CODE,
            ],
            kinds,
        )
        self.assertEqual(3, parsed["entities"][0].offset)
        self.assertEqual(7, parsed["entities"][0].length)

    def test_pre_custom_emoji_and_tlrpc_bridge(self):
        parsed = Markdown.parse("[🙂](123) ```python\nprint(1)```")
        self.assertEqual(TLEntityType.CUSTOM_EMOJI, parsed["entities"][0].type)
        self.assertEqual(123, parsed["entities"][0].document_id)
        self.assertEqual("python", parsed["entities"][1].language)
        services.configure(raw_entity_to_tlrpc=lambda entity: (entity.type.value, entity.offset))
        self.assertEqual(("custom_emoji", 0), parsed["entities"][0].to_tlrpc_object())

    def test_html_parser_uses_utf16_offsets(self):
        parsed = HTML.parse("😀<b>x</b>")
        self.assertEqual("😀x", parsed["text"])
        self.assertEqual(2, parsed["entities"][0].offset)
        self.assertEqual(1, parsed["entities"][0].length)

    def test_official_html_tags_and_nested_unparse_are_stable(self):
        parsed = HTML.parse(
            '<emoji id="123">\U0001f642</emoji>'
            '<pre language="python">x</pre><del>y</del>'
        )
        self.assertEqual(
            [
                TLEntityType.CUSTOM_EMOJI,
                TLEntityType.PRE,
                TLEntityType.STRIKETHROUGH,
            ],
            [entity.type for entity in parsed["entities"]],
        )
        self.assertEqual(123, parsed["entities"][0].document_id)
        self.assertEqual("python", parsed["entities"][1].language)

        entities = [
            RawEntity(TLEntityType.BOLD, 0, 3),
            RawEntity(TLEntityType.ITALIC, 0, 3),
        ]
        encoded = HTML.unparse("<&x", entities)
        self.assertEqual("<b><i>&lt;&amp;x</i></b>", encoded)
        self.assertEqual("<&x", HTML.parse(encoded)["text"])

    def test_html_legacy_aliases_and_utf16_nested_offsets(self):
        parsed = HTML.parse(
            '<tg-emoji emoji-id="789">\U0001f642</tg-emoji>'
            '<tg-spoiler>x</tg-spoiler>'
            '<blockquote expandable>q</blockquote>'
        )
        self.assertEqual("\U0001f642xq", parsed["message"])
        self.assertEqual(
            [
                TLEntityType.CUSTOM_EMOJI,
                TLEntityType.SPOILER,
                TLEntityType.BLOCKQUOTE,
            ],
            [entity.type for entity in parsed["entities"]],
        )
        self.assertEqual((0, 2, 789), (
            parsed["entities"][0].offset,
            parsed["entities"][0].length,
            parsed["entities"][0].document_id,
        ))
        self.assertEqual((2, 1), (
            parsed["entities"][1].offset,
            parsed["entities"][1].length,
        ))
        self.assertTrue(parsed["entities"][2].collapsed)

        nested = HTML.unparse(
            "\U0001f642x",
            [
                RawEntity(TLEntityType.BOLD, 0, 3),
                RawEntity(TLEntityType.ITALIC, 2, 1),
            ],
        )
        self.assertEqual("<b>\U0001f642<i>x</i></b>", nested)
        round_trip = HTML.parse(nested)
        self.assertEqual("\U0001f642x", round_trip["text"])
        self.assertEqual((0, 3), (
            round_trip["entities"][0].offset,
            round_trip["entities"][0].length,
        ))

    def test_expandable_quote_and_custom_emoji_markdown_round_trip(self):
        parsed = Markdown.parse("**> one\n> two||")
        self.assertEqual("one\ntwo", parsed["text"])
        self.assertTrue(parsed["entities"][0].collapsed)

        entity = RawEntity(
            TLEntityType.CUSTOM_EMOJI, 0, 2, document_id=456
        )
        encoded = Markdown.unparse("\U0001f642", [entity])
        self.assertEqual("![\U0001f642](tg://emoji?id=456)", encoded)
        round_trip = Markdown.parse(encoded)
        self.assertEqual("\U0001f642", round_trip["text"])
        self.assertEqual(456, round_trip["entities"][0].document_id)

    def test_escape_and_create_quotes_matches_legacy_pipeline(self):
        self.assertEqual(
            "<blockquote>a\nb</blockquote>\nplain",
            Markdown.escape_and_create_quotes("> a\n> b\nplain", False),
        )


if __name__ == "__main__":
    unittest.main()
