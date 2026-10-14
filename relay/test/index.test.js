import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';

import { handle, appJwt, title, body, network, forgetToken, DailyCounter, DAILY_LIMIT, CLIENT_LIMIT, LABEL } from '../src/index.js';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const NOW = new Date('2026-10-14T15:00:00Z');
const REPORT = `UI freeze of Eclipse, seen by EFreeze
Start: 2026-10-14 17:00:00.000 +02:00

=== +1012 ms: all the threads ===
Summary: The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by "Worker-1" #7.

"main" #1 prio=5 BLOCKED (UI thread)
\tat org.demo.Editor.save(Editor.java:10)

=== End ===
The UI thread answered again after 2350 ms.
`;

/** The requests to GitHub, the issues opened, and the counter of the day. */
let calls;
let issues;
let failing;
/** The token that GitHub has revoked. */
let revoked;
let env;

/**
 * @return a namespace of Durable Objects with one counter, which handles one request at a time as Cloudflare does
 */
function counterNamespace() {
  const values = new Map();
  const counter = new DailyCounter({
    storage: {
      get: async (key) => structuredClone(values.get(key)),
      put: async (key, value) => {
        values.set(key, structuredClone(value));
      },
    },
  });
  let queue = Promise.resolve();
  return {
    idFromName: (name) => name,
    get: () => ({
      fetch: (url, init) => {
        const result = queue.then(() => counter.fetch(new Request(url, init)));
        queue = result.catch(() => {});
        return result;
      },
    }),
  };
}

beforeEach(() => {
  calls = [];
  issues = [];
  failing = false;
  revoked = null;
  forgetToken();
  env = {
    APP_ID: '123',
    INSTALLATION_ID: '456',
    PRIVATE_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    REPOSITORY: 'RoiSoleil/efreeze',
    COUNTER: counterNamespace(),
  };
});

async function github(url, init) {
  calls.push({ url, method: init.method, headers: init.headers, body: init.body && JSON.parse(init.body) });
  // The answers of GitHub come later, while other requests go on.
  await new Promise((resolve) => setTimeout(resolve, 1));
  if (failing) {
    return new Response('down', { status: 503 });
  }
  if (url.endsWith('/app/installations/456/access_tokens')) {
    const tokens = calls.filter((call) => call.url.endsWith('/access_tokens')).length;
    return Response.json({ token: `installation-token-${tokens}`, expires_at: '2026-10-14T16:00:00Z' }, { status: 201 });
  }
  if (init.headers.Authorization === `Bearer ${revoked}`) {
    return new Response('Bad credentials', { status: 401 });
  }
  const issue = { html_url: `https://github.com/RoiSoleil/efreeze/issues/${issues.length + 1}` };
  issues.push(issue);
  return Response.json(issue, { status: 201 });
}

function post(report, address = '192.0.2.1', path = '/reports', now = NOW) {
  const request = new Request(`https://relay.example.org${path}`, { method: 'POST', body: report, headers: { 'CF-Connecting-IP': address } });
  return handle(request, env, github, now);
}

test('a report opens an issue with the label of the reports', async () => {
  const response = await post(REPORT);

  assert.equal(response.status, 201);
  assert.deepEqual(await response.json(), { url: 'https://github.com/RoiSoleil/efreeze/issues/1' });
  const created = calls.at(-1);
  assert.equal(created.url, 'https://api.github.com/repos/RoiSoleil/efreeze/issues');
  assert.equal(created.headers.Authorization, 'Bearer installation-token-1');
  assert.deepEqual(created.body.labels, [LABEL]);
  assert.equal(created.body.title, 'Freeze of 2350 ms: The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by "Worker-1" #7.');
  assert.ok(created.body.body.includes('\tat org.demo.Editor.save(Editor.java:10)'));
});

test('the issues of the day are limited, even for requests at the same time', async () => {
  const addresses = Array.from({ length: 2 * DAILY_LIMIT }, (_, i) => `192.0.2.${i}`);

  const statuses = await Promise.all(addresses.map(async (address) => (await post(REPORT, address)).status));

  assert.equal(statuses.filter((status) => status === 201).length, DAILY_LIMIT);
  assert.equal(statuses.filter((status) => status === 429).length, DAILY_LIMIT);
  assert.equal(issues.length, DAILY_LIMIT);
  assert.equal(calls.filter((call) => call.url.endsWith('/access_tokens')).length >= 1, true);
});

test('an address opens a few issues a day', async () => {
  for (let i = 0; i < CLIENT_LIMIT; i++) {
    assert.equal((await post(REPORT)).status, 201);
  }

  assert.equal((await post(REPORT)).status, 429);
  assert.equal((await post(REPORT, '192.0.2.99')).status, 201, 'another address');
  assert.equal(issues.length, CLIENT_LIMIT + 1);
});

test('the counts start again the next day', async () => {
  for (let i = 0; i < CLIENT_LIMIT; i++) {
    await post(REPORT);
  }

  assert.equal((await post(REPORT, '192.0.2.1', '/reports', new Date('2026-10-15T00:00:01Z'))).status, 201);
});

test('requests over the limits do not reach GitHub', async () => {
  for (let i = 0; i < CLIENT_LIMIT; i++) {
    await post(REPORT);
  }
  const before = calls.length;

  await post(REPORT);

  assert.equal(calls.length, before);
});

