// src/app/components/schema-browser/schema-browser.component.ts
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { SchemaMetadata, TableMetadata } from '../../models/api.models';

@Component({
  selector: 'app-schema-browser',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="modal-overlay" (click)="close.emit()">
      <div class="modal-box browser-modal" (click)="$event.stopPropagation()">

        <div class="modal-header">
          <div class="header-left">
            <span class="modal-title">⬡ Schema Browser</span>
            <span class="schema-badge" *ngIf="schema">{{ schema.schemaName }}</span>
          </div>
          <button class="modal-close" (click)="close.emit()">✕</button>
        </div>

        <!-- Search -->
        <div class="search-bar">
          <span class="search-icon">⌕</span>
          <input class="search-input" type="text" [(ngModel)]="searchText"
                 placeholder="Search tables or columns..." (ngModelChange)="applyFilter()" />
          <button class="clear-btn" *ngIf="searchText" (click)="searchText=''; applyFilter()">✕</button>
        </div>

        <!-- Stats -->
        <div class="stats-bar" *ngIf="schema">
          <span class="stat">{{ filteredTables.length }} / {{ schema.tables.length }} tables</span>
          <span class="stat">{{ totalColumns }} columns</span>
          <button class="expand-all-btn" (click)="expandAll()">Expand all</button>
          <button class="expand-all-btn" (click)="collapseAll()">Collapse all</button>
        </div>

        <!-- Loading -->
        <div class="browser-loading" *ngIf="loading">
          <div class="spinner"></div> Loading schema...
        </div>

        <!-- Error -->
        <div class="browser-error" *ngIf="error">⚠ {{ error }}</div>

        <!-- Tree -->
        <div class="tree-wrapper" *ngIf="!loading && schema">
          <div class="table-node" *ngFor="let table of filteredTables">

            <!-- Table row -->
            <div class="table-row" (click)="toggleTable(table.tableName)">
              <span class="toggle-icon">{{ isExpanded(table.tableName) ? '▾' : '▸' }}</span>
              <span class="table-icon">▦</span>
              <span class="table-name" [innerHTML]="highlight(table.tableName)"></span>
              <span class="pk-badges">
                <span class="pk-badge" *ngFor="let pk of table.primaryKeys">PK:{{ pk }}</span>
              </span>
              <span class="col-count">{{ table.columns.length }} cols</span>
              <button class="use-btn" (click)="useTable($event, table.tableName)"
                      title="Use table name in prompt">+</button>
            </div>

            <!-- Table comment -->
            <div class="table-comment" *ngIf="table.tableComment && isExpanded(table.tableName)">
              {{ table.tableComment }}
            </div>

            <!-- Columns -->
            <div class="columns-list" *ngIf="isExpanded(table.tableName)">
              <div class="column-row" *ngFor="let col of getFilteredColumns(table)"
                   (click)="useColumn(table.tableName, col.columnName)">
                <span class="col-icon" [class.pk-col]="isPk(table, col.columnName)"
                                       [class.fk-col]="isFk(table, col.columnName)">
                  {{ isPk(table, col.columnName) ? '🔑' : isFk(table, col.columnName) ? '🔗' : '○' }}
                </span>
                <span class="col-name" [innerHTML]="highlight(col.columnName)"></span>
                <span class="col-type">{{ col.dataType }}{{ col.dataLength && col.dataType !== 'DATE' ? '(' + col.dataLength + ')' : '' }}</span>
                <span class="null-tag" *ngIf="col.nullable">NULL</span>
                <span class="col-comment" *ngIf="col.columnComment" [title]="col.columnComment">
                  {{ col.columnComment | slice:0:40 }}{{ col.columnComment.length > 40 ? '…' : '' }}
                </span>
              </div>

              <!-- Foreign key references -->
              <div class="fk-section" *ngIf="table.foreignKeys.length > 0">
                <div class="fk-header">Foreign Keys</div>
                <div class="fk-row" *ngFor="let fk of table.foreignKeys">
                  <span class="fk-icon">🔗</span>
                  <span class="fk-text">{{ fk.localColumn }} → {{ fk.referencedTable }}.{{ fk.referencedColumn }}</span>
                </div>
              </div>
            </div>

          </div>

          <div class="no-results" *ngIf="filteredTables.length === 0">
            No tables match "{{ searchText }}"
          </div>
        </div>

        <div class="modal-footer">
          <span class="footer-hint">Click a table name (+) or column name to insert into prompt</span>
          <button class="btn btn-ghost" (click)="close.emit()">Close</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .browser-modal {
      width: 680px; max-width: 95vw;
      height: 80vh; max-height: 80vh;
    }

    .header-left { display: flex; align-items: center; gap: 10px; }
    .schema-badge {
      font-family: 'JetBrains Mono', monospace; font-size: 11px;
      background: var(--accent-dim); color: var(--accent);
      padding: 2px 8px; border-radius: 4px; border: 1px solid var(--accent-dim);
    }

    .search-bar {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 16px; border-bottom: 1px solid var(--border);
      flex-shrink: 0;
    }
    .search-icon { color: var(--text-muted); font-size: 16px; }
    .search-input {
      flex: 1; background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 6px 10px; border-radius: 6px;
      font-size: 13px; outline: none; transition: border-color .2s;
    }
    .search-input:focus { border-color: var(--accent); }
    .clear-btn {
      background: none; border: none; color: var(--text-muted);
      cursor: pointer; font-size: 14px; padding: 2px 6px; border-radius: 4px;
    }
    .clear-btn:hover { color: var(--error-text); }

    .stats-bar {
      display: flex; align-items: center; gap: 12px;
      padding: 6px 16px; border-bottom: 1px solid var(--border-faint);
      background: var(--bg-header); flex-shrink: 0;
    }
    .stat { font-size: 11px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
    .expand-all-btn {
      font-size: 11px; background: none; border: 1px solid var(--border);
      color: var(--text-muted); padding: 2px 8px; border-radius: 4px; cursor: pointer;
      font-family: 'JetBrains Mono', monospace; transition: all .15s; margin-left: auto;
    }
    .expand-all-btn:first-of-type { margin-left: auto; }
    .expand-all-btn + .expand-all-btn { margin-left: 4px; }
    .expand-all-btn:hover { border-color: var(--accent); color: var(--accent); }

    .browser-loading, .browser-error {
      display: flex; align-items: center; justify-content: center; gap: 10px;
      padding: 32px; color: var(--text-muted); font-size: 13px;
    }
    .browser-error { color: var(--error-text); }
    .spinner {
      width: 18px; height: 18px; border-radius: 50%;
      border: 2px solid var(--border); border-top-color: var(--accent);
      animation: spin .7s linear infinite;
    }

    .tree-wrapper { flex: 1; overflow-y: auto; padding: 8px 0; }

    /* Table nodes */
    .table-node { border-bottom: 1px solid var(--border-faint); }
    .table-row {
      display: flex; align-items: center; gap: 6px;
      padding: 7px 16px; cursor: pointer; transition: background .15s;
      user-select: none;
    }
    .table-row:hover { background: var(--row-hover); }
    .toggle-icon { color: var(--text-muted); font-size: 12px; width: 12px; }
    .table-icon { color: var(--accent); font-size: 13px; }
    .table-name {
      font-family: 'JetBrains Mono', monospace; font-size: 12px;
      font-weight: 600; color: var(--text-primary); flex: 1;
    }
    .pk-badges { display: flex; gap: 4px; }
    .pk-badge {
      font-size: 9px; font-family: 'JetBrains Mono', monospace;
      background: rgba(255,200,0,0.12); color: #d4a800; border-radius: 3px; padding: 1px 5px;
    }
    .col-count { font-size: 10px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
    .use-btn {
      width: 20px; height: 20px; background: var(--accent-dim); border: 1px solid var(--accent-dim);
      color: var(--accent); border-radius: 4px; cursor: pointer; font-size: 14px;
      display: flex; align-items: center; justify-content: center;
      opacity: 0; transition: opacity .15s;
    }
    .table-row:hover .use-btn { opacity: 1; }
    .use-btn:hover { background: var(--accent); color: #fff; }

    .table-comment {
      padding: 2px 16px 6px 46px; font-size: 11px; color: var(--text-muted);
      font-style: italic; line-height: 1.4;
    }

    /* Columns */
    .columns-list { padding: 0 0 6px 0; }
    .column-row {
      display: flex; align-items: center; gap: 6px;
      padding: 4px 16px 4px 46px; cursor: pointer;
      transition: background .1s;
    }
    .column-row:hover { background: var(--row-hover); }
    .col-icon { font-size: 10px; width: 16px; text-align: center; flex-shrink: 0; }
    .pk-col { filter: brightness(1.2); }
    .col-name {
      font-family: 'JetBrains Mono', monospace; font-size: 11px;
      color: var(--text-primary); min-width: 140px;
    }
    .col-type {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      color: var(--code-text); min-width: 90px;
    }
    .null-tag {
      font-size: 9px; color: var(--text-muted); background: var(--badge-bg);
      padding: 1px 4px; border-radius: 3px; font-family: 'JetBrains Mono', monospace;
    }
    .col-comment { font-size: 10px; color: var(--text-muted); flex: 1; }

    /* FK section */
    .fk-section { padding: 4px 16px 6px 46px; }
    .fk-header { font-size: 10px; color: var(--text-muted); text-transform: uppercase;
                 letter-spacing: 0.8px; margin-bottom: 4px; font-family: 'JetBrains Mono', monospace; }
    .fk-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; }
    .fk-icon { font-size: 10px; }
    .fk-text { font-size: 11px; color: var(--text-secondary); font-family: 'JetBrains Mono', monospace; }

    .no-results { padding: 24px; text-align: center; color: var(--text-muted); font-size: 13px; }

    .modal-footer { justify-content: space-between !important; }
    .footer-hint { font-size: 11px; color: var(--text-muted); }

    .btn { padding: 7px 14px; border-radius: 6px; font-size: 13px;
           font-family: inherit; cursor: pointer; transition: all .15s; }
    .btn-ghost { background: transparent; border: 1px solid var(--border); color: var(--text-secondary); }
    .btn-ghost:hover { border-color: var(--accent); color: var(--accent); }

    :global(mark.hl) { background: rgba(255,200,0,0.3); color: inherit; border-radius: 2px; }
  `]
})
export class SchemaBrowserComponent implements OnChanges {
  @Input() schemaName = '';
  @Input() visible = false;
  @Output() close = new EventEmitter<void>();
  @Output() insertText = new EventEmitter<string>();

  schema: SchemaMetadata | null = null;
  filteredTables: TableMetadata[] = [];
  searchText = '';
  loading = false;
  error = '';
  expandedTables = new Set<string>();

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges) {
    if (changes['visible']?.currentValue && this.schemaName && !this.schema) {
      this.loadSchema();
    }
    if (changes['schemaName']?.currentValue !== changes['schemaName']?.previousValue) {
      this.schema = null;
      this.expandedTables.clear();
      if (this.visible) this.loadSchema();
    }
  }

  loadSchema() {
    this.loading = true;
    this.error = '';
    this.api.getSchemaMetadata(this.schemaName).subscribe({
      next: s => {
        this.schema = s;
        this.applyFilter();
        this.loading = false;
      },
      error: e => {
        this.error = e.error?.message || 'Failed to load schema metadata';
        this.loading = false;
      }
    });
  }

  applyFilter() {
    if (!this.schema) return;
    const q = this.searchText.toLowerCase().trim();
    if (!q) {
      this.filteredTables = this.schema.tables;
      return;
    }
    this.filteredTables = this.schema.tables.filter(t =>
      t.tableName.toLowerCase().includes(q) ||
      t.tableComment?.toLowerCase().includes(q) ||
      t.columns.some(c => c.columnName.toLowerCase().includes(q) ||
                          c.columnComment?.toLowerCase().includes(q))
    );
    // Auto-expand tables that have matching columns
    this.filteredTables.forEach(t => {
      if (t.columns.some(c => c.columnName.toLowerCase().includes(q))) {
        this.expandedTables.add(t.tableName);
      }
    });
  }

  getFilteredColumns(table: TableMetadata) {
    if (!this.searchText) return table.columns;
    const q = this.searchText.toLowerCase();
    return table.columns.filter(c =>
      c.columnName.toLowerCase().includes(q) || c.columnComment?.toLowerCase().includes(q)
    );
  }

  get totalColumns(): number {
    return this.filteredTables.reduce((sum, t) => sum + t.columns.length, 0);
  }

  toggleTable(name: string) {
    if (this.expandedTables.has(name)) this.expandedTables.delete(name);
    else this.expandedTables.add(name);
  }

  isExpanded(name: string) { return this.expandedTables.has(name); }

  expandAll()   { this.schema?.tables.forEach(t => this.expandedTables.add(t.tableName)); }
  collapseAll() { this.expandedTables.clear(); }

  isPk(table: TableMetadata, col: string): boolean {
    return table.primaryKeys?.includes(col) ?? false;
  }
  isFk(table: TableMetadata, col: string): boolean {
    return table.foreignKeys?.some(fk => fk.localColumn === col) ?? false;
  }

  useTable(e: Event, tableName: string) {
    e.stopPropagation();
    this.insertText.emit(tableName);
  }

  useColumn(tableName: string, columnName: string) {
    this.insertText.emit(`${tableName}.${columnName}`);
  }

  highlight(text: string): string {
    if (!this.searchText) return text;
    const q = this.searchText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return text.replace(new RegExp(q, 'gi'),
      m => `<mark class="hl">${m}</mark>`);
  }
}
