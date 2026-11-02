"""Record actual protocol behavior and separately supplied configuration provenance."""
import os


def verify_sync_profile(client, calendar):
    body = '<D:sync-collection xmlns:D="DAV:"><D:sync-token/><D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>'
    results = {}
    for depth in (None, '0', '1'):
        headers = {'Content-Type': 'application/xml; charset=utf-8'}
        if depth is not None:
            headers['Depth'] = depth
        response = client.request(str(calendar.url), method='REPORT', body=body, headers=headers)
        results['missing' if depth is None else depth] = response.status
    assert results == {'missing': 207, '0': 207, '1': 207}, results
    rejected = client.request(str(calendar.url), method='REPORT', body=body.replace('<D:sync-level>1</D:sync-level>', '<D:sync-level>2</D:sync-level>'), headers={'Depth': '1', 'Content-Type': 'application/xml'})
    assert rejected.status in (400, 403), rejected.status
    return {'configurationInput': os.environ.get('BUSYSTACK_CLIENT_SYNC_DEPTH_INPUT', 'not reported by calling harness'), 'observedDepthStatus': results, 'depthOneWithOtherSyncLevelStatus': rejected.status, 'allowSyncDepthOne': results['1'] == 207, 'verifiedBy': 'actual authenticated REPORTs to this collection'}
