// src/app/components/query-templates/query-templates.component.ts
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { QueryTemplate } from '../../models/api.models';

@Component({
  selector: 'app-query-templates',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="modal-overlay" (click)="close.emit()">
      <div class="modal-box templates-modal" (click)="$event.stopPropagation()">

        <div class="modal-header">
          <span class="modal-title">⚡ Query Templates</span>
          <button class="modal-close" (click)="close.emit()">✕</button>
        </div>

        <!-- Search + category filter -->
        <div class="filter-bar">
          <input class="search-input" type="text" [(ngModel)]="searchText"
                 (ngModelChange)="applyFilter()"
                 placeholder="Search templates..." />
          <div class="category-pills">
            <button class="cat-pill" [class.active]="selectedCategory === ''"
                    (click)="setCategory('')">All</button>
            <button class="cat-pill" *ngFor="let cat of categories"
                    [class.active]="selectedCategory === cat"
                    (click)="setCategory(cat)">{{ cat }}</button>
          </div>
        </div>

        <!-- Template list -->
        <div class="templates-list">
          <div class="template-card" *ngFor="let t of filtered"
               (click)="select(t)">
            <div class="template-top">
              <span class="template-title">{{ t.title }}</span>
              <span class="template-category">{{ t.category }}</span>
            </div>
            <div class="template-desc">{{ t.description }}</div>
            <div class="template-prompt">{{ t.prompt }}</div>
            <div class="template-tags">
              <span class="tag" *ngFor="let tag of t.tags">{{ tag }}</span>
            </div>
          </div>

          <div class="no-results" *ngIf="filtered.length === 0">
            No templates match your search.
          </div>
        </div>

        <div class="modal-footer">
          <span class="footer-hint">Click a template to load it into the prompt editor</span>
          <button class="btn btn-ghost" (click)="close.emit()">Close</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .templates-modal { width: 640px; max-width: 95vw; height: 75vh; }

    .filter-bar {
      padding: 10px 16px; border-bottom: 1px solid var(--border);
      display: flex; flex-direction: column; gap: 8px; flex-shrink: 0;
    }
    .search-input {
      width: 100%; background: var(--input-bg); border: 1px solid var(--border);
      color: var(--text-primary); padding: 7px 12px; border-radius: 6px;
      font-size: 13px; outline: none; transition: border-color .2s;
    }
    .search-input:focus { border-color: var(--accent); }

    .category-pills { display: flex; flex-wrap: wrap; gap: 6px; }
    .cat-pill {
      font-size: 11px; padding: 3px 10px; border-radius: 20px;
      background: var(--badge-bg); border: 1px solid var(--border);
      color: var(--text-secondary); cursor: pointer; transition: all .15s;
      font-family: inherit;
    }
    .cat-pill:hover, .cat-pill.active {
      background: var(--accent-dim); border-color: var(--accent); color: var(--accent);
    }

    .templates-list { flex: 1; overflow-y: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 10px; }

    .template-card {
      background: var(--bg-elevated, var(--bg-header)); border: 1px solid var(--border);
      border-radius: 8px; padding: 12px 14px; cursor: pointer;
      transition: all .15s; display: flex; flex-direction: column; gap: 5px;
    }
    .template-card:hover {
      border-color: var(--accent); background: var(--accent-glow);
      transform: translateY(-1px);
    }

    .template-top { display: flex; align-items: center; justify-content: space-between; }
    .template-title { font-size: 13px; font-weight: 600; color: var(--text-primary); }
    .template-category {
      font-size: 10px; font-family: 'JetBrains Mono', monospace;
      padding: 2px 7px; border-radius: 4px;
      background: var(--badge-bg); color: var(--accent); border: 1px solid var(--accent-dim);
    }

    .template-desc { font-size: 12px; color: var(--text-secondary); line-height: 1.4; }

    .template-prompt {
      font-family: 'JetBrains Mono', monospace; font-size: 11px;
      color: var(--code-text); background: var(--code-bg);
      padding: 6px 10px; border-radius: 4px; line-height: 1.5;
    }

    .template-tags { display: flex; gap: 5px; flex-wrap: wrap; }
    .tag {
      font-size: 10px; padding: 1px 7px; border-radius: 10px;
      background: var(--badge-bg); color: var(--text-muted);
      border: 1px solid var(--border-faint);
    }

    .no-results { text-align: center; padding: 32px; color: var(--text-muted); font-size: 13px; }

    .modal-footer { justify-content: space-between !important; }
    .footer-hint { font-size: 11px; color: var(--text-muted); }
    .btn { padding: 7px 14px; border-radius: 6px; font-size: 13px; font-family: inherit; cursor: pointer; }
    .btn-ghost { background: transparent; border: 1px solid var(--border); color: var(--text-secondary); }
    .btn-ghost:hover { border-color: var(--accent); color: var(--accent); }
  `]
})
export class QueryTemplatesComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  @Output() templateSelected = new EventEmitter<string>();

  templates: QueryTemplate[] = [];
  filtered:  QueryTemplate[] = [];
  categories: string[] = [];
  selectedCategory = '';
  searchText = '';

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getTemplates().subscribe({
      next: t => {
        this.templates = t;
        this.categories = [...new Set(t.map(x => x.category))].sort();
        this.applyFilter();
      }
    });
  }

  setCategory(cat: string) {
    this.selectedCategory = cat;
    this.applyFilter();
  }

  applyFilter() {
    let result = this.templates;
    if (this.selectedCategory) {
      result = result.filter(t => t.category === this.selectedCategory);
    }
    if (this.searchText.trim()) {
      const q = this.searchText.toLowerCase();
      result = result.filter(t =>
        t.title.toLowerCase().includes(q) ||
        t.description.toLowerCase().includes(q) ||
        t.prompt.toLowerCase().includes(q) ||
        t.tags.some(tag => tag.toLowerCase().includes(q))
      );
    }
    this.filtered = result;
  }

  select(template: QueryTemplate) {
    this.templateSelected.emit(template.prompt);
    this.close.emit();
  }
}
