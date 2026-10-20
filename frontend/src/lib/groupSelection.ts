import type { GroupDetail } from '../types';

// Group details arrive from async requests (open, add member, member-list refresh) that can
// finish after the user has switched to another group. Only accept one for the group that
// is open now, so the header never shows group A while sends go to group B.
export function acceptDetail(
  current: GroupDetail | null,
  incoming: GroupDetail,
  selectedGroupId: number | null
): GroupDetail | null {
  return incoming.id === selectedGroupId ? incoming : current;
}
