package com.streambravia.tv.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intelligent Voice Search Engine for StreamBravia TV.
 *
 * Supports:
 * 1. Physical remote Voice / Mic button press & hold detection (or click-to-speak).
 * 2. Native SpeechRecognizer on Android TV / Google TV / Sony Bravia.
 * 3. High-accuracy Google Chromium Speech API v2 with direct MediaCodec FLAC encoding
 *    for Amazon Fire TV Stick / Fire OS devices lacking Google Play Services.
 * 4. Silence detection (VAD) to auto-finalize speech without manual button release.
 * 5. Automatic search query execution and immediate cursor focus on the first result.
 */
public class VoiceSearchManager {

    private static final String TAG = "VoiceSearchManager";
    private static final int SAMPLE_RATE = 16000;
    private static final String CHROMIUM_SPEECH_KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw";

    public interface VoiceSearchCallback {
        void onListeningStarted();
        void onAudioLevel(float level); // 0.0 to 1.0 normalized
        void onRecognizing();
        void onResult(String text);
        void onError(String message);
    }

    private final Activity mActivity;
    private final Handler mMainHandler;
    private VoiceSearchCallback mCallback;

    private SpeechRecognizer mSpeechRecognizer;
    private AudioRecord mAudioRecord;
    private Thread mRecordingThread;
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private long mRecordingStartTime = 0;
    private String mCurrentLanguage = "en-IN"; // Default high-accuracy Indian English / English

    public VoiceSearchManager(Activity activity) {
        this.mActivity = activity;
        this.mMainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(VoiceSearchCallback callback) {
        this.mCallback = callback;
    }

    public void setLanguage(String lang) {
        if (lang != null && !lang.isEmpty()) {
            this.mCurrentLanguage = lang;
        }
    }

    public boolean isRecording() {
        return mIsRecording.get();
    }

    /**
     * Start capturing voice.
     * Tries native SpeechRecognizer first; if unavailable (e.g. Fire TV), uses AudioRecord + Google Speech API.
     */
    public synchronized void startListening() {
        if (mIsRecording.get()) return;

        if (mActivity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mActivity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2001);
            if (mCallback != null) mCallback.onError("Microphone permission required. Please allow access.");
            return;
        }

        mIsRecording.set(true);
        mRecordingStartTime = System.currentTimeMillis();

        if (mCallback != null) {
            mCallback.onListeningStarted();
        }

        // Check if native SpeechRecognizer is functional on this OS
        if (SpeechRecognizer.isRecognitionAvailable(mActivity)) {
            try {
                startNativeSpeechRecognizer();
                return;
            } catch (Exception e) {
                Log.w(TAG, "Native SpeechRecognizer failed, falling back to AudioRecord pipeline: " + e.getMessage());
            }
        }

        // Fire TV Stick or systems without Google Play Services
        startAudioRecordPipeline();
    }

