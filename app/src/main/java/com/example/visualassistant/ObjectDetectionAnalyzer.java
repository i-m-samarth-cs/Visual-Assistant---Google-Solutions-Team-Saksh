package com.example.visualassistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ObjectDetectionAnalyzer implements ImageAnalysis.Analyzer {
    private static final String TAG = "ObjectDetectionAnalyzer";
    private static final String MODEL_FILE = "lite-model_ssd_mobilenet_v1_1_metadata_2.tflite";

    private final Context context;
    private ObjectDetector objectDetector;
    private long lastAnalysisTimestamp = 0;
    private static final long ANALYSIS_INTERVAL = 1000; // 1 sec
    private static final long SPEECH_INTERVAL = 3000;   // 3 sec

    private final Map<String, Long> lastDetectionTimes = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private boolean isNavigationMode = false;
    private static final float CONFIDENCE_THRESHOLD = 0.45f;
    private static final float OBSTACLE_PROXIMITY_THRESHOLD = 0.4f;

    private String currentLanguage = "ENGLISH";

    public interface SpeechCallback {
        void speak(String text, int queueMode);
    }

    private SpeechCallback speechCallback;

    public void setSpeechCallback(SpeechCallback callback) {
        this.speechCallback = callback;
    }

    public void setCurrentLanguage(String language) {
        this.currentLanguage = language;
    }

    public interface ObjectDetectionListener {
        void onDetectionResults(List<ObjectDetectionOverlay.DetectionResult> results);
    }

    private ObjectDetectionListener detectionListener;

    public void setDetectionListener(ObjectDetectionListener listener) {
        this.detectionListener = listener;
    }

    public ObjectDetectionAnalyzer(Context context) {
        this.context = context;
        initializeObjectDetector();
    }

    public void setIsNavigationMode(boolean isNavigationMode) {
        this.isNavigationMode = isNavigationMode;
    }

    private void initializeObjectDetector() {
        backgroundExecutor.execute(() -> {
            try {
                ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setMaxResults(10)
                        .setScoreThreshold(CONFIDENCE_THRESHOLD)
                        .build();
                objectDetector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options);
                Log.d(TAG, "Object detector initialized successfully");
            } catch (IOException e) {
                Log.e(TAG, "Error initializing object detector: " + e.getMessage());
            }
        });
    }


    @Override
    public void analyze(@NonNull ImageProxy image) {
        long currentTimestamp = System.currentTimeMillis();

        if (currentTimestamp - lastAnalysisTimestamp < ANALYSIS_INTERVAL) {
            image.close();
            return;
        }

        if (objectDetector == null) {
            image.close();
            return;
        }

        try {
            Bitmap bitmap = BitmapUtils.getBitmap(image);
            if (bitmap != null) {
                backgroundExecutor.execute(() -> {
                    try {
                        TensorImage tensorImage = TensorImage.fromBitmap(bitmap);
                        List<Detection> results = objectDetector.detect(tensorImage);

                        if (!results.isEmpty()) {
                            processDetectionResults(results, image.getWidth(), image.getHeight(), currentTimestamp);

                            // Speak the first detected object's label
                            Detection firstDetection = results.get(0);
                            String label = firstDetection.getCategories().get(0).getLabel();

                            String message;
                            if (currentLanguage.equals("HINDI")) {
                                message = "पहचाना गया ऑब्जेक्ट: " + label;
                            } else if (currentLanguage.equals("MARATHI")) {
                                message = "ओळखलेली वस्तू: " + label;
                            } else {
                                message = "Detected object: " + label;
                            }

                            final String finalMessage = message;
                            mainHandler.post(() -> {
                                if (speechCallback != null) {
                                    speechCallback.speak(finalMessage, TextToSpeech.QUEUE_FLUSH);
                                }
                            });
                        } else {
                            // No objects detected
                            if (detectionListener != null) {
                                mainHandler.post(() -> detectionListener.onDetectionResults(new ArrayList<>()));
                            }

                            String noObjectMessage;
                            if (currentLanguage.equals("HINDI")) {
                                noObjectMessage = "कोई वस्तु नहीं मिली";
                            } else if (currentLanguage.equals("MARATHI")) {
                                noObjectMessage = "कोणतीही वस्तू सापडली नाही";
                            } else {
                                noObjectMessage = "No object detected";
                            }

                            final String finalNoObjectMessage = noObjectMessage;
                            mainHandler.post(() -> {
                                if (speechCallback != null) {
                                    speechCallback.speak(finalNoObjectMessage, TextToSpeech.QUEUE_FLUSH);
                                }
                            });
                        }

                        lastAnalysisTimestamp = currentTimestamp;
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing image: " + e.getMessage());
        }

        image.close();
    }



    private void processDetectionResults(List<Detection> results, int imageWidth, int imageHeight, long currentTime) {
        List<String> detectedObjects = new ArrayList<>();
        StringBuilder alertMessage = new StringBuilder();
        List<ObjectDetectionOverlay.DetectionResult> overlayResults = new ArrayList<>();

        Map<String, Boolean> spokenObjects = new HashMap<>();
        boolean shouldSpeak = (currentTime - lastAnalysisTimestamp >= SPEECH_INTERVAL);

        for (Detection detection : results) {
            if (detection.getCategories().get(0).getScore() < CONFIDENCE_THRESHOLD) continue;

            String label = detection.getCategories().get(0).getLabel();
            float confidence = detection.getCategories().get(0).getScore();
            RectF boundingBox = detection.getBoundingBox();

            RectF normalizedBox = new RectF(
                    boundingBox.left / imageWidth,
                    boundingBox.top / imageHeight,
                    boundingBox.right / imageWidth,
                    boundingBox.bottom / imageHeight
            );

            overlayResults.add(new ObjectDetectionOverlay.DetectionResult(normalizedBox, label, confidence));

            float objectArea = boundingBox.width() * boundingBox.height();
            float imageArea = imageWidth * imageHeight;
            float areaRatio = objectArea / imageArea;

            float centerX = boundingBox.centerX();
            float normalizedX = centerX / imageWidth;

            if (shouldSpeak) {
                if (!isNavigationMode &&
                        lastDetectionTimes.containsKey(label) &&
                        currentTime - lastDetectionTimes.get(label) < 10000) {
                    continue;
                }

                if (isNavigationMode) {
                    if (areaRatio > OBSTACLE_PROXIMITY_THRESHOLD && !spokenObjects.containsKey(label)) {
                        String direction = normalizedX < 0.4 ? "left" : (normalizedX > 0.6 ? "right" : "front");
                        alertMessage.append(label).append(" ahead to the ").append(direction).append(". ");
                        spokenObjects.put(label, true);
                    }
                } else {
                    if (!spokenObjects.containsKey(label)) {
                        detectedObjects.add(label + " (" + Math.round(confidence * 100) + "%)");
                        lastDetectionTimes.put(label, currentTime);
                        spokenObjects.put(label, true);
                    }
                }
            }
        }

        if (detectionListener != null) {
            mainHandler.post(() -> detectionListener.onDetectionResults(overlayResults));
        }

        if (shouldSpeak && speechCallback != null) {
            if (isNavigationMode && alertMessage.length() > 0) {
                String translated = translateOutput(alertMessage.toString());
                final String finalTranslated = translated;
                mainHandler.post(() -> speechCallback.speak(finalTranslated, TextToSpeech.QUEUE_FLUSH));
            } else if (!isNavigationMode && !detectedObjects.isEmpty()) {
                int maxObjects = Math.min(detectedObjects.size(), 3);
                StringBuilder message = new StringBuilder("I can see ");
                for (int i = 0; i < maxObjects; i++) {
                    message.append(detectedObjects.get(i));
                    if (i < maxObjects - 1) message.append(", ");
                }

                String finalMessage = message.toString();
                String translated = translateOutput(finalMessage);
                final String finalTranslated = translated;
                mainHandler.post(() -> speechCallback.speak(finalTranslated, TextToSpeech.QUEUE_FLUSH));
            }
        }
    }

    private String translateOutput(String englishText) {
        if (currentLanguage.equals("HINDI")) {
            if (englishText.contains("ahead to the")) englishText = englishText.replace("ahead to the", "के पास है");
            if (englishText.contains("I can see")) englishText = englishText.replace("I can see", "मैं देख सकता हूँ");
            if (englishText.contains("detected")) englishText = englishText.replace("detected", "पहचाना गया");

            if (englishText.contains("left")) englishText = englishText.replace("left", "बाईं ओर बाधा");
            if (englishText.contains("right")) englishText = englishText.replace("right", "दाईं ओर बाधा");
            if (englishText.contains("front")) englishText = englishText.replace("front", "सामने बाधा");
            if (englishText.contains("clear")) englishText = englishText.replace("clear", "रास्ता साफ है");

        } else if (currentLanguage.equals("MARATHI")) {
            if (englishText.contains("ahead to the")) englishText = englishText.replace("ahead to the", "समोर आहे");
            if (englishText.contains("I can see")) englishText = englishText.replace("I can see", "मी पाहू शकतो");
            if (englishText.contains("detected")) englishText = englishText.replace("detected", "शोधले");

            if (englishText.contains("left")) englishText = englishText.replace("left", "डावीकडे अडथळा");
            if (englishText.contains("right")) englishText = englishText.replace("right", "उजवीकडे अडथळा");
            if (englishText.contains("front")) englishText = englishText.replace("front", "पुढे अडथळा");
            if (englishText.contains("clear")) englishText = englishText.replace("clear", "मार्ग मोकळा आहे");
        }

        return englishText;
    }

    public void shutdown() {
        backgroundExecutor.shutdown();
    }
}