import { test } from 'node:test';
import assert from 'node:assert/strict';
import { acceptDetail } from '../src/lib/groupSelection.ts';

const groupA = { id: 1, groupName: 'a', createdBy: 1, members: [] };
const groupB = { id: 2, groupName: 'b', createdBy: 1, members: [] };

test('a late response for a group that is no longer open is ignored', () => {
  assert.equal(acceptDetail(groupB, groupA, 2), groupB);
});

test('a response for the open group replaces what is shown', () => {
  const updatedB = { ...groupB, members: [{ id: 3, username: 'carol' }] };
  assert.equal(acceptDetail(groupB, updatedB, 2), updatedB);
});

test('nothing is shown when no group is open', () => {
  assert.equal(acceptDetail(null, groupA, null), null);
});
