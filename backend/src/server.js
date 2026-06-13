import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  createLocalAssistResponse,
  createFallbackAssistResponse,
  sanitizeAssistResponse,
  validateAssistRequest,
  validateTranscribeRequest
} from './protocol.js';
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
  const providerTimeoutMillis = options.providerTimeoutMillis || defaultProviderTimeoutMillis;
  const getQwenConfig = options.qwenConfig
    ? () => options.qwenConfig
    : () => createQwenConfig(process.env);

  return http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/v1/health') {
      if (!isAuthorized(req)) {
        sendError(res, 401, 'authorization_failed', 'unauthorized');
        return;
      }
      sendHealth(res, getQwenConfig());
      return;
    }

    if (req.method !== 'POST' || !['/v1/assist', '/v1/transcribe'].includes(req.url)) {
      sendError(res, 404, 'not_found', 'not found');
      return;
    }

    if (!isAuthorized(req)) {
      sendError(res, 401, 'authorization_failed', 'unauthorized');
      return;
    }

    let body;
    try {
      body = JSON.parse(await readBody(req));
    } catch {
      sendError(res, 400, 'invalid_json', 'invalid json');
      return;
    }

    try {
      const qwenConfig = getQwenConfig();
      if (req.url === '/v1/transcribe') {
        const requestValidation = validateTranscribeRequest(body);
        if (!requestValidation.valid) {
          sendError(res, 400, 'invalid_request', requestValidation.reason);
          return;
        }
        if (!isQwenConfigured(qwenConfig)) {
          sendError(res, 503, 'provider_unavailable', 'asr provider not configured');
          return;
        }
        const text = await callQwenAsr(body, qwenConfig, fetchImpl, providerTimeoutMillis);
        sendJson(res, 200, { text });
        return;
      }

      const requestValidation = validateAssistRequest(body);
      if (!requestValidation.valid) {
        sendError(res, 400, 'invalid_request', requestValidation.reason);
        return;
      }

      const localResponse = createLocalAssistResponse(body);
      const response = localResponse || (isQwenConfigured(qwenConfig)
        ? await callQwenProvider(body, qwenConfig, fetchImpl, providerTimeoutMillis)
        : createFallbackAssistResponse(body));
      sendJson(res, 200, sanitizeAssistResponse(response));
    } catch (error) {
      if (error instanceof ProviderTimeoutError) {
        sendError(res, 504, 'provider_timeout', 'ai provider timeout', error.message);
        return;
      }
      sendError(
        res,
        502,
        'provider_response_invalid',
        'ai response invalid',
        error instanceof Error ? error.message : String(error)
      );
    }
  });
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
    throw new Error(`provider returned ${providerResponse.status}`);
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
    throw new Error(`asr provider returned ${providerResponse.status}`);
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

function sendHealth(res, qwenConfig) {
  if (!isQwenConfigured(qwenConfig)) {
    sendJson(res, 503, {
      status: 'unavailable',
      error: {
        code: 'provider_unavailable',
        message: 'ai provider not configured'
      }
    });
    return;
  }

  sendJson(res, 200, {
    status: 'ok',
    provider: 'configured',
    asrProvider: 'configured'
  });
}

function sendError(res, statusCode, code, message, detail) {
  const error = { code, message };
  if (detail) error.detail = detail;
  sendJson(res, statusCode, { error });
}

class ProviderTimeoutError extends Error {}

function isMainModule(moduleUrl, argvPath = process.argv[1]) {
  if (!argvPath) return false;
  return path.resolve(fileURLToPath(moduleUrl)) === path.resolve(argvPath);
}

if (isMainModule(import.meta.url)) {
  createServer().listen(port, () => {
    console.log(`AI proxy listening on http://localhost:${port}`);
  });
}
