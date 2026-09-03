package dev.paymentflow.internal;

/**
 * Fetches one page, given the pagination parameter that identifies it — a cursor string for a
 * {@link dev.paymentflow.CursorPage}, a zero-based index (boxed) for a
 * {@link dev.paymentflow.OffsetPage}. Internal plumbing; a caller never constructs one.
 */
@FunctionalInterface
public interface PageFetch {

    /** @param locator the cursor ({@code String}) or page index ({@code Integer}) for the page to fetch */
    Transport.Result fetch(Object locator);
}