test('the token of the installation is used again until it expires', async () => {
  await post(REPORT, '192.0.2.1');
  await post(REPORT, '192.0.2.2');
  assert.equal(calls.filter((call) => call.url.endsWith('/access_tokens')).length, 1);

  await post(REPORT, '192.0.2.3', '/reports', new Date('2026-10-14T15:56:00Z'));
  assert.equal(calls.filter((call) => call.url.endsWith('/access_tokens')).length, 2, 'less than five minutes left');
});

test('only reports of EFreeze are accepted', async () => {
  assert.equal((await post('Hello\n')).status, 400);
  assert.equal((await post('UI freeze of Eclipse, seen by EFreeze\nspam\n')).status, 400, 'no summary and no end');
  assert.equal((await post(`${REPORT}${'x'.repeat(1024 * 1024)}`)).status, 413);
  assert.equal((await post(REPORT, '192.0.2.1', '/other')).status, 404);
  const get = await handle(new Request('https://relay.example.org/reports'), env, github, NOW);
  assert.equal(get.status, 405);
  assert.deepEqual(calls, [], 'GitHub is not asked');
});

test('GitHub that cannot be reached is a bad gateway, and the report does not count', async () => {
  failing = true;
  const error = console.error;
  console.error = () => {};
  try {
    for (let i = 0; i < CLIENT_LIMIT + 1; i++) {
      assert.equal((await post(REPORT)).status, 502);
    }
  } finally {
    console.error = error;
  }

  failing = false;
  for (let i = 0; i < CLIENT_LIMIT; i++) {
    assert.equal((await post(REPORT)).status, 201);
  }
});

test('a revoked token is asked again', async () => {
  await post(REPORT, '192.0.2.1');
  revoked = 'installation-token-1';

  assert.equal((await post(REPORT, '192.0.2.2')).status, 201);
  assert.equal(calls.filter((call) => call.url.endsWith('/access_tokens')).length, 2);
  assert.equal(calls.at(-1).headers.Authorization, 'Bearer installation-token-2');
});

test('an address in IPv6 counts for its network', async () => {
  assert.equal(network('192.0.2.1'), '192.0.2.1');
  assert.equal(network('2001:DB8:0:1:aaaa::1'), '2001:db8:0:1::/64');
  assert.equal(network('2001:db8::1'), '2001:db8:0:0::/64');
  assert.equal(network('2001:0db8:0000:0001:0:0:0:1'), '2001:db8:0:1::/64');

  for (let i = 0; i < CLIENT_LIMIT; i++) {
    assert.equal((await post(REPORT, `2001:db8:0:1::${i}`)).status, 201);
  }
  assert.equal((await post(REPORT, '2001:db8:0:1:ffff::9')).status, 429);
  assert.equal((await post(REPORT, '2001:db8:0:2::1')).status, 201);
});

test('the token of the app is signed with its private key', async () => {
  const jwt = await appJwt('123', env.PRIVATE_KEY, NOW);

  const [header, payload, signature] = jwt.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT' });
  const seconds = NOW.getTime() / 1000;
  assert.deepEqual(JSON.parse(Buffer.from(payload, 'base64url')), { iat: seconds - 60, exp: seconds + 540, iss: '123' });
  assert.ok(verify('RSA-SHA256', Buffer.from(`${header}.${payload}`), publicKey, Buffer.from(signature, 'base64url')));
});

test('the report is shown in blocks of code only', () => {
  const report = REPORT.replace(/^Summary: .*$/m, 'Summary: @someone ![x](https://example.org/x.png) </details><a href="x">~~~~\n~~~~');

  const text = body(report);

  const outside = text.replace(/~~~~text\n[\s\S]*?\n~~~~(\n|$)/g, '');
  assert.equal(outside, 'Reported automatically by EFreeze, by a user who chose to share the reports of the freezes.\n\n\n<details><summary>Report</summary>\n\n\n</details>');
  assert.ok(text.includes('@someone ![x](https://example.org/x.png) </details><a href="x">~ ~ ~~'));
});

test('a long report is truncated to the size of an issue', () => {
  const long = `${REPORT}${'~~~~'.repeat(30000)}${'x'.repeat(70000)}`;

  const text = body(long);

  assert.ok(text.length <= 65536, `${text.length}`);
  assert.ok(/\[\.\.\. \d+ characters more, truncated\]/.test(text));
  assert.ok(body(`${REPORT.replace(/^Summary: .*$/m, `Summary: ${'y'.repeat(50000)}`)}${'x'.repeat(70000)}`).length <= 65536);
});

test('a character out of the basic plane is not cut in two', () => {
  const text = body(`${REPORT}${'😀'.repeat(40000)}`);

  assert.ok(text.length <= 65536);
  assert.ok(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(text), 'no lone high surrogate');
});

test('a report without end or summary still has a title', () => {
  assert.equal(title('UI freeze of Eclipse, seen by EFreeze\n'), 'Freeze: no summary');
  assert.equal(title(`UI freeze of Eclipse, seen by EFreeze\nSummary: ${'y'.repeat(300)}\n`).length, 200);
});
