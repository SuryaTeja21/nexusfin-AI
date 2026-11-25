import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../services/toast.service';

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};

export const roleGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const toastService = inject(ToastService);

  if (!authService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }

  const requiredRoles = (route.data?.['roles'] as string[] | undefined) ?? [];
  if (requiredRoles.length === 0 || requiredRoles.some((role) => authService.hasRole(role))) {
    return true;
  }

  toastService.show('error', 'Access denied: insufficient role privileges for this view.');
  return router.createUrlTree(['/accounts']);
};
