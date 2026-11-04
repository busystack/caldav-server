import assert from 'node:assert/strict';
import {createCollection, prefixOf} from './portal-helpers.mjs';

export async function qualifyImportProcessInterruption(page,base,waitFor) {
  const id=await createCollection(page,base,'Interrupted import fixture','VEVENT');
  const file=['interrupted-a','interrupted-b'].map(uid=>'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack crash qualification//EN\r\nBEGIN:VEVENT\r\nUID:'+uid+'\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261102T090000Z\r\nSUMMARY:Disposable interruption fixture\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n').join('');
  async function preview() {
    await page.goto(base+'/portability?destination='+id);
    const upload=page.locator('form').filter({has:page.locator(`input[name=destination][value="${id}"]`)});
    await upload.locator('[name=file]').setInputFiles({name:'interruption.ics',mimeType:'text/calendar',buffer:Buffer.from(file)});
    await Promise.all([page.waitForURL(base+'/portability/preview'),upload.getByRole('button',{name:'Validate and preview import',exact:true}).click()]);
    const form=page.locator('form').filter({has:page.getByRole('button',{name:'Confirm import',exact:true})});
    await form.locator('[name=confirmed]').check();
    return form;
  }
  let form=await preview();
  const oldPath=await form.getAttribute('action');
  assert.match(oldPath,/\/portability\/import\/[a-f0-9-]{36}$/);
  console.log('READY_PORTABILITY_CRASH_ARM');
  await waitFor('PORTABILITY_CRASH_ARMED');
  // The owned PostgreSQL fixture pauses the second insert after the first write.
  const pending=Promise.allSettled([page.waitForNavigation({timeout:180000}),form.getByRole('button',{name:'Confirm import',exact:true}).click({timeout:180000})]);
  console.log('READY_PORTABILITY_CRASH_REQUESTED');
  await waitFor('PORTABILITY_CRASH_RESTARTED');
  await pending;
  const old=await page.goto(new URL(oldPath,base).toString());
  assert.equal(old.status(),412,'A process restart invalidates pending previews');
  form=await preview();
  await Promise.all([page.waitForURL(url=>url.pathname.startsWith(prefixOf(base)+'/portability/import/')),form.getByRole('button',{name:'Confirm import',exact:true}).click()]);
  await page.getByRole('heading',{name:'Import results',exact:true}).waitFor();
  assert.equal(await page.getByRole('cell',{name:'Imported',exact:true}).count(),2);
  console.log('READY_PORTABILITY_CRASH_VERIFY');
  await waitFor('PORTABILITY_CRASH_VERIFIED');
  console.log('PORTABILITY_PROCESS_INTERRUPTION_ROLLBACK_RESTART_AND_FRESH_IMPORT_PASSED');
}
