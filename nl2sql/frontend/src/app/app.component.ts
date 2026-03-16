// src/app/app.component.ts
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HeaderComponent } from './components/header/header.component';
import { QueryEditorComponent } from './components/query-editor/query-editor.component';
import { ResultsGridComponent } from './components/results-grid/results-grid.component';
import { HistoryComponent } from './components/history/history.component';
import { FavouritesComponent } from './components/favourites/favourites.component';
import { GenerateSqlResponse, SqlExecutionResult } from './models/api.models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, HeaderComponent, QueryEditorComponent,
            ResultsGridComponent, HistoryComponent, FavouritesComponent],
  template: `
    <div class="app-shell">
      <app-header></app-header>
      <main class="app-main">

        <!-- Left: Query Editor (prompt → generate → run) -->
        <div class="left-panel">
          <app-query-editor
            #editor
            (queryGenerated)="onGenerated($event)"
            (sqlExecuted)="onExecuted($event)">
          </app-query-editor>
        </div>

        <!-- Right: Results + History + Favourites -->
        <div class="right-panel">
          <app-results-grid
            [result]="currentResult"
            [historyId]="currentHistoryId">
          </app-results-grid>

          <app-history
            (querySelected)="onHistorySelected($event)">
          </app-history>

          <app-favourites
            (querySelected)="onFavSelected($event)">
          </app-favourites>
        </div>

      </main>
    </div>
  `,
  styles: [`
    /* app-shell fills the viewport and is the only scroll root */
    .app-shell {
      display: flex;
      flex-direction: column;
      height: 100vh;
      max-height: 100vh;       /* hard cap — never grow beyond viewport */
      overflow: hidden;
      background: var(--bg-primary);
    }

    /* app-main takes all remaining height below the header */
    .app-main {
      display: grid;
      grid-template-columns: 44% 1fr;
      /* Must use explicit height, not flex:1, because this is a grid not a flex child */
      height: calc(100vh - 56px);  /* 56px = header height */
      min-height: 0;
      overflow: hidden;
    }

    /* Left panel scrolls independently */
    .left-panel {
      border-right: 1px solid var(--border);
      overflow-y: auto;
      overflow-x: hidden;
      background: var(--bg-primary);
      height: 100%;             /* fill grid cell */
      min-height: 0;
    }

    /* Right panel is a flex column — children share its fixed height */
    .right-panel {
      display: flex;
      flex-direction: column;
      height: 100%;             /* fill grid cell */
      min-height: 0;
      overflow: hidden;
      background: var(--bg-secondary);
    }

    /*
     * Right panel flex budget:
     *   app-results-grid  — flex: 1 1 50%   grows/shrinks, guaranteed 50% minimum
     *   app-history       — flex: 0 1 28%   can shrink, capped at 28%
     *   app-favourites    — flex: 0 1 22%   can shrink, capped at 22%
     *
     * All three can shrink (second value = 1) so the total never exceeds 100%.
     * results-grid has min-height: 200px so it is never crushed to zero.
     */
    app-results-grid {
      flex: 1 1 50%;
      min-height: 200px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }

    app-history {
      flex: 0 1 28%;
      min-height: 0;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }

    app-favourites {
      flex: 0 1 22%;
      min-height: 0;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
  `]
})
export class AppComponent {
  currentResult: SqlExecutionResult | null = null;
  currentHistoryId: number | null = null;

  onGenerated(r: GenerateSqlResponse) {
    // SQL was generated — clear old results, wait for explicit Run
    this.currentResult = null;
    this.currentHistoryId = r.historyId;
  }

  onExecuted(e: { result: SqlExecutionResult; historyId: number }) {
    this.currentResult = e.result;
    this.currentHistoryId = e.historyId;
  }

  onHistorySelected(e: { sql: string; historyId: number }) {
    this.currentHistoryId = e.historyId;
    this.currentResult = null;
  }

  onFavSelected(e: { sql: string; prompt: string; schemaName: string }) {
    // Favourites load the SQL back into the editor via the editor ref
    // For simplicity we reset results; the user can re-run from the editor
    this.currentResult = null;
  }
}
