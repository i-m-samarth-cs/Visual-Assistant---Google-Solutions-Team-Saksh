# Visual Assistance

An Android application built in Java that uses computer vision to provide real-time assistance to visually impaired users.

## 📋 Overview

Visual Assistance is an Android application that leverages device cameras and machine learning to identify objects, read text, and describe surroundings for visually impaired users. The app converts visual information into audio feedback, helping users navigate their environment with greater independence.

## ✨ Features

- **Real-time Object Detection**: Identifies objects and their relative positions
- **Text Recognition (OCR)**: Reads text from signs, labels, and documents
- **Scene Description**: Provides simple descriptions of the user's surroundings
- **Navigation Assistance**: Warns about obstacles and provides guidance
- **Voice Commands**: Hands-free operation through voice instructions
- **Offline Functionality**: Core features work without internet connection
- **Vibration Feedback**: Tactile alerts for detected objects or obstacles

## 🔧 Technologies

- Java for Android development
- Android Camera2 API
- TensorFlow Lite for on-device ML models
- Google ML Kit for text recognition
- Android TTS (Text-to-Speech) for audio feedback
- Android Speech Recognition for voice commands

## ⚙️ Installation

### Requirements
- Android Studio 4.0+
- Android SDK 21+
- Android device with camera (minimum Android 5.0 Lollipop)

### Setup Instructions
```
# Clone the repository
git clone https://github.com/i-m-samarth-cs/Visual-Assistant---Google-Solutions-Team-Saksh
# Open the project in Android Studio
File > Open > [select project directory]

# Build the project
Build > Make Project

# Run on a device or emulator
Run > Run 'app'
```

## 📱 Usage

1. Install the app on your Android device
2. Grant camera and microphone permissions when prompted
3. Use the following features:
   - Single tap: Identify objects in front of the camera
   - Double tap: Read visible text
   - Long press: Describe the current scene
   - Swipe right: Access settings menu
   - Voice command "help": Hear available commands

## 🔄 Permissions

The app requires the following permissions:
- Camera access
- Microphone access
- Storage access (for saving preferences)
- Internet access (for cloud-based features)

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgements

- [TensorFlow Lite](https://www.tensorflow.org/lite) for on-device machine learning
- [Google ML Kit](https://developers.google.com/ml-kit) for vision APIs
- [Android Open Source Project](https://source.android.com/) for the foundation
- All contributors who have helped improve this project

## 📞 Contact

Project Link: [https://github.com/yourusername/visual-assistance](https://github.com/i-m-samarth-cs/Visual---Assistant-Team-Saksh)

For questions or support, please open an issue or contact the repository owner.
