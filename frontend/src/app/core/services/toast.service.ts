import { Injectable, signal } from '@angular/core';

export interface ToastMessage {
  id: string;
  kind: 'error' | 'success' | 'info';
  text: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly messages = signal<ToastMessage[]>([]);

  show(kind: ToastMessage['kind'], text: string): void {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    this.messages.update((list) => [...list.slice(-3), { id, kind, text }]);
    setTimeout(() => this.dismiss(id), 6000);
  }

  dismiss(id: string): void {
    this.messages.update((list) => list.filter((m) => m.id !== id));
  }
}
