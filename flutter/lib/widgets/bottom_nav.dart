// flutter/lib/widgets/bottom_nav.dart
// 底部导航栏

import 'package:flutter/material.dart';
import '../screens/home/home_screen.dart';
import '../screens/meal/meal_screen.dart';
import '../screens/exercise/exercise_screen.dart';
import '../screens/prediction/prediction_screen.dart';
import '../screens/settings/settings_screen.dart';

class BottomNav extends StatefulWidget {
  @override
  _BottomNavState createState() => _BottomNavState();
}

class _BottomNavState extends State<BottomNav> {
  int _currentIndex = 0;

  final List<Widget> _screens = [
    HomeScreen(),
    MealScreen(),
    ExerciseScreen(),
    PredictionScreen(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _screens[_currentIndex],
      bottomNavigationBar: BottomNavigationBar(
        type: BottomNavigationBarType.fixed,
        currentIndex: _currentIndex,
        onTap: (index) {
          setState(() {
            _currentIndex = index;
          });
        },
        selectedItemColor: Color(0xFF007A8C),
        unselectedItemColor: Colors.grey,
        items: [
          BottomNavigationBarItem(
            icon: Icon(Icons.home),
            label: '首页',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.restaurant),
            label: '饮食',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.directions_run),
            label: '运动',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.show_chart),
            label: '预测',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.person),
            label: '我的',
          ),
        ],
      ),
    );
  }
}
