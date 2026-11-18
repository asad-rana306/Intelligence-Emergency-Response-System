// Firebase settings for the Android app (package com.example.untitled).
// Copied from android/app/google-services.json (the client for com.example.untitled).
// Only Android is set up. On Chrome / iOS the app runs without push (see main.dart).
import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart' show TargetPlatform, defaultTargetPlatform, kIsWeb;

class DefaultFirebaseOptions {
  DefaultFirebaseOptions._();

  /// True when this build has Firebase settings (Android only).
  static bool get isSupported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static FirebaseOptions get currentPlatform {
    if (isSupported) return android;
    throw UnsupportedError('Firebase is only set up for Android in this app.');
  }

  static const FirebaseOptions android = FirebaseOptions(
    apiKey: 'AIzaSyA9LJSv42iwJBU-8xzYTeDkPBNdMJeUdjY',
    appId: '1:266260269995:android:85bacb31140a25ecdee0ee',
    messagingSenderId: '266260269995',
    projectId: 'iers-a6400',
    storageBucket: 'iers-a6400.firebasestorage.app',
  );
}
