'use client';

import { ArrowRight } from 'lucide-react';
import { useRouter } from 'next/navigation';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * "Open by id" (frontend build).
 *
 * Several agentic screens have no list endpoint (`frontend_Design.md` gap G-4) but a working
 * fetch-by-id, so the design gives them a single input that navigates to a detail route. This is
 * that control — no fetch here, just a route push.
 */
export function OpenById({
  base,
  placeholder,
  label,
}: {
  base: string;
  placeholder: string;
  label: string;
}) {
  const router = useRouter();
  const [value, setValue] = React.useState('');

  function go(e: React.FormEvent) {
    e.preventDefault();
    const id = value.trim();
    if (id) router.push(`${base}/${encodeURIComponent(id)}`);
  }

  return (
    <form onSubmit={go} className="flex max-w-md flex-wrap items-end gap-2">
      <label className="min-w-[14rem] flex-1 space-y-1.5">
        <span className="text-label text-fg-muted">{label}</span>
        <Input value={value} onChange={(e) => setValue(e.target.value)} placeholder={placeholder} />
      </label>
      <Button type="submit" variant="secondary" size="md" disabled={value.trim().length === 0}>
        Open <ArrowRight />
      </Button>
    </form>
  );
}
