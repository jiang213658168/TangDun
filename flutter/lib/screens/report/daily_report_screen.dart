// flutter/lib/screens/report/daily_report_screen.dart
// 日报告页面 - 真实API调用

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import '../../widgets/glucose_chart.dart';
import '../../config.dart';

class DailyReportScreen extends StatefulWidget {
  @override
  _DailyReportScreenState createState() => _DailyReportScreenState();
}

class _DailyReportScreenState extends State<DailyReportScreen> {
  final _apiService = ApiService();

  Map<String, dynamic>? reportData;
  bool isLoading = true;
  String? errorMessage;
  DateTime selectedDate = DateTime.now();

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
      final data = await _apiService.getDailyReport(date: selectedDate);
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
      appBar: AppBar(title: Text('日报告')),
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
                      _buildDateSelector(),
                      SizedBox(height: 16),
                      _buildTIRCard(),
                      SizedBox(height: 16),
                      _buildGlucoseStats(),
                      SizedBox(height: 16),
                      _buildSummaryCards(),
                    ],
                  ),
                ),
    );
  }

  Widget _buildDateSelector() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            IconButton(
              icon: Icon(Icons.chevron_left),
              onPressed: () {
                setState(() {
                  selectedDate = selectedDate.subtract(Duration(days: 1));
                });
                _loadData();
              },
            ),
            Text(
              '${selectedDate.year}-${selectedDate.month.toString().padLeft(2, '0')}-${selectedDate.day.toString().padLeft(2, '0')}',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            IconButton(
              icon: Icon(Icons.chevron_right),
              onPressed: () {
                setState(() {
                  selectedDate = selectedDate.add(Duration(days: 1));
                });
                _loadData();
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTIRCard() {
    final tir = (reportData?['tir'] as num?)?.toDouble() ?? 0;
    final tirLow = (reportData?['tir_low'] as num?)?.toDouble() ?? 0;
    final tirHigh = (reportData?['tir_high'] as num?)?.toDouble() ?? 0;

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('TIR统计', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildTIRItem('目标范围内', '${tir.toStringAsFixed(1)}%', Colors.green),
                _buildTIRItem('低于目标', '${tirLow.toStringAsFixed(1)}%', Colors.red),
                _buildTIRItem('高于目标', '${tirHigh.toStringAsFixed(1)}%', Colors.orange),
              ],
            ),
            SizedBox(height: 12),
            LinearProgressIndicator(
              value: tir / 100,
              backgroundColor: Colors.grey[200],
              valueColor: AlwaysStoppedAnimation<Color>(Colors.green),
              minHeight: 10,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTIRItem(String label, String value, Color color) {
    return Column(
      children: [
        Text(value, style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: color)),
        SizedBox(height: 4),
        Text(label, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
      ],
    );
  }

  Widget _buildGlucoseStats() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('血糖统计', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildStatItem('平均', '${(reportData?['avg_glucose'] as num?)?.toStringAsFixed(1) ?? '-'}'),
                _buildStatItem('最低', '${(reportData?['min_glucose'] as num?)?.toStringAsFixed(1) ?? '-'}'),
                _buildStatItem('最高', '${(reportData?['max_glucose'] as num?)?.toStringAsFixed(1) ?? '-'}'),
                _buildStatItem('标准差', '${(reportData?['std_glucose'] as num?)?.toStringAsFixed(2) ?? '-'}'),
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

  Widget _buildSummaryCards() {
    return Row(
      children: [
        Expanded(
          child: Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                children: [
                  Icon(Icons.restaurant, color: Color(0xFF007A8C)),
                  SizedBox(height: 8),
                  Text('碳水摄入', style: TextStyle(fontSize: 12, color: Colors.grey[600])),
                  SizedBox(height: 4),
                  Text('${(reportData?['total_carbs'] as num?)?.toStringAsFixed(0) ?? '0'}g',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                ],
              ),
            ),
          ),
        ),
        SizedBox(width: 12),
        Expanded(
          child: Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                children: [
                  Icon(Icons.directions_walk, color: Color(0xFF007A8C)),
                  SizedBox(height: 8),
                  Text('运动时长', style: TextStyle(fontSize: 12, color: Colors.grey[600])),
                  SizedBox(height: 4),
                  Text('${reportData?['total_exercise_min'] ?? 0}分钟',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
