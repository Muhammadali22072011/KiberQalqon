// Telegram xabarlari uchun formatlash — barchasi o'zbekcha

const VERDICT_LABEL: Record<string, string> = {
  safe: '🟢 XAVFSIZ',
  suspicious: '🟠 SHUBHALI',
  danger: '🔴 XAVFLI',
  error: '⚠️ XATO',
};

export function verdictLabel(v: string): string {
  return VERDICT_LABEL[v] ?? v;
}

export function formatThreatAlert(scan: {
  app_label?: string | null;
  package_name?: string | null;
  apk_hash: string;
  verdict: string;
  reasons?: unknown;
  device_name?: string | null;
}): string {
  const lines: string[] = [];
  lines.push(`${verdictLabel(scan.verdict)} *${scan.verdict === 'danger' ? 'topildi' : 'aniqlandi'}!*`);
  lines.push('');
  if (scan.app_label) lines.push(`*Ilova:* ${escape(scan.app_label)}`);
  if (scan.package_name) lines.push(`*Package:* \`${escape(scan.package_name)}\``);
  lines.push(`*Hash:* \`${scan.apk_hash.slice(0, 16)}…\``);
  if (scan.device_name) lines.push(`*Qurilma:* ${escape(scan.device_name)}`);

  const reasons = Array.isArray(scan.reasons) ? (scan.reasons as string[]) : [];
  if (reasons.length) {
    lines.push('');
    lines.push('*Xavf belgilari:*');
    for (const r of reasons.slice(0, 6)) lines.push(`• ${escape(String(r))}`);
  }
  if (scan.verdict === 'danger') {
    lines.push('');
    lines.push('❌ *Bu APKni o\'rnatmang!*');
  }
  return lines.join('\n');
}

export function formatStats(s: {
  total_scans: number;
  danger_count: number;
  suspicious_count: number;
  safe_count: number;
  active_devices: number;
}): string {
  return [
    '*📊 Bugungi statistika*',
    '',
    `Jami skanlar:  *${s.total_scans}*`,
    `🔴 Xavfli:      *${s.danger_count}*`,
    `🟠 Shubhali:   *${s.suspicious_count}*`,
    `🟢 Xavfsiz:    *${s.safe_count}*`,
    '',
    `Faol qurilmalar: *${s.active_devices}*`,
  ].join('\n');
}

// Markdown belgilarini ekran qiladi (Telegram MarkdownV1 uchun yetarli)
export function escape(s: string): string {
  return s.replace(/([\\_*`\[])/g, '\\$1');
}
