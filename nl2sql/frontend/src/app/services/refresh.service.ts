// src/app/services/refresh.service.ts
import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/**
 * Simple event bus for triggering auto-refresh of history and favourites panels.
 *
 * Usage:
 *   Emit:     refreshService.refreshHistory()   or  refreshService.refreshFavourites()
 *   Listen:   refreshService.history$           or  refreshService.favourites$
 *
 * Using a Subject (not BehaviorSubject) so components only react to new events,
 * not replay the last emission on subscribe.
 */
@Injectable({ providedIn: 'root' })
export class RefreshService {
  private historySource      = new Subject<void>();
  private favouritesSource   = new Subject<void>();

  /** Subscribe to this in HistoryComponent */
  history$     = this.historySource.asObservable();

  /** Subscribe to this in FavouritesComponent */
  favourites$  = this.favouritesSource.asObservable();

  /** Call after a query is generated or executed */
  refreshHistory()     { this.historySource.next(); }

  /** Call after a favourite is saved or deleted */
  refreshFavourites()  { this.favouritesSource.next(); }
}