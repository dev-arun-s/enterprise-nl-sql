// src/app/components/favourites/favourites.component.ts
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { FavouriteQuery } from '../../models/api.models';

@Component({
  selector: 'app-favourites',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="fav-panel">
      <div class="fav-toolbar">
        <span class="panel-title">★ FAVOURITES</span>
        <span class="count-chip" *ngIf="items.length">{{ items.length }}</span>
        <button class="refresh-btn" (click)="load()" title="Refresh">↻</button>
      </div>

      <div class="fav-empty" *ngIf="items.length === 0">
        <span>No saved favourites yet. Click ★ Save on a generated query.</span>
      </div>

      <div class="fav-list">
        <div class="fav-item" *ngFor="let item of items" (click)="select(item)">
          <div class="fav-top">
            <span class="fav-schema">{{ item.schemaName }}</span>
            <span class="fav-time">{{ formatTime(item.savedAt!) }}</span>
            <button class="del-btn" (click)="delete($event, item.id!)" title="Remove favourite">✕</button>
          </div>
          <div class="fav-title">{{ item.title }}</div>
          <div class="fav-prompt">{{ item.prompt }}</div>
          <div class="fav-sql">{{ truncate(item.sql, 90) }}</div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .fav-panel {
      flex-shrink: 0; max-height: 38%;
      display: flex; flex-direction: column;
      border-top: 1px solid var(--border); background: var(--bg-primary);
      overflow: hidden;
    }
    .fav-toolbar {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 16px; background: var(--bg-header);
      border-bottom: 1px solid var(--border); flex-shrink: 0;
    }
    .panel-title {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      font-weight: 600; color: var(--fav-star); letter-spacing: 1.5px; flex: 1;
    }
    .count-chip {
      font-size: 11px; color: var(--fav-star); background: rgba(227,179,65,0.10);
      padding: 1px 7px; border-radius: 10px; font-family: 'JetBrains Mono', monospace;
    }
    .refresh-btn { background: none; border: none; color: var(--text-muted);
      cursor: pointer; font-size: 14px; transition: color .2s; }
    .refresh-btn:hover { color: var(--accent); }

    .fav-empty { padding: 14px 16px; font-size: 12px; color: var(--text-muted); }

    .fav-list { overflow-y: auto; flex: 1; }
    .fav-item {
      padding: 10px 16px; border-bottom: 1px solid var(--border-faint);
      cursor: pointer; transition: background .15s;
      display: flex; flex-direction: column; gap: 3px;
    }
    .fav-item:hover { background: var(--row-hover); }

    .fav-top { display: flex; align-items: center; gap: 6px; }
    .fav-schema {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      color: var(--accent); background: var(--accent-dim);
      padding: 1px 6px; border-radius: 3px;
    }
    .fav-time { font-size: 11px; color: var(--text-muted); flex: 1; }
    .del-btn {
      background: none; border: none; color: var(--text-muted); cursor: pointer;
      font-size: 11px; padding: 2px 4px; border-radius: 3px; opacity: 0; transition: all .2s;
    }
    .fav-item:hover .del-btn { opacity: 1; }
    .del-btn:hover { background: var(--error-bg); color: var(--error-text); }

    .fav-title {
      font-size: 12px; font-weight: 600; color: var(--text-primary);
      color: var(--fav-star);
    }
    .fav-prompt {
      font-size: 12px; color: var(--text-secondary);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .fav-sql {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      color: var(--text-muted);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
  `]
})
export class FavouritesComponent implements OnInit {
  @Output() querySelected = new EventEmitter<{ sql: string; prompt: string; schemaName: string }>();

  items: FavouriteQuery[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() { this.load(); }

  load() {
    this.api.getFavourites().subscribe({ next: r => this.items = r, error: () => {} });
  }

  select(item: FavouriteQuery) {
    this.querySelected.emit({ sql: item.sql, prompt: item.prompt, schemaName: item.schemaName });
  }

  delete(e: Event, id: string) {
    e.stopPropagation();
    this.api.deleteFavourite(id).subscribe(() => this.load());
  }

  formatTime(dt: string): string {
    return new Date(dt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }

  truncate(s: string, n: number): string {
    return s?.length > n ? s.substring(0, n) + '...' : s;
  }
}
