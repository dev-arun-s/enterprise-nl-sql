// src/app/components/query-editor/query-editor.component.ts
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { GenerateSqlResponse, FavouriteQuery, SecurityConfig } from '../../models/api.models';

@Component({
  selector: 'app-query-editor',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="editor-panel">

      <!-- Schema Selector -->
      <div class="section">
        <div class="section-label">SCHEMA</div>
        <div class="schema-row">
          <select class="select" [(ngModel)]="selectedSchema" [disabled]="loading">
            <option value="" disabled>Select schema...</option>
            <option *ngFor="let s of schemas" [value]="s">{{ s }}</option>
          </select>
          <button class="icon-btn" (click)="refreshSchemas()" title="Refresh schema list">
            <span [class.spinning]="refreshing">↻</span>
          </button>
          <button class="icon-btn" (click)="showExtractModal = true"
                  [disabled]="!selectedSchema" title="Extract / refresh metadata from Oracle">
            ⬇
          </button>
        </div>
      </div>

      <!-- Prompt Input -->
      <div class="section">
        <div class="section-label">NATURAL LANGUAGE PROMPT</div>
        <textarea class="prompt-textarea" [(ngModel)]="prompt"
          placeholder="e.g. Show all employees in Sales hired after 2020, with their manager's name..."
          rows="5" [disabled]="loading">
        </textarea>
        <div class="char-count">{{ prompt.length }} chars</div>
      </div>

      <!-- Quick Examples -->
      <div class="section">
        <div class="section-label">QUICK EXAMPLES</div>
        <div class="chips">
          <button class="chip" *ngFor="let ex of examples" (click)="prompt = ex">{{ ex }}</button>
        </div>
      </div>

      <!-- Action Buttons -->
      <div class="section actions-row">
        <button class="btn btn-primary flex1" (click)="generate()" [disabled]="!canGenerate">
          <span *ngIf="!loading">⚡ Generate SQL</span>
          <span *ngIf="loading" class="loading-row"><span class="dot-pulse"></span>Generating...</span>
        </button>
      </div>

      <!-- Error -->
      <div class="error-banner" *ngIf="error">
        <span>⚠</span> {{ error }}
      </div>

      <!-- Generated SQL Block -->
      <div class="section" *ngIf="lastResponse">
        <div class="sql-header-row">
          <div class="section-label">GENERATED SQL</div>
          <div class="sql-actions">
            <button class="pill-btn" (click)="copySql()" title="Copy SQL">
              {{ copied ? '✓ Copied' : '⎘ Copy' }}
            </button>
            <button class="pill-btn" (click)="openExplain()" [disabled]="explaining" title="Explain this SQL">
              <span *ngIf="!explaining">💡 Explain</span>
              <span *ngIf="explaining">...</span>
            </button>
            <button class="pill-btn star-btn" (click)="openSaveFavourite()" title="Save as favourite">
              ★ Save
            </button>
          </div>
        </div>
        <div class="sql-box">
          <pre>{{ lastResponse.generatedSql }}</pre>
        </div>

        <!-- SQL type badge -->
        <div class="sql-type-row">
          <span class="type-tag" [ngClass]="getSqlTypeClass(lastResponse.generatedSql)">
            {{ getSqlType(lastResponse.generatedSql) }}
          </span>
          <span class="schema-tag">{{ lastResponse.schemaName }}</span>
        </div>

        <!-- Run Button -->
        <button class="btn btn-run" (click)="run()" [disabled]="running || !canRun(lastResponse.generatedSql)">
          <span *ngIf="!running">▶ Run Query</span>
          <span *ngIf="running" class="loading-row"><span class="dot-pulse dot-white"></span>Running...</span>
        </button>
        <div class="run-blocked" *ngIf="!canRun(lastResponse.generatedSql)">
          <span>⊘</span> This query type is not permitted by current configuration.
        </div>
      </div>

    </div>

    <!-- Extract Metadata Modal -->
    <div class="modal-overlay" *ngIf="showExtractModal" (click)="showExtractModal = false">
      <div class="modal-box extract-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <span class="modal-title">Extract Schema Metadata</span>
          <button class="modal-close" (click)="showExtractModal = false">✕</button>
        </div>
        <div class="modal-body">
          <p class="modal-desc">
            Extract or refresh metadata for <strong>{{ selectedSchema }}</strong> from Oracle.
            This queries <code>ALL_TAB_COLUMNS</code>, <code>ALL_CONSTRAINTS</code>,
            <code>ALL_INDEXES</code>, and comment views.
          </p>
          <div class="extract-result success" *ngIf="extractResult">
            ✓ {{ extractResult.tableCount }} tables extracted at {{ formatTime(extractResult.extractedAt) }}
          </div>
          <div class="extract-result error" *ngIf="extractError">
            ✗ {{ extractError }}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" (click)="showExtractModal = false">Close</button>
          <button class="btn btn-primary" (click)="extractMetadata()" [disabled]="extracting">
            <span *ngIf="!extracting">⬇ Extract Now</span>
            <span *ngIf="extracting" class="loading-row"><span class="dot-pulse"></span>Extracting...</span>
          </button>
        </div>
      </div>
    </div>

    <!-- Explain SQL Modal -->
    <div class="modal-overlay" *ngIf="showExplainModal" (click)="showExplainModal = false">
      <div class="modal-box explain-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <span class="modal-title">💡 SQL Explanation</span>
          <button class="modal-close" (click)="showExplainModal = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="sql-snippet-box">
            <pre>{{ lastResponse?.generatedSql }}</pre>
          </div>
          <div class="explanation-text" *ngIf="explanation">{{ explanation }}</div>
          <div class="explanation-loading" *ngIf="explaining">
            <span class="spinner-sm"></span> Asking the LLM to explain...
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" (click)="showExplainModal = false">Close</button>
        </div>
      </div>
    </div>

    <!-- Save Favourite Modal -->
    <div class="modal-overlay" *ngIf="showFavModal" (click)="showFavModal = false">
      <div class="modal-box fav-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <span class="modal-title">★ Save as Favourite</span>
          <button class="modal-close" (click)="showFavModal = false">✕</button>
        </div>
        <div class="modal-body">
          <label class="field-label">Title</label>
          <input class="field-input" type="text" [(ngModel)]="favTitle"
                 placeholder="e.g. Sales employees hired after 2020" maxlength="120" />
          <div class="field-hint">{{ favTitle.length }}/120</div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" (click)="showFavModal = false">Cancel</button>
          <button class="btn btn-primary" (click)="saveFavourite()" [disabled]="!favTitle.trim() || savingFav">
            {{ savingFav ? 'Saving...' : '★ Save' }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .editor-panel { padding: 20px; display: flex; flex-direction: column; gap: 18px; }

    .section { display: flex; flex-direction: column; gap: 8px; }
    .section-label {
      font-family: 'JetBrains Mono', monospace; font-size: 10px;
      font-weight: 600; color: var(--text-muted); letter-spacing: 1.5px;
    }

    .schema-row { display: flex; gap: 6px; align-items: center; }
    .select {
      flex: 1; background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 8px 12px; border-radius: 6px;
      font-size: 13px; font-family: 'JetBrains Mono', monospace; outline: none;
      transition: border-color .2s;
    }
    .select:focus { border-color: var(--accent); }

    .icon-btn {
      width: 32px; height: 32px; background: var(--input-bg);
      border: 1px solid var(--border); color: var(--text-muted);
      border-radius: 6px; cursor: pointer; font-size: 16px;
      display: flex; align-items: center; justify-content: center;
      transition: all .2s;
    }
    .icon-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .icon-btn:disabled { opacity: .4; cursor: not-allowed; }

    .spinning { display: inline-block; animation: spin .8s linear infinite; }

    .prompt-textarea {
      background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 12px; border-radius: 6px;
      font-size: 13px; font-family: inherit; resize: vertical;
      min-height: 100px; outline: none; line-height: 1.6; transition: border-color .2s;
    }
    .prompt-textarea:focus { border-color: var(--accent); }
    .char-count { font-size: 11px; color: var(--text-muted); text-align: right; }

    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip {
      font-size: 11px; padding: 4px 10px;
      background: var(--badge-bg); border: 1px solid var(--border);
      color: var(--text-secondary); border-radius: 20px; cursor: pointer;
      transition: all .2s; font-family: inherit; white-space: nowrap;
    }
    .chip:hover { border-color: var(--accent); color: var(--accent); }

    .actions-row { flex-direction: row !important; }
    .flex1 { flex: 1; }

    .btn { padding: 10px 18px; border-radius: 6px; font-size: 13px; font-weight: 600;
      cursor: pointer; border: none; transition: all .2s; font-family: inherit; }
    .btn:disabled { opacity: .5; cursor: not-allowed; }
    .btn-primary {
      background: var(--accent); color: #fff;
    }
    .btn-primary:hover:not(:disabled) { background: var(--accent-bright); transform: translateY(-1px); }
    .btn-ghost {
      background: transparent; border: 1px solid var(--border);
      color: var(--text-secondary);
    }
    .btn-ghost:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }

    .loading-row { display: flex; align-items: center; gap: 8px; }
    .dot-pulse {
      width: 8px; height: 8px; border-radius: 50%;
      background: currentColor; animation: pulse 1s infinite;
    }
    .dot-white { background: #fff; }

    .error-banner {
      background: var(--error-bg); border: 1px solid var(--error-border);
      color: var(--error-text); padding: 10px 14px; border-radius: 6px;
      font-size: 12px; display: flex; gap: 8px;
    }

    /* Generated SQL block */
    .sql-header-row { display: flex; align-items: center; justify-content: space-between; }
    .sql-actions { display: flex; gap: 6px; }
    .pill-btn {
      font-size: 11px; padding: 3px 10px;
      background: var(--badge-bg); border: 1px solid var(--border);
      color: var(--text-secondary); border-radius: 20px; cursor: pointer;
      font-family: 'JetBrains Mono', monospace; transition: all .2s;
    }
    .pill-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
    .pill-btn:disabled { opacity: .4; cursor: not-allowed; }
    .star-btn:hover:not(:disabled) { color: var(--fav-star) !important; border-color: var(--fav-star) !important; }

    .sql-box {
      background: var(--code-bg); border: 1px solid var(--border);
      border-radius: 6px; overflow: hidden;
    }
    .sql-box pre {
      margin: 0; padding: 14px;
      font-family: 'JetBrains Mono', monospace; font-size: 12px;
      color: var(--code-text); overflow-x: auto;
      white-space: pre-wrap; word-break: break-all; line-height: 1.6;
    }

    .sql-type-row { display: flex; align-items: center; gap: 8px; }
    .type-tag {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      padding: 2px 8px; border-radius: 4px; font-weight: 600; text-transform: uppercase;
    }
    .tag-select  { background: var(--tag-select-bg);  color: var(--tag-select-text); }
    .tag-insert  { background: var(--tag-insert-bg);  color: var(--tag-insert-text); }
    .tag-update  { background: var(--tag-update-bg);  color: var(--tag-update-text); }
    .tag-other   { background: var(--badge-bg);       color: var(--text-muted); }
    .schema-tag {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      padding: 2px 8px; border-radius: 4px;
      background: var(--badge-bg); color: var(--accent); border: 1px solid var(--accent-dim);
    }

    .btn-run {
      width: 100%; padding: 11px;
      background: var(--accent); color: #fff;
      border: none; border-radius: 6px;
      font-size: 14px; font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
      cursor: pointer; transition: all .2s;
      display: flex; align-items: center; justify-content: center; gap: 8px;
    }
    .btn-run:hover:not(:disabled) { background: var(--accent-bright); transform: translateY(-1px); }
    .btn-run:disabled { opacity: .5; cursor: not-allowed; transform: none; }

    .run-blocked {
      font-size: 12px; color: var(--error-text);
      display: flex; align-items: center; gap: 6px;
      padding: 8px 12px;
      background: var(--error-bg); border: 1px solid var(--error-border); border-radius: 6px;
    }

    /* Modals */
    .extract-modal, .explain-modal, .fav-modal { width: 480px; }
    .explain-modal { width: 580px; }

    .modal-desc { font-size: 13px; color: var(--text-secondary); line-height: 1.6; margin-bottom: 12px; }
    .modal-desc code {
      font-family: 'JetBrains Mono', monospace; font-size: 11px;
      background: var(--code-bg); padding: 1px 5px; border-radius: 3px; color: var(--code-text);
    }

    .extract-result {
      padding: 10px 14px; border-radius: 6px; font-size: 12px;
      font-family: 'JetBrains Mono', monospace; margin-top: 8px;
    }
    .extract-result.success { background: var(--success-bg); color: var(--success-text); }
    .extract-result.error   { background: var(--error-bg);   color: var(--error-text); }

    .sql-snippet-box {
      background: var(--code-bg); border: 1px solid var(--border);
      border-radius: 6px; margin-bottom: 16px;
    }
    .sql-snippet-box pre {
      padding: 12px; font-family: 'JetBrains Mono', monospace;
      font-size: 11px; color: var(--code-text);
      white-space: pre-wrap; word-break: break-all; margin: 0;
    }

    .explanation-text {
      font-size: 13px; color: var(--text-primary); line-height: 1.8;
      white-space: pre-wrap;
    }
    .explanation-loading {
      display: flex; align-items: center; gap: 10px;
      color: var(--text-muted); font-size: 13px;
    }
    .spinner-sm {
      width: 16px; height: 16px; border-radius: 50%;
      border: 2px solid var(--border); border-top-color: var(--accent);
      animation: spin .7s linear infinite; flex-shrink: 0;
    }

    .field-label { font-size: 12px; color: var(--text-secondary); font-weight: 500; margin-bottom: 4px; display: block; }
    .field-input {
      width: 100%; background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 9px 12px; border-radius: 6px;
      font-size: 13px; font-family: inherit; outline: none; transition: border-color .2s;
    }
    .field-input:focus { border-color: var(--accent); }
    .field-hint { font-size: 11px; color: var(--text-muted); text-align: right; margin-top: 4px; }
  `]
})
export class QueryEditorComponent implements OnInit {

  @Output() queryGenerated  = new EventEmitter<GenerateSqlResponse>();
  @Output() sqlExecuted     = new EventEmitter<{ result: any; historyId: number }>();

  schemas: string[] = [];
  selectedSchema = '';
  prompt = '';
  loading = false;
  running = false;
  refreshing = false;
  extracting = false;
  explaining = false;
  savingFav = false;
  copied = false;
  error = '';

  lastResponse: GenerateSqlResponse | null = null;
  explanation = '';

  showExtractModal = false;
  showExplainModal = false;
  showFavModal = false;

  extractResult: any = null;
  extractError = '';
  favTitle = '';

  securityConfig: SecurityConfig = {
    allowSelect: true, allowInsert: false,
    allowUpdate: false, allowDelete: false
  };

  examples = [
    'List all employees with their department name',
    'Show top 10 customers by total order value',
    'Count employees per department',
    'Find orders placed in the last 30 days',
    'Show products with stock quantity below 50'
  ];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.refreshSchemas();
    this.api.getSecurityConfig().subscribe({
      next: cfg => this.securityConfig = cfg,
      error: () => {}
    });
  }

  get canGenerate(): boolean {
    return !!this.prompt.trim() && !!this.selectedSchema && !this.loading;
  }

  canRun(sql: string): boolean {
    const upper = sql.trim().toUpperCase();
    if (upper.startsWith('INSERT') && !this.securityConfig.allowInsert) return false;
    if (upper.startsWith('UPDATE') && !this.securityConfig.allowUpdate) return false;
    if (upper.startsWith('DELETE')) return false;
    return true;
  }

  getSqlType(sql: string): string {
    const u = sql.trim().toUpperCase();
    if (u.startsWith('INSERT')) return 'INSERT';
    if (u.startsWith('UPDATE')) return 'UPDATE';
    if (u.startsWith('DELETE')) return 'DELETE';
    return 'SELECT';
  }

  getSqlTypeClass(sql: string): string {
    const t = this.getSqlType(sql);
    return { 'SELECT': 'type-tag tag-select', 'INSERT': 'type-tag tag-insert',
             'UPDATE': 'type-tag tag-update', 'DELETE': 'type-tag tag-other' }[t] ?? 'type-tag tag-other';
  }

  refreshSchemas() {
    this.refreshing = true;
    this.api.listSchemas().subscribe({
      next: s => {
        this.schemas = s;
        if (s.length && !this.selectedSchema) this.selectedSchema = s[0];
        this.refreshing = false;
      },
      error: () => { this.refreshing = false; }
    });
  }

  extractMetadata() {
    if (!this.selectedSchema) return;
    this.extracting = true;
    this.extractResult = null;
    this.extractError = '';
    this.api.extractMetadata(this.selectedSchema).subscribe({
      next: r => { this.extractResult = r; this.extracting = false; this.refreshSchemas(); },
      error: e => { this.extractError = e.error?.message || 'Extraction failed'; this.extracting = false; }
    });
  }

  generate() {
    if (!this.canGenerate) return;
    this.loading = true;
    this.error = '';
    this.lastResponse = null;
    this.explanation = '';

    this.api.generateSql({ prompt: this.prompt, schemaName: this.selectedSchema }).subscribe({
      next: r => {
        this.lastResponse = r;
        this.loading = false;
        this.queryGenerated.emit(r);
      },
      error: e => {
        this.error = e.error?.message || 'Failed to generate SQL. Please try again.';
        this.loading = false;
      }
    });
  }

  run() {
    if (!this.lastResponse || this.running) return;
    this.running = true;
    this.api.executeSql({ sql: this.lastResponse.generatedSql, historyId: this.lastResponse.historyId }).subscribe({
      next: result => {
        this.running = false;
        this.sqlExecuted.emit({ result, historyId: this.lastResponse!.historyId });
      },
      error: e => {
        this.running = false;
        const errResult = { executedSql: this.lastResponse!.generatedSql, columns: [], rows: [],
          rowCount: 0, executionTimeMs: 0, errorMessage: e.error?.message || 'Execution failed' };
        this.sqlExecuted.emit({ result: errResult, historyId: this.lastResponse!.historyId });
      }
    });
  }

  openExplain() {
    if (!this.lastResponse) return;
    this.showExplainModal = true;
    if (!this.explanation) {
      this.explaining = true;
      this.api.explainSql(this.lastResponse.generatedSql).subscribe({
        next: r => { this.explanation = r.explanation; this.explaining = false; },
        error: () => { this.explanation = 'Could not generate explanation.'; this.explaining = false; }
      });
    }
  }

  openSaveFavourite() {
    this.favTitle = this.prompt.substring(0, 80);
    this.showFavModal = true;
  }

  saveFavourite() {
    if (!this.lastResponse || !this.favTitle.trim()) return;
    this.savingFav = true;
    const fav: FavouriteQuery = {
      schemaName: this.lastResponse.schemaName,
      title: this.favTitle.trim(),
      prompt: this.prompt,
      sql: this.lastResponse.generatedSql
    };
    this.api.addFavourite(fav).subscribe({
      next: () => { this.savingFav = false; this.showFavModal = false; this.favTitle = ''; },
      error: () => { this.savingFav = false; }
    });
  }

  copySql() {
    if (!this.lastResponse) return;
    navigator.clipboard.writeText(this.lastResponse.generatedSql).then(() => {
      this.copied = true;
      setTimeout(() => this.copied = false, 2000);
    });
  }

  formatTime(dt: string): string {
    return new Date(dt).toLocaleString();
  }
}
