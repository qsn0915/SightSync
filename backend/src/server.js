import http from 'node:http';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  createLocalAssistResponse,
  createFallbackAssistResponse,
  createScreenReadingFallbackResponse,
  detectScreenReadingMode,
  sanitizeAssistResponse,
  validateScreenReadingProviderResponse,
  validateAssistRequest,
  validateTranscribeRequest
} from './protocol.js';
import { buildScreenSummary } from './screen-summary.js';
import {
  buildQwenChatCompletionRequest,
  createQwenConfig,
  isQwenConfigured,
  parseQwenChatCompletion
} from './qwen.js';
import {
  buildQwenAsrRequest,
  parseQwenAsrCompletion
} from './qwen-asr.js';

const port = Number.parseInt(process.env.PORT || '8787', 10);
const appToken = process.env.APP_API_TOKEN || 'dev-token';
const defaultProviderTimeoutMillis = Number.parseInt(process.env.AI_PROVIDER_TIMEOUT_MS || '20000', 10);

export function createServer(options = {}) {
  const fetchImpl = options.fetchImpl || fetch;
  const logger = options.logger || console;
  const providerTimeoutMillis = options.providerTimeoutMillis || defaultProviderTimeoutMillis;
  const getQwenConfig = options.qwenConfig
    ? () => options.qwenConfig
    : () => createQwenConfig(process.env);

  return http.createServer(async (req, res) => {
    const startedAt = Date.now();
    const requestId = randomUUID();
    const parsedUrl = new URL(req.url || '/', 'http://localhost');
    const endpoint = parsedUrl.pathname;
    let statusCode = 500;
    let errorCode;
    let qwenConfigForLog;
    let providerStatus;
    let assistSource;
    let fallbackReason;

    const writeJson = (nextStatusCode, responseBody) => {
      statusCode = nextStatusCode;
      errorCode = responseBody?.error?.code;
      sendJson(res, nextStatusCode, responseBody);
    };
    const writeError = (nextStatusCode, code, message, detail) => {
      const error = { code, message };
      if (detail) error.detail = detail;
      writeJson(nextStatusCode, { error });
    };

    try {
      if (req.method === 'GET' && endpoint === '/v1/health') {
        if (!isAuthorized(req)) {
          writeError(401, 'authorization_failed', 'unauthorized');
          return;
        }
        const qwenConfig = getQwenConfig();
        qwenConfigForLog = qwenConfig;
        const health = parsedUrl.searchParams.get('probe') === 'provider'
          ? await buildProviderProbeHealth(qwenConfig, fetchImpl, providerTimeoutMillis)
          : buildConfiguredHealth(qwenConfig);
        providerStatus = health.providerStatus;
        writeJson(health.statusCode, health.body);
        return;
      }

      if (req.method !== 'POST' || !['/v1/assist', '/v1/transcribe'].includes(endpoint)) {
        writeError(404, 'not_found', 'not found');
        return;
      }

      if (!isAuthorized(req)) {
        writeError(401, 'authorization_failed', 'unauthorized');
        return;
      }

      let body;
      try {
        body = JSON.parse(await readBody(req));
      } catch {
        writeError(400, 'invalid_json', 'invalid json');
        return;
      }

      const qwenConfig = getQwenConfig();
      qwenConfigForLog = qwenConfig;
      if (endpoint === '/v1/transcribe') {
        const requestValidation = validateTranscribeRequest(body);
        if (!requestValidation.valid) {
          writeError(400, 'invalid_request', requestValidation.reason);
          return;
        }
        if (!isQwenConfigured(qwenConfig)) {
          writeError(503, 'provider_unavailable', 'asr provider not configured');
          return;
        }
        const text = await callQwenAsr(body, qwenConfig, fetchImpl, providerTimeoutMillis);
        writeJson(200, { text });
        return;
      }

      const requestValidation = validateAssistRequest(body);
      if (!requestValidation.valid) {
        writeError(400, 'invalid_request', requestValidation.reason);
        return;
      }

      const screenReadingMode = detectScreenReadingMode(body.utterance);
      const response = screenReadingMode
        ? await resolveScreenReadingResponse({
          request: body,
          mode: screenReadingMode,
          qwenConfig,
          fetchImpl,
          providerTimeoutMillis,
          onDiagnostic: (diagnostic) => {
            assistSource = diagnostic.assistSource;
            fallbackReason = diagnostic.fallbackReason;
            providerStatus = diagnostic.providerStatus;
          }
        })
        : await resolveGeneralAssistResponse(body, qwenConfig, fetchImpl, providerTimeoutMillis);
      writeJson(200, sanitizeAssistResponse(response));
    } catch (error) {
      if (error instanceof ProviderTimeoutError) {
        writeError(504, 'provider_timeout', 'ai provider timeout', error.message);
        return;
      }
      if (error instanceof ProviderHttpError) {
        providerStatus = error.status;
      }
      writeError(
        502,
        'provider_response_invalid',
        'ai response invalid',
        error instanceof Error ? error.message : String(error)
      );
    } finally {
      logRequest(logger, {
        requestId,
        method: req.method,
        endpoint,
        statusCode,
        errorCode,
        durationMs: Date.now() - startedAt,
        model: qwenConfigForLog?.model,
        asrModel: qwenConfigForLog?.asrModel,
        providerStatus,
        assistSource,
        fallbackReason
      });
    }
  });
}

async function resolveGeneralAssistResponse(request, config, fetchImpl, timeoutMillis) {
  const localResponse = createLocalAssistResponse(request);
  if (localResponse) return localResponse;
  return isQwenConfigured(config)
    ? callQwenProvider(request, config, fetchImpl, timeoutMillis)
    : createFallbackAssistResponse(request);
}

