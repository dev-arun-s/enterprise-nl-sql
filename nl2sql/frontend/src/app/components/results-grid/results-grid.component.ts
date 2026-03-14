// src/app/components/results-grid/results-grid.component.ts
import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { SqlExecutionResult } from '../../models/api.models';

@Component({
  selector: 'app-results-grid',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="results-panel">

      <!-- Toolbar -->
      <div class="toolbar">
        <span class="panel-title">RESULTS</span>
        <div class="toolbar-right" *ngIf="result && !result.errorMessage">
          <span class="meta-chip">{{ result.rowCount }} rows</span>
          <span class="meta-chip">{{ result.executionTimeMs }}ms</span>
          <button class="tb-btn" (click)="downloadCsv()" [disabled]="!historyId" title="Download as CSV">
            ⬇ CSV
          </button>
          <button class="tb-btn" (click)="downloadXlsx()" [disabled]="!historyId" title="Download as Excel">
            ⬇ XLSX
          </button>
        </div>
      </div>

      <!-- Empty -->
      <div class="empty-state" *ngIf="!result">
        <div class="empty-icon">◈</div>
        <div class="empty-title">No results yet</div>
        <div class="empty-sub">Generate a SQL query, then click Run Query</div>
      </div>

      <!-- Error -->
      <div class="error-state" *ngIf="result?.errorMessage">
        <div class="err-icon">⚠</div>
        <div class="err-title">Execution Error</div>
        <pre class="err-detail">{{ result!.errorMessage }}</pre>
      </div>

      <!-- Grid -->
      <div class="grid-wrapper" *ngIf="result && !result.errorMessage && result.columns?.length">
        <table class="data-table">
          <thead>
            <tr>
              <th class="rn">#</th>
              <th *ngFor="let col of result.columns">{{ col }}</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let row of paginatedRows; let i = index">
              <td class="rn">{{ offset + i + 1 }}</td>
              <td *ngFor="let cell of row" [title]="cell ?? 'NULL'">
                <span *ngIf="cell !== null && cell !== undefined">{{ cell }}</span>
                <span class="null-chip" *ngIf="cell === null || cell === undefined">NULL</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div class="pagination" *ngIf="result && totalPages > 1">
        <button class="pg-btn" (click)="goTo(0)" [disabled]="page === 0">«</button>
        <button class="pg-btn" (click)="goTo(page - 1)" [disabled]="page === 0">‹</button>
        <span class="pg-info">{{ page + 1 }} / {{ totalPages }} &nbsp;·&nbsp; {{ result.rowCount }} rows</span>
        <button class="pg-btn" (click)="goTo(page + 1)" [disabled]="page >= totalPages - 1">›</button>
        <button class="pg-btn" (click)="goTo(totalPages - 1)" [disabled]="page >= totalPages - 1">»</button>
      </div>

    </div>
  `,
  styles: [`
    .results-panel { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0; }

    .toolbar {
      display: flex; align-items: center; justify-content: space-between;
      padding: 10px 16px; border-bottom: 1px solid var(--border);
      background: var(--bg-header); flex-shrink: 0;
    }
    .panel-title {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      font-weight: 600; color: var(--text-muted); letter-spacing: 1.5px;
    }
    .toolbar-right { display: flex; align-items: center; gap: 8px; }
    .meta-chip {
      font-family: 'JetBrains Mono', monospace; font-size: 11px;
      color: var(--accent); background: var(--badge-bg);
      padding: 2px 8px; border-radius: 4px;
    }
    .tb-btn {
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      padding: 4px 10px; background: transparent;
      border: 1px solid var(--border); color: var(--text-secondary);
      border-radius: 4px; cursor: pointer; transition: all .2s;
    }
    .tb-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .tb-btn:disabled { opacity: .4; cursor: not-allowed; }

    .empty-state, .error-state {
      flex: 1; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 8px;
      padding: 32px; color: var(--text-muted);
    }
    .empty-icon  { font-size: 36px; opacity: .3; color: var(--accent); }
    .empty-title { font-size: 14px; font-weight: 600; color: var(--text-secondary); }
    .empty-sub   { font-size: 12px; }

    .err-icon  { font-size: 32px; color: var(--error-text); }
    .err-title { font-size: 14px; font-weight: 600; color: var(--error-text); }
    .err-detail {
      background: var(--error-bg); border: 1px solid var(--error-border);
      color: var(--error-text); padding: 12px; border-radius: 6px;
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      max-width: 500px; white-space: pre-wrap; word-break: break-all;
    }

    .grid-wrapper { flex: 1; overflow: auto; min-height: 0; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .data-table thead { position: sticky; top: 0; z-index: 1; }
    .data-table th {
      background: var(--table-header-bg); color: var(--text-secondary);
      font-family: 'JetBrains Mono', monospace; font-size: 11px; font-weight: 600;
      padding: 8px 12px; text-align: left; border-bottom: 2px solid var(--border);
      white-space: nowrap; letter-spacing: .3px;
    }
    .rn { width: 40px; text-align: right !important; color: var(--text-muted) !important;
          font-size: 10px !important; user-select: none; }
    .data-table td {
      padding: 7px 12px; border-bottom: 1px solid var(--border-faint);
      color: var(--text-primary); max-width: 260px;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .data-table tr:hover td { background: var(--row-hover); }
    .null-chip {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      color: var(--text-muted); background: var(--badge-bg);
      padding: 1px 5px; border-radius: 3px;
    }

    .pagination {
      display: flex; align-items: center; justify-content: center; gap: 10px;
      padding: 8px; border-top: 1px solid var(--border);
      background: var(--bg-header); flex-shrink: 0;
    }
    .pg-btn {
      width: 28px; height: 28px; background: var(--input-bg);
      border: 1px solid var(--border); color: var(--text-secondary);
      border-radius: 4px; cursor: pointer; font-size: 14px; transition: all .2s;
    }
    .pg-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .pg-btn:disabled { opacity: .4; cursor: not-allowed; }
    .pg-info { font-size: 11px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
  `]
})
export class ResultsGridComponent implements OnChanges {

  @Input() result: SqlExecutionResult | null = null;
  @Input() historyId: number | null = null;

  page = 0;
  pageSize = 50;

  get offset()      { return this.page * this.pageSize; }
  get totalPages()  { return Math.ceil((this.result?.rows?.length || 0) / this.pageSize); }
  get paginatedRows() {
    return this.result?.rows?.slice(this.offset, this.offset + this.pageSize) ?? [];
  }

  constructor(private api: ApiService) {}

  ngOnChanges(c: SimpleChanges) {
    if (c['result']) this.page = 0;
  }

  goTo(p: number) { this.page = Math.max(0, Math.min(p, this.totalPages - 1)); }

  downloadCsv() {
    if (this.historyId) window.open(this.api.getHistoryCsvUrl(this.historyId), '_blank');
  }

  downloadXlsx() {
    if (this.historyId) window.open(this.api.getHistoryXlsxUrl(this.historyId), '_blank');
  }
}
