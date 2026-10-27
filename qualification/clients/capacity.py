#!/usr/bin/env python3
"""Independent raw RFC6578 traversal of a disposable SQL-seeded, valid iCalendar dataset."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import ssl
import sys
import time
import threading
from urllib.error import HTTPError
from urllib.parse import quote, urljoin, urlsplit
from urllib.request import Request, urlopen
import xml.etree.ElementTree as XML
import icalendar

D='{DAV:}'; C='{urn:ietf:params:xml:ns:caldav}'


def main():
    url=os.environ['BUSYSTACK_CAPACITY_URL']
    auth='Basic '+base64.b64encode((os.environ['BUSYSTACK_CLIENT_USER']+':'+Path(os.environ['BUSYSTACK_CLIENT_SECRET_FILE']).read_text().strip()).encode()).decode()
    context=ssl.create_default_context(cafile=os.environ['BUSYSTACK_CLIENT_CA'])
    metrics={'requests':0,'responseBytes':0,'largestResponseBytes':0,'pageLimit':97,'syncFallbackDisabled':True,'configurationInput':os.environ['BUSYSTACK_CLIENT_SYNC_DEPTH_INPUT']}
    metrics_lock=threading.Lock()
    def request(method,target=url,body=None,headers=None):
        response=None
        try: response=urlopen(Request(target,data=body,method=method,headers={'Authorization':auth,**(headers or {})}),context=context,timeout=60)
        except HTTPError as error: response=error
        with response:
            data=response.read(9*1024*1024)
            assert len(data)<9*1024*1024, 'Response exceeded supported serialized budget'
            with metrics_lock:
                metrics['requests']+=1;metrics['responseBytes']+=len(data);metrics['largestResponseBytes']=max(metrics['largestResponseBytes'],len(data))
            return response.status,response.headers,data
    def page(token,content):
        document=XML.Element(D+'sync-collection');XML.SubElement(document,D+'sync-token').text=token
        XML.SubElement(document,D+'sync-level').text='1'
        prop=XML.SubElement(document,D+'prop');XML.SubElement(prop,D+'getetag')
        if content: XML.SubElement(prop,C+'calendar-data')
        XML.SubElement(XML.SubElement(document,D+'limit'),D+'nresults').text='97'
        status,_,body=request('REPORT',body=XML.tostring(document),headers={'Depth':'1','Content-Type':'application/xml'})
        assert status==207, (status,body[:200])
        root=XML.fromstring(body);next_token=root.findtext(D+'sync-token');assert next_token
        rows=[];partial=False
        for response in root.findall(D+'response'):
            href=urljoin(url,response.findtext(D+'href'))
            status=response.findtext(D+'status','')
            if href.rstrip('/')==url.rstrip('/'):
                assert ' 507 ' in status, status
                assert response.find('.//'+D+'number-of-matches-within-limits') is not None
                partial=True;continue
            rows.append({'href':href,'deleted':' 404 ' in status,'etag':response.findtext('.//'+D+'getetag'),'content':response.findtext('.//'+C+'calendar-data')})
        assert len(rows)<=97
        if partial: assert rows and next_token!=token, 'Truncated sync must advance and make progress'
        return next_token,rows,partial
    def resource(name):return url+quote(name,safe='')
    def calendar(uid,summary):
        return ('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack capacity fixture//EN\r\nBEGIN:VEVENT\r\nUID:'+uid+'\r\nDTSTAMP:20261027T000000Z\r\nDTSTART:20000101T000000Z\r\nDTEND:20000101T010000Z\r\nSUMMARY:'+summary+'\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n').encode()
    began=time.monotonic();token=None;seen={};pages=0;body_bytes=0
    while True:
        previous=token;token,rows,partial=page(token,True);pages+=1
        for row in rows:
            assert not row['deleted'] and row['content'] is not None
            assert row['href'] not in seen,'Duplicate resource in initial snapshot'
            parsed=icalendar.Calendar.from_ical(row['content'])
            assert len(parsed.walk('VEVENT'))==1
            seen[row['href']]=row['etag'];body_bytes+=len(row['content'].encode())
        if pages==1:
            # The entire multi-request view must stay pinned despite names changing behind/ahead of its cursor.
            assert request('PUT',resource('capacity-00001.ics'),calendar('capacity-1','first update'),{'Content-Type':'text/calendar'})[0]==204
            assert request('PUT',resource('capacity-00001.ics'),calendar('capacity-1','second update'),{'Content-Type':'text/calendar'})[0]==204
            assert request('DELETE',resource('capacity-00002.ics'))[0]==204
            assert request('DELETE',resource('capacity-10000.ics'))[0]==204
            assert request('PUT',resource('capacity-10000.ics'),calendar('capacity-10000-new','recreated'),{'Content-Type':'text/calendar','If-None-Match':'*'})[0]==201
            assert request('PUT',resource('000-added-雪.ics'),calendar('capacity-added','inserted before boundary'),{'Content-Type':'text/calendar','If-None-Match':'*'})[0]==201
        if pages==2:
            # Replaying a continuation must not consume it or silently change the page.
            again,again_rows,again_partial=page(previous,True)
            assert again_rows==rows and again_partial==partial
            print('CAPACITY_READY_RESTART',flush=True)
            assert sys.stdin.readline().strip()=='CONTINUE_AFTER_RESTART'
            again,again_rows,again_partial=page(previous,True)
            assert again_rows==rows and again_partial==partial,'Restart must preserve continuation state'
        if not partial:break
        assert pages<200, 'No finite initial-sync completion'
    assert len(seen)==10037 and body_bytes>32*1024*1024
    assert resource('capacity-00002.ics') in seen and resource('000-added-雪.ics') not in seen
    delta=[];delta_pages=0
    while True:
        token,rows,partial=page(token,True);delta+=rows;delta_pages+=1
        if not partial:break
        assert delta_pages<200
    changes={row['href']:row for row in delta}
    assert changes[resource('capacity-00002.ics')]['deleted']
    assert 'second update' in changes[resource('capacity-00001.ics')]['content']
    assert 'recreated' in changes[resource('capacity-10000.ics')]['content']
    assert 'inserted before boundary' in changes[resource('000-added-雪.ics')]['content']
    metadata_seen=set();metadata_pages=0;metadata_token=None
    while True:
        metadata_token,rows,partial=page(metadata_token,False);metadata_pages+=1
        for row in rows:
            assert row['content'] is None and not row['deleted'];assert row['href'] not in metadata_seen;metadata_seen.add(row['href'])
        if not partial:break
        assert metadata_pages<200
    assert len(metadata_seen)==10037 and resource('000-added-雪.ics') in metadata_seen
    query=XML.Element(C+'calendar-query');XML.SubElement(XML.SubElement(query,D+'prop'),D+'getetag')
    filters=XML.SubElement(query,C+'filter');top=XML.SubElement(filters,C+'comp-filter',name='VCALENDAR');event=XML.SubElement(top,C+'comp-filter',name='VEVENT')
    XML.SubElement(event,C+'time-range',start='20261027T000000Z',end='20261028T000000Z')
    query_start=time.monotonic();status,_,body=request('REPORT',body=XML.tostring(query),headers={'Depth':'1','Content-Type':'application/xml'})
    assert status==207;assert len(XML.fromstring(body).findall(D+'response'))==1
    query_ms=round((time.monotonic()-query_start)*1000,2)
    # Many individually admitted property updates exercise aggregate storage and selected response work.
    values='x'*60000
    def patch(target,index,value):
        change=XML.Element(D+'propertyupdate');prop=XML.SubElement(XML.SubElement(change,D+'set'),D+'prop')
        XML.SubElement(prop,'{urn:busystack:capacity}p'+str(index)).text=value
        return request('PROPPATCH',target,XML.tostring(change),{'Content-Type':'application/xml'})
    targets=[resource('capacity-'+str(i).zfill(5)+'.ics') for i in range(20,32)]
    for target in targets:
        for index in range(17):
            status,_,body=patch(target,index,values)
            assert status==207 and b' 200 OK' in body
        status,_,body=patch(target,'small','selected value')
        assert status==207 and b' 200 OK' in body
    status,_,body=patch(targets[0],17,values)
    assert status in (403,507) or status==207 and b' 507 ' in body, 'Over-budget property growth must fail explicitly'
    def propfind(target,mode):
        root=XML.Element(D+'propfind')
        if mode=='one': XML.SubElement(XML.SubElement(root,D+'prop'),'{urn:busystack:capacity}psmall')
        else: XML.SubElement(root,D+mode)
        status,_,body=request('PROPFIND',target,XML.tostring(root),{'Depth':'0','Content-Type':'application/xml'})
        assert status==207
        if mode=='one': assert len(body)<4096 and b'selected value' in body and b'x'*100 not in body
        if mode=='propname': assert len(body)<32768 and b'x'*100 not in body
        if mode=='allprop': assert len(body)>1000000
        return len(body)
    with ThreadPoolExecutor(max_workers=3) as executor:
        small_responses=list(executor.map(lambda target:propfind(target,'one'),targets))
        name_responses=list(executor.map(lambda target:propfind(target,'propname'),targets))
    propfind(targets[0],'allprop')
    multi=XML.Element(C+'calendar-multiget');props=XML.SubElement(multi,D+'prop')
    for index in range(17): XML.SubElement(props,'{urn:busystack:capacity}p'+str(index))
    for target in targets: XML.SubElement(multi,D+'href').text=target
    status,_,body=request('REPORT',body=XML.tostring(multi),headers={'Content-Type':'application/xml'})
    assert status==507, 'A genuinely oversized selected-property response must fail before commit'
    assert request('GET',targets[0])[0]==200, 'Server must remain usable after limit failure'
    metrics['propertyStress']={'resources':len(targets),'acceptedPropertiesPerResource':17,'valueBytes':60000,'concurrentReaders':3,'largestSmallPropertyResponseBytes':max(small_responses),'largestPropertyNameResponseBytes':max(name_responses),'combinedSelectedPropertyLimitStatus':status,'growthLimitVerified':True,'healthyAfterLimit':True}
    metrics.update(status='passed',seededResources=10037,seededCalendarBodyBytes=body_bytes,initialContentPages=pages,initialMetadataPages=metadata_pages,incrementalPages=delta_pages,changedResources=len(changes),restartContinuationVerified=True,continuationReplayVerified=True,selectiveQueryMs=query_ms,elapsedSeconds=round(time.monotonic()-began,2),populationMethod='owned disposable SQL fixture; independently generated valid iCalendar bytes, not a measurement of HTTP bulk create throughput')
    print(json.dumps(metrics,sort_keys=True),flush=True)

if __name__=='__main__':main()
