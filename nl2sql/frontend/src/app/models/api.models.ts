// src/app/models/api.models.ts

export interface GenerateSqlRequest {
  prompt: string;
  schemaName: string;
}

export interface SqlExecutionResult {
  executedSql: string;
  columns: string[];
  rows: any[][];
  rowCount: number;
  executionTimeMs: number;
  errorMessage?: string;
}

export interface GenerateSqlResponse {
  historyId: number;
  schemaName: string;
  prompt: string;
  generatedSql: string;
}

export interface ExecuteSqlRequest {
  sql: string;
  historyId?: number;
}

export interface ExplainSqlResponse {
  sql: string;
  explanation: string;
}

export interface QueryHistory {
  id: number;
  schemaName: string;
  naturalLanguagePrompt: string;
  generatedSql: string;
  executed: boolean;
  rowCount?: number;
  executionTimeMs?: number;
  errorMessage?: string;
  createdAt: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface MetadataExtractResponse {
  schema: string;
  tableCount: number;
  extractedAt: string;
}

export interface FavouriteQuery {
  id?: string;
  schemaName: string;
  title: string;
  prompt: string;
  sql: string;
  savedAt?: string;
}

export interface SecurityConfig {
  allowSelect: boolean;
  allowInsert: boolean;
  allowUpdate: boolean;
  allowDelete: boolean;
}

export type Theme = 'dark' | 'light';
