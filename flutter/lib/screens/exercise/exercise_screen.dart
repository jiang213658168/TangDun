// flutter/lib/screens/exercise/exercise_screen.dart
// 运动模块主页 - 真实API调用

import 'package:flutter/material.dart';
import '../../services/api_service.dart';

class ExerciseScreen extends StatefulWidget {
  @override
  _ExerciseScreenState createState() => _ExerciseScreenState();
}

class _ExerciseScreenState extends State<ExerciseScreen> {
  final _apiService = ApiService();

  Map<String, dynamic>? exerciseStats;
  List<dynamic> exerciseRecords = [];
  Map<String, dynamic>? prescription;
  bool isLoading = true;
  String? errorMessage;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      isLoading = true;
      errorMessage = null;
    });

    try {
      final now = DateTime.now();
      final startOfDay = DateTime(now.year, now.month, now.day);

      final results = await Future.wait([
        _apiService.getExerciseStats(start: startOfDay),
        _apiService.getExerciseRecords(start: startOfDay, limit: 50),
        _apiService.getExercisePrescription(),
      ]);

      setState(() {
        exerciseStats = results[0] as Map<String, dynamic>;
        exerciseRecords = results[1] as List<dynamic>;
        prescription = results[2] as Map<String, dynamic>;
        isLoading = false;
      });
    } catch (e) {
      setState(() {
        errorMessage = '加载数据失败: $e';
        isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('运动管理')),
      body: isLoading
          ? Center(child: CircularProgressIndicator())
          : errorMessage != null
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(errorMessage!, style: TextStyle(color: Colors.red)),
                      SizedBox(height: 16),
                      ElevatedButton(onPressed: _loadData, child: Text('重试')),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _loadData,
                  child: SingleChildScrollView(
                    padding: EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _buildExerciseStats(),
                        SizedBox(height: 16),
                        _buildExercisePrescription(),
                        SizedBox(height: 16),
                        _buildExerciseRecords(),
                      ],
                    ),
                  ),
                ),
    );
  }

  Widget _buildExerciseStats() {
    final stats = exerciseStats ?? {};

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('今日运动数据', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildStatItem('运动时长', '${stats['total_duration_min'] ?? 0}分钟'),
                _buildStatItem('步数', '${stats['total_steps'] ?? 0}'),
                _buildStatItem('消耗热量', '${(stats['total_calories'] as num?)?.toStringAsFixed(0) ?? '0'}kcal'),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatItem(String label, String value) {
    return Column(
      children: [
        Text(value, style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Color(0xFF007A8C))),
        SizedBox(height: 4),
        Text(label, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
      ],
    );
  }

  Widget _buildExercisePrescription() {
    if (prescription == null) return SizedBox();

    return Card(
      color: Colors.blue[50],
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.medical_services, color: Color(0xFF007A8C)),
                SizedBox(width: 8),
                Text('运动处方推荐', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            SizedBox(height: 12),
            Text(prescription!['notes'] ?? ''),
            SizedBox(height: 8),
            Text(
              '推荐: ${_getExerciseName(prescription!['exercise_type'])} ${prescription!['duration_min']}分钟',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Color(0xFF007A8C)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildExerciseRecords() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('今日运动记录', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
        SizedBox(height: 12),
        if (exerciseRecords.isEmpty)
          Center(child: Text('暂无运动记录', style: TextStyle(color: Colors.grey)))
        else
          ...exerciseRecords.map((record) => _buildExerciseCard(record)).toList(),
      ],
    );
  }

  Widget _buildExerciseCard(Map<String, dynamic> record) {
    return Card(
      margin: EdgeInsets.only(bottom: 12),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: Color(0xFF007A8C),
          child: Icon(Icons.directions_run, color: Colors.white),
        ),
        title: Text('${_getExerciseName(record['exercise_type'])} - ${_formatTime(record['start_time'])}'),
        subtitle: Text('${record['duration_min'] ?? 0}分钟 | ${record['steps'] ?? 0}步'),
      ),
    );
  }

  String _getExerciseName(String? type) {
    switch (type) {
      case 'walking': return '步行';
      case 'running': return '跑步';
      case 'cycling': return '骑行';
      case 'swimming': return '游泳';
      default: return type ?? '运动';
    }
  }

  String _formatTime(String? timestamp) {
    if (timestamp == null) return '';
    final dt = DateTime.parse(timestamp);
    return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}
