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
package org.apache.hadoop.ozone.recon.chatbot.api;

import javax.inject.Inject;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.recon.chatbot.ChatbotConfigKeys;
import org.apache.hadoop.ozone.recon.chatbot.agent.ChatbotAgent;
import org.apache.hadoop.ozone.recon.chatbot.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST API endpoint for the Recon Chatbot.
 *
 * <p>
 * API keys are managed via JCEKS (admin-configured),
 * so there are no per-user key storage endpoints.
 * </p>
 */
@Path("/chatbot")
@Produces(MediaType.APPLICATION_JSON)
public class ChatbotEndpoint {

  private static final Logger LOG = LoggerFactory.getLogger(ChatbotEndpoint.class);

  private final ChatbotAgent chatbotAgent;
  private final LLMClient llmClient;
  private final OzoneConfiguration configuration;

  // Bounded executor isolates chatbot work from Jetty's main thread pool.
  // Daemon threads → no explicit shutdown needed; they die with the JVM.
  private final ThreadPoolExecutor chatbotExecutor;
  private final long requestTimeoutMs;

  @Inject
  public ChatbotEndpoint(ChatbotAgent chatbotAgent,
      LLMClient llmClient,
      OzoneConfiguration configuration) {
    this.chatbotAgent = chatbotAgent;
    this.llmClient = llmClient;
    this.configuration = configuration;

    int poolSize = configuration.getInt(
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_THREAD_POOL_SIZE,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_THREAD_POOL_SIZE_DEFAULT);
    int queueCapacity = configuration.getInt(
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_QUEUE_CAPACITY,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_QUEUE_CAPACITY_DEFAULT);
    this.requestTimeoutMs = configuration.getInt(
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_REQUEST_TIMEOUT_MS,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_REQUEST_TIMEOUT_MS_DEFAULT);

    // Fixed-size pool + bounded queue + AbortPolicy.
    // When pool is busy AND queue is full → new submissions throw
    // RejectedExecutionException, which we map to HTTP 503. This is what
    // protects the Jetty pool: chatbot saturation can never spill over.
    AtomicInteger threadCounter = new AtomicInteger();
    ThreadFactory threadFactory = r -> {
      Thread t = new Thread(r, "recon-chatbot-" + threadCounter.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
    this.chatbotExecutor = new ThreadPoolExecutor(
        poolSize, poolSize,
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        threadFactory,
        new ThreadPoolExecutor.AbortPolicy());

    LOG.info("ChatbotEndpoint initialized: poolSize={}, queueCapacity={}, requestTimeoutMs={}",
        poolSize, queueCapacity, requestTimeoutMs);
  }

  /**
   * Checks if the chatbot is enabled in configuration.
   */
  private boolean isChatbotEnabled() {
    return configuration.getBoolean(
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ENABLED,
        ChatbotConfigKeys.OZONE_RECON_CHATBOT_ENABLED_DEFAULT);
  }

  /**
   * Health check endpoint.
   */
  @GET
  @Path("/health")
  public Response health() {
    Map<String, Object> response = new HashMap<>();
    boolean enabled = isChatbotEnabled();
    response.put("enabled", enabled);
    response.put("llmClientAvailable",
        enabled && llmClient != null && llmClient.isAvailable());
    return Response.ok(response).build();
  }

  /**
   * Chat endpoint - processes a user query.
   */
  @POST
  @Path("/chat")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response chat(ChatRequest request) {

    // Safety check 1: If chatbot is disabled, throw a 503 Service Unavailable error immediately.
    if (!isChatbotEnabled()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Collections.singletonMap("error", "Chatbot service is not enabled"))
          .build();
    }

    // Safety check 2: If the user didn't really ask a question, throw a 400 Bad Request.
    if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Collections.singletonMap("error", "Query cannot be empty"))
          .build();
    }

    LOG.info("Chat request: userId={}, model={}, provider={}",
        sanitizeUserId(request.getUserId()),
        request.getModel() == null ? "default" : request.getModel(),
        request.getProvider() == null ? "auto" : request.getProvider());

