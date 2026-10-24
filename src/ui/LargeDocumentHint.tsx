import { useEffect, useRef } from 'react';
import { JobSnapshot, NotificationPermissionSnapshot } from '../native/PdfTranslator';
import { useToast } from '../components/kinetik/toast-stack';
import { processingUx } from '../i18n/processingUx';

/** One contextual hint per job in this app session; polling never repeats it. */
export function LargeDocumentHint({ jobs, notifications, active, locale, onEnable }: {
  jobs: JobSnapshot[]; notifications?: NotificationPermissionSnapshot; active: boolean; locale: string; onEnable: () => void;
}) {
  const seen = useRef(new Set<string>());
  const toast = useToast();
  useEffect(() => {
    if (!active || !notifications || notifications.status === 'granted' || notifications.status === 'not_required') return;
    const job = jobs.find(value => value.totalPages > 100 && (value.status === 'running' || value.status === 'queued'));
    if (!job || seen.current.has(job.id)) return;
    seen.current.add(job.id);
    toast.show({
      title: processingUx(locale, 'notifications'), description: processingUx(locale, 'notificationsBody'),
      duration: 10000, action: { label: processingUx(locale, 'enable'), onPress: onEnable },
    });
  }, [active, jobs, notifications, locale, onEnable, toast]);
  return null;
}
