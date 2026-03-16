// src/app/components/results-grid/results-grid.component.ts
import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { SqlExecutionResult } from '../../models/api.models';

@Component({
  selector: 'app-results-grid',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="results-panel">

      <!-- Toolbar -->
      <div class="toolbar">
        <span class="panel-title">RESULTS</span>
        <div class="toolbar-right" *ngIf="result && !result.errorMessage">
          <span class="meta-chip">{{ result.rowCount }} rows</span>
          <span class="meta-chip">{{ result.executionTimeMs }}ms</span>
          <span class="meta-chip" *ngIf="result.columns?.length">
            {{ result.columns.length }} cols
          </span>
          <button class="tb-btn" (click)="downloadCsv()" [disabled]="!historyId">⬇ CSV</button>
          <button class="tb-btn" (click)="downloadXlsx()" [disabled]="!historyId">⬇ XLSX</button>
        </div>
      </div>

      <!-- Page size selector — only shown when there are results -->
      <div class="page-size-bar" *ngIf="result && !result.errorMessage && result.rows?.length">
        <span class="ps-label">Rows per page:</span>
        <select class="ps-select" [(ngModel)]="pageSize" (ngModelChange)="onPageSizeChange()">
          <option [value]="25">25</option>
          <option [value]="50">50</option>
          <option [value]="100">100</option>
          <option [value]="250">250</option>
        </select>
        <span class="ps-info">
          Showing {{ offset + 1 }}–{{ Math.min(offset + pageSize, result.rowCount) }}
          of {{ result.rowCount }}
        </span>
      </div>

      <!-- Empty state -->
      <div class="empty-state" *ngIf="!result">
        <div class="empty-icon">◈</div>
        <div class="empty-title">No results yet</div>
        <div class="empty-sub">Generate a SQL query then click ▶ Run Query</div>
      </div>

      <!-- Error state -->
      <div class="error-state" *ngIf="result?.errorMessage">
        <div class="err-icon">⚠</div>
        <div class="err-title">Execution Error</div>
        <pre class="err-detail">{{ result!.errorMessage }}</pre>
      </div>

      <!-- Data grid — scrollable wrapper -->
      <div class="grid-wrapper"
           *ngIf="result && !result.errorMessage && result.columns?.length">
        <table class="data-table">
          <thead>
            <tr>
              <th class="rn">#</th>
              <th *ngFor="let col of result.columns"
                  (click)="sortBy(col)"
                  [class.sorted]="sortCol === col"
                  title="Click to sort">
                {{ col }}
                <span class="sort-icon" *ngIf="sortCol === col">
                  {{ sortAsc ? '▲' : '▼' }}
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let row of paginatedRows; let i = index"
                [class.alt-row]="(offset + i) % 2 === 1">
              <td class="rn">{{ offset + i + 1 }}</td>
              <td *ngFor="let cell of row; let ci = index"
                  [title]="cell ?? 'NULL'"
                  [class.null-cell]="cell === null || cell === undefined">
                <span *ngIf="cell !== null && cell !== undefined">{{ cell }}</span>
                <span class="null-chip" *ngIf="cell === null || cell === undefined">NULL</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Empty result set (query ran but returned 0 rows) -->
      <div class="zero-rows"
           *ngIf="result && !result.errorMessage && result.columns?.length && result.rowCount === 0">
        <span>Query executed successfully — 0 rows returned.</span>
      </div>

      <!-- Pagination controls -->
      <div class="pagination"
           *ngIf="result && !result.errorMessage && totalPages > 1">
        <button class="pg-btn" (click)="goTo(0)"            [disabled]="page === 0">«</button>
        <button class="pg-btn" (click)="goTo(page - 1)"     [disabled]="page === 0">‹</button>

        <!-- Page number chips -->
        <ng-container *ngFor="let p of pageNumbers">
          <button class="pg-num"
                  [class.active]="p === page"
                  (click)="goTo(p)">
            {{ p + 1 }}
          </button>
        </ng-container>

        <button class="pg-btn" (click)="goTo(page + 1)"     [disabled]="page >= totalPages - 1">›</button>
        <button class="pg-btn" (click)="goTo(totalPages - 1)" [disabled]="page >= totalPages - 1">»</button>
      </div>

    </div>
  `,
  styles: [`
    /* ── Panel shell ───────────────────────────────────────────── */
    /* :host fills the app-results-grid host element which is sized by app.component */
    :host {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-height: 0;
      overflow: hidden;
    }

    .results-panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;       /* Panel itself does NOT scroll */
      min-height: 0;          /* Required for flex children to shrink */
      height: 100%;
    }

    /* ── Toolbar ────────────────────────────────────────────────── */
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

    /* ── Page size bar ──────────────────────────────────────────── */
    .page-size-bar {
      display: flex; align-items: center; gap: 10px;
      padding: 6px 16px; border-bottom: 1px solid var(--border-faint);
      background: var(--bg-header); flex-shrink: 0;
    }
    .ps-label { font-size: 11px; color: var(--text-muted); }
    .ps-select {
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 2px 6px; border-radius: 4px; outline: none;
    }
    .ps-info { font-size: 11px; color: var(--text-muted); margin-left: auto; }

    /* ── Empty / error states ───────────────────────────────────── */
    .empty-state, .error-state {
      flex: 1; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 8px;
      padding: 32px; color: var(--text-muted);
    }
    .empty-icon  { font-size: 36px; opacity: .3; color: var(--accent); }
    .empty-title { font-size: 14px; font-weight: 600; color: var(--text-secondary); }
    .empty-sub   { font-size: 12px; }
    .err-icon    { font-size: 32px; color: var(--error-text); }
    .err-title   { font-size: 14px; font-weight: 600; color: var(--error-text); }
    .err-detail  {
      background: var(--error-bg); border: 1px solid var(--error-border);
      color: var(--error-text); padding: 12px; border-radius: 6px;
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      max-width: 500px; white-space: pre-wrap; word-break: break-all;
    }

    /* ── Grid wrapper — THIS is the scrollable element ─────────── */
    .grid-wrapper {
      flex: 1;          /* Takes all remaining vertical space */
      overflow: auto;   /* Scrolls both X and Y */
      min-height: 0;    /* Critical: allows flex child to shrink below content size */
    }

    /* ── Table ──────────────────────────────────────────────────── */
    .data-table {
      width: 100%; border-collapse: collapse; font-size: 12px;
      table-layout: auto;
    }
    .data-table thead {
      position: sticky; top: 0; z-index: 2;  /* Sticks to top of .grid-wrapper */
    }
    .data-table th {
      background: var(--table-header-bg); color: var(--text-secondary);
      font-family: 'JetBrains Mono', monospace; font-size: 11px; font-weight: 600;
      padding: 8px 12px; text-align: left;
      border-bottom: 2px solid var(--border);
      white-space: nowrap; cursor: pointer; user-select: none;
      transition: color .15s;
    }
    .data-table th:hover { color: var(--accent); }
    .data-table th.sorted { color: var(--accent); }
    .sort-icon { margin-left: 4px; font-size: 9px; }

    .rn {
      width: 44px; text-align: right !important;
      color: var(--text-muted) !important; font-size: 10px !important;
      user-select: none; background: var(--table-header-bg);
    }
    thead .rn { position: sticky; left: 0; z-index: 3; }
    tbody  .rn { position: sticky; left: 0; z-index: 1;
                 background: var(--bg-secondary); border-right: 1px solid var(--border-faint); }

    .data-table td {
      padding: 7px 12px; border-bottom: 1px solid var(--border-faint);
      color: var(--text-primary); max-width: 280px;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .data-table tr:hover td { background: var(--row-hover); }
    .alt-row td { background: var(--row-alt, rgba(0,0,0,0.015)); }
    .alt-row:hover td { background: var(--row-hover) !important; }

    .null-chip {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      color: var(--text-muted); background: var(--badge-bg);
      padding: 1px 5px; border-radius: 3px;
    }

    /* ── Zero rows notice ───────────────────────────────────────── */
    .zero-rows {
      flex-shrink: 0; padding: 16px; text-align: center;
      font-size: 12px; color: var(--text-muted);
      border-top: 1px solid var(--border-faint);
    }

    /* ── Pagination ─────────────────────────────────────────────── */
    .pagination {
      display: flex; align-items: center; justify-content: center; gap: 4px;
      padding: 8px 16px; border-top: 1px solid var(--border);
      background: var(--bg-header); flex-shrink: 0; flex-wrap: wrap;
    }
    .pg-btn {
      width: 30px; height: 28px; background: var(--input-bg);
      border: 1px solid var(--border); color: var(--text-secondary);
      border-radius: 4px; cursor: pointer; font-size: 14px; transition: all .2s;
    }
    .pg-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .pg-btn:disabled { opacity: .4; cursor: not-allowed; }
    .pg-num {
      min-width: 30px; height: 28px; padding: 0 6px;
      background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-secondary); border-radius: 4px;
      cursor: pointer; font-size: 12px;
      font-family: 'JetBrains Mono', monospace; transition: all .2s;
    }
    .pg-num:hover { border-color: var(--accent); color: var(--accent); }
    .pg-num.active {
      background: var(--accent); color: #fff;
      border-color: var(--accent); font-weight: 700;
    }
  `]
})
export class ResultsGridComponent implements OnChanges {

  @Input() result: SqlExecutionResult | null = null;
  @Input() historyId: number | null = null;

  page     = 0;
  pageSize = 50;
  sortCol  = '';
  sortAsc  = true;

  protected readonly Math = Math;

  private sortedRows: any[][] = [];

  constructor(private api: ApiService) {}

  ngOnChanges(c: SimpleChanges) {
    if (c['result']) {
      this.page    = 0;
      this.sortCol = '';
      this.sortAsc = true;
      this.buildSortedRows();
    }
  }

  get offset()     { return this.page * this.pageSize; }
  get totalPages() { return Math.ceil((this.sortedRows.length) / this.pageSize); }

  get paginatedRows(): any[][] {
    return this.sortedRows.slice(this.offset, this.offset + this.pageSize);
  }

  /** Page number chips — show at most 7 pages around current */
  get pageNumbers(): number[] {
    const total  = this.totalPages;
    const radius = 3;
    const start  = Math.max(0, Math.min(this.page - radius, total - 2 * radius - 1));
    const end    = Math.min(total - 1, start + 2 * radius);
    const pages: number[] = [];
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  goTo(p: number) {
    this.page = Math.max(0, Math.min(p, this.totalPages - 1));
  }

  onPageSizeChange() {
    this.page = 0;
  }

  sortBy(col: string) {
    if (this.sortCol === col) {
      this.sortAsc = !this.sortAsc;
    } else {
      this.sortCol = col;
      this.sortAsc = true;
    }
    this.buildSortedRows();
    this.page = 0;
  }

  private buildSortedRows() {
    if (!this.result?.rows) { this.sortedRows = []; return; }
    if (!this.sortCol)       { this.sortedRows = [...this.result.rows]; return; }

    const colIdx = this.result.columns.indexOf(this.sortCol);
    if (colIdx === -1) { this.sortedRows = [...this.result.rows]; return; }

    this.sortedRows = [...this.result.rows].sort((a, b) => {
      const av = a[colIdx], bv = b[colIdx];
      if (av === null || av === undefined) return 1;
      if (bv === null || bv === undefined) return -1;
      const an = Number(av), bn = Number(bv);
      const cmp = !isNaN(an) && !isNaN(bn)
          ? an - bn
          : String(av).localeCompare(String(bv));
      return this.sortAsc ? cmp : -cmp;
    });
  }

  downloadCsv()  { if (this.historyId) window.open(this.api.getHistoryCsvUrl(this.historyId),  '_blank'); }
  downloadXlsx() { if (this.historyId) window.open(this.api.getHistoryXlsxUrl(this.historyId), '_blank'); }
}