    // Submit the heavy work (LLM calls + Recon API calls) to the chatbot's
    // bounded executor instead of running it on the Jetty request thread.
    // If the pool + queue are full, AbortPolicy throws immediately and we
    // return 503 — Jetty pool stays free for the rest of Recon.
    Future<String> future;
    try {
      future = chatbotExecutor.submit(() -> chatbotAgent.processQuery(
          request.getQuery(),
          request.getModel(),
          request.getProvider(),
          null));
    } catch (RejectedExecutionException rej) {
      LOG.warn("Chatbot is at capacity; rejecting request");
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Collections.singletonMap("error",
              "Chatbot is busy. Please retry in a few moments."))
          .build();
    }

    try {
      // Bounded wait: caps how long a Jetty thread can be parked on the future.
      String response = future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
      ChatResponse chatResponse = new ChatResponse();
      chatResponse.setResponse(response);
      chatResponse.setSuccess(true);
      return Response.ok(chatResponse).build();

    } catch (TimeoutException te) {
      // Best-effort: signal the worker thread to stop (LLM/HTTP calls
      // respect interrupts in their network/io layers).
      future.cancel(true);
      LOG.warn("Chatbot request timed out after {}ms", requestTimeoutMs);
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Collections.singletonMap("error",
              "Chatbot request timed out after " + requestTimeoutMs + " ms."))
          .build();

    } catch (ExecutionException ee) {
      // Unwrap so the client sees the real failure, not a plain ExecutionException.
      Throwable cause = ee.getCause() == null ? ee : ee.getCause();
      LOG.error("Error processing chat request", cause);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Collections.singletonMap("error", cause.getMessage()))
          .build();

    } catch (InterruptedException ie) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      LOG.warn("Chatbot request interrupted");
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Collections.singletonMap("error", "Request interrupted"))
          .build();
    }
  }

  /**
   * List supported models.
   */
  @GET
  @Path("/models")
  public Response getSupportedModels() {
    if (!isChatbotEnabled()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Collections.singletonMap("error", "Chatbot service is not enabled"))
          .build();
    }

    try {
      List<String> models = llmClient.getSupportedModels();
      return Response.ok(Collections.singletonMap("models", models)).build();
    } catch (Exception e) {
      LOG.error("Error fetching supported models", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Collections.singletonMap("error", "Failed to fetch models"))
          .build();
    }
  }

  /**
   * Helper function: Masks user ID for safe logging.
   * E.g., turns "admin@example.com" into "ad***@example.com"
   * This is important so we don't leak user identities in system logs.
   */
  private String sanitizeUserId(String userId) {
    if (userId == null || userId.isEmpty()) {
      return "none";
    }
    int atIndex = userId.indexOf('@');
    // If it's an email address...
    if (atIndex > 0 && atIndex < userId.length() - 1) {
      String local = userId.substring(0, atIndex);
      String domain = userId.substring(atIndex + 1);
      String maskedLocal = local.length() <= 2 ? "**"
          : local.substring(0, 2) + "***";
      return maskedLocal + "@" + domain;
    }

    // If it's just a short username
    if (userId.length() <= 4) {
      return "****";
    }

    // If it's a longer username
    return userId.substring(0, 2) + "***" +
        userId.substring(userId.length() - 2);
  }

  // =========================================================================
  // Data Transfer Objects (DTOs)
  // These are simple classes that translate JSON into Java objects and vice versa.
  // =========================================================================
  /**
   * Chat request DTO. (This maps to the JSON we send in our Curl command)
   * The JsonIgnoreProperties annotation tells the JSON parser not to crash
   * if the user sends an extra field we aren't expecting.
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChatRequest {
    private String query;
    private String model;
    private String provider;
    private String userId;

    public String getQuery() {
      return query;
    }

    public void setQuery(String query) {
      this.query = query;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public String getUserId() {
      return userId;
    }

    public void setUserId(String userId) {
      this.userId = userId;
    }
  }

  /**
   * Chat response DTO. (This maps to the JSON we send BACK to the user)
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChatResponse {
    private String response;
    private boolean success;

    public String getResponse() {
      return response;
    }

    public void setResponse(String response) {
      this.response = response;
    }

    public boolean isSuccess() {
      return success;
    }

    public void setSuccess(boolean success) {
      this.success = success;
    }
  }
}
