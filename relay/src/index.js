// The relay of EFreeze: it receives the report of a freeze and opens an issue for it on GitHub, as a GitHub App, so
// that the users of EFreeze need no account. It opens DAILY_LIMIT issues a day at most, and CLIENT_LIMIT for the same
// address: the counters are kept by a Durable Object, which handles one request at a time.
//
// Secrets of the worker: APP_ID, INSTALLATION_ID and PRIVATE_KEY (the private key of the GitHub App, in PKCS#8).
// Variables: REPOSITORY (owner/name). Durable Object: COUNTER (DailyCounter).

export const DAILY_LIMIT = 20;
export const CLIENT_LIMIT = 3;
export const LABEL = 'freeze-report';
const MARKER = 'UI freeze of Eclipse, seen by EFreeze\n';
const MAX_REPORT = 1024 * 1024;
/** The body of an issue is limited to 65536 characters. */
const MAX_BODY = 65000;
const MAX_SUMMARY = 2000;
const API = 'https://api.github.com';

/** The token of the installation of the App, valid for an hour: it is used again until a few minutes before. */
let installation = null;

/** For the tests: forgets the token of the installation. */
export function forgetToken() {
  installation = null;
}

export default {
  async fetch(request, env) {
    return handle(request, env);
  },
};

/**
 * @param fetcher the access to GitHub, replaced by the tests
 * @param now the time, replaced by the tests
 */
export async function handle(request, env, fetcher = fetch, now = new Date()) {
  const url = new URL(request.url);
  if (url.pathname !== '/reports') {
    return answer(404, { error: 'not found' });
  }
  if (request.method !== 'POST') {
    return answer(405, { error: 'POST a report of EFreeze' });
  }
  if (Number(request.headers.get('Content-Length') || 0) > MAX_REPORT) {
    return answer(413, { error: 'report too large' });
  }
  const report = await request.text();
  if (report.length > MAX_REPORT) {
    return answer(413, { error: 'report too large' });
  }
  if (!report.startsWith(MARKER) || !/^Summary: /m.test(report) || !report.includes('\n=== End ===\n')) {
    return answer(400, { error: 'not a report of EFreeze' });
  }
  const day = now.toISOString().slice(0, 10);
  const counter = env.COUNTER.get(env.COUNTER.idFromName('issues'));
  const count = async (action, client) => (await counter.fetch(`https://counter/${action}`, {
    method: 'POST',
    body: JSON.stringify({ day, client }),
  })).json();
  const client = await hash(`${env.PRIVATE_KEY}\n${day}\n${network(request.headers.get('CF-Connecting-IP') || 'unknown')}`);
  const taken = await count('take', client);
  if (!taken.ok) {
    return answer(429, { error: taken.reason });
  }
  const issue = { title: title(report), body: body(report), labels: [LABEL] };
  try {
    let created;
    try {
      created = await github(fetcher, 'POST', `/repos/${env.REPOSITORY}/issues`, await installationToken(env, fetcher, now), issue);
    } catch (e) {
      if (e.status !== 401) {
        throw e;
      }
      // A token revoked before it expires, e.g. when the App is installed again.
      forgetToken();
      created = await github(fetcher, 'POST', `/repos/${env.REPOSITORY}/issues`, await installationToken(env, fetcher, now), issue);
    }
    return answer(201, { url: created.html_url });
  } catch (e) {
    console.error(e);
    // The issue has not been opened: it does not count.
    await count('release', client);
    return answer(502, { error: 'GitHub could not be reached' });
  }
}

/**
 * The issues opened today, in all and by address. It handles one request at a time, and its storage keeps the requests
 * of others out while it reads and writes: a count is never read by two requests.
 */
export class DailyCounter {
  constructor(state) {
    this.storage = state.storage;
  }

  async fetch(request) {
    const { day, client } = await request.json();
    let counts = await this.storage.get('counts');
    if (!counts || counts.day !== day) {
      counts = { day, total: 0, clients: {} };
    }
    if (new URL(request.url).pathname === '/release') {
      if (counts.clients[client] > 0) {
        counts.total -= 1;
        counts.clients[client] -= 1;
        await this.storage.put('counts', counts);
      }
      return Response.json({ ok: true });
    }
    if (counts.total >= DAILY_LIMIT) {
      return Response.json({ ok: false, reason: `${DAILY_LIMIT} issues have already been opened today` });
    }
    if ((counts.clients[client] || 0) >= CLIENT_LIMIT) {
      return Response.json({ ok: false, reason: `${CLIENT_LIMIT} issues have already been opened today for this address` });
    }
    counts.total += 1;
    counts.clients[client] = (counts.clients[client] || 0) + 1;
    await this.storage.put('counts', counts);
    return Response.json({ ok: true });
  }
}

