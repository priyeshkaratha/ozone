/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.recon.chatbot;

import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;

/**
 * Configuration keys for Recon Chatbot service.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public final class ChatbotConfigKeys {

  private ChatbotConfigKeys() {
    // No instances
  }

  public static final String OZONE_RECON_CHATBOT_PREFIX = "ozone.recon.chatbot.";

  // ── Feature toggle ──────────────────────────────────────────
  public static final String OZONE_RECON_CHATBOT_ENABLED = OZONE_RECON_CHATBOT_PREFIX + "enabled";
  public static final boolean OZONE_RECON_CHATBOT_ENABLED_DEFAULT = false;

  // ── Provider selection ──────────────────────────────────────
  /**
   * Active default provider: openai, gemini, anthropic.
   */
  public static final String OZONE_RECON_CHATBOT_PROVIDER = OZONE_RECON_CHATBOT_PREFIX + "provider";
  public static final String OZONE_RECON_CHATBOT_PROVIDER_DEFAULT = "gemini";

  // ── Default model ───────────────────────────────────────────
  public static final String OZONE_RECON_CHATBOT_DEFAULT_MODEL = OZONE_RECON_CHATBOT_PREFIX + "default.model";
  public static final String OZONE_RECON_CHATBOT_DEFAULT_MODEL_DEFAULT = "gemini-2.5-flash";

  // ── HTTP timeout for provider calls ─────────────────────────
  public static final String OZONE_RECON_CHATBOT_TIMEOUT_MS = OZONE_RECON_CHATBOT_PREFIX + "timeout.ms";
  public static final int OZONE_RECON_CHATBOT_TIMEOUT_MS_DEFAULT = 120000;

  // ── Per-provider API keys (resolved via JCEKS / CredentialHelper) ──
  public static final String OZONE_RECON_CHATBOT_OPENAI_API_KEY = OZONE_RECON_CHATBOT_PREFIX + "openai.api.key";
  public static final String OZONE_RECON_CHATBOT_GEMINI_API_KEY = OZONE_RECON_CHATBOT_PREFIX + "gemini.api.key";
  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_API_KEY = OZONE_RECON_CHATBOT_PREFIX
      + "anthropic.api.key";

  // ── Per-provider base URL overrides (optional) ──────────────
  public static final String OZONE_RECON_CHATBOT_OPENAI_BASE_URL = OZONE_RECON_CHATBOT_PREFIX + "openai.base.url";
  public static final String OZONE_RECON_CHATBOT_OPENAI_BASE_URL_DEFAULT = "https://api.openai.com";

  public static final String OZONE_RECON_CHATBOT_GEMINI_BASE_URL = OZONE_RECON_CHATBOT_PREFIX + "gemini.base.url";
  public static final String OZONE_RECON_CHATBOT_GEMINI_BASE_URL_DEFAULT = "https://generativelanguage.googleapis.com";

  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_BASE_URL = OZONE_RECON_CHATBOT_PREFIX
      + "anthropic.base.url";
  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_BASE_URL_DEFAULT = "https://api.anthropic.com";

  // ── Per-provider supported models (comma-separated) ─────────
  // Allows admins to add new models (e.g. gpt-5, claude-opus-5) without code changes.
  public static final String OZONE_RECON_CHATBOT_OPENAI_MODELS = OZONE_RECON_CHATBOT_PREFIX + "openai.models";
  public static final String OZONE_RECON_CHATBOT_OPENAI_MODELS_DEFAULT = "gpt-4.1,gpt-4.1-mini,gpt-4.1-nano";

  public static final String OZONE_RECON_CHATBOT_GEMINI_MODELS = OZONE_RECON_CHATBOT_PREFIX + "gemini.models";
  public static final String OZONE_RECON_CHATBOT_GEMINI_MODELS_DEFAULT =
      "gemini-2.5-pro,gemini-2.5-flash,gemini-3-flash-preview,gemini-3.1-pro-preview";

  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_MODELS = OZONE_RECON_CHATBOT_PREFIX + "anthropic.models";
  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_MODELS_DEFAULT = "claude-opus-4-6,claude-sonnet-4-6";

  /**
   * Anthropic beta feature header. Controls features like the 1M-token
   * context window. Set to empty string to disable the beta header entirely.
   * Anthropic rotates these strings; admins can override without a rebuild.
   */
  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_BETA = OZONE_RECON_CHATBOT_PREFIX + "anthropic.beta";
  public static final String OZONE_RECON_CHATBOT_ANTHROPIC_BETA_DEFAULT = "context-1m-2025-08-07";

  // ── Model prefix → provider mapping for fallback resolution ─
  // Format: "prefix1:provider1,prefix2:provider2"
  // Used when user provides a bare model name (no "provider:" prefix)
  // to infer which provider should handle it.
  public static final String OZONE_RECON_CHATBOT_MODEL_PREFIX_MAPPING =
      OZONE_RECON_CHATBOT_PREFIX + "model.prefix.mapping";
  public static final String OZONE_RECON_CHATBOT_MODEL_PREFIX_MAPPING_DEFAULT =
      "gpt-:openai,o1:openai,o3:openai,gemini:gemini,claude:anthropic";

  // ── Execution policy ────────────────────────────────────────
  public static final String OZONE_RECON_CHATBOT_EXEC_MAX_RECORDS = OZONE_RECON_CHATBOT_PREFIX
      + "exec.max.records";
  public static final int OZONE_RECON_CHATBOT_EXEC_MAX_RECORDS_DEFAULT = 1000;

  public static final String OZONE_RECON_CHATBOT_EXEC_MAX_PAGES = OZONE_RECON_CHATBOT_PREFIX + "exec.max.pages";
  public static final int OZONE_RECON_CHATBOT_EXEC_MAX_PAGES_DEFAULT = 5;

  public static final String OZONE_RECON_CHATBOT_EXEC_PAGE_SIZE = OZONE_RECON_CHATBOT_PREFIX + "exec.page.size";
  public static final int OZONE_RECON_CHATBOT_EXEC_PAGE_SIZE_DEFAULT = 200;

  public static final String OZONE_RECON_CHATBOT_EXEC_REQUIRE_SAFE_SCOPE = OZONE_RECON_CHATBOT_PREFIX
      + "exec.require.safe.scope";
  public static final boolean OZONE_RECON_CHATBOT_EXEC_REQUIRE_SAFE_SCOPE_DEFAULT = true;

  // ── Agent configuration ─────────────────────────────────────
  public static final String OZONE_RECON_CHATBOT_MAX_TOOL_CALLS = OZONE_RECON_CHATBOT_PREFIX + "max.tool.calls";
  public static final int OZONE_RECON_CHATBOT_MAX_TOOL_CALLS_DEFAULT = 5;

  // ── Tool (Recon API) HTTP timeouts ──────────────────────────
  // Tunable for large clusters where slow Recon endpoints (e.g. listKeys with
  // millions of records) would otherwise hit the previous hardcoded 30s ceiling.
  public static final String OZONE_RECON_CHATBOT_TOOL_CONNECT_TIMEOUT_MS =
      OZONE_RECON_CHATBOT_PREFIX + "tool.connect.timeout.ms";
  public static final int OZONE_RECON_CHATBOT_TOOL_CONNECT_TIMEOUT_MS_DEFAULT = 30_000;

  public static final String OZONE_RECON_CHATBOT_TOOL_READ_TIMEOUT_MS =
      OZONE_RECON_CHATBOT_PREFIX + "tool.read.timeout.ms";
  public static final int OZONE_RECON_CHATBOT_TOOL_READ_TIMEOUT_MS_DEFAULT = 60_000;

  // ── Concurrency / resource isolation ────────────────────────
  // The chatbot has its own bounded executor so a few slow LLM calls can't
  // exhaust Jetty's main thread pool and starve the rest of the Recon UI.
  // When all worker slots AND the queue are full, new requests fail fast
  // with HTTP 503 instead of piling onto Jetty threads.
  public static final String OZONE_RECON_CHATBOT_THREAD_POOL_SIZE =
      OZONE_RECON_CHATBOT_PREFIX + "thread.pool.size";
  public static final int OZONE_RECON_CHATBOT_THREAD_POOL_SIZE_DEFAULT = 4;

  public static final String OZONE_RECON_CHATBOT_QUEUE_CAPACITY =
      OZONE_RECON_CHATBOT_PREFIX + "queue.capacity";
  public static final int OZONE_RECON_CHATBOT_QUEUE_CAPACITY_DEFAULT = 8;

  /**
   * Hard ceiling on a single chatbot request's end-to-end duration. Caps the
   * worst case (tool selection LLM + multiple Recon API calls + summarization
   * LLM) so a Jetty thread can't be blocked indefinitely.
   */
  public static final String OZONE_RECON_CHATBOT_REQUEST_TIMEOUT_MS =
      OZONE_RECON_CHATBOT_PREFIX + "request.timeout.ms";
  public static final int OZONE_RECON_CHATBOT_REQUEST_TIMEOUT_MS_DEFAULT = 420_000; // 7 minutes
}
