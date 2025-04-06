package com.example.visualassistant;

import android.content.Context;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.util.concurrent.ExecutorService;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;

import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TextRecognitionAnalyzer implements ImageAnalysis.Analyzer {
    private static final String TAG = "TextRecognitionAnalyzer";

    private final Context context;
    private TextRecognizer latinTextRecognizer;
    private TextRecognizer devanagariTextRecognizer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    private long lastProcessingTimestamp = 0;
    private static final long PROCESSING_INTERVAL = 3000; // 3 seconds to allow TTS to complete

    private String lastRecognizedText = "";
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isSpeaking = new AtomicBoolean(false);

    private OnTextRecognizedListener listener;
    private TextToSpeech englishTTS;
    private TextToSpeech hindiTTS;
    private TextToSpeech marathiTTS;

    private String preferredLanguage = "AUTO"; // AUTO, ENGLISH, HINDI, MARATHI

    public void setPreferredLanguage(String language) {
        Log.d(TAG, "Setting preferred language to: " + language);
        this.preferredLanguage = language;

        // Reset processing to force immediate new scan
        isProcessing.set(false);
        lastProcessingTimestamp = 0;
    }

    public TextRecognitionAnalyzer(Context context) {
        this.context = context;

        // Initialize both text recognizers
        latinTextRecognizer = TextRecognition.getClient(new TextRecognizerOptions.Builder().build());
        devanagariTextRecognizer = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());

        // Initialize all TTS engines
        initializeTextToSpeech();

        Log.d(TAG, "TextRecognitionAnalyzer initialized with multi-language support");
    }

    private void initializeTextToSpeech() {
        // Initialize English TTS
        englishTTS = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                englishTTS.setLanguage(Locale.US);
                englishTTS.setSpeechRate(0.9f);
                setupTTSListener(englishTTS);
                Log.d(TAG, "English TTS initialized successfully");
            } else {
                Log.e(TAG, "English TTS initialization failed with status: " + status);
            }
        });

        // Initialize Hindi TTS
        hindiTTS = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = hindiTTS.setLanguage(new Locale("hi", "IN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Hindi language not supported by TTS engine");
                }
                hindiTTS.setSpeechRate(0.9f);
                setupTTSListener(hindiTTS);
                Log.d(TAG, "Hindi TTS initialized successfully");
            } else {
                Log.e(TAG, "Hindi TTS initialization failed with status: " + status);
            }
        });

        // Initialize Marathi TTS
        marathiTTS = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = marathiTTS.setLanguage(new Locale("mr", "IN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Marathi language not supported by TTS engine");
                }
                marathiTTS.setSpeechRate(0.9f);
                setupTTSListener(marathiTTS);
                Log.d(TAG, "Marathi TTS initialized successfully");
            } else {
                Log.e(TAG, "Marathi TTS initialization failed with status: " + status);
            }
        });
    }

    private void setupTTSListener(TextToSpeech tts) {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isSpeaking.set(true);
                Log.d(TAG, "Started speaking utterance: " + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking.set(false);
                Log.d(TAG, "Finished speaking utterance: " + utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                isSpeaking.set(false);
                Log.e(TAG, "TTS error for utterance: " + utteranceId);
            }
        });
    }

    /**
     * Interface for text recognition callbacks
     */
    public interface OnTextRecognizedListener {
        void onTextRecognized(String text);
    }

    /**
     * Set the listener for text recognition events
     */
    public void setOnTextRecognizedListener(OnTextRecognizedListener listener) {
        this.listener = listener;
    }

    // Check if text contains Devanagari script
    private boolean containsDevanagari(String text) {
        // Devanagari Unicode range check
        for (char c : text.toCharArray()) {
            if (c >= 0x0900 && c <= 0x097F) {
                return true;
            }
        }
        return false;
    }

    // Check if text is likely Hindi
    private boolean isLikelyHindi(String text) {
        // This is a simplified check - could be enhanced with more sophisticated language detection
        // Basic check based on common Hindi words/characters
        return text.contains("है") || text.contains("में") || text.contains("का") ||
                text.contains("के") || text.contains("की") || text.contains("एक");
    }

    // Check if text is likely Marathi
    private boolean isLikelyMarathi(String text) {
        // This is a simplified check - could be enhanced with more sophisticated language detection
        // Basic check based on common Marathi words/characters
        return text.contains("आहे") || text.contains("मध्ये") || text.contains("च्या") ||
                text.contains("आणि") || text.contains("एक") || text.contains("मराठी");
    }

    // Speak text using the appropriate TTS engine
    private void speakTextInDetectedLanguage(String text) {
        if (text.isEmpty()) {
            speakText(englishTTS, "No text found", TextToSpeech.QUEUE_FLUSH);
            return;
        }

        Log.d(TAG, "Detected text: " + text);

        // Check for preferred language override
        if (preferredLanguage.equals("ENGLISH")) {
            speakText(englishTTS, "Recognized text: " + text, TextToSpeech.QUEUE_FLUSH);
            return;
        } else if (preferredLanguage.equals("HINDI")) {
            speakText(hindiTTS, "पहचाना गया पाठ: " + text, TextToSpeech.QUEUE_FLUSH);
            return;
        } else if (preferredLanguage.equals("MARATHI")) {
            speakText(marathiTTS, "ओळखलेला मजकूर: " + text, TextToSpeech.QUEUE_FLUSH);
            return;
        }

        // Auto detect and speak in appropriate language
        if (containsDevanagari(text)) {
            if (isLikelyHindi(text)) {
                speakText(hindiTTS, "पहचाना गया पाठ: " + text, TextToSpeech.QUEUE_FLUSH);
            } else if (isLikelyMarathi(text)) {
                speakText(marathiTTS, "ओळखलेला मजकूर: " + text, TextToSpeech.QUEUE_FLUSH);
            } else {
                // If we can't determine specific Devanagari language, try Hindi by default
                speakText(hindiTTS, "पहचाना गया पाठ: " + text, TextToSpeech.QUEUE_FLUSH);
            }
        } else {
            // Default to English for non-Devanagari text
            speakText(englishTTS, "Recognized text: " + text, TextToSpeech.QUEUE_FLUSH);
        }
    }

    // Safe method to speak text with proper error handling
    private void speakText(TextToSpeech tts, String textToSpeak, int queueMode) {
        if (tts == null) {
            Log.e(TAG, "TextToSpeech is null, cannot speak");
            return;
        }

        if (tts.isSpeaking() && queueMode == TextToSpeech.QUEUE_FLUSH) {
            tts.stop();
        }

        // Generate unique utterance ID
        String utteranceId = UUID.randomUUID().toString();

        HashMap<String, String> params = new HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

        int result = tts.speak(textToSpeak, queueMode, params);
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to speak text. Error code: " + result);
            // Reset speaking flag in case of failure
            isSpeaking.set(false);
        } else {
            Log.d(TAG, "Speaking text: " + textToSpeak);
        }
    }


    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        long currentTimestamp = System.currentTimeMillis();

        // Don't process if we're still processing an image or speaking text
        if (isProcessing.get() || isSpeaking.get() ||
                currentTimestamp - lastProcessingTimestamp < PROCESSING_INTERVAL) {
            imageProxy.close();
            return;
        }

        // Set processing flag to true
        isProcessing.set(true);
        lastProcessingTimestamp = currentTimestamp;

        Log.d(TAG, "Starting multi-language image analysis");

        // Process on a separate thread
        analysisExecutor.execute(() -> {
            try {
                // Get the image
                Image mediaImage = imageProxy.getImage();

                if (mediaImage != null) {
                    // Create InputImage
                    InputImage inputImage = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees());

                    // Process with both recognizers simultaneously
                    try {
                        // Process with Latin recognizer
                        String latinText = Tasks.await(
                                latinTextRecognizer.process(inputImage)
                                        .continueWith(task -> {
                                            if (task.isSuccessful() && task.getResult() != null) {
                                                return task.getResult().getText().trim();
                                            }
                                            return "";
                                        }),
                                3, TimeUnit.SECONDS
                        );

                        // Process with Devanagari recognizer
                        String devanagariText = Tasks.await(
                                devanagariTextRecognizer.process(inputImage)
                                        .continueWith(task -> {
                                            if (task.isSuccessful() && task.getResult() != null) {
                                                return task.getResult().getText().trim();
                                            }
                                            return "";
                                        }),
                                3, TimeUnit.SECONDS
                        );

                        // Combine results, prioritizing Devanagari if present
                        final String recognizedText;
                        if (!devanagariText.isEmpty()) {
                            recognizedText = devanagariText;
                            Log.d(TAG, "Devanagari text recognized: " + devanagariText);
                        } else if (!latinText.isEmpty()) {
                            recognizedText = latinText;
                            Log.d(TAG, "Latin text recognized: " + latinText);
                        } else {
                            recognizedText = "";
                            Log.d(TAG, "No text recognized");
                        }

                        // Handle on main thread
                        mainHandler.post(() -> {
                            // Save the text
                            lastRecognizedText = recognizedText;

                            // Notify listener
                            if (listener != null) {
                                listener.onTextRecognized(recognizedText);
                            }

                            // Speak recognized text with auto-detection
                            speakTextInDetectedLanguage(recognizedText);
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Error during text recognition tasks: " + e.getMessage(), e);
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onTextRecognized("");
                            }
                            speakText(englishTTS, "Text recognition failed", TextToSpeech.QUEUE_FLUSH);
                        });
                    }

                } else {
                    Log.e(TAG, "Media image is null");
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onTextRecognized("");
                        }
                        speakText(englishTTS, "Camera image unavailable", TextToSpeech.QUEUE_FLUSH);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in analyze: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onTextRecognized("");
                    }
                    speakText(englishTTS, "Image analysis error", TextToSpeech.QUEUE_FLUSH);
                });
            } finally {
                // Release the ImageProxy resources and reset processing flag
                imageProxy.close();
                isProcessing.set(false);
            }
        });
    }