/**
 * @return the network of an address: the address itself in IPv4, its prefix /64 in IPv6, where a host has many
 */
export function network(address) {
  if (!address.includes(':')) {
    return address;
  }
  const [head, tail] = address.toLowerCase().split('::');
  const start = head ? head.split(':') : [];
  const end = tail ? tail.split(':') : [];
  const groups = tail === undefined ? start : [...start, ...Array(8 - start.length - end.length).fill('0'), ...end];
  return `${groups.slice(0, 4).map((group) => group.replace(/^0+(?=.)/, '')).join(':')}::/64`;
}

/**
 * @return the hash of a text: the counter does not keep the addresses, but their hashes with a secret, which change
 *         every day
 */
async function hash(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return base64url(new Uint8Array(digest));
}

export function title(report) {
  const summary = /^Summary: (.*)$/m.exec(report);
  const duration = /^The UI thread answered again after (\d+) ms\.$/m.exec(report);
  const text = `Freeze${duration ? ` of ${duration[1]} ms` : ''}: ${summary ? summary[1] : 'no summary'}`;
  return text.length <= 200 ? text : `${text.slice(0, 197)}...`;
}

/**
 * @return the body of the issue: the report is in blocks of code, where nothing is a link, a mention or HTML
 */
export function body(report) {
  const fenced = (text) => text.replaceAll('~~~', '~ ~ ~');
  const found = /^Summary: (.*)$/m.exec(report);
  const summary = fenced(found ? found[1].slice(0, MAX_SUMMARY) : 'none');
  const start = [
    'Reported automatically by EFreeze, by a user who chose to share the reports of the freezes.',
    '',
    '~~~~text',
    summary,
    '~~~~',
    '',
    '<details><summary>Report</summary>',
    '',
    '~~~~text',
  ].join('\n');
  const end = ['~~~~', '', '</details>'].join('\n');
  let shown = fenced(report);
  const room = MAX_BODY - start.length - end.length - 100;
  if (shown.length > room) {
    // Not between the two halves of a character out of the basic plane.
    const cut = /[\uD800-\uDBFF]/.test(shown[room - 1]) ? room - 1 : room;
    shown = `${shown.slice(0, cut)}\n[... ${shown.length - cut} characters more, truncated]`;
  }
  return `${start}\n${shown}\n${end}`;
}

/**
 * @return a token of the installation of the GitHub App on the repository, valid for an hour
 */
async function installationToken(env, fetcher, now) {
  if (installation && installation.expires - now.getTime() > 5 * 60 * 1000) {
    return installation.token;
  }
  const jwt = await appJwt(env.APP_ID, env.PRIVATE_KEY, now);
  const answer = await github(fetcher, 'POST', `/app/installations/${env.INSTALLATION_ID}/access_tokens`, jwt);
  installation = { token: answer.token, expires: Date.parse(answer.expires_at) };
  return answer.token;
}

/**
 * @return the JSON Web Token of the GitHub App, signed with its private key
 */
export async function appJwt(appId, privateKey, now) {
  const seconds = Math.floor(now.getTime() / 1000);
  // Clocks drift: GitHub advises to start a minute before.
  const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify({ iat: seconds - 60, exp: seconds + 540, iss: String(appId) }));
  const key = await crypto.subtle.importKey('pkcs8', pem(privateKey), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${payload}`));
  return `${header}.${payload}.${base64url(new Uint8Array(signature))}`;
}

async function github(fetcher, method, path, token, json) {
  const response = await fetcher(`${API}${path}`, {
    method,
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'User-Agent': 'efreeze-relay',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(json ? { 'Content-Type': 'application/json' } : {}),
    },
    body: json ? JSON.stringify(json) : undefined,
  });
  if (!response.ok) {
    const error = new Error(`GitHub answered ${response.status} to ${method} ${path}: ${await response.text()}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

function pem(text) {
  const base64 = text.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function base64url(data) {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function answer(status, json) {
  return new Response(JSON.stringify(json), { status, headers: { 'Content-Type': 'application/json' } });
}
