package com.example.visualassistant;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.Context;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener, SensorEventListener {

    private static final String TAG = "VisualAssistant";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_AUDIO_PERMISSION = 101;

    // UI Components
    private PreviewView previewView;
    private Button emergencyButton;
    private Button sosButton;

    private TextView modeIndicator;
    private TextView statusText;
    private TextView commandHintText;

    // Camera variables
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    // Text-to-Speech
    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;

    // Speech Recognition
    private enum AppLanguage {
        ENGLISH, HINDI, MARATHI
    }
    private AppLanguage currentLanguage = AppLanguage.ENGLISH;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListening = false;

    // Mode tracking
    private enum AppMode {
        HOME, OBJECT_DETECTION, TEXT_RECOGNITION, NAVIGATION, SOS
    }
    private AppMode currentMode = AppMode.HOME;

    // Vibration for feedback
    private Vibrator vibrator;

    // Object detection analyzer
    private ObjectDetectionAnalyzer objectDetectionAnalyzer;

    // Text recognition analyzer
    private TextRecognitionAnalyzer textRecognitionAnalyzer;

    // SOS related variables
    private MediaPlayer sirenPlayer;
    private boolean isSirenPlaying = false;
    private boolean sosManuallyTriggered = false;

    // Shake detection
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float accelerationThreshold = 500.0f; // Threshold for shake detection
    private float lastX, lastY, lastZ;
    private long lastUpdate = 0;
    private long shakeThresholdTime = 50000; // Minimum time between shakes (ms)
    private boolean appInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeIndicator = findViewById(R.id.modeIndicator);
        statusText = findViewById(R.id.statusText);
        commandHintText = findViewById(R.id.commandHintText);

        // Initialize UI components
        previewView = findViewById(R.id.previewView);
        emergencyButton = findViewById(R.id.emergencyButton);
        sosButton = findViewById(R.id.sosButton);

        // Make SOS button visible from the start
        sosButton.setVisibility(View.VISIBLE);

        // Initialize Text-to-Speech
        textToSpeech = new TextToSpeech(this, this);

        // Initialize vibrator
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // Initialize analyzers
        objectDetectionAnalyzer = new ObjectDetectionAnalyzer(this);
        textRecognitionAnalyzer = new TextRecognitionAnalyzer(this);

        SharedPreferences prefs = getSharedPreferences("VisualAssistantPrefs", MODE_PRIVATE);
        String savedLanguage = prefs.getString("language", "ENGLISH");
        currentLanguage = AppLanguage.valueOf(savedLanguage);

        // Set click listener for emergency button - MODIFIED for direct restart
        emergencyButton.setOnClickListener(v -> {
            vibrator.vibrate(200);

            // Stop current activities
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                isListening = false;
            }

            // Stop all camera analyzer bindings
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                // Rebind basic preview only
                bindPreview(cameraProvider);
            }

            // Reset to home mode
            currentMode = AppMode.HOME;
            updateUIForMode("HOME");

            // Announce return to home based on language
            String homeMessage;
            switch (currentLanguage) {
                case HINDI:
                    homeMessage = "होम मोड पर वापस आ गए";
                    break;
                case MARATHI:
                    homeMessage = "होम मोड वर परत आलो";
                    break;
                default:
                    homeMessage = "Returned to home mode";
                    break;
            }
            speak(homeMessage, TextToSpeech.QUEUE_FLUSH);

            // Wait for announcement to finish before welcoming user
            new Handler().postDelayed(this::welcomeUser, 2000);
        });

        // Initialize the media player for SOS
        sirenPlayer = MediaPlayer.create(this, R.raw.siren_sound);
        sirenPlayer.setLooping(true); // Set looping to repeat the siren

        // Set up SOS button click listener
        sosButton.setOnClickListener(v -> {
            if (isSirenPlaying) {
                stopSiren();
            } else {
                sosManuallyTriggered = true;
                toggleSiren();
            }
        });

        // Initialize shake detection
        initShakeDetection();

        // Request permissions
        requestPermissions();

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initShakeDetection() {
        // Get sensor manager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Get accelerometer sensor
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

            if (accelerometer != null) {
                // Register sensor listener
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                Toast.makeText(this, "Accelerometer not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Toggle siren on/off
    private void toggleSiren() {
        if (isSirenPlaying) {
            stopSiren();
        } else {
            startSiren();
        }
    }

    // Start playing the siren
    private void startSiren() {
        if (!isSirenPlaying) {
            sirenPlayer.start();
            isSirenPlaying = true;
            sosButton.setText("STOP");
            sosButton.setBackgroundResource(R.drawable.stop_button_background);
            statusText.setText("SOS ALARM ACTIVE");
            currentMode = AppMode.SOS;

            // Vibrate to indicate SOS is active
            if (vibrator.hasVibrator()) {
                // Vibrate pattern: wait 0ms, vibrate 500ms, wait 500ms, repeat
                long[] pattern = {0, 500, 500};
                vibrator.vibrate(pattern, 0); // 0 means repeat indefinitely
            }

            // Announce SOS mode activation through TTS
            speak("SOS Emergency Mode activated", TextToSpeech.QUEUE_FLUSH);
        }
    }

    // Stop playing the siren
    private void stopSiren() {
        if (isSirenPlaying) {
            try {
                // Stop the media player and reset it for future use
                sirenPlayer.stop();
                sirenPlayer.reset();
                sirenPlayer = MediaPlayer.create(this, R.raw.siren_sound);
                sirenPlayer.setLooping(true);

                isSirenPlaying = false;
                sosButton.setText("SOS");
                sosButton.setBackgroundResource(R.drawable.sos_button_background);
                statusText.setText("Listening for commands...");
                currentMode = AppMode.HOME;
                sosManuallyTriggered = false;

                // Stop vibration
                vibrator.cancel();

                // Announce SOS mode deactivation through TTS
                speak("SOS Emergency Mode deactivated", TextToSpeech.QUEUE_FLUSH);

                // Restart voice recognition
                new Handler().postDelayed(this::startVoiceRecognition, 2000);

                Log.d("SirenDebug", "Siren stopping procedure completed");
            } catch (Exception e) {
                Log.e("SirenDebug", "Error stopping siren: " + e.getMessage());
            }
        } else {
            Log.d("SirenDebug", "stopSiren called but isSirenPlaying is false");
        }
    }

    private void requestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_CAMERA_PERMISSION);
        } else {
            initializeApp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                initializeApp();
            } else {
                speak("Camera and microphone permissions are required for this app to function properly",
                        TextToSpeech.QUEUE_FLUSH);
                new Handler().postDelayed(this::finish, 5000);
            }
        }
    }

    private void initializeApp() {
        if (appInitialized) {
            // App already initialized, don't repeat the process
            return;
        }

        appInitialized = true;

        // Initialize the camera
        initializeCamera();

        // Initialize speech recognizer
        if (speechRecognizer == null) {
            initializeSpeechRecognizer();
        }

        // Set default UI mode
        updateUIForMode("HOME");

        // Start with welcome message
        new Handler().postDelayed(() -> {
            if (ttsInitialized) {
                welcomeUser();
            } else {
                new Handler().postDelayed(this::welcomeUser, 2000);
            }
        }, 1000);
    }

    private void setAppLanguage(Locale locale) {

        if (locale == null) {
            Log.e(TAG, "Attempted to set null locale, defaulting to English");
            locale = Locale.US;
        }

        SharedPreferences prefs = getSharedPreferences("VisualAssistantPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("language", currentLanguage.toString());
        editor.apply();
        // Update TextToSpeech language
        if (ttsInitialized) {
            int result = textToSpeech.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported: " + locale);
                // Fall back to English if requested language is not available
                textToSpeech.setLanguage(Locale.US);
                Toast.makeText(this, "Requested language not available, using English", Toast.LENGTH_SHORT).show();
            }
        }

        // Update SpeechRecognizer language
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);

        // Update UI text
        updateUITexts(locale);

        // Add this at the end of setAppLanguage method:
        AppMode tempMode = currentMode;
        String modeString;
        switch (tempMode) {
            case OBJECT_DETECTION:
                modeString = "IDENTIFY";
                break;
            case TEXT_RECOGNITION:
                modeString = "READ";
                break;
            case NAVIGATION:
                modeString = "NAVIGATE";
                break;
            case SOS:
                modeString = "SOS";
                break;
            default:
                modeString = "HOME";
                break;
        }
        updateUIForMode(modeString);
    }

    private void updateUITexts(Locale locale) {
        // Get language code
        String lang = locale.getLanguage();

        switch (lang) {
            case "hi": // Hindi
                commandHintText.setText("कहें: 'नेविगेट', 'रीड', या 'आइडेंटिफाई'");
                emergencyButton.setText("आपातकालीन बटन");
                sosButton.setText(isSirenPlaying ? "स्टॉप" : "एसओएस");
                statusText.setText("सुन रहा है...");
                updateModeIndicatorText(currentMode, "hi");
                break;
            case "mr": // Marathi
                commandHintText.setText("म्हणा: 'नेविगेट', 'रीड', या 'आयडेंटिफाय'");
                emergencyButton.setText("आणीबाणी बटण");
                sosButton.setText(isSirenPlaying ? "स्टॉप" : "एसओएस");
                statusText.setText("ऐकत आहे...");
                updateModeIndicatorText(currentMode, "mr");
                break;
            default: // English
                commandHintText.setText("Say: 'Navigate', 'Read', or 'Identify'");
                emergencyButton.setText("Emergency Button");
                sosButton.setText(isSirenPlaying ? "STOP" : "SOS");
                statusText.setText("Listening for commands...");
                updateModeIndicatorText(currentMode, "en");
                break;
        }
    }

    private void updateModeIndicatorText(AppMode mode, String languageCode) {
        String modeText;

        if (languageCode.equals("hi")) { // Hindi
            switch (mode) {
                case OBJECT_DETECTION:
                    modeText = "पहचान मोड";
                    break;
                case TEXT_RECOGNITION:
                    modeText = "पढ़ने का मोड";
                    break;
                case NAVIGATION:
                    modeText = "नेविगेशन मोड";
                    break;
                case SOS:
                    modeText = "एसओएस मोड";
                    break;
                default:
                    modeText = "होम मोड";
                    break;
            }
        } else if (languageCode.equals("mr")) { // Marathi
            switch (mode) {
                case OBJECT_DETECTION:
                    modeText = "ओळख मोड";
                    break;
                case TEXT_RECOGNITION:
                    modeText = "वाचन मोड";
                    break;
                case NAVIGATION:
                    modeText = "नेविगेशन मोड";
                    break;
                case SOS:
                    modeText = "एसओएस मोड";
                    break;
                default:
                    modeText = "होम मोड";
                    break;
            }
        } else { // English
            switch (mode) {
                case OBJECT_DETECTION:
                    modeText = "IDENTIFY";
                    break;
                case TEXT_RECOGNITION:
                    modeText = "READ";
                    break;
                case NAVIGATION:
                    modeText = "NAVIGATE";
                    break;
                case SOS:
                    modeText = "SOS";
                    break;
                default:
                    modeText = "HOME";
                    break;
            }
        }

        modeIndicator.setText(modeText);
    }

    private String translateMode(String mode, String languageCode) {
        if (languageCode.equals("hi")) { // Hindi
            switch (mode) {
                case "IDENTIFY": return "पहचान";
                case "READ": return "पढ़ना";
                case "NAVIGATE": return "नेविगेशन";
                case "SOS": return "एसओएस";
                default: return "होम";
            }
        } else if (languageCode.equals("mr")) { // Marathi
            switch (mode) {
                case "IDENTIFY": return "ओळख";
                case "READ": return "वाचन";
                case "NAVIGATE": return "नेविगेशन";
                case "SOS": return "एसओएस";
                default: return "होम";
            }
        }
        return mode;
    }

    private void welcomeUser() {
        String welcomeMessage;

        switch (currentLanguage) {
            case HINDI:
                welcomeMessage = "विजुअल असिस्टेंट में आपका स्वागत है। बाधाओं का पता लगाने के लिए 'नेविगेट' कहें, " +
                        "टेक्स्ट पहचान के लिए 'रीड', या अपने आसपास की वस्तुओं की पहचान के लिए 'आइडेंटिफाई' कहें।";
                break;
            case MARATHI:
                welcomeMessage = "व्हिज्युअल असिस्टंट मध्ये आपले स्वागत आहे। अडथळे शोधण्यासाठी 'नेविगेट' म्हणा, " +
                        "मजकूर ओळखण्यासाठी 'रीड', किंवा आपल्या आजूबाजूच्या वस्तू ओळखण्यासाठी 'आयडेंटिफाय' म्हणा।";
                break;
            default:
                welcomeMessage = "Welcome to Visual Assistant. Say 'Navigate' to detect obstacles, " +
                        "'Read' for text recognition, or 'Identify' to identify objects around you.";
                break;
        }

        speak(welcomeMessage, TextToSpeech.QUEUE_FLUSH);

        // Start listening after welcome message
        new Handler().postDelayed(this::startVoiceRecognition, 5000);
    }

    private void updateUIForMode(String mode) {
        // Change mode indicator color based on mode
        int backgroundColor;
        switch (mode) {
            case "IDENTIFY":
                backgroundColor = getResources().getColor(R.color.warningYellow);
                break;
            case "READ":
                backgroundColor = getResources().getColor(R.color.colorPrimary);
                break;
            case "NAVIGATE":
                backgroundColor = getResources().getColor(R.color.successGreen);
                break;
            case "SOS":
                backgroundColor = getResources().getColor(R.color.colorRed);
                break;
            default:
                backgroundColor = getResources().getColor(R.color.colorAccent);
                break;
        }

        GradientDrawable background = (GradientDrawable) modeIndicator.getBackground();
        background.setColor(backgroundColor);

        // Update status text based on current language
        String lang = textToSpeech != null && textToSpeech.getLanguage() != null ?
                textToSpeech.getLanguage().getLanguage() : "en";

        // Map string mode to enum
        AppMode appMode;
        switch (mode) {
            case "IDENTIFY":
                appMode = AppMode.OBJECT_DETECTION;
                break;
            case "READ":
                appMode = AppMode.TEXT_RECOGNITION;
                break;
            case "NAVIGATE":
                appMode = AppMode.NAVIGATION;
                break;
            case "SOS":
                appMode = AppMode.SOS;
                break;
            default:
                appMode = AppMode.HOME;
                break;
        }

        currentMode = appMode; // Update current mode
        updateModeIndicatorText(appMode, lang);

        if (mode.equals("HOME")) {
            if (lang.equals("hi")) {
                statusText.setText("सुन रहा है...");
            } else if (lang.equals("mr")) {
                statusText.setText("ऐकत आहे...");
            } else {
                statusText.setText("Listening for commands...");
            }
            commandHintText.setVisibility(View.VISIBLE);
        } else if (mode.equals("SOS")) {
            if (lang.equals("hi")) {
                statusText.setText("एसओएस सक्रिय");
            } else if (lang.equals("mr")) {
                statusText.setText("एसओएस सक्रिय");
            } else {
                statusText.setText("SOS ALARM ACTIVE");
            }
            commandHintText.setVisibility(View.GONE);
        } else {
            if (lang.equals("hi")) {
                statusText.setText("सक्रिय मोड: " + translateMode(mode, "hi"));
            } else if (lang.equals("mr")) {
                statusText.setText("सक्रिय मोड: " + translateMode(mode, "mr"));
            } else {
                statusText.setText("Active mode: " + mode);
            }
            commandHintText.setVisibility(View.GONE);
        }
    }

    private void initializeSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            Locale recognitionLocale;
            switch (currentLanguage) {
                case HINDI:
                    recognitionLocale = new Locale("hi", "IN");
                    break;
                case MARATHI:
                    recognitionLocale = new Locale("mr", "IN");
                    break;
                default:
                    recognitionLocale = Locale.getDefault();
                    break;
            }

            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLocale);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle bundle) {
                    isListening = true;
                }

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float v) {}

                @Override
                public void onBufferReceived(byte[] bytes) {}

                @Override
                public void onEndOfSpeech() {
                    isListening = false;
                }

                @Override
                public void onError(int error) {
                    String errorMessage;
                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO:
                            errorMessage = "Audio recording error";
                            break;
                        case SpeechRecognizer.ERROR_CLIENT:
                            errorMessage = "Client side error";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            errorMessage = "Insufficient permissions";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                            errorMessage = "Network error";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            errorMessage = "Network timeout";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            errorMessage = "No match found";
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            errorMessage = "Recognition service busy";
                            break;
                        case SpeechRecognizer.ERROR_SERVER:
                            errorMessage = "Server error";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            errorMessage = "No speech input";
                            break;
                        default:
                            errorMessage = "Unknown error";
                            break;
                    }

                    Log.e(TAG, "Speech recognition error: " + errorMessage);
                    startVoiceRecognition();
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String command = matches.get(0).toLowerCase();
                        processVoiceCommand(command);
                    }
                }

                @Override
                public void onPartialResults(Bundle bundle) {}

                @Override
                public void onEvent(int i, Bundle bundle) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Error initializing speech recognizer: " + e.getMessage());
        }
    }

    private void startVoiceRecognition() {
        // Don't start voice recognition if in SOS mode
        if (currentMode == AppMode.SOS) {
            return;
        }

        if (!isListening && ttsInitialized && !textToSpeech.isSpeaking()) {
            try {
                speechRecognizer.startListening(speechRecognizerIntent);
                vibrator.vibrate(100); // Short vibration to indicate listening started
            } catch (Exception e) {
                Log.e(TAG, "Error starting voice recognition: " + e.getMessage());
                new Handler().postDelayed(this::startVoiceRecognition, 3000);
            }
        } else {
            new Handler().postDelayed(this::startVoiceRecognition, 1000);
        }
    }

    private void processVoiceCommand(String command) {
        Log.d(TAG, "Command received: " + command);
        vibrator.vibrate(200); // Vibrate to indicate command received

        if (command.contains("navigate")) {
            speak("Starting navigation mode. I will detect obstacles and guide you.", TextToSpeech.QUEUE_FLUSH);
            currentMode = AppMode.NAVIGATION;
            startNavigationMode();
        } else if (command.contains("read")) {
            // Language-specific announcement for reading mode
            String readingStartMessage;
            switch (currentLanguage) {
                case HINDI:
                    readingStartMessage = "पढ़ने का मोड शुरू हो रहा है। पाठ पढ़ने के लिए कैमरे को टेक्स्ट की ओर घुमाएं।";
                    break;
                case MARATHI:
                    readingStartMessage = "वाचन मोड सुरू होत आहे। मजकूर वाचण्यासाठी कॅमेरा मजकूराकडे निर्देशित करा।";
                    break;
                default:
                    readingStartMessage = "Starting text recognition mode. Point the camera at text to read it.";
                    break;
            }
            speak(readingStartMessage, TextToSpeech.QUEUE_FLUSH);
            currentMode = AppMode.TEXT_RECOGNITION;
            startTextRecognitionMode();
        } else if (command.contains("identify")) {
            speak("Starting object detection mode. I will identify objects around you.", TextToSpeech.QUEUE_FLUSH);
            currentMode = AppMode.OBJECT_DETECTION;
            startObjectDetectionMode();
        } else if (command.contains("home")) {
            speak("Returning to home mode", TextToSpeech.QUEUE_FLUSH);
            currentMode = AppMode.HOME;
            updateUIForMode("HOME");
        } else if (command.contains("sos") || command.contains("emergency") || command.contains("help")) {
            speak("Activating SOS emergency mode", TextToSpeech.QUEUE_FLUSH);
            sosManuallyTriggered = true;
            startSiren();
        } else if (command.contains("stop")) {
            if (isSirenPlaying) {
                stopSiren();
            } else {
                speak("Stopping application", TextToSpeech.QUEUE_FLUSH);
                new Handler().postDelayed(this::finish, 2000);
                return;
            }
        } else if (command.contains("english")) {
            currentLanguage = AppLanguage.ENGLISH;
            setAppLanguage(new Locale("en", "US"));
            speak("Language changed to English", TextToSpeech.QUEUE_FLUSH);
        } else if (command.contains("hindi")) {
            currentLanguage = AppLanguage.HINDI;
            setAppLanguage(new Locale("hi", "IN"));
            speak("भाषा हिंदी में बदलीगई", TextToSpeech.QUEUE_FLUSH);
        } else if (command.contains("marathi")) {
            currentLanguage = AppLanguage.MARATHI;
            setAppLanguage(new Locale("mr", "IN"));
            speak("भाषा मराठी मध्ये बदलली", TextToSpeech.QUEUE_FLUSH);
        } else {
            speak("I didn't understand. Try saying 'Navigate', 'Read', or 'Identify'.", TextToSpeech.QUEUE_FLUSH);
        }

        // Continue listening for commands
        new Handler().postDelayed(this::startVoiceRecognition, 3000);
    }

    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error initializing camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera preview: " + e.getMessage());
        }
    }

    private void startObjectDetectionMode() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, objectDetectionAnalyzer);

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                updateUIForMode("IDENTIFY");
            } catch (Exception e) {
                Log.e(TAG, "Error binding camera for object detection: " + e.getMessage());
            }
        }
    }

    private void startTextRecognitionMode() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, textRecognitionAnalyzer);

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                updateUIForMode("READ");
            } catch (Exception e) {
                Log.e(TAG, "Error binding camera for text recognition: " + e.getMessage());
            }
        }
    }

    private void startNavigationMode() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Consider using a specialized analyzer for detecting obstacles
            // For now, we'll use the object detection analyzer as a placeholder
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            // You might want to create a specific NavigationAnalyzer class
            imageAnalysis.setAnalyzer(cameraExecutor, objectDetectionAnalyzer);

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                updateUIForMode("NAVIGATE");
            } catch (Exception e) {
                Log.e(TAG, "Error binding camera for navigation: " + e.getMessage());
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true;

            // Set language based on current selection
            Locale ttsLocale;
            switch (currentLanguage) {
                case HINDI:
                    ttsLocale = new Locale("hi", "IN");
                    break;
                case MARATHI:
                    ttsLocale = new Locale("mr", "IN");
                    break;
                default:
                    ttsLocale = Locale.US;
                    break;
            }

            int result = textToSpeech.setLanguage(ttsLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported: " + ttsLocale);
                textToSpeech.setLanguage(Locale.US);
            }

            // Initialize speech recognizer after TTS initialization
            initializeSpeechRecognizer();
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    private void speak(String text, int queueMode) {
        if (ttsInitialized) {
            textToSpeech.speak(text, queueMode, null, null);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            long currentTime = System.currentTimeMillis();

            // Only process if enough time has passed
            if ((currentTime - lastUpdate) > shakeThresholdTime) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];

                float acceleration = Math.abs(x + y + z - lastX - lastY - lastZ) /
                        (currentTime - lastUpdate) * 10000;

                if (acceleration > accelerationThreshold) {
                    // Shake detected
                    if (currentMode != AppMode.SOS) {
                        Log.d(TAG, "Shake detected - activating SOS");
                        startSiren();
                    }
                }

                lastX = x;
                lastY = y;
                lastZ = z;
                lastUpdate = currentTime;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register sensor listeners
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Resume voice recognition if needed
        if (!isListening && currentMode != AppMode.SOS) {
            startVoiceRecognition();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Unregister sensor listeners
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        // Stop voice recognition
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            isListening = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Release resources
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        if (sirenPlayer != null) {
            sirenPlayer.release();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}