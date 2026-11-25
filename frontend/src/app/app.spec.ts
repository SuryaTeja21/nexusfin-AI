import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth/auth.service';
import { AppUser } from './core/models/nexusfin.models';
import { LiveFeedService } from './core/services/live-feed.service';

describe('App Shell', () => {
  const userSig = signal<AppUser | null>(null);
  const authMock = {
    user: userSig.asReadonly(),
    initialized: signal(true).asReadonly(),
    isAuthenticated: () => userSig() !== null,
    isAdmin: () => userSig()?.roles.includes('ADMIN') ?? false,
    isCustomer: () => userSig()?.roles.includes('CUSTOMER') ?? false,
    logout: vi.fn(),
  };

  const liveFeedMock = {
    disconnect: vi.fn(),
  };

  beforeEach(async () => {
    userSig.set(null);
    vi.clearAllMocks();

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authMock },
        { provide: LiveFeedService, useValue: liveFeedMock },
      ],
    }).compileComponents();
  });

  it('should render NexusFin AI brand and hide admin nav when signed out', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('NexusFin AI');
    expect(compiled.textContent).not.toContain('Fraud Console');
    expect(compiled.textContent).toContain('Sign In');
  });

  it('should show Fraud Console navigation only for ADMIN role', () => {
    userSig.set({
      username: 'customer1',
      email: 'c1@nexusfin.local',
      subject: 'sub-1',
      roles: ['CUSTOMER'],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    let text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Accounts');
    expect(text).not.toContain('Fraud Console');

    userSig.set({
      username: 'admin1',
      email: 'admin@nexusfin.local',
      subject: 'sub-admin',
      roles: ['ADMIN'],
    });
    fixture.detectChanges();
    text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Fraud Console');
  });
});
