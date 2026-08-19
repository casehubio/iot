export function field(item: any, name: string, fallback = ''): string {
  return item?.[name] ?? item?.text?.(name) ?? fallback;
}
