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
package org.apache.hadoop.ozone.recon.chatbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.recon.chatbot.ChatbotConfigKeys;
import org.apache.hadoop.ozone.recon.chatbot.security.CredentialHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct client for Anthropic Claude models using Composition.
 */
public class AnthropicClient implements LLMClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private final OzoneConfiguration configuration;
  private final CredentialHelper credentialHelper;
  private final LLMNetworkClient networkClient;

  public AnthropicClient(OzoneConfiguration configuration,
                         CredentialHelper credentialHelper,
                         int timeoutMs) {
    this.configuration = configuration;
    this.credentialHelper = credentialHelper;
    this.networkClient = new LLMNetworkClient(timeoutMs);
  }

  @Override
  public LLMResponse chatCompletion(List<ChatMessage> messages, String model, String apiKey, Map<String, Object> parameters) throws LLMException {
    String resolvedKey = resolveApiKey(apiKey);
    if (resolvedKey == null || resolvedKey.isEmpty()) {
      throw new LLMException("No API key configured for provider 'anthropic'.");
    }

    String url = getBaseUrl() + "/v1/messages";
    
    // Construct the Anthropic specific JSON
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model);

    // Anthropic accepts a single top-level "system" field. Concatenate all
    // system messages so we don't silently overwrite earlier ones when the
    // caller supplies more than one.
    StringBuilder systemPrompt = new StringBuilder();
    ArrayNode messagesArray = body.putArray("messages");
    for (ChatMessage msg : messages) {
      if ("system".equals(msg.getRole())) {
        if (systemPrompt.length() > 0) {
          systemPrompt.append("\n\n");
        }
        systemPrompt.append(msg.getContent());
      } else {
        ObjectNode m = messagesArray.addObject();
        m.put("role", msg.getRole());
        m.put("content", msg.getContent());
      }
    }
    if (systemPrompt.length() > 0) {
      body.put("system", systemPrompt.toString());
    }

    if (parameters != null) {
      if (parameters.containsKey("max_tokens")) {
        body.put("max_tokens", ((Number) parameters.get("max_tokens")).intValue());
      } else {
        body.put("max_tokens", 4096);
      }
      if (parameters.containsKey("temperature")) {
        body.put("temperature", ((Number) parameters.get("temperature")).doubleValue());
      }
    } else {
      body.put("max_tokens", 4096);
    }

    Map<String, String> headers = new HashMap<>();
    headers.put("x-api-key", resolvedKey);
    headers.put("anthropic-version", ANTHROPIC_VERSION);
    // Beta header is admin-configurable. Anthropic rotates these strings; an
    // empty value disables the beta entirely so a stale default can't break us.
    String betaHeader = configuration.get(
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_BETA,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_BETA_DEFAULT);
    if (betaHeader != null && !betaHeader.isEmpty()) {
      headers.put("anthropic-beta", betaHeader);
    }

    try {
      String responseBody = networkClient.executePost(url, headers, MAPPER.writeValueAsString(body), "anthropic");
      return parseAnthropicResponse(responseBody, model);
    } catch (Exception e) {
      if (e instanceof LLMException) {
        throw (LLMException) e;
      }
      throw new LLMException("Anthropic Request Failed: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean isAvailable() {
    String key = credentialHelper.getSecret(ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_API_KEY);
    return key != null && !key.isEmpty();
  }

  @Override
  public List<String> getSupportedModels() {
    return GeminiClient.loadModelsFromConfig(configuration,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_MODELS,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_MODELS_DEFAULT);
  }

  private String resolveApiKey(String perRequestKey) {
    if (perRequestKey != null && !perRequestKey.isEmpty()) {
      return perRequestKey;
    }
    return credentialHelper.getSecret(ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_API_KEY);
  }

  private String getBaseUrl() {
    return configuration.get(ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_BASE_URL,
                             ChatbotConfigKeys.OZONE_RECON_CHATBOT_ANTHROPIC_BASE_URL_DEFAULT);
  }

  private LLMResponse parseAnthropicResponse(String responseBody, String model) throws LLMException {
    try {
      JsonNode root = MAPPER.readTree(responseBody);
      JsonNode content = root.get("content");
      if (content == null || !content.isArray() || content.isEmpty()) {
        throw new LLMException("Invalid Anthropic response: no content blocks found");
      }

      StringBuilder text = new StringBuilder();
      for (JsonNode block : content) {
        if ("text".equals(block.path("type").asText())) {
          text.append(block.path("text").asText());
        }
      }

      int inputTokens = root.path("usage").path("input_tokens").asInt(0);
      int outputTokens = root.path("usage").path("output_tokens").asInt(0);

      Map<String, Object> metadata = new HashMap<>();
      metadata.put("finish_reason", root.path("stop_reason").asText("unknown"));
      metadata.put("response_id", root.path("id").asText(""));
      metadata.put("provider", "anthropic");

      return new LLMResponse(text.toString(), model, inputTokens, outputTokens, metadata);
    } catch (Exception e) {
      throw new LLMException("Failed to parse Anthropic response", e);
    }
  }
}
