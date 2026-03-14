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
    .app-shell {
      display: flex; flex-direction: column; height: 100vh;
      background: var(--bg-primary); overflow: hidden;
    }
    .app-main {
      display: grid; grid-template-columns: 44% 1fr;
      flex: 1; overflow: hidden; min-height: 0;
    }
    .left-panel {
      border-right: 1px solid var(--border);
      overflow-y: auto; background: var(--bg-primary);
    }
    .right-panel {
      display: flex; flex-direction: column;
      overflow: hidden; background: var(--bg-secondary);
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
