import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'app/coordinator.dart';
import 'app/root_gate.dart';
import 'controllers/driver_controller.dart';
import 'controllers/responder_controller.dart';
import 'core/config/app_config.dart';
import 'core/config/app_settings.dart';
import 'core/net/api_client.dart';
import 'core/theme/app_theme.dart';
import 'services/auth_service.dart';
import 'services/background_service.dart';
import 'services/call_service.dart';
import 'services/chat_service.dart';
import 'services/connectivity_service.dart';
import 'services/crash_detector.dart';
import 'services/duty_service.dart';
import 'services/local_alerts.dart';
import 'services/local_history.dart';
import 'services/profile_service.dart';
import 'services/push_handler.dart';
import 'services/push_service.dart';
import 'services/saved_store.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Push (Android only; on Chrome / iOS this does nothing and the app still works).
  await PushService.initFirebase();
  // The background service must be set up before the first screen.
  BackgroundService.I.init();

  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  await AppConfig.I.load();
  await AppSettings.I.load();
  await AuthService.I.load();
  await Future.wait([
    SavedStore.I.load(),
    LocalHistory.I.load(),
    ProfileService.I.loadCached(),
    ConnectivityService.I.start(),
  ]);
  ApiClient.I.onSessionExpired = () => AuthService.I.sessionExpired();
  // Creates the Android notification channels before the first push can arrive.
  await LocalAlerts.I.init();

  runApp(const IersApp());
}

class IersApp extends StatefulWidget {
  const IersApp({super.key});

  @override
  State<IersApp> createState() => _IersAppState();
}

class _IersAppState extends State<IersApp> {
  @override
  void initState() {
    super.initState();
    PushService.I.setup(onMessage: handlePushMessage);
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: AppConfig.I),
        ChangeNotifierProvider.value(value: AppSettings.I),
        ChangeNotifierProvider.value(value: AuthService.I),
        ChangeNotifierProvider.value(value: ConnectivityService.I),
        ChangeNotifierProvider.value(value: DriverController.I),
        ChangeNotifierProvider.value(value: ResponderController.I),
        ChangeNotifierProvider.value(value: DutyService.I),
        ChangeNotifierProvider.value(value: CallService.I),
        ChangeNotifierProvider.value(value: ChatService.I),
        ChangeNotifierProvider.value(value: CrashDetector.I),
        ChangeNotifierProvider.value(value: ProfileService.I),
        ChangeNotifierProvider.value(value: SavedStore.I),
        ChangeNotifierProvider.value(value: LocalHistory.I),
      ],
      child: MaterialApp(
        title: 'IERS',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        darkTheme: AppTheme.light(),
        themeMode: ThemeMode.light,
        navigatorKey: AppCoordinator.I.navKey,
        scaffoldMessengerKey: AppCoordinator.I.messengerKey,
        home: const RootGate(),
      ),
    );
  }
}