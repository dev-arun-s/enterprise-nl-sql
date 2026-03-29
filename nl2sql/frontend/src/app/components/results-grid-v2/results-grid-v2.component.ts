// src/app/components/results-grid-v2/results-grid-v2.component.ts
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
          <span class="meta-chip">{{ filteredRows.length }}<span *ngIf="filterActive"> / {{ result.rowCount }}</span> rows</span>
          <span class="meta-chip">{{ result.executionTimeMs }}ms</span>
          <span class="meta-chip filter-active-chip" *ngIf="filterActive" title="Filters active">⚲ filtered</span>
          <button class="tb-btn" (click)="toggleFilters()" [class.active-btn]="showFilters" title="Toggle column filters">⚲ Filter</button>
          <button class="tb-btn" (click)="clearFilters()" *ngIf="filterActive" title="Clear all filters">✕ Clear</button>
          <button class="tb-btn" (click)="downloadCsv()"  [disabled]="!historyId">⬇ CSV</button>
          <button class="tb-btn" (click)="downloadXlsx()" [disabled]="!historyId">⬇ XLSX</button>
        </div>
      </div>

      <!-- Page size + range -->
      <div class="page-size-bar" *ngIf="result && !result.errorMessage && filteredRows.length > 0">
        <span class="ps-label">Rows per page:</span>
        <select class="ps-select" [(ngModel)]="pageSize" (ngModelChange)="page=0">
          <option [value]="25">25</option>
          <option [value]="50">50</option>
          <option [value]="100">100</option>
          <option [value]="250">250</option>
        </select>
        <span class="ps-info">
          {{ offset + 1 }}–{{ Math.min(offset + pageSize, filteredRows.length) }} of {{ filteredRows.length }}
          <span *ngIf="filterActive"> (filtered from {{ result!.rowCount }})</span>
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

      <!-- Grid wrapper -->
      <div class="grid-wrapper" *ngIf="result && !result.errorMessage && result.columns?.length">
        <table class="data-table">
          <thead>
            <!-- Column header row -->
            <tr class="header-row">
              <th class="rn sticky-left">#</th>
              <th *ngFor="let col of result.columns; let ci = index"
                  [class.pinned-col]="isPinned(ci)"
                  [class.sorted-col]="sortCol === col"
                  [style.left]="getPinnedLeft(ci)"
                  (click)="sortBy(col)">
                <div class="th-inner">
                  <span class="th-text">{{ col }}</span>
                  <span class="sort-icon" *ngIf="sortCol === col">{{ sortAsc ? '▲' : '▼' }}</span>
                  <button class="pin-btn" (click)="togglePin($event, ci)"
                          [title]="isPinned(ci) ? 'Unpin column' : 'Pin column left'">
                    {{ isPinned(ci) ? '📌' : '📍' }}
                  </button>
                </div>
              </th>
            </tr>
            <!-- Filter row -->
            <tr class="filter-row" *ngIf="showFilters">
              <th class="rn sticky-left filter-spacer"></th>
              <th *ngFor="let col of result.columns; let ci = index"
                  [class.pinned-col]="isPinned(ci)"
                  [style.left]="getPinnedLeft(ci)">
                <input class="filter-input" type="text"
                       [(ngModel)]="columnFilters[col]"
                       (ngModelChange)="applyFilters()"
                       [placeholder]="'Filter ' + col"
                       (click)="$event.stopPropagation()" />
              </th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let row of paginatedRows; let i = index"
                [class.alt-row]="(offset + i) % 2 === 1"
                (click)="openRowDetail(row)">
              <td class="rn sticky-left">{{ offset + i + 1 }}</td>
              <td *ngFor="let cell of row; let ci = index"
                  [class.pinned-col]="isPinned(ci)"
                  [style.left]="getPinnedLeft(ci)"
                  [title]="cell ?? 'NULL'">
                <span *ngIf="cell !== null && cell !== undefined"
                      [innerHTML]="highlightFilter(String(cell), result!.columns[ci])">
                </span>
                <span class="null-chip" *ngIf="cell === null || cell === undefined">NULL</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Zero rows -->
      <div class="zero-rows" *ngIf="result && !result.errorMessage && result.columns?.length && result.rowCount === 0">
        Query executed successfully — 0 rows returned.
      </div>

      <!-- Filtered to zero -->
      <div class="zero-rows" *ngIf="result && !result.errorMessage && result.rowCount > 0 && filteredRows.length === 0">
        No rows match the current filters. <button class="link-btn" (click)="clearFilters()">Clear filters</button>
      </div>

      <!-- Pagination -->
      <div class="pagination" *ngIf="result && !result.errorMessage && totalPages > 1">
        <button class="pg-btn" (click)="goTo(0)"             [disabled]="page === 0">«</button>
        <button class="pg-btn" (click)="goTo(page - 1)"      [disabled]="page === 0">‹</button>
        <button class="pg-num" *ngFor="let p of pageNumbers"
                [class.active]="p === page" (click)="goTo(p)">{{ p + 1 }}</button>
        <button class="pg-btn" (click)="goTo(page + 1)"      [disabled]="page >= totalPages - 1">›</button>
        <button class="pg-btn" (click)="goTo(totalPages - 1)" [disabled]="page >= totalPages - 1">»</button>
      </div>

    </div>

    <!-- Row Detail Side Panel -->
    <div class="row-detail-overlay" *ngIf="selectedRow" (click)="selectedRow = null">
      <div class="row-detail-panel" (click)="$event.stopPropagation()">
        <div class="detail-header">
          <span class="detail-title">Row Detail</span>
          <span class="detail-index">Row {{ selectedRowIndex + 1 }}</span>
          <button class="modal-close" (click)="selectedRow = null">✕</button>
        </div>
        <div class="detail-body">
          <div class="detail-row" *ngFor="let col of result!.columns; let ci = index">
            <div class="detail-col-name">{{ col }}</div>
            <div class="detail-col-value" [class.null-value]="selectedRow[ci] === null || selectedRow[ci] === undefined">
              <span *ngIf="selectedRow[ci] !== null && selectedRow[ci] !== undefined">{{ selectedRow[ci] }}</span>
              <span class="null-chip" *ngIf="selectedRow[ci] === null || selectedRow[ci] === undefined">NULL</span>
            </div>
          </div>
        </div>
        <div class="detail-footer">
          <button class="tb-btn" (click)="prevRow()" [disabled]="selectedRowIndex <= 0">‹ Prev</button>
          <span class="ps-info">{{ selectedRowIndex + 1 }} / {{ filteredRows.length }}</span>
          <button class="tb-btn" (click)="nextRow()" [disabled]="selectedRowIndex >= filteredRows.length - 1">Next ›</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; flex: 1; min-height: 0; overflow: hidden; }

    .results-panel { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0; height: 100%; position: relative; }

    /* Toolbar */
    .toolbar { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; border-bottom: 1px solid var(--border); background: var(--bg-header); flex-shrink: 0; }
    .panel-title { font-family: 'JetBrains Mono', monospace; font-size: 10px; font-weight: 600; color: var(--text-muted); letter-spacing: 1.5px; }
    .toolbar-right { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .meta-chip { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--accent); background: var(--badge-bg); padding: 2px 8px; border-radius: 4px; }
    .filter-active-chip { color: var(--warning-text); background: var(--tag-update-bg); }
    .tb-btn { font-size: 11px; font-family: 'JetBrains Mono', monospace; padding: 4px 10px; background: transparent; border: 1px solid var(--border); color: var(--text-secondary); border-radius: 4px; cursor: pointer; transition: all .2s; }
    .tb-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .tb-btn:disabled { opacity: .4; cursor: not-allowed; }
    .active-btn { border-color: var(--accent) !important; color: var(--accent) !important; background: var(--accent-dim) !important; }

    /* Page size bar */
    .page-size-bar { display: flex; align-items: center; gap: 10px; padding: 5px 16px; border-bottom: 1px solid var(--border-faint); background: var(--bg-header); flex-shrink: 0; }
    .ps-label { font-size: 11px; color: var(--text-muted); }
    .ps-select { font-size: 11px; font-family: 'JetBrains Mono', monospace; background: var(--input-bg); border: 1px solid var(--border); color: var(--text-primary); padding: 2px 6px; border-radius: 4px; outline: none; }
    .ps-info { font-size: 11px; color: var(--text-muted); margin-left: auto; }

    /* States */
    .empty-state, .error-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 32px; color: var(--text-muted); }
    .empty-icon { font-size: 36px; opacity: .3; color: var(--accent); }
    .empty-title { font-size: 14px; font-weight: 600; color: var(--text-secondary); }
    .empty-sub { font-size: 12px; }
    .err-icon { font-size: 32px; color: var(--error-text); }
    .err-title { font-size: 14px; font-weight: 600; color: var(--error-text); }
    .err-detail { background: var(--error-bg); border: 1px solid var(--error-border); color: var(--error-text); padding: 12px; border-radius: 6px; font-size: 11px; font-family: 'JetBrains Mono', monospace; max-width: 500px; white-space: pre-wrap; word-break: break-all; }
    .zero-rows { flex-shrink: 0; padding: 14px; text-align: center; font-size: 12px; color: var(--text-muted); border-top: 1px solid var(--border-faint); }
    .link-btn { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 12px; text-decoration: underline; }

    /* Grid */
    .grid-wrapper { flex: 1; overflow: auto; min-height: 0; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 12px; }

    /* Sticky header */
    .data-table thead { position: sticky; top: 0; z-index: 3; }
    .header-row th { background: var(--table-header-bg); color: var(--text-secondary); font-family: 'JetBrains Mono', monospace; font-size: 11px; font-weight: 600; padding: 0; text-align: left; border-bottom: 2px solid var(--border); white-space: nowrap; cursor: pointer; user-select: none; }
    .th-inner { display: flex; align-items: center; gap: 4px; padding: 8px 10px; }
    .th-text { flex: 1; }
    .sort-icon { font-size: 9px; color: var(--accent); }
    .header-row th:hover .th-text { color: var(--accent); }
    .sorted-col .th-text { color: var(--accent); }

    .pin-btn { background: none; border: none; font-size: 11px; cursor: pointer; opacity: 0; transition: opacity .15s; padding: 0; line-height: 1; }
    .header-row th:hover .pin-btn { opacity: 1; }
    .pinned-col .pin-btn { opacity: 1; }

    /* Filter row */
    .filter-row th { background: var(--bg-elevated, var(--bg-header)); padding: 4px 6px; border-bottom: 1px solid var(--border); position: sticky; top: 37px; z-index: 3; }
    .filter-spacer { padding: 4px 6px; }
    .filter-input { width: 100%; background: var(--input-bg); border: 1px solid var(--border); color: var(--text-primary); padding: 3px 7px; border-radius: 4px; font-size: 11px; outline: none; font-family: 'JetBrains Mono', monospace; }
    .filter-input:focus { border-color: var(--accent); }

    /* Pinned columns */
    .pinned-col { position: sticky; z-index: 2; background: var(--bg-secondary); box-shadow: 2px 0 4px rgba(0,0,0,0.15); }
    thead .pinned-col { z-index: 4; background: var(--table-header-bg); }

    /* Row number */
    .rn { width: 44px; text-align: right !important; color: var(--text-muted) !important; font-size: 10px !important; user-select: none; padding: 7px 8px !important; }
    .sticky-left { position: sticky; left: 0; z-index: 2; background: var(--bg-secondary); }
    thead .sticky-left { z-index: 5; background: var(--table-header-bg); }

    /* Data rows */
    .data-table td { padding: 6px 10px; border-bottom: 1px solid var(--border-faint); color: var(--text-primary); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .data-table tr { cursor: pointer; }
    .data-table tr:hover td { background: var(--row-hover); }
    .alt-row td { background: var(--row-alt, rgba(0,0,0,0.015)); }
    .alt-row:hover td { background: var(--row-hover) !important; }

    .null-chip { font-size: 10px; font-family: 'JetBrains Mono', monospace; color: var(--text-muted); background: var(--badge-bg); padding: 1px 5px; border-radius: 3px; }

    /* Pagination */
    .pagination { display: flex; align-items: center; justify-content: center; gap: 4px; padding: 8px; border-top: 1px solid var(--border); background: var(--bg-header); flex-shrink: 0; flex-wrap: wrap; }
    .pg-btn { width: 28px; height: 26px; background: var(--input-bg); border: 1px solid var(--border); color: var(--text-secondary); border-radius: 4px; cursor: pointer; font-size: 13px; transition: all .2s; }
    .pg-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .pg-btn:disabled { opacity: .4; cursor: not-allowed; }
    .pg-num { min-width: 28px; height: 26px; padding: 0 6px; background: var(--input-bg); border: 1px solid var(--border); color: var(--text-secondary); border-radius: 4px; cursor: pointer; font-size: 11px; font-family: 'JetBrains Mono', monospace; transition: all .2s; }
    .pg-num:hover { border-color: var(--accent); color: var(--accent); }
    .pg-num.active { background: var(--accent); color: #fff; border-color: var(--accent); font-weight: 700; }

    /* Row detail panel */
    .row-detail-overlay { position: absolute; inset: 0; z-index: 10; display: flex; justify-content: flex-end; background: rgba(0,0,0,0.3); }
    .row-detail-panel { width: 380px; max-width: 90%; background: var(--bg-modal, var(--bg-header)); border-left: 1px solid var(--border); display: flex; flex-direction: column; animation: slideIn .2s ease; }
    @keyframes slideIn { from { transform: translateX(100%); } to { transform: translateX(0); } }
    .detail-header { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
    .detail-title { font-size: 14px; font-weight: 600; color: var(--text-primary); flex: 1; }
    .detail-index { font-size: 11px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
    .modal-close { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 18px; padding: 2px 6px; border-radius: 4px; }
    .modal-close:hover { color: var(--text-primary); }
    .detail-body { flex: 1; overflow-y: auto; padding: 8px 0; }
    .detail-row { display: flex; flex-direction: column; gap: 2px; padding: 8px 16px; border-bottom: 1px solid var(--border-faint); }
    .detail-col-name { font-family: 'JetBrains Mono', monospace; font-size: 10px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
    .detail-col-value { font-size: 13px; color: var(--text-primary); word-break: break-all; line-height: 1.5; }
    .null-value { color: var(--text-muted); }
    .detail-footer { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; border-top: 1px solid var(--border); flex-shrink: 0; }
  `]
})
export class ResultsGridComponent implements OnChanges {
  @Input() result: SqlExecutionResult | null = null;
  @Input() historyId: number | null = null;

  page = 0; pageSize = 50;
  sortCol = ''; sortAsc = true;
  showFilters = false;
  columnFilters: Record<string, string> = {};
  pinnedColumns = new Set<number>();

  filteredRows: any[][] = [];
  sortedRows:   any[][] = [];

  selectedRow: any[] | null = null;
  selectedRowIndex = 0;

  protected readonly Math = Math;
  protected readonly String = String;

  constructor(private api: ApiService) {}

  ngOnChanges(c: SimpleChanges) {
    if (c['result']) {
      this.page = 0; this.sortCol = ''; this.sortAsc = true;
      this.columnFilters = {}; this.selectedRow = null;
      this.pinnedColumns.clear();
      this.buildSortedRows();
      this.applyFilters();
    }
  }

  get offset()     { return this.page * this.pageSize; }
  get totalPages() { return Math.ceil(this.filteredRows.length / this.pageSize); }
  get filterActive(): boolean {
    return Object.values(this.columnFilters).some(v => v?.trim());
  }

  get paginatedRows(): any[][] {
    return this.filteredRows.slice(this.offset, this.offset + this.pageSize);
  }

  get pageNumbers(): number[] {
    const total = this.totalPages, r = 3;
    const start = Math.max(0, Math.min(this.page - r, total - 2*r - 1));
    const end   = Math.min(total - 1, start + 2*r);
    const pages: number[] = [];
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  applyFilters() {
    if (!this.filterActive) { this.filteredRows = [...this.sortedRows]; return; }
    const cols = this.result?.columns ?? [];
    this.filteredRows = this.sortedRows.filter(row =>
      cols.every((col, ci) => {
        const f = this.columnFilters[col]?.trim().toLowerCase();
        if (!f) return true;
        const v = row[ci];
        return v !== null && v !== undefined && String(v).toLowerCase().includes(f);
      })
    );
    this.page = 0;
  }

  clearFilters() { this.columnFilters = {}; this.applyFilters(); }
  toggleFilters() { this.showFilters = !this.showFilters; }

  sortBy(col: string) {
    this.sortAsc = this.sortCol === col ? !this.sortAsc : true;
    this.sortCol = col;
    this.buildSortedRows();
    this.applyFilters();
    this.page = 0;
  }

  private buildSortedRows() {
    if (!this.result?.rows) { this.sortedRows = []; return; }
    if (!this.sortCol)       { this.sortedRows = [...this.result.rows]; return; }
    const ci = this.result.columns.indexOf(this.sortCol);
    this.sortedRows = [...this.result.rows].sort((a, b) => {
      const av = a[ci], bv = b[ci];
      if (av == null) return 1; if (bv == null) return -1;
      const an = Number(av), bn = Number(bv);
      const cmp = !isNaN(an) && !isNaN(bn) ? an - bn : String(av).localeCompare(String(bv));
      return this.sortAsc ? cmp : -cmp;
    });
  }

  /* Column pinning */
  isPinned(ci: number)   { return this.pinnedColumns.has(ci); }
  togglePin(e: Event, ci: number) {
    e.stopPropagation();
    if (this.pinnedColumns.has(ci)) this.pinnedColumns.delete(ci);
    else this.pinnedColumns.add(ci);
  }
  getPinnedLeft(ci: number): string {
    if (!this.isPinned(ci)) return '';
    // Sum widths of earlier pinned columns (rn col = 52px, data col ≈ 120px)
    const earlier = [...this.pinnedColumns].filter(p => p < ci).length;
    return (52 + earlier * 120) + 'px';
  }

  /* Row detail */
  openRowDetail(row: any[]) {
    this.selectedRowIndex = this.filteredRows.indexOf(row);
    this.selectedRow = row;
  }
  prevRow() { if (this.selectedRowIndex > 0) { this.selectedRowIndex--; this.selectedRow = this.filteredRows[this.selectedRowIndex]; } }
  nextRow() { if (this.selectedRowIndex < this.filteredRows.length - 1) { this.selectedRowIndex++; this.selectedRow = this.filteredRows[this.selectedRowIndex]; } }

  goTo(p: number) { this.page = Math.max(0, Math.min(p, this.totalPages - 1)); }

  /* Filter highlight */
  highlightFilter(value: string, col: string): string {
    const f = this.columnFilters[col]?.trim();
    if (!f) return value;
    const escaped = f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return value.replace(new RegExp(escaped, 'gi'), m => `<mark style="background:rgba(255,200,0,0.35);border-radius:2px">${m}</mark>`);
  }

  downloadCsv()  { if (this.historyId) window.open(this.api.getHistoryCsvUrl(this.historyId),  '_blank'); }
  downloadXlsx() { if (this.historyId) window.open(this.api.getHistoryXlsxUrl(this.historyId), '_blank'); }
}
