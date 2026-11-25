import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const outputFile = resolve('dist/frontend/browser/nexusfin-config.js');

const config = {};
const gatewayUrl = (process.env.NEXUSFIN_GATEWAY_URL || '').trim().replace(/\/+$/, '');
const keycloakIssuerUrl = (process.env.NEXUSFIN_KEYCLOAK_ISSUER_URL || '').trim().replace(/\/+$/, '');
const wsBrokerUrl = (process.env.NEXUSFIN_WS_BROKER_URL || '').trim();
const explicitDemo = (process.env.NEXUSFIN_DEMO_MODE || '').trim().toLowerCase();

if (gatewayUrl) config.gatewayUrl = gatewayUrl;
if (keycloakIssuerUrl) config.keycloakIssuerUrl = keycloakIssuerUrl;
if (wsBrokerUrl) config.wsBrokerUrl = wsBrokerUrl;

if (explicitDemo === 'true') {
  config.demoMode = true;
} else if (explicitDemo === 'false') {
  config.demoMode = false;
} else if (process.env.VERCEL === '1' && !gatewayUrl) {
  // Standalone Vercel deploy without a remote gateway automatically runs Sandbox Mode
  config.demoMode = true;
}

mkdirSync(dirname(outputFile), { recursive: true });
writeFileSync(outputFile, `window.__NEXUSFIN_CONFIG__ = ${JSON.stringify(config, null, 2)};\n`, 'utf8');
console.log(`Generated ${outputFile} with keys: ${Object.keys(config).join(', ') || '(defaults)'}`);