async function resolveScreenReadingResponse({
  request,
  mode,
  qwenConfig,
  fetchImpl,
  providerTimeoutMillis,
  onDiagnostic
}) {
  const fallback = (reason, status) => {
    onDiagnostic({
      assistSource: 'local_fallback',
      fallbackReason: reason,
      providerStatus: status
    });
    return createScreenReadingFallbackResponse(request, mode);
  };

  if (!isQwenConfigured(qwenConfig)) {
    return fallback('provider_not_configured');
  }

  try {
    const providerResponse = await callQwenProvider(
      request,
      qwenConfig,
      fetchImpl,
      providerTimeoutMillis
    );
    const validation = validateScreenReadingProviderResponse(
      providerResponse,
      buildScreenSummary(request.screen)
    );
    if (!validation.valid) {
      return fallback('provider_screen_reading_unsafe', 200);
    }
    onDiagnostic({ assistSource: 'provider', providerStatus: 200 });
    return providerResponse;
  } catch (error) {
    if (error instanceof ProviderTimeoutError) {
      return fallback('provider_timeout');
    }
    if (error instanceof ProviderHttpError) {
      return fallback('provider_http_error', error.status);
    }
    return fallback('provider_response_invalid');
  }
}

async function callQwenProvider(request, config, fetchImpl, timeoutMillis) {
  const providerResponse = await fetchWithTimeout(fetchImpl, config.baseUrl, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(buildQwenChatCompletionRequest(request, config.model))
  }, timeoutMillis);

  if (!providerResponse.ok) {
    throw new ProviderHttpError(`provider returned ${providerResponse.status}`, providerResponse.status);
  }

  const payload = await providerResponse.json();
  return parseQwenChatCompletion(payload);
}

async function callQwenAsr(request, config, fetchImpl, timeoutMillis) {
  const providerResponse = await fetchWithTimeout(fetchImpl, config.baseUrl, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(buildQwenAsrRequest(request, config.asrModel))
  }, timeoutMillis);

  if (!providerResponse.ok) {
    throw new ProviderHttpError(`asr provider returned ${providerResponse.status}`, providerResponse.status);
  }

  const payload = await providerResponse.json();
  return parseQwenAsrCompletion(payload);
}

async function fetchWithTimeout(fetchImpl, url, options, timeoutMillis) {
  const controller = new AbortController();
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => {
      controller.abort();
      reject(new ProviderTimeoutError('provider request timed out'));
    }, timeoutMillis);
  });

  try {
    return await Promise.race([
      fetchImpl(url, { ...options, signal: controller.signal }),
      timeout
    ]);
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new ProviderTimeoutError('provider request timed out');
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

function isAuthorized(req) {
  return req.headers.authorization === `Bearer ${appToken}`;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        reject(new Error('request too large'));
        req.destroy();
      }
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function sendJson(res, statusCode, body) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

function buildConfiguredHealth(qwenConfig) {
  if (!isQwenConfigured(qwenConfig)) {
    return {
      statusCode: 503,
      body: {
        status: 'unavailable',
        error: {
          code: 'provider_unavailable',
          message: 'ai provider not configured'
        }
      }
    };
  }

  return {
    statusCode: 200,
    body: {
      status: 'ok',
      provider: 'configured',
      asrProvider: 'configured'
    }
  };
}

async function buildProviderProbeHealth(qwenConfig, fetchImpl, timeoutMillis) {
  const configured = buildConfiguredHealth(qwenConfig);
  if (configured.statusCode !== 200) {
    return configured;
  }

  try {
    await callQwenProvider(createProviderProbeRequest(), qwenConfig, fetchImpl, timeoutMillis);
    return {
      statusCode: 200,
      providerStatus: 200,
      body: {
        status: 'ok',
        provider: 'configured',
        asrProvider: 'configured',
        providerProbe: 'ok',
        model: qwenConfig.model,
        asrModel: qwenConfig.asrModel
      }
    };
  } catch (error) {
    if (error instanceof ProviderTimeoutError) {
      return {
        statusCode: 504,
        body: {
          status: 'unavailable',
          error: {
            code: 'provider_timeout',
            message: 'ai provider timeout',
            detail: error.message
          }
        }
      };
    }
    return {
      statusCode: 503,
      providerStatus: error instanceof ProviderHttpError ? error.status : undefined,
      body: {
        status: 'unavailable',
        error: {
          code: 'provider_unavailable',
          message: 'ai provider probe failed',
          detail: error instanceof Error ? error.message : String(error)
        }
      }
    };
  }
}

function createProviderProbeRequest() {
  return {
    sessionId: 'health-probe',
    locale: 'zh-CN',
    utterance: 'health probe',
    screen: {
      packageName: 'com.sightsync.health',
      activityName: 'HealthProbe',
      nodes: [
        {
          nodeId: 'health_probe_status',
          text: 'Health probe',
          role: 'TextView',
          clickable: false
        }
      ],
      screenshotBase64: null
    }
  };
}

function logRequest(logger, event) {
  if (!logger?.info) return;
  logger.info('ai_proxy_request', removeUndefinedFields(event));
}

class ProviderTimeoutError extends Error {}
class ProviderHttpError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

function removeUndefinedFields(event) {
  return Object.fromEntries(Object.entries(event).filter(([, value]) => value !== undefined));
}

function isMainModule(moduleUrl, argvPath = process.argv[1]) {
  if (!argvPath) return false;
  return path.resolve(fileURLToPath(moduleUrl)) === path.resolve(argvPath);
}

if (isMainModule(import.meta.url)) {
  createServer().listen(port, () => {
    console.log(`AI proxy listening on http://localhost:${port}`);
  });
}
