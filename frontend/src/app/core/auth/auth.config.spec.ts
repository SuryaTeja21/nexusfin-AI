import { buildAuthConfig, getApiBaseUrl, getWsBrokerUrl, isDemoMode } from './auth.config';

describe('auth.config runtime environment resolution', () => {
  afterEach(() => {
    delete window.__NEXUSFIN_CONFIG__;
    window.sessionStorage.clear();
  });

  it('uses local defaults when __NEXUSFIN_CONFIG__ is empty', () => {
    window.__NEXUSFIN_CONFIG__ = {};
    const auth = buildAuthConfig();
    expect(auth.issuer).toBe('http://localhost:8083/realms/nexusfin');
    expect(getApiBaseUrl()).toBe('');
    expect(getWsBrokerUrl()).toMatch(/^wss?:\/\/.+\/ws$/);
    expect(isDemoMode()).toBe(false);
  });

  it('derives HTTPS API and WSS WebSocket URLs when remote gateway config is injected', () => {
    window.__NEXUSFIN_CONFIG__ = {
      gatewayUrl: 'https://api.example.sslip.io/',
      keycloakIssuerUrl: 'https://auth.example.sslip.io/realms/nexusfin/',
      demoMode: false,
    };

    const auth = buildAuthConfig();
    expect(auth.issuer).toBe('https://auth.example.sslip.io/realms/nexusfin');
    expect(getApiBaseUrl()).toBe('https://api.example.sslip.io');
    expect(getWsBrokerUrl()).toBe('wss://api.example.sslip.io/ws');
    expect(isDemoMode()).toBe(false);
  });

  it('enables sandbox demo mode cleanly when configured', () => {
    window.__NEXUSFIN_CONFIG__ = { demoMode: true };
    expect(isDemoMode()).toBe(true);
  });
});
