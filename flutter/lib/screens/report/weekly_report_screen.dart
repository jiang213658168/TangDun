// flutter/lib/screens/report/weekly_report_screen.dart
// 周报告页面 - 真实API调用

import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import '../../services/api_service.dart';

class WeeklyReportScreen extends StatefulWidget {
  @override
  _WeeklyReportScreenState createState() => _WeeklyReportScreenState();
}

class _WeeklyReportScreenState extends State<WeeklyReportScreen> {
  final _apiService = ApiService();

  Map<String, dynamic>? reportData;
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
      final startOfWeek = now.subtract(Duration(days: now.weekday - 1));
      final data = await _apiService.getWeeklyReport(startDate: startOfWeek);

      setState(() {
        reportData = data;
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
      appBar: AppBar(title: Text('周报告')),
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
              : SingleChildScrollView(
                  padding: EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildWeekRange(),
                      SizedBox(height: 16),
                      _buildStats(),
                      SizedBox(height: 16),
                      _buildHighlightsAndImprovements(),
                    ],
                  ),
                ),
    );
  }

  Widget _buildWeekRange() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Center(
          child: Text(
            '${reportData?['start_date'] ?? ''} ~ ${reportData?['end_date'] ?? ''}',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
        ),
      ),
    );
  }

  Widget _buildStats() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('本周统计', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildStatItem('平均TIR', '${(reportData?['avg_tir'] as num?)?.toStringAsFixed(1) ?? '-'}%'),
                _buildStatItem('平均血糖', '${(reportData?['avg_glucose'] as num?)?.toStringAsFixed(1) ?? '-'}'),
                _buildStatItem('血糖波动', '${(reportData?['glucose_variability'] as num?)?.toStringAsFixed(2) ?? '-'}'),
              ],
            ),
            SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildStatItem('总碳水', '${(reportData?['total_carbs'] as num?)?.toStringAsFixed(0) ?? '0'}g'),
                _buildStatItem('总步数', '${reportData?['total_steps'] ?? 0}'),
                _buildStatItem('运动时长', '${reportData?['total_exercise_min'] ?? 0}分钟'),
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
        Text(value, style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Color(0xFF007A8C))),
        SizedBox(height: 4),
        Text(label, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
      ],
    );
  }

  Widget _buildHighlightsAndImprovements() {
    final highlights = (reportData?['highlights'] as List<dynamic>?) ?? [];
    final improvements = (reportData?['improvements'] as List<dynamic>?) ?? [];

    return Column(
      children: [
        if (highlights.isNotEmpty)
          Card(
            color: Colors.green[50],
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.star, color: Colors.green),
                      SizedBox(width: 8),
                      Text('管理亮点', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                    ],
                  ),
                  SizedBox(height: 8),
                  ...highlights.map((item) => Padding(
                    padding: EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      children: [
                        Icon(Icons.check, color: Colors.green, size: 16),
                        SizedBox(width: 8),
                        Expanded(child: Text(item.toString())),
                      ],
                    ),
                  )).toList(),
                ],
              ),
            ),
          ),
        SizedBox(height: 12),
        if (improvements.isNotEmpty)
          Card(
            color: Colors.orange[50],
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.trending_up, color: Colors.orange),
                      SizedBox(width: 8),
                      Text('待改进项', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                    ],
                  ),
                  SizedBox(height: 8),
                  ...improvements.map((item) => Padding(
                    padding: EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      children: [
                        Icon(Icons.arrow_forward, color: Colors.orange, size: 16),
                        SizedBox(width: 8),
                        Expanded(child: Text(item.toString())),
                      ],
                    ),
                  )).toList(),
                ],
              ),
            ),
          ),
      ],
    );
  }
}
