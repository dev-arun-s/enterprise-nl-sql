// src/app/components/header/header.component.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ThemeService } from '../../services/theme.service';
import { Theme } from '../../models/api.models';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  template: `
    <header class="header">
      <div class="header-brand">
        <span class="header-icon">⬡</span>
        <span class="header-title">NL<span class="accent">2</span>SQL</span>
        <span class="header-subtitle">Natural Language Query Engine</span>
      </div>
      <div class="header-meta">
        <span class="badge">Oracle</span>
        <span class="badge">Spring AI</span>
        <button class="theme-toggle" (click)="toggleTheme()" [title]="theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'">
          <span *ngIf="theme === 'dark'">☀</span>
          <span *ngIf="theme === 'light'">☽</span>
        </button>
      </div>
    </header>
  `,
  styles: [`
    .header {
      height: 56px;
      background: var(--bg-header);
      border-bottom: 1px solid var(--border);
      display: flex; align-items: center; justify-content: space-between;
      padding: 0 24px; flex-shrink: 0;
    }
    .header-brand { display: flex; align-items: center; gap: 10px; }
    .header-icon  { font-size: 20px; color: var(--accent); }
    .header-title {
      font-family: 'JetBrains Mono', monospace;
      font-size: 18px; font-weight: 700;
      color: var(--text-primary); letter-spacing: -0.5px;
    }
    .accent { color: var(--accent); }
    .header-subtitle {
      font-size: 12px; color: var(--text-muted);
      font-family: 'JetBrains Mono', monospace;
    }
    .header-meta { display: flex; align-items: center; gap: 8px; }
    .badge {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      padding: 3px 8px; border-radius: 4px;
      background: var(--badge-bg); color: var(--accent);
      border: 1px solid var(--accent-dim);
      letter-spacing: 0.5px; text-transform: uppercase;
    }
    .theme-toggle {
      width: 32px; height: 32px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      color: var(--text-secondary); border-radius: 6px;
      cursor: pointer; font-size: 16px;
      display: flex; align-items: center; justify-content: center;
      transition: all .2s;
    }
    .theme-toggle:hover { border-color: var(--accent); color: var(--accent); }
  `]
})
export class HeaderComponent implements OnInit {
  theme: Theme = 'dark';

  constructor(private themeService: ThemeService) {}

  ngOnInit() {
    this.themeService.theme$.subscribe(t => this.theme = t);
  }

  toggleTheme() { this.themeService.toggle(); }
}
