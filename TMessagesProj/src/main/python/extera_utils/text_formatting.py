"""Text formatting helpers compatible with the stable exteraGram SDK."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import html as html_module
from html.parser import HTMLParser
import re
from typing import Any

from plugin_runtime import services


def add_surrogates(text: str) -> str:
    encoded = text.encode("utf-16-le", "surrogatepass")
    return "".join(
        chr(int.from_bytes(encoded[index : index + 2], "little"))
        for index in range(0, len(encoded), 2)
    )


def remove_surrogates(text: str) -> str:
    encoded = bytearray()
    for char in text:
        codepoint = ord(char)
        if codepoint <= 0xFFFF:
            encoded.extend(codepoint.to_bytes(2, "little"))
        else:
            encoded.extend(char.encode("utf-16-le", "surrogatepass"))
    return bytes(encoded).decode("utf-16-le", "surrogatepass")


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"

    @classmethod
    def from_(cls, entity):
        value = getattr(entity, "type", entity)
        if isinstance(value, cls):
            return value
        raw = str(value).lower()
        try:
            return cls(raw)
        except ValueError:
            try:
                simple_name = entity.getClass().getSimpleName()
            except BaseException:
                simple_name = type(entity).__name__
            mapping = {
                "TL_messageEntityCode": cls.CODE,
                "TL_messageEntityPre": cls.PRE,
                "TL_messageEntityStrike": cls.STRIKETHROUGH,
                "TL_messageEntityTextUrl": cls.TEXT_LINK,
                "TL_messageEntityBold": cls.BOLD,
                "TL_messageEntityItalic": cls.ITALIC,
                "TL_messageEntityUnderline": cls.UNDERLINE,
                "TL_messageEntitySpoiler": cls.SPOILER,
                "TL_messageEntityCustomEmoji": cls.CUSTOM_EMOJI,
                "TL_messageEntityBlockquote": cls.BLOCKQUOTE,
            }
            if simple_name not in mapping:
                raise
            return mapping[simple_name]


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: str | None = None
    url: str | None = None
    document_id: int | None = None
    collapsed: bool | None = None

    @property
    def TLRPC_ENTITIES_MAP(self):
        try:
            from org.telegram.tgnet import TLRPC

            return {
                TLEntityType.CODE: TLRPC.TL_messageEntityCode,
                TLEntityType.PRE: TLRPC.TL_messageEntityPre,
                TLEntityType.STRIKETHROUGH: TLRPC.TL_messageEntityStrike,
                TLEntityType.TEXT_LINK: TLRPC.TL_messageEntityTextUrl,
                TLEntityType.BOLD: TLRPC.TL_messageEntityBold,
                TLEntityType.ITALIC: TLRPC.TL_messageEntityItalic,
                TLEntityType.UNDERLINE: TLRPC.TL_messageEntityUnderline,
                TLEntityType.SPOILER: TLRPC.TL_messageEntitySpoiler,
                TLEntityType.CUSTOM_EMOJI: TLRPC.TL_messageEntityCustomEmoji,
                TLEntityType.BLOCKQUOTE: TLRPC.TL_messageEntityBlockquote,
            }
        except (ImportError, ModuleNotFoundError):
            return {}

    def to_tlrpc_object(self):
        return services.call("raw_entity_to_tlrpc", self)


class Parser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.text = ""
        self.entities: list[RawEntity] = []
        self.tag_entities: list[tuple[str, int, list[tuple[str, str | None]]]] = []

    def handle_starttag(self, tag, attrs) -> None:
        if tag.lower() == "br":
            self.text += "\n"
            return
        self.tag_entities.append((tag.lower(), len(add_surrogates(self.text)), attrs))

    def handle_data(self, data) -> None:
        self.text += data

    def handle_endtag(self, tag) -> None:
        tag = tag.lower()
        for index in range(len(self.tag_entities) - 1, -1, -1):
            open_tag, offset, attrs = self.tag_entities[index]
            if open_tag != tag:
                continue
            del self.tag_entities[index]
            mapping = {
                "b": TLEntityType.BOLD, "strong": TLEntityType.BOLD,
                "i": TLEntityType.ITALIC, "em": TLEntityType.ITALIC,
                "u": TLEntityType.UNDERLINE,
                "s": TLEntityType.STRIKETHROUGH,
                "del": TLEntityType.STRIKETHROUGH,
                "strike": TLEntityType.STRIKETHROUGH,
                "code": TLEntityType.CODE, "pre": TLEntityType.PRE,
                "spoiler": TLEntityType.SPOILER,
                "tg-spoiler": TLEntityType.SPOILER,
                "blockquote": TLEntityType.BLOCKQUOTE,
                "a": TLEntityType.TEXT_LINK,
                "emoji": TLEntityType.CUSTOM_EMOJI,
                "tg-emoji": TLEntityType.CUSTOM_EMOJI,
            }
            entity_type = mapping.get(tag)
            if entity_type is not None:
                attributes = dict(attrs)
                self.entities.append(
                    RawEntity(
                        entity_type,
                        offset,
                        len(add_surrogates(self.text)) - offset,
                        language=(
                            attributes.get("language")
                            or attributes.get("lang")
                            or (attributes.get("class") or "").removeprefix("language-")
                        ) if tag == "pre" else None,
                        url=attributes.get("href") if tag == "a" else None,
                        document_id=int(
                            attributes.get("id")
                            or attributes.get("document_id")
                            or attributes.get("emoji-id")
                        )
                        if tag in {"emoji", "tg-emoji"} and str(
                            attributes.get("id")
                            or attributes.get("document_id")
                            or attributes.get("emoji-id")
                            or ""
                        ).isdigit()
                        else None,
                        collapsed=("expandable" in attributes or "collapsed" in attributes)
                        if tag == "blockquote"
                        else None,
                    )
                )
            return


class HTML:
    @staticmethod
    def parse(text: str) -> dict:
        parser = Parser()
        parser.feed(text)
        parser.close()
        entities = sorted(
            (entity for entity in parser.entities if entity.length > 0),
            key=lambda entity: (entity.offset, -entity.length, entity.type.value),
        )
        return {"text": parser.text, "message": parser.text, "entities": entities}

    @staticmethod
    def unparse(text: str, entities: list) -> str:
        if services.has("unparse_html_entities"):
            return services.call("unparse_html_entities", text, entities)
        return _unparse(text, entities, html=True)


BOLD_DELIM = "*"
ITALIC_DELIM = "_"
UNDERLINE_DELIM = "__"
STRIKE_DELIM = "~"
SPOILER_DELIM = "||"
CODE_DELIM = "`"
PRE_DELIM = "```"
BLOCKQUOTE_DELIM = ">"
BLOCKQUOTE_EXPANDABLE_DELIM = "**>"
BLOCKQUOTE_EXPANDABLE_END_DELIM = "||"
OPENING_TAG = "["
CLOSING_TAG = "]"
URL_MARKUP = "({url})"
EMOJI_MARKUP = "({document_id})"
FIXED_WIDTH_DELIMS = (CODE_DELIM, PRE_DELIM)
MARKDOWN_ESCAPABLE_CHARS = r"_*[]()~`>#+-=|{}.!\\"
MARKDOWN_RE = re.compile(r"(\\.|```|\|\||__|[*_~`]|\[|^>)", re.MULTILINE)
ESCAPED_MARKDOWN_RE = re.compile(r"\\([_\*\[\]\(\)~`>#+\-=|{}.!\\])")


def replace_once(source: str, old: str, new: str, start: int):
    index = source.find(old, start)
    if index < 0:
        return source
    return source[:index] + new + source[index + len(old) :]


def _utf16_len(value: str) -> int:
    return len(value.encode("utf-16-le", "surrogatepass")) // 2


def _find_unescaped(source: str, needle: str, start: int) -> int:
    index = start
    while True:
        index = source.find(needle, index)
        if index < 0:
            return -1
        slashes = 0
        cursor = index - 1
        while cursor >= 0 and source[cursor] == "\\":
            slashes += 1
            cursor -= 1
        if slashes % 2 == 0:
            return index
        index += len(needle)


def _shift_entities(entities: list[RawEntity], offset: int) -> None:
    for entity in entities:
        entity.offset += offset


_STYLE_DELIMITERS = (
    (SPOILER_DELIM, TLEntityType.SPOILER),
    (UNDERLINE_DELIM, TLEntityType.UNDERLINE),
    (BOLD_DELIM, TLEntityType.BOLD),
    (ITALIC_DELIM, TLEntityType.ITALIC),
    (STRIKE_DELIM, TLEntityType.STRIKETHROUGH),
)


def _parse_markdown_fragment(source: str, strict: bool) -> tuple[str, list[RawEntity]]:
    output: list[str] = []
    entities: list[RawEntity] = []
    index = 0

    def output_text() -> str:
        return "".join(output)

    while index < len(source):
        if source[index] == "\\" and index + 1 < len(source):
            output.append(source[index + 1])
            index += 2
            continue

        at_line_start = index == 0 or source[index - 1] == "\n"
        if at_line_start and source.startswith(BLOCKQUOTE_EXPANDABLE_DELIM, index):
            cursor = index
            quote_lines: list[str] = []
            closed = False
            first_line = True
            while cursor <= len(source):
                line_end = source.find("\n", cursor)
                if line_end < 0:
                    line_end = len(source)
                line = source[cursor:line_end]
                prefix = BLOCKQUOTE_EXPANDABLE_DELIM if first_line else BLOCKQUOTE_DELIM
                if line.startswith(prefix):
                    line = line[len(prefix):]
                    if line.startswith(" "):
                        line = line[1:]
                if line.endswith(BLOCKQUOTE_EXPANDABLE_END_DELIM):
                    line = line[:-len(BLOCKQUOTE_EXPANDABLE_END_DELIM)]
                    closed = True
                quote_lines.append(line)
                first_line = False
                if closed or line_end == len(source):
                    break
                next_line = line_end + 1
                if not source.startswith(BLOCKQUOTE_DELIM, next_line):
                    break
                cursor = next_line
            if strict and not closed:
                raise SyntaxError("unclosed expandable blockquote")
            inner_text, inner_entities = _parse_markdown_fragment(
                "\n".join(quote_lines), strict
            )
            offset = _utf16_len(output_text())
            _shift_entities(inner_entities, offset)
            output.append(inner_text)
            entities.extend(inner_entities)
            entities.append(
                RawEntity(
                    TLEntityType.BLOCKQUOTE,
                    offset,
                    _utf16_len(inner_text),
                    collapsed=True,
                )
            )
            index = line_end
            continue

        if at_line_start and source.startswith(BLOCKQUOTE_DELIM, index):
            cursor = index
            quote_lines: list[str] = []
            while cursor <= len(source):
                line_end = source.find("\n", cursor)
                if line_end < 0:
                    line_end = len(source)
                line = source[cursor:line_end]
                if not line.startswith(BLOCKQUOTE_DELIM):
                    break
                line = line[len(BLOCKQUOTE_DELIM):]
                if line.startswith(" "):
                    line = line[1:]
                quote_lines.append(line)
                if line_end == len(source):
                    break
                next_line = line_end + 1
                if not source.startswith(BLOCKQUOTE_DELIM, next_line):
                    break
                cursor = next_line
            inner_text, inner_entities = _parse_markdown_fragment(
                "\n".join(quote_lines), strict
            )
            offset = _utf16_len(output_text())
            _shift_entities(inner_entities, offset)
            output.append(inner_text)
            entities.extend(inner_entities)
            entities.append(
                RawEntity(TLEntityType.BLOCKQUOTE, offset, _utf16_len(inner_text))
            )
            index = line_end
            continue

        if source.startswith(PRE_DELIM, index):
            close = _find_unescaped(source, PRE_DELIM, index + len(PRE_DELIM))
            if close < 0:
                raise SyntaxError("unclosed fenced code block")
            body = source[index + len(PRE_DELIM) : close]
            language = None
            newline = body.find("\n")
            if newline >= 0 and re.fullmatch(r"[A-Za-z0-9_+.-]{1,64}", body[:newline]):
                language = body[:newline] or None
                body = body[newline + 1 :]
            offset = _utf16_len(output_text())
            output.append(body)
            entities.append(
                RawEntity(TLEntityType.PRE, offset, _utf16_len(body), language=language)
            )
            index = close + len(PRE_DELIM)
            continue

        if source.startswith(CODE_DELIM, index):
            close = _find_unescaped(source, CODE_DELIM, index + 1)
            if close < 0:
                if strict:
                    raise SyntaxError("unclosed inline code")
            else:
                body = source[index + 1 : close].replace("\\`", "`")
                offset = _utf16_len(output_text())
                output.append(body)
                entities.append(
                    RawEntity(TLEntityType.CODE, offset, _utf16_len(body))
                )
                index = close + 1
                continue

        custom_emoji_markup = source.startswith("![", index)
        opening_index = index + 1 if custom_emoji_markup else index
        if source.startswith(OPENING_TAG, opening_index):
            label_end = _find_unescaped(source, CLOSING_TAG, opening_index + 1)
            if label_end >= 0 and source.startswith("(", label_end + 1):
                target_end = _find_unescaped(source, ")", label_end + 2)
                if target_end < 0:
                    raise SyntaxError("unclosed markdown link")
                label_source = source[opening_index + 1 : label_end]
                target = source[label_end + 2 : target_end].replace("\\)", ")")
                label, nested = _parse_markdown_fragment(label_source, strict)
                offset = _utf16_len(output_text())
                _shift_entities(nested, offset)
                output.append(label)
                entities.extend(nested)
                if target.isdecimal():
                    entities.append(
                        RawEntity(
                            TLEntityType.CUSTOM_EMOJI,
                            offset,
                            _utf16_len(label),
                            document_id=int(target),
                        )
                    )
                else:
                    emoji_match = re.fullmatch(r"tg://emoji\?id=(\d+)", target)
                    if emoji_match:
                        entities.append(
                            RawEntity(
                                TLEntityType.CUSTOM_EMOJI,
                                offset,
                                _utf16_len(label),
                                document_id=int(emoji_match.group(1)),
                            )
                        )
                    elif custom_emoji_markup:
                        if strict:
                            raise SyntaxError("custom emoji link must contain a numeric id")
                    else:
                        entities.append(
                            RawEntity(
                                TLEntityType.TEXT_LINK,
                                offset,
                                _utf16_len(label),
                                url=target,
                            )
                        )
                index = target_end + 1
                continue

        matched_style = False
        for delimiter, entity_type in _STYLE_DELIMITERS:
            if not source.startswith(delimiter, index):
                continue
            # Keep underscores inside identifiers literal.
            if delimiter in {ITALIC_DELIM, UNDERLINE_DELIM} and index > 0:
                if source[index - 1].isalnum():
                    continue
            close = _find_unescaped(source, delimiter, index + len(delimiter))
            if close < 0:
                if strict:
                    raise SyntaxError(f"unclosed markdown delimiter {delimiter!r}")
                continue
            inner, nested = _parse_markdown_fragment(
                source[index + len(delimiter) : close], strict
            )
            offset = _utf16_len(output_text())
            _shift_entities(nested, offset)
            output.append(inner)
            entities.extend(nested)
            entities.append(RawEntity(entity_type, offset, _utf16_len(inner)))
            index = close + len(delimiter)
            matched_style = True
            break
        if matched_style:
            continue

        output.append(source[index])
        index += 1

    entities.sort(key=lambda item: (item.offset, -item.length, item.type.value))
    return output_text(), entities


_ENTITY_ORDER = {entity_type: index for index, entity_type in enumerate(TLEntityType)}


def _entity_tags(entity_type: TLEntityType, entity: Any, html: bool) -> tuple[str, str]:
    if html:
        wrappers = {
            TLEntityType.BOLD: ("<b>", "</b>"),
            TLEntityType.ITALIC: ("<i>", "</i>"),
            TLEntityType.UNDERLINE: ("<u>", "</u>"),
            TLEntityType.STRIKETHROUGH: ("<s>", "</s>"),
            TLEntityType.SPOILER: ("<spoiler>", "</spoiler>"),
            TLEntityType.CODE: ("<code>", "</code>"),
        }
        if entity_type == TLEntityType.TEXT_LINK:
            url = html_module.escape(getattr(entity, "url", "") or "", quote=True)
            return f'<a href="{url}">', "</a>"
        if entity_type == TLEntityType.CUSTOM_EMOJI:
            document_id = int(getattr(entity, "document_id", 0) or 0)
            return f'<emoji id="{document_id}">', "</emoji>"
        if entity_type == TLEntityType.PRE:
            language = html_module.escape(
                getattr(entity, "language", "") or "", quote=True
            )
            return (
                f'<pre language="{language}">' if language else "<pre>",
                "</pre>",
            )
        if entity_type == TLEntityType.BLOCKQUOTE:
            collapsed = bool(
                getattr(entity, "collapsed", getattr(entity, "expandable", False))
            )
            return "<blockquote expandable>" if collapsed else "<blockquote>", "</blockquote>"
        return wrappers.get(entity_type, ("", ""))

    wrappers = {
        TLEntityType.BOLD: (BOLD_DELIM, BOLD_DELIM),
        TLEntityType.ITALIC: (ITALIC_DELIM, ITALIC_DELIM),
        TLEntityType.UNDERLINE: (UNDERLINE_DELIM, UNDERLINE_DELIM),
        TLEntityType.STRIKETHROUGH: (STRIKE_DELIM, STRIKE_DELIM),
        TLEntityType.SPOILER: (SPOILER_DELIM, SPOILER_DELIM),
        TLEntityType.CODE: (CODE_DELIM, CODE_DELIM),
    }
    if entity_type == TLEntityType.TEXT_LINK:
        return "[", f"]({getattr(entity, 'url', '') or ''})"
    if entity_type == TLEntityType.CUSTOM_EMOJI:
        document_id = int(getattr(entity, "document_id", 0) or 0)
        return "![", f"](tg://emoji?id={document_id})"
    if entity_type == TLEntityType.PRE:
        language = getattr(entity, "language", "") or ""
        return f"{PRE_DELIM}{language}\n", f"\n{PRE_DELIM}"
    return wrappers.get(entity_type, ("", ""))


def _unparse(text: str, entities: list, *, html: bool) -> str:
    surrogate_text = add_surrogates(text)
    normalized: list[tuple[int, int, TLEntityType, Any, int]] = []
    for original_index, entity in enumerate(entities):
        entity_type = TLEntityType.from_(entity)
        start = max(0, min(len(surrogate_text), int(entity.offset)))
        end = max(start, min(len(surrogate_text), start + int(entity.length)))
        if end == start:
            continue
        normalized.append((start, end, entity_type, entity, original_index))
    normalized.sort(
        key=lambda value: (
            value[0],
            -value[1],
            _ENTITY_ORDER[value[2]],
            value[4],
        )
    )

    openings: dict[int, list[tuple[int, int, str]]] = {}
    closings: dict[int, list[tuple[int, int, str]]] = {}
    quote_prefixes: dict[int, list[str]] = {}
    quote_suffixes: dict[int, list[str]] = {}
    for start, end, entity_type, entity, original_index in normalized:
        if not html and entity_type == TLEntityType.BLOCKQUOTE:
            collapsed = bool(
                getattr(entity, "collapsed", getattr(entity, "expandable", False))
            )
            line_starts = [start]
            line_starts.extend(
                position + 1
                for position in range(start, end)
                if surrogate_text[position] == "\n" and position + 1 < end
            )
            for line_index, position in enumerate(line_starts):
                prefix = (
                    f"{BLOCKQUOTE_EXPANDABLE_DELIM} "
                    if collapsed and line_index == 0
                    else f"{BLOCKQUOTE_DELIM} "
                )
                quote_prefixes.setdefault(position, []).append(prefix)
            if collapsed:
                quote_suffixes.setdefault(end, []).append(
                    BLOCKQUOTE_EXPANDABLE_END_DELIM
                )
            continue
        opening, closing = _entity_tags(entity_type, entity, html)
        if opening:
            openings.setdefault(start, []).append(
                (-end, _ENTITY_ORDER[entity_type], opening)
            )
        if closing:
            closings.setdefault(end, []).append(
                (-start, -_ENTITY_ORDER[entity_type], closing)
            )

    output: list[str] = []
    for position in range(len(surrogate_text) + 1):
        for _start, _order, closing in sorted(closings.get(position, ())):
            output.append(closing)
        output.extend(quote_suffixes.get(position, ()))
        output.extend(quote_prefixes.get(position, ()))
        for _end, _order, opening in sorted(openings.get(position, ())):
            output.append(opening)
        if position < len(surrogate_text):
            character = surrogate_text[position]
            output.append(
                html_module.escape(character, quote=False) if html else character
            )
    return remove_surrogates("".join(output))


class Markdown:
    @staticmethod
    def protect_escaped_chars(text: str) -> tuple[str, dict[str, str]]:
        escaped: dict[str, str] = {}

        def replace(match: re.Match[str]) -> str:
            token = chr(0xE000 + len(escaped))
            escaped[token] = match.group(1)
            return token

        return ESCAPED_MARKDOWN_RE.sub(replace, text), escaped

    @staticmethod
    def restore_escaped_chars(text: str, escaped_chars: dict[str, str]) -> str:
        for token, value in escaped_chars.items():
            text = text.replace(token, value)
        return text

    @staticmethod
    def escape_and_create_quotes(text: str, strict: bool):
        text_lines: list[str | None] = text.splitlines()
        escaped_lines: set[int] = set()
        queued: list[tuple[int, str]] = []

        def create_blockquote(expandable: bool = False) -> None:
            if not queued:
                return
            first_index = queued[0][0]
            joined = "\n".join(value for _index, value in queued)
            text_lines[first_index] = (
                f"<blockquote{' expandable' if expandable else ''}>"
                f"{joined}</blockquote>"
            )
            for line_index, _value in queued[1:]:
                text_lines[line_index] = None
            queued.clear()

        inside_expandable = False
        for line_index, raw_line in enumerate(text_lines):
            line = raw_line or ""
            if line.startswith(BLOCKQUOTE_EXPANDABLE_DELIM) and not inside_expandable:
                value = line[len(BLOCKQUOTE_EXPANDABLE_DELIM):]
                if value.startswith(" "):
                    value = value[1:]
                queued.append(
                    (line_index, html_module.escape(value) if strict else value)
                )
                escaped_lines.add(line_index)
                inside_expandable = True
                continue
            if inside_expandable:
                if line.startswith(BLOCKQUOTE_DELIM):
                    line = line[len(BLOCKQUOTE_DELIM):]
                    if line.startswith(" "):
                        line = line[1:]
                finished = line.endswith(BLOCKQUOTE_EXPANDABLE_END_DELIM)
                if finished:
                    line = line[:-len(BLOCKQUOTE_EXPANDABLE_END_DELIM)]
                queued.append(
                    (line_index, html_module.escape(line) if strict else line)
                )
                escaped_lines.add(line_index)
                if finished:
                    inside_expandable = False
                    create_blockquote(expandable=True)

        if inside_expandable:
            create_blockquote(expandable=False)

        for line_index, raw_line in enumerate(text_lines):
            if raw_line is None:
                continue
            line = raw_line
            if line.startswith(BLOCKQUOTE_DELIM):
                value = line[len(BLOCKQUOTE_DELIM):]
                if value.startswith(" "):
                    value = value[1:]
                queued.append(
                    (line_index, html_module.escape(value) if strict else value)
                )
                escaped_lines.add(line_index)
            elif queued:
                create_blockquote()
        create_blockquote()

        if strict:
            for line_index, line in enumerate(text_lines):
                if line is not None and line_index not in escaped_lines:
                    text_lines[line_index] = html_module.escape(line)
        return "\n".join(line for line in text_lines if line is not None)

    @classmethod
    def parse(cls, text: str, strict: bool = False):
        if not isinstance(text, str):
            raise TypeError("text must be str")
        parsed_text, entities = _parse_markdown_fragment(text, strict)
        return {"text": parsed_text, "entities": entities}

    @staticmethod
    def unparse(text: str, entities: list):
        if services.has("unparse_markdown_entities"):
            return services.call("unparse_markdown_entities", text, entities)
        return _unparse(text, entities, html=False)


def parse_text(text: str, parse_mode: str | None = "HTML", is_caption: bool = False) -> dict[str, Any]:
    if parse_mode is None:
        return {"text": text, "entities": []}
    mode = parse_mode.upper()
    if mode == "HTML":
        return HTML.parse(text)
    if mode in {"MARKDOWN", "MARKDOWNV2"}:
        return Markdown.parse(text, strict=mode == "MARKDOWNV2")
    raise ValueError(f"unknown parse mode {parse_mode!r}")
