// flutter/lib/app.dart
// MaterialApp配置

import 'package:flutter/material.dart';
import 'screens/home/home_screen.dart';
import 'screens/meal/meal_screen.dart';
import 'screens/exercise/exercise_screen.dart';
import 'screens/prediction/prediction_screen.dart';
import 'screens/settings/settings_screen.dart';
import 'widgets/bottom_nav.dart';

class TangDunApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '糖盾',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        primaryColor: Color(0xFF007A8C),
        scaffoldBackgroundColor: Colors.white,
        fontFamily: 'PingFang SC',
        colorScheme: ColorScheme.fromSeed(
          seedColor: Color(0xFF007A8C),
          secondary: Color(0xFF1A3C5E),
        ),
        appBarTheme: AppBarTheme(
          backgroundColor: Color(0xFF007A8C),
          foregroundColor: Colors.white,
          elevation: 0,
        ),
        cardTheme: CardTheme(
          elevation: 2,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      home: BottomNav(),
    );
  }
}