    /**
     * Stop capturing voice and finalize transcription.
     */
    public synchronized void stopListening() {
        if (!mIsRecording.get()) return;
        mIsRecording.set(false);

        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.stopListening();
            } catch (Exception ignored) {}
        }

        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Cancel listening without transcribing.
     */
    public synchronized void cancel() {
        mIsRecording.set(false);
        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.cancel();
                mSpeechRecognizer.destroy();
                mSpeechRecognizer = null;
            } catch (Exception ignored) {}
        }

        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            } catch (Exception ignored) {}
        }

        if (mRecordingThread != null) {
            mRecordingThread.interrupt();
            mRecordingThread = null;
        }
    }

    // ---------------- Native SpeechRecognizer Path (Android TV / Sony Bravia) ----------------

    private void startNativeSpeechRecognizer() {
        mMainHandler.post(() -> {
            try {
                if (mSpeechRecognizer != null) {
                    mSpeechRecognizer.destroy();
                }
                mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mActivity);
                mSpeechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        Log.i(TAG, "Native SpeechRecognizer ready");
                    }

                    @Override
                    public void onBeginningOfSpeech() {}

                    @Override
                    public void onRmsChanged(float rmsdB) {
                        float normalized = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
                        if (mCallback != null) mCallback.onAudioLevel(normalized);
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {
                        if (mCallback != null) mCallback.onRecognizing();
                    }

                    @Override
                    public void onError(int error) {
                        Log.w(TAG, "Native SpeechRecognizer error code: " + error);
                        // If native recognizer fails on this device, fallback to AudioRecord next time
                        mIsRecording.set(false);
                        if (mCallback != null) {
                            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                                mCallback.onError("No speech heard. Please try again.");
                            } else {
                                mCallback.onError("Voice recognition unavailable (" + error + ")");
                            }
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        mIsRecording.set(false);
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String query = matches.get(0).trim();
                            Log.i(TAG, "Native speech recognition result: " + query);
                            if (mCallback != null) mCallback.onResult(query);
                        } else {
                            if (mCallback != null) mCallback.onError("No speech heard. Please try again.");
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {}

                    @Override
                    public void onEvent(int eventType, Bundle params) {}
                });

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, mCurrentLanguage);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                mSpeechRecognizer.startListening(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start native speech recognizer", e);
                startAudioRecordPipeline();
            }
        });
    }

    // ---------------- AudioRecord + Google Speech API Path (Fire TV / Fire OS) ----------------

    private void startAudioRecordPipeline() {
        int bufferSize = Math.max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
        );

        try {
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
            );

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                mIsRecording.set(false);
                if (mCallback != null) mCallback.onError("Microphone hardware initialization failed.");
                return;
            }

            mAudioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord start failed", e);
            mIsRecording.set(false);
            if (mCallback != null) mCallback.onError("Cannot access microphone: " + e.getMessage());
            return;
        }

        mRecordingThread = new Thread(() -> {
            ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            boolean hasDetectedSpeech = false;
            long lastSpeechTime = System.currentTimeMillis();
            long maxDurationMs = 8000; // 8 seconds maximum
            long silenceTimeoutMs = 1400; // 1.4s silence after user starts talking

            try {
                while (mIsRecording.get()) {
                    int read = mAudioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmStream.write(buffer, 0, read);

                        // Calculate RMS Amplitude
                        long sum = 0;
                        for (int i = 0; i < read; i += 2) {
                            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                            sum += (long) sample * sample;
                        }
                        double rms = Math.sqrt(sum / (double) (read / 2));
                        float level = (float) Math.min(1.0, rms / 6000.0);

                        mMainHandler.post(() -> {
                            if (mCallback != null) mCallback.onAudioLevel(level);
                        });

                        // Voice Activity Detection (VAD)
                        if (rms > 700) {
                            hasDetectedSpeech = true;
                            lastSpeechTime = System.currentTimeMillis();
                        } else if (hasDetectedSpeech) {
                            if (System.currentTimeMillis() - lastSpeechTime > silenceTimeoutMs) {
                                Log.i(TAG, "Silence detected after speech; finishing capture automatically.");
                                break;
                            }
                        }

                        if (System.currentTimeMillis() - mRecordingStartTime > maxDurationMs) {
                            Log.i(TAG, "Max recording duration reached.");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio capture error", e);
            } finally {
                mIsRecording.set(false);
                try {
                    if (mAudioRecord != null) {
                        mAudioRecord.stop();
                        mAudioRecord.release();
                        mAudioRecord = null;
                    }
                } catch (Exception ignored) {}
            }

            final byte[] pcmBytes = pcmStream.toByteArray();
            if (pcmBytes.length < 3200) { // Under 0.1s
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onError("No speech heard. Please try again.");
                });
                return;
            }

            mMainHandler.post(() -> {
                if (mCallback != null) mCallback.onRecognizing();
            });

            // Perform FLAC encoding & speech-to-text recognition asynchronously
            processAudioAndRecognize(pcmBytes);
        }, "VoiceSearchAudioThread");

        mRecordingThread.start();
    }

    private void processAudioAndRecognize(final byte[] pcmBytes) {
        new Thread(() -> {
            try {
                byte[] flacBytes = encodePcmToFlac(pcmBytes, SAMPLE_RATE);
                if (flacBytes == null || flacBytes.length == 0) {
                    mMainHandler.post(() -> {
                        if (mCallback != null) mCallback.onError("Audio encoding failed.");
                    });
                    return;
                }

                String transcript = queryGoogleSpeechApi(flacBytes, mCurrentLanguage);
                if (transcript == null || transcript.trim().isEmpty()) {
                    // Try fallback language en-US if en-IN had no hit
                    if (!"en-US".equals(mCurrentLanguage)) {
                        transcript = queryGoogleSpeechApi(flacBytes, "en-US");
                    }
                }

                final String result = transcript;
                mMainHandler.post(() -> {
                    if (result != null && !result.trim().isEmpty()) {
                        Log.i(TAG, "Voice recognition succeeded: " + result);
                        if (mCallback != null) mCallback.onResult(result.trim());
                    } else {
                        if (mCallback != null) mCallback.onError("Could not understand audio. Please speak clearly.");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Speech processing error", e);
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onError("Voice service error: " + e.getMessage());
                });
            }
        }, "VoiceSearchRecognizeThread").start();
    }

    /**
     * Encode raw 16kHz 16-bit mono PCM into FLAC format using Android's native MediaCodec (OMX.google.flac.encoder).
     */
    private static byte[] encodePcmToFlac(byte[] pcmBytes, int sampleRate) {
        MediaCodec encoder = null;
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5);

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer[] inputBuffers = encoder.getInputBuffers();
            ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            int offset = 0;
            boolean inputDone = false;
            long pts = 0;

            while (true) {
                if (!inputDone) {
                    int inIndex = encoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = inputBuffers[inIndex];
                        inBuf.clear();
                        int bytesToWrite = Math.min(inBuf.remaining(), pcmBytes.length - offset);
                        if (bytesToWrite > 0) {
                            inBuf.put(pcmBytes, offset, bytesToWrite);
                            offset += bytesToWrite;
                            encoder.queueInputBuffer(inIndex, 0, bytesToWrite, pts, 0);
                            pts += (bytesToWrite / 2) * 1000000L / sampleRate;
                        } else {
                            encoder.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = encoder.getOutputBuffers();
                } else if (outIndex >= 0) {
                    ByteBuffer outBuf = outputBuffers[outIndex];
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset);
                        outBuf.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] chunk = new byte[bufferInfo.size];
                        outBuf.get(chunk);
                        out.write(chunk);
                    }
                    encoder.releaseOutputBuffer(outIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break;
                }
            }

            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec FLAC encoding error", e);
            return null;
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Send FLAC audio to Google Chromium Speech API v2 over HTTPS.
     */
    private static String queryGoogleSpeechApi(byte[] flacBytes, String lang) {
        if (flacBytes == null || flacBytes.length == 0) return null;
        HttpURLConnection conn = null;
        try {
            String targetLang = (lang != null && !lang.isEmpty()) ? lang : "en-IN";
            String urlStr = "https://www.google.com/speech-api/v2/recognize?client=chromium&lang="
                    + URLEncoder.encode(targetLang, "UTF-8")
                    + "&key=" + CHROMIUM_SPEECH_KEY
                    + "&pFilter=0";

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "audio/x-flac; rate=" + SAMPLE_RATE);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            OutputStream os = conn.getOutputStream();
            os.write(flacBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JSONObject obj = new JSONObject(line);
                        JSONArray resultArr = obj.optJSONArray("result");
                        if (resultArr != null && resultArr.length() > 0) {
                            JSONObject firstRes = resultArr.getJSONObject(0);
                            JSONArray altArr = firstRes.optJSONArray("alternative");
                            if (altArr != null && altArr.length() > 0) {
                                String transcript = altArr.getJSONObject(0).optString("transcript");
                                if (transcript != null && !transcript.trim().isEmpty()) {
                                    return transcript.trim();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                Log.w(TAG, "Google Speech API returned HTTP " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Google Speech API query failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
