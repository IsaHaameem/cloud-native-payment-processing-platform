package dev.paymentflow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A page of list results that iterates transparently across page boundaries.
 *
 * <p>§7.1's rule is that no SDK user should ever have to implement cursor handling, and the way
 * to make that true rather than aspirational is for the ordinary thing — a {@code for} loop over
 * a list — to already be the paginating thing:
 *
 * <pre>{@code
 * for (PaymentResponse payment : client.payments().list(params)) {
 *     // every payment, not just the first page
 * }
 * }</pre>
 *
 * <p>A caller who {@code break}s out stops making requests at that point. There are two page
 * shapes on the wire — {@link CursorPage} for the M19 lists, {@link OffsetPage} for the two
 * endpoints left on the older envelope — and they iterate identically, which is the part a
 * caller actually cares about.
 */
public abstract sealed class Page<T> implements Iterable<T> permits CursorPage, OffsetPage {

    private final ResponseMeta meta;

    Page(ResponseMeta meta) {
        this.meta = meta;
    }

    /** The objects on this page. */
    public abstract List<T> items();

    /** Whether another page exists after this one. */
    public abstract boolean hasMore();

    /** Fetches the next page, or {@code null} when this is the last. */
    public abstract Page<T> nextPage();

    /** What the exchange that produced <em>this</em> page reported. */
    public final ResponseMeta meta() {
        return meta;
    }

    /**
     * Collects every remaining object into a list, fetching pages as needed.
     *
     * <p>A convenience for the cases where holding the whole result in memory is fine. It is not
     * the default for a reason — a list of every payment a merchant has ever taken is not a thing
     * to materialise — so the cap is required, and exceeding it throws rather than silently
     * truncating.
     */
    public final List<T> toList(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative");
        }
        List<T> out = new ArrayList<>();
        for (T item : this) {
            if (out.size() >= max) {
                throw new IllegalStateException(
                        "the result has more than " + max + " objects; raise the cap or iterate lazily");
            }
            out.add(item);
        }
        return out;
    }

    @Override
    public final Iterator<T> iterator() {
        return new Iterator<>() {
            private Page<T> page = Page.this;
            private Iterator<T> current = Page.this.items().iterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    Page<T> next = page.nextPage();
                    if (next == null) {
                        return false;
                    }
                    page = next;
                    current = next.items().iterator();
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
    }
}
