from dataclasses import dataclass
from enum import Enum

from extera_utils.text_formatting import (
    Markdown,
    RawEntity as ExtendedRawEntity,
    TLEntityType as ExtendedTLEntityType,
)

__all__ = ["parse_markdown"]


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


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: str | None = None
    url: str | None = None
    document_id: int | None = None

    def to_tlrpc_object(self):
        return ExtendedRawEntity(
            ExtendedTLEntityType(self.type.value),
            self.offset,
            self.length,
            language=self.language,
            url=self.url,
            document_id=self.document_id,
        ).to_tlrpc_object()


@dataclass
class ParsedMessage:
    text: str
    entities: tuple[RawEntity, ...]


def parse_markdown(markdown: str) -> ParsedMessage:
    parsed = Markdown.parse(markdown)
    entities = []
    for entity in parsed["entities"]:
        try:
            entity_type = TLEntityType(entity.type.value)
        except ValueError:
            # The newer text-formatting facade supports blockquotes, while the
            # frozen markdown_utils 1.4.3.10 surface did not expose that enum.
            continue
        entities.append(
            RawEntity(
                entity_type,
                entity.offset,
                entity.length,
                language=entity.language,
                url=entity.url,
                document_id=entity.document_id,
            )
        )
    return ParsedMessage(parsed["text"], tuple(entities))
