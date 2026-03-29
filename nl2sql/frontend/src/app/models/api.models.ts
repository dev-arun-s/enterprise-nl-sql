// src/app/models/api.models.ts

export interface ConversationMessage {
  role: 'USER' | 'ASSISTANT';
  content: string;
}

export interface GenerateSqlRequest {
  prompt: string;
  schemaName: string;
  conversationHistory?: ConversationMessage[];
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
  promptValid: boolean;
  validationReason: string;
  confidenceScore?: number;
  confidenceLabel?: 'High' | 'Medium' | 'Low';
  conversationHistory?: ConversationMessage[];
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

export interface QueryTemplate {
  id: string;
  category: string;
  title: string;
  description: string;
  prompt: string;
  tags: string[];
}

export interface SchemaMetadata {
  schemaName: string;
  extractedAt: string;
  tables: TableMetadata[];
}

export interface TableMetadata {
  tableName: string;
  tableComment?: string;
  columns: ColumnMetadata[];
  primaryKeys: string[];
  foreignKeys: ForeignKeyMetadata[];
  indexes: IndexMetadata[];
}

export interface ColumnMetadata {
  columnName: string;
  dataType: string;
  dataLength?: number;
  dataPrecision?: number;
  dataScale?: number;
  nullable: boolean;
  defaultValue?: string;
  columnComment?: string;
  columnOrder: number;
}

export interface ForeignKeyMetadata {
  constraintName: string;
  localColumn: string;
  referencedTable: string;
  referencedColumn: string;
}

export interface IndexMetadata {
  indexName: string;
  unique: boolean;
  columns: string[];
}

export type Theme = 'dark' | 'light';
