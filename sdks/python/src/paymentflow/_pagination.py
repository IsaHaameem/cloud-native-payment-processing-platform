"""Pagination, in both of the shapes this platform publishes (M22.5).

§7.1's rule is that no SDK user should ever have to implement cursor handling, and the way to
make that true rather than aspirational is for the ordinary thing — a ``for`` over a list — to
already be the paginating thing. A helper the caller has to know to reach for is a helper most
callers will not reach for, and their code will silently process only the first page for as
long as their account is small enough for that to look correct.

So the page objects are **iterable**, and iterating one walks every page from it onward::

    for payment in client.payments.list(status="captured"):
        ...

``page.data`` (or ``page.content``), ``page.has_more`` and ``page.next_page()`` remain, for a
caller keeping their own cursor.

Why there are two page types
----------------------------
There are two on the wire. M19 put cursor pagination on every list it introduced, and D139
deliberately left ``/v1/webhook_deliveries`` and ``/v1/test/decisions`` on the older offset
``PageResponse``. Papering over that here would mean inventing a ``total_elements`` a cursor
page does not have — it reports no total precisely because counting costs a second query — or
hiding the total the offset endpoints genuinely do return. Both iterate identically, which is
the part a caller actually cares about.
"""

from __future__ import annotations

from typing import Any, Callable, Dict, Generic, Iterator, List, Optional, Tuple, TypeVar

from ._transport import ResponseMeta

__all__ = ["CursorPage", "OffsetPage", "Page"]

T = TypeVar("T")

#: Fetches one page, given the pagination parameter for it.
_CursorFetch = Callable[[Optional[str]], Tuple[Dict[str, Any], ResponseMeta]]
_OffsetFetch = Callable[[int], Tuple[Dict[str, Any], ResponseMeta]]


class Page(Generic[T]):
    """What both page types share, so a caller can write code against either."""

    def __init__(self, meta: ResponseMeta, items: List[T], has_more: bool) -> None:
        #: What the exchange that produced *this* page reported.
        self.meta = meta
        #: Whether another page exists after this one.
        self.has_more = has_more
        self._items = items

    def next_page(self) -> Optional["Page[T]"]:
        """Fetches the next page, or returns ``None`` when this is the last."""
        raise NotImplementedError

    def __iter__(self) -> Iterator[T]:
        """Walks every object from this page onward, fetching as it goes.

        A generator over ``next_page()`` rather than a loop that collects everything: a list of
        every payment a merchant has ever taken is not a thing to hold in memory, and a caller
        who ``break``s out of the loop should stop making requests at that point rather than
        after the last page.
        """
        page: Optional[Page[T]] = self
        while page is not None:
            for item in page._items:
                yield item
            page = page.next_page()

    def __len__(self) -> int:
        """The number of objects on **this** page, not in the result set.

        A cursor page cannot know the total, so no page type pretends to. ``len(page)`` is the
        length of ``page.data``; the offset pages additionally expose ``total_elements``.
        """
        return len(self._items)

    def __repr__(self) -> str:
        return f"<{type(self).__name__} items={len(self._items)} has_more={self.has_more}>"


class CursorPage(Page[T]):
    """A cursor page: the M19 list shape. No total count, deliberately."""

    def __init__(self, body: Dict[str, Any], meta: ResponseMeta, fetch: _CursorFetch) -> None:
        data: List[T] = list(body.get("data") or [])
        next_cursor = body.get("nextCursor")
        # `hasMore` is the platform's own answer and is trusted where present. The fallback is
        # not `False`: a page carrying a cursor and no flag plainly has more, and stopping
        # there would silently truncate the result — the failure this module exists to prevent.
        has_more = body.get("hasMore") if body.get("hasMore") is not None else next_cursor is not None
        super().__init__(meta, data, bool(has_more))

        #: The objects on this page, most recent first.
        self.data = data
        #: The cursor to pass as ``starting_after`` for the next page.
        self.next_cursor: Optional[str] = next_cursor
        self._fetch = fetch

    def next_page(self) -> Optional["CursorPage[T]"]:
        if not self.has_more or self.next_cursor is None:
            return None
        body, meta = self._fetch(self.next_cursor)
        return CursorPage(body, meta, self._fetch)


class OffsetPage(Page[T]):
    """An offset page: the older ``PageResponse``, on the two endpoints D139 left alone."""

    def __init__(self, body: Dict[str, Any], meta: ResponseMeta, fetch: _OffsetFetch) -> None:
        content: List[T] = list(body.get("content") or [])
        index = body.get("page") or 0
        total_pages = body.get("totalPages") or 0
        last = body.get("last")
        # `last` where the platform sent it; otherwise derived from the index, which the offset
        # envelope always carries enough of to compute.
        has_more = (index + 1 < total_pages) if last is None else (not last)
        super().__init__(meta, content, bool(has_more))

        #: The objects on this page.
        self.content = content
        #: The zero-based index of this page.
        self.page = index
        #: How many objects each page holds.
        self.size = body.get("size") if body.get("size") is not None else len(content)
        #: How many objects match the query in total.
        self.total_elements = body.get("totalElements") if body.get("totalElements") is not None else len(content)
        #: How many pages the result set spans.
        self.total_pages = total_pages
        self._fetch = fetch

    def next_page(self) -> Optional["OffsetPage[T]"]:
        if not self.has_more:
            return None
        body, meta = self._fetch(self.page + 1)
        return OffsetPage(body, meta, self._fetch)
