# The relay of EFreeze

EFreeze can share the reports of the freezes in public issues of this repository, without an account on GitHub: the
plug-in sends the report to this relay, a [Cloudflare Worker](https://developers.cloudflare.com/workers/), which opens
the issue as a GitHub App. The relay opens 20 issues a day at most (UTC), and 3 for the same address, with the label
`freeze-report`: a Durable Object counts them, one request at a time, and keeps a hash of the addresses, not the
addresses. It accepts only the reports of EFreeze, of 1 MB at most, and shows the report in blocks of code, truncated to
the size of an issue.

The free plan of Cloudflare Workers is enough: 100 000 requests a day, Durable Objects included, and the relay needs
about a millisecond of processor to sign the token of the App, once an hour.

## Setting up

1. Create the GitHub App: *Settings > Developer settings > GitHub Apps > New GitHub App*.
   - Name: `EFreeze reports`, homepage: `https://github.com/RoiSoleil/efreeze`.
   - Webhook: not active.
   - Repository permissions: *Issues: Read and write* (*Metadata: Read-only* comes with it). No other permission.
   - Where can this GitHub App be installed: *Only on this account*.
2. In the page of the App, note its *App ID*, and generate a private key. GitHub gives it in PKCS#1, the Worker needs it
   in PKCS#8:
   ```shell
   openssl pkcs8 -topk8 -nocrypt -in efreeze-reports.*.private-key.pem -out private-key-pkcs8.pem
   ```
3. Install the App (*Install App*) on the repository `RoiSoleil/efreeze` only. The address of the page of the
   installation ends with its *installation ID*: `.../settings/installations/<installation ID>`.
4. Create the label of the reports:
   ```shell
   gh label create freeze-report --repo RoiSoleil/efreeze --description "Report of a freeze shared by EFreeze"
   ```
5. Deploy the relay with your account on Cloudflare:
   ```shell
   cd relay
   npx wrangler login
   npx wrangler secret put APP_ID
   npx wrangler secret put INSTALLATION_ID
   npx wrangler secret put PRIVATE_KEY < private-key-pkcs8.pem
   npx wrangler deploy
   ```
   `wrangler deploy` tells the address of the relay: `https://efreeze-relay.<subdomain>.workers.dev`. The plug-in sends
   the reports to `<address>/reports`: its default is `PreferenceInitializer.DEFAULT_RELAY`.

Then delete the private key from your disk: Cloudflare keeps it, encrypted, and GitHub can generate another one.

## Tests

```shell
cd relay
npm test
```
