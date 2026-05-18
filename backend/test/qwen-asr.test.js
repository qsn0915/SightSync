import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_QWEN_ASR_MODEL,
  buildQwenAsrRequest,
  parseQwenAsrCompletion
} from '../src/qwen-asr.js';

test('buildQwenAsrRequest sends audio to Qwen ASR model as input_audio', () => {
  const payload = buildQwenAsrRequest({
    audioBase64: 'AAAA',
    mimeType: 'audio/mp4',
    locale: 'zh-CN'
  });

  assert.equal(payload.model, DEFAULT_QWEN_ASR_MODEL);
  assert.equal(payload.messages[0].role, 'user');
  assert.equal(payload.messages[0].content.length, 1);
  assert.equal(payload.messages[0].content[0].type, 'input_audio');
  assert.equal(payload.messages[0].content[0].input_audio.data, 'data:audio/mp4;base64,AAAA');
  assert.deepEqual(payload.asr_options, {
    language: 'zh',
    enable_itn: false
  });
});

test('parseQwenAsrCompletion trims transcribed text from assistant response', () => {
  const text = parseQwenAsrCompletion({
    choices: [
      {
        message: {
          content: '  点击确定  '
        }
      }
    ]
  });

  assert.equal(text, '点击确定');
});
