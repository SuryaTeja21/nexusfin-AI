import { AuthConfig } from 'angular-oauth2-oidc';

export function buildAuthConfig(): AuthConfig {
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost:4200';
  return {
    issuer: 'http://localhost:8083/realms/nexusfin',
    redirectUri: `${origin}/login`,
    postLogoutRedirectUri: `${origin}/login`,
    clientId: 'nexusfin-gateway',
    responseType: 'code',
    scope: 'openid profile email roles',
    showDebugInformation: false,
    strictDiscoveryDocumentValidation: false,
  };
}

export function getApiBaseUrl(): string {
  if (typeof window !== 'undefined' && window.location.port === '4200') {
    return 'http://localhost:8080';
  }
  return '';
}

export function getWsBrokerUrl(): string {
  if (typeof window === 'undefined') {
    return 'ws://localhost:8080/ws';
  }
  if (window.location.port === '4200') {
    return 'ws://localhost:8080/ws';
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}
