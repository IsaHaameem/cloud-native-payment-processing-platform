import { BackendGapNotice } from '@/components/patterns/backend-gap';
import { PageHeader } from '@/components/patterns/page-header';
import { Card, CardContent } from '@/components/ui/card';

/**
 * The shared shape for an agentic screen whose data source is a backend gap (frontend build).
 *
 * The design for every one of these is complete and worth showing, so the page renders its
 * header, the honest gap notice, and a dimmed preview of the layout the data would fill. The
 * preview is `aria-hidden` and non-interactive — it communicates intent, never fake data.
 */
export function GapScreen({
  title,
  description,
  gapId,
  gapTitle,
  gapBody,
  preview,
  children,
}: {
  title: string;
  description: string;
  gapId: string;
  gapTitle: string;
  gapBody: React.ReactNode;
  /** A dimmed structural preview of the designed layout. */
  preview?: React.ReactNode;
  /** Anything genuinely usable on this screen (e.g. an "open by id" control). */
  children?: React.ReactNode;
}) {
  return (
    <div>
      <PageHeader title={title} description={description} />
      <BackendGapNotice id={gapId} title={gapTitle}>
        {gapBody}
      </BackendGapNotice>
      {children ? <div className="mt-4">{children}</div> : null}
      {preview ? (
        <Card className="mt-4">
          <CardContent
            aria-hidden
            className="pointer-events-none overflow-x-auto pt-4 opacity-45 select-none"
          >
            {preview}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
