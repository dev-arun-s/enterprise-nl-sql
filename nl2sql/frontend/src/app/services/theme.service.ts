// src/app/services/theme.service.ts
import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { Theme } from '../models/api.models';

@Injectable({ providedIn: 'root' })
export class ThemeService {

  private readonly STORAGE_KEY = 'nl2sql-theme';
  private themeSubject = new BehaviorSubject<Theme>(this.getInitialTheme());

  theme$ = this.themeSubject.asObservable();

  get current(): Theme { return this.themeSubject.value; }

  toggle() {
    this.apply(this.current === 'dark' ? 'light' : 'dark');
  }

  private apply(theme: Theme) {
    this.themeSubject.next(theme);
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(this.STORAGE_KEY, theme);
  }

  private getInitialTheme(): Theme {
    const stored = localStorage.getItem(this.STORAGE_KEY) as Theme | null;
    const preferred = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    const initial = stored ?? preferred;
    document.documentElement.setAttribute('data-theme', initial);
    return initial;
  }
}
