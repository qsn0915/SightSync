import http from 'node:http';
import crypto from 'node:crypto';
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
const host = process.env.HOST?.trim() || '127.0.0.1';
const defaultProviderTimeoutMillis = Number.parseInt(process.env.AI_PROVIDER_TIMEOUT_MS || '20000', 10);
const MAX_REQUEST_BYTES = 1_000_000;
const PLACEHOLDER_TOKENS = new Set(['dev-token', 'change-me', 'changeme']);

export function createServer(options = {}) {
  const configuredAppToken = resolveAppToken(options);
  const fetchImpl = options.fetchImpl || fetch;
  const providerTimeoutMillis = options.providerTimeoutMillis || defaultProviderTimeoutMillis;
  const getQwenConfig = options.qwenConfig
    ? () => options.qwenConfig
    : () => createQwenConfig(process.env);

  return http.createServer(async (req, res) => {
    if (req.method !== 'POST' || !['/v1/assist', '/v1/transcribe'].includes(req.url)) {
      sendJson(res, 404, { error: 'not found' });
      return;
    }

    if (!isAuthorized(req, configuredAppToken)) {
      sendJson(res, 401, { error: 'unauthorized' });
      return;
    }

    if (!isJsonContentType(req.headers['content-type'])) {
      sendJson(res, 415, { error: 'content type must be application/json' });
      return;
    }

    let body;
    try {
      body = JSON.parse(await readBody(req));
    } catch (error) {
      if (error instanceof RequestTooLargeError) {
        sendJson(res, 413, { error: 'request too large' });
        return;
      }
      sendJson(res, 400, { error: 'invalid json' });
      return;
    }

    const clientAbortController = new AbortController();
    const abortForDisconnectedClient = () => clientAbortController.abort();
    const abortForClosedResponse = () => {
      if (!res.writableEnded) clientAbortController.abort();
    };
    req.once('aborted', abortForDisconnectedClient);
    res.once('close', abortForClosedResponse);

    try {
      const qwenConfig = getQwenConfig();
      if (req.url === '/v1/transcribe') {
        const requestValidation = validateTranscribeRequest(body);
        if (!requestValidation.valid) {
          sendJson(res, 400, { error: requestValidation.reason });
          return;
        }
        if (!isQwenConfigured(qwenConfig)) {
          sendJson(res, 503, { error: 'asr provider not configured' });
          return;
        }
        const text = await callQwenAsr(
          body,
          qwenConfig,
          fetchImpl,
          providerTimeoutMillis,
          clientAbortController.signal
        );
        sendJson(res, 200, { text });
        return;
      }

      const requestValidation = validateAssistRequest(body);
      if (!requestValidation.valid) {
        sendJson(res, 400, { error: requestValidation.reason });
        return;
      }

      const localResponse = createLocalAssistResponse(body);
      const response = localResponse || (isQwenConfigured(qwenConfig)
        ? await callQwenProvider(
            body,
            qwenConfig,
            fetchImpl,
            providerTimeoutMillis,
            clientAbortController.signal
          )
        : createFallbackAssistResponse(body));
      sendJson(res, 200, sanitizeAssistResponse(response));
    } catch (error) {
      if (error instanceof ClientDisconnectedError) return;
      if (error instanceof ProviderTimeoutError) {
        sendJson(res, 504, { error: 'ai provider timeout' });
        return;
      }
      sendJson(res, 502, { error: 'ai response invalid' });
    } finally {
      req.off('aborted', abortForDisconnectedClient);
      res.off('close', abortForClosedResponse);
    }
  });
}

async function callQwenProvider(request, config, fetchImpl, timeoutMillis, clientSignal) {
  const providerResponse = await fetchWithTimeout(fetchImpl, config.baseUrl, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(buildQwenChatCompletionRequest(request, config.model))
  }, timeoutMillis, clientSignal);

  if (!providerResponse.ok) {
    throw new Error(`provider returned ${providerResponse.status}`);
  }

  const payload = await providerResponse.json();
  return parseQwenChatCompletion(payload);
}

