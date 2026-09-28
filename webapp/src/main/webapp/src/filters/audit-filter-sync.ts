const filters = new Map<string, string>();

function onFilter(e: Event) {
  const { columnId, value, reset, group } = (e as CustomEvent).detail;
  if (group !== 'audit') return;
  if (reset) filters.delete(columnId);
  else filters.set(columnId, value);

  const trail = document.querySelector('blocks-event-trail') as
    HTMLElement & { endpoint: string; syncEndpoint(): void } | null;
  if (!trail) return;

  const params = new URLSearchParams({ limit: '500' });
  for (const [k, v] of filters) params.set(k, v);
  trail.endpoint = `/api/bridge/audit?${params}`;
  trail.syncEndpoint();
}

document.addEventListener('pages-filter', onFilter);
