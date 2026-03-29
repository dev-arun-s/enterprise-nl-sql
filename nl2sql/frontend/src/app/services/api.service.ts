// src/app/services/api.service.ts
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  GenerateSqlRequest, GenerateSqlResponse,
  ExecuteSqlRequest, SqlExecutionResult,
  ExplainSqlResponse, QueryHistory,
  PagedResponse, MetadataExtractResponse,
  FavouriteQuery, SecurityConfig,
  QueryTemplate, SchemaMetadata
} from '../models/api.models';

@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly base = 'http://localhost:8080/api';

  constructor(private http: HttpClient) {}

  // ── Metadata ──────────────────────────────────────────────────────────────
  listSchemas(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/metadata/schemas`);
  }
  extractMetadata(schemaName: string): Observable<MetadataExtractResponse> {
    return this.http.post<MetadataExtractResponse>(
      `${this.base}/metadata/extract/${schemaName}`, {});
  }
  getSchemaMetadata(schemaName: string): Observable<SchemaMetadata> {
    return this.http.get<SchemaMetadata>(`${this.base}/metadata/${schemaName}`);
  }

  // ── SQL Generation ────────────────────────────────────────────────────────
  generateSql(request: GenerateSqlRequest): Observable<GenerateSqlResponse> {
    return this.http.post<GenerateSqlResponse>(`${this.base}/sql/generate`, request);
  }
  executeSql(request: ExecuteSqlRequest): Observable<SqlExecutionResult> {
    return this.http.post<SqlExecutionResult>(`${this.base}/sql/execute`, request);
  }
  explainSql(sql: string): Observable<ExplainSqlResponse> {
    return this.http.post<ExplainSqlResponse>(`${this.base}/sql/explain`, { sql });
  }
  formatSql(sql: string): Observable<{ formattedSql: string }> {
    return this.http.post<{ formattedSql: string }>(`${this.base}/sql/format`, { sql });
  }

  // ── History ───────────────────────────────────────────────────────────────
  getHistory(schema?: string, page = 0, size = 20): Observable<PagedResponse<QueryHistory>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (schema) params = params.set('schema', schema);
    return this.http.get<PagedResponse<QueryHistory>>(`${this.base}/history`, { params });
  }
  deleteHistory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/history/${id}`);
  }
  getHistoryCsvUrl(id: number):  string { return `${this.base}/history/${id}/csv`; }
  getHistoryXlsxUrl(id: number): string { return `${this.base}/history/${id}/xlsx`; }

  // ── Favourites ────────────────────────────────────────────────────────────
  getFavourites(): Observable<FavouriteQuery[]> {
    return this.http.get<FavouriteQuery[]>(`${this.base}/favourites`);
  }
  addFavourite(fav: FavouriteQuery): Observable<FavouriteQuery> {
    return this.http.post<FavouriteQuery>(`${this.base}/favourites`, fav);
  }
  deleteFavourite(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/favourites/${id}`);
  }

  // ── Templates ─────────────────────────────────────────────────────────────
  getTemplates(): Observable<QueryTemplate[]> {
    return this.http.get<QueryTemplate[]>(`${this.base}/templates`);
  }

  // ── Config ────────────────────────────────────────────────────────────────
  getSecurityConfig(): Observable<SecurityConfig> {
    return this.http.get<SecurityConfig>(`${this.base}/config/security`);
  }
}