async function callQwenAsr(request, config, fetchImpl, timeoutMillis, clientSignal) {
  const providerResponse = await fetchWithTimeout(fetchImpl, config.baseUrl, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(buildQwenAsrRequest(request, config.asrModel))
  }, timeoutMillis, clientSignal);

  if (!providerResponse.ok) {
    throw new Error(`asr provider returned ${providerResponse.status}`);
  }

  const payload = await providerResponse.json();
  return parseQwenAsrCompletion(payload);
}

async function fetchWithTimeout(fetchImpl, url, options, timeoutMillis, clientSignal) {
  if (clientSignal?.aborted) throw new ClientDisconnectedError('client disconnected');
  const controller = new AbortController();
  let timeoutId;
  let abortReason;
  let rejectAbort;
  const aborted = new Promise((_, reject) => {
    rejectAbort = reject;
    timeoutId = setTimeout(() => {
      abortReason = new ProviderTimeoutError('provider request timed out');
      controller.abort();
      reject(abortReason);
    }, timeoutMillis);
  });
  const onClientAbort = () => {
    if (abortReason) return;
    abortReason = new ClientDisconnectedError('client disconnected');
    controller.abort();
    rejectAbort(abortReason);
  };
  clientSignal?.addEventListener('abort', onClientAbort, { once: true });

  try {
    return await Promise.race([
      fetchImpl(url, { ...options, signal: controller.signal }),
      aborted
    ]);
  } catch (error) {
    if (abortReason) throw abortReason;
    if (error?.name === 'AbortError') {
      throw new ProviderTimeoutError('provider request timed out');
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
    clientSignal?.removeEventListener('abort', onClientAbort);
  }
}

function isAuthorized(req, expectedToken) {
  const authorization = req.headers.authorization;
  const candidate = typeof authorization === 'string' && authorization.startsWith('Bearer ')
    ? authorization.slice('Bearer '.length)
    : '';
  const expectedDigest = crypto.createHash('sha256').update(expectedToken).digest();
  const candidateDigest = crypto.createHash('sha256').update(candidate).digest();
  return crypto.timingSafeEqual(expectedDigest, candidateDigest);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const contentLength = Number.parseInt(req.headers['content-length'] || '0', 10);
    if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) {
      req.resume();
      reject(new RequestTooLargeError('request too large'));
      return;
    }

    const chunks = [];
    let receivedBytes = 0;
    let tooLarge = false;
    req.on('data', (chunk) => {
      receivedBytes += chunk.length;
      if (receivedBytes > MAX_REQUEST_BYTES) {
        tooLarge = true;
        chunks.length = 0;
      } else if (!tooLarge) {
        chunks.push(chunk);
      }
    });
    req.on('end', () => {
      if (tooLarge) reject(new RequestTooLargeError('request too large'));
      else resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', reject);
  });
}

function isJsonContentType(contentType) {
  return typeof contentType === 'string' && /^application\/json(?:\s*;|$)/iu.test(contentType);
}

function resolveAppToken(options) {
  const hasExplicitOption = Object.prototype.hasOwnProperty.call(options, 'appToken');
  const token = String(hasExplicitOption ? options.appToken ?? '' : process.env.APP_API_TOKEN ?? '').trim();
  if (!token) throw new Error('APP_API_TOKEN is required');
  if (PLACEHOLDER_TOKENS.has(token.toLowerCase())) {
    throw new Error('APP_API_TOKEN must not use a placeholder');
  }
  return token;
}

function sendJson(res, statusCode, body) {
  if (res.writableEnded || res.destroyed) return;
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

class ProviderTimeoutError extends Error {}
class ClientDisconnectedError extends Error {}
class RequestTooLargeError extends Error {}

function isMainModule(moduleUrl, argvPath = process.argv[1]) {
  if (!argvPath) return false;
  return path.resolve(fileURLToPath(moduleUrl)) === path.resolve(argvPath);
}

if (isMainModule(import.meta.url)) {
  createServer().listen(port, host, () => {
    console.log(`AI proxy listening on http://${host}:${port}`);
  });
}
