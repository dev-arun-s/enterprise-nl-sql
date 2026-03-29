// src/app/components/history/history.component.ts
import { Component, EventEmitter, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { RefreshService } from '../../services/refresh.service';
import { QueryHistory } from '../../models/api.models';

@Component({
  selector: 'app-history',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="history-panel">
      <div class="history-toolbar">
        <span class="panel-title">QUERY HISTORY</span>
        <span class="count-chip" *ngIf="totalElements > 0">{{ totalElements }}</span>
        <button class="refresh-btn" (click)="load()" title="Refresh">↻</button>
      </div>

      <div class="history-empty" *ngIf="items.length === 0 && !loading">No queries yet</div>

      <div class="history-list">
        <div class="history-item" *ngFor="let item of items" (click)="select(item)">
          <div class="item-top">
            <span class="schema-chip">{{ item.schemaName }}</span>
            <span class="item-time">{{ formatTime(item.createdAt) }}</span>
            <span class="status-ok" *ngIf="item.executed && !item.errorMessage">
              {{ item.rowCount }} rows
            </span>
            <span class="status-err" *ngIf="item.errorMessage">✗</span>
            <button class="del-btn" (click)="delete($event, item.id)" title="Delete">✕</button>
          </div>
          <div class="item-prompt">{{ item.naturalLanguagePrompt }}</div>
          <div class="item-sql">{{ truncate(item.generatedSql, 80) }}</div>
        </div>
      </div>

      <div class="pagination" *ngIf="totalPages > 1">
        <button class="pg-btn" (click)="prev()" [disabled]="page === 0">‹ Prev</button>
        <span class="pg-info">{{ page + 1 }} / {{ totalPages }}</span>
        <button class="pg-btn" (click)="next()" [disabled]="page >= totalPages - 1">Next ›</button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 0;
      overflow: hidden;
    }

    .history-panel {
      display: flex; flex-direction: column;
      border-top: 1px solid var(--border); background: var(--bg-primary);
      overflow: hidden;
      flex: 1;       /* fills the :host which is sized by app.component */
      min-height: 0;
    }
    .history-toolbar {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 16px; background: var(--bg-header);
      border-bottom: 1px solid var(--border); flex-shrink: 0;
    }
    .panel-title {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      font-weight: 600; color: var(--text-muted); letter-spacing: 1.5px; flex: 1;
    }
    .count-chip {
      font-size: 11px; color: var(--accent); background: var(--badge-bg);
      padding: 1px 7px; border-radius: 10px; font-family: 'JetBrains Mono', monospace;
    }
    .refresh-btn { background: none; border: none; color: var(--text-muted);
      cursor: pointer; font-size: 14px; transition: color .2s; }
    .refresh-btn:hover { color: var(--accent); }

    .history-empty { padding: 14px 16px; font-size: 12px; color: var(--text-muted); }
    .history-list  { overflow-y: auto; flex: 1; }

    .history-item {
      padding: 9px 16px; border-bottom: 1px solid var(--border-faint);
      cursor: pointer; transition: background .15s;
      display: flex; flex-direction: column; gap: 3px;
    }
    .history-item:hover { background: var(--row-hover); }

    .item-top { display: flex; align-items: center; gap: 6px; }
    .schema-chip {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      color: var(--accent); background: var(--accent-dim); padding: 1px 6px; border-radius: 3px;
    }
    .item-time { font-size: 11px; color: var(--text-muted); flex: 1; }
    .status-ok  { font-size: 10px; color: var(--success-text); font-family: 'JetBrains Mono', monospace; }
    .status-err { font-size: 10px; color: var(--error-text); }
    .del-btn {
      background: none; border: none; color: var(--text-muted);
      cursor: pointer; font-size: 11px; opacity: 0; transition: all .2s;
    }
    .history-item:hover .del-btn { opacity: 1; }
    .del-btn:hover { color: var(--error-text); }

    .item-prompt {
      font-size: 12px; color: var(--text-primary);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .item-sql {
      font-family: 'JetBrains Mono', monospace; font-size: 10px; color: var(--text-muted);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }

    .pagination {
      display: flex; align-items: center; justify-content: center; gap: 12px;
      padding: 6px; border-top: 1px solid var(--border);
      background: var(--bg-header); flex-shrink: 0;
    }
    .pg-btn {
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      padding: 4px 10px; background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-secondary); border-radius: 4px; cursor: pointer; transition: all .2s;
    }
    .pg-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .pg-btn:disabled { opacity: .4; cursor: not-allowed; }
    .pg-info { font-size: 11px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
  `]
})
export class HistoryComponent implements OnInit, OnDestroy {
  @Output() querySelected = new EventEmitter<{ sql: string; historyId: number }>();

  items: QueryHistory[] = [];
  loading = false;
  page = 0; pageSize = 10;
  totalPages = 1; totalElements = 0;

  private sub = new Subscription();

  constructor(private api: ApiService, private refreshService: RefreshService) {}

  ngOnInit() {
    this.load();
    // Auto-reload whenever a query is generated or executed
    this.sub.add(
      this.refreshService.history$.subscribe(() => {
        this.page = 0;   // always jump back to first page so new entry is visible
        this.load();
      })
    );
  }

  ngOnDestroy() { this.sub.unsubscribe(); }

  load() {
    this.loading = true;
    this.api.getHistory(undefined, this.page, this.pageSize).subscribe({
      next: r => { this.items = r.content; this.totalPages = r.totalPages; this.totalElements = r.totalElements; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  select(item: QueryHistory) { this.querySelected.emit({ sql: item.generatedSql, historyId: item.id }); }
  delete(e: Event, id: number) { e.stopPropagation(); this.api.deleteHistory(id).subscribe(() => this.load()); }
  prev() { if (this.page > 0) { this.page--; this.load(); } }
  next() { if (this.page < this.totalPages - 1) { this.page++; this.load(); } }

  formatTime(dt: string): string {
    const d = new Date(dt);
    return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) +
           ' ' + d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
  truncate(s: string, n: number): string { return s?.length > n ? s.substring(0, n) + '...' : s; }
}