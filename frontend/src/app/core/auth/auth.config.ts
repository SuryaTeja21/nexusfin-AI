import { AuthConfig } from 'angular-oauth2-oidc';

export interface NexusFinRuntimeConfig {
  gatewayUrl?: string;
  keycloakIssuerUrl?: string;
  wsBrokerUrl?: string;
  demoMode?: boolean;
}

declare global {
  interface Window {
    __NEXUSFIN_CONFIG__?: NexusFinRuntimeConfig;
  }
}

export function getRuntimeConfig(): NexusFinRuntimeConfig {
  if (typeof window === 'undefined') {
    return {};
  }
  return window.__NEXUSFIN_CONFIG__ ?? {};
}

export function isDemoMode(): boolean {
  const cfg = getRuntimeConfig();
  if (typeof cfg.demoMode === 'boolean') {
    return cfg.demoMode;
  }
  if (typeof window !== 'undefined') {
    if (window.sessionStorage?.getItem('nexusfin_demo_active') === 'true') {
      return true;
    }
    const host = window.location.hostname;
    if (!cfg.gatewayUrl && host.endsWith('.vercel.app')) {
      return true;
    }
  }
  return false;
}

export function buildAuthConfig(): AuthConfig {
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost:4200';
  const cfg = getRuntimeConfig();
  const issuer = (cfg.keycloakIssuerUrl || 'http://localhost:8083/realms/nexusfin').replace(/\/+$/, '');

  return {
    issuer,
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
  const cfg = getRuntimeConfig();
  if (cfg.gatewayUrl) {
    return cfg.gatewayUrl.replace(/\/+$/, '');
  }
  if (typeof window !== 'undefined' && window.location.port === '4200') {
    return 'http://localhost:8080';
  }
  return '';
}

export function getWsBrokerUrl(): string {
  const cfg = getRuntimeConfig();
  if (cfg.wsBrokerUrl) {
    return cfg.wsBrokerUrl;
  }
  if (cfg.gatewayUrl) {
    const parsed = new URL(cfg.gatewayUrl);
    const protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${parsed.host}/ws`;
  }
  if (typeof window === 'undefined') {
    return 'ws://localhost:8080/ws';
  }
  if (window.location.port === '4200') {
    return 'ws://localhost:8080/ws';
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}
