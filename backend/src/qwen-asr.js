export const DEFAULT_QWEN_ASR_MODEL = 'qwen3-asr-flash';

export function buildQwenAsrRequest(request, model = DEFAULT_QWEN_ASR_MODEL) {
  return {
    model,
    messages: [
      {
        role: 'user',
        content: [
          {
            type: 'input_audio',
            input_audio: {
              data: `data:${request.mimeType};base64,${request.audioBase64}`
            }
          }
        ]
      }
    ],
    asr_options: {
      language: toAsrLanguage(request.locale),
      enable_itn: false
    }
  };
}

export function parseQwenAsrCompletion(payload) {
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content !== 'string') {
    throw new Error('provider response did not include ASR text');
  }
  return content.trim();
}

function toAsrLanguage(locale) {
  return typeof locale === 'string' && locale.toLowerCase().startsWith('zh') ? 'zh' : 'auto';
}