/**
 * Clean up resources
 */
public void shutdown() {
    if (latinTextRecognizer != null) {
        latinTextRecognizer.close();
        latinTextRecognizer = null;
    }

    if (devanagariTextRecognizer != null) {
        devanagariTextRecognizer.close();
        devanagariTextRecognizer = null;
    }

    if (englishTTS != null) {
        englishTTS.stop();
        englishTTS.shutdown();
        englishTTS = null;
    }

    if (hindiTTS != null) {
        hindiTTS.stop();
        hindiTTS.shutdown();
        hindiTTS = null;
    }

    if (marathiTTS != null) {
        marathiTTS.stop();
        marathiTTS.shutdown();
        marathiTTS = null;
    }

    analysisExecutor.shutdown();
    Log.d(TAG, "TextRecognitionAnalyzer resources released");
}

/**
 * Get the last recognized text
 */
public String getLastRecognizedText() {
    return lastRecognizedText;
}

/**
 * Check if the analyzer is currently processing an image
 */
public boolean isProcessing() {
    return isProcessing.get();
}

/**
 * Check if TTS is currently speaking
 */
public boolean isSpeaking() {
    return isSpeaking.get();
}

/**
 * Stop any ongoing TTS
 */
public void stopSpeaking() {
    englishTTS.stop();
    hindiTTS.stop();
    marathiTTS.stop();
    isSpeaking.set(false);
    Log.d(TAG, "TTS speech stopped");
}
}