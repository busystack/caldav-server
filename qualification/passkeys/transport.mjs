import {request} from 'node:https';
import {readFileSync} from 'node:fs';

// The temporary CA is scoped to this client; system/production trust is never changed.
export function call(base, path, {method = 'GET', headers = {}, form, body} = {}) {
  if (form !== undefined && body !== undefined) throw new Error('Specify form or body, not both');
  const data = body === undefined ? (form === undefined ? undefined : new URLSearchParams(form).toString()) : body;
  return new Promise((resolve, reject) => {
    const outgoing = request(new URL(base + path), {
      method,
      ca: readFileSync(process.env.BUSYSTACK_BROWSER_CA_FILE),
      rejectUnauthorized: true,
      headers: {...headers, ...(data === undefined ? {} : {
        'Content-Type': body === undefined ? 'application/x-www-form-urlencoded' : (headers['Content-Type'] || 'application/xml; charset=utf-8'),
        'Content-Length': Buffer.byteLength(data)
      })}
    }, incoming => {
      const chunks = [];
      let bytes = 0;
      incoming.on('data', value => {
        bytes += value.length;
        if (bytes > 65536) outgoing.destroy(new Error('Qualification response exceeds its limit'));
        else chunks.push(value);
      });
      incoming.on('error', reject);
      incoming.on('end', () => resolve({status: incoming.statusCode, body: Buffer.concat(chunks).toString('utf8')}));
    });
    outgoing.on('error', reject);
    outgoing.setTimeout(15000, () => outgoing.destroy(new Error('Qualification HTTPS request timed out')));
    outgoing.end(data);
  });
}

export function json(response) {
  try { return JSON.parse(response.body); }
  catch { throw new Error('Qualification endpoint returned malformed JSON'); }
}
