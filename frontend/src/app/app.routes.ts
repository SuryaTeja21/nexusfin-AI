import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/guards/role.guard';
import { AccountsComponent } from './features/accounts/accounts.component';
import { LiveFeedComponent } from './features/feed/live-feed.component';
import { FraudAuditComponent } from './features/fraud/fraud-audit.component';
import { LoginComponent } from './features/login/login.component';
import { TransferComponent } from './features/transfer/transfer.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'accounts' },
  { path: 'login', component: LoginComponent },
  { path: 'callback', redirectTo: 'login', pathMatch: 'full' },
  { path: 'accounts', component: AccountsComponent, canActivate: [authGuard] },
  { path: 'transfer', component: TransferComponent, canActivate: [authGuard] },
  { path: 'feed', component: LiveFeedComponent, canActivate: [authGuard] },
  {
    path: 'fraud',
    component: FraudAuditComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
  },
  { path: '**', redirectTo: 'accounts' },
];
