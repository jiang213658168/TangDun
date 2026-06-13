// flutter/lib/screens/report/monthly_report_screen.dart
// 月报告页面 - 真实API调用

import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import '../../services/api_service.dart';

class MonthlyReportScreen extends StatefulWidget {
  @override
  _MonthlyReportScreenState createState() => _MonthlyReportScreenState();
}

class _MonthlyReportScreenState extends State<MonthlyReportScreen> {
  final _apiService = ApiService();

  Map<String, dynamic>? reportData;
  bool isLoading = true;
  String? errorMessage;
  int selectedYear = DateTime.now().year;
  int selectedMonth = DateTime.now().month;

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
      final data = await _apiService.getMonthlyReport(
        year: selectedYear,
        month: selectedMonth,
      );

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
      appBar: AppBar(title: Text('月报告')),
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
                      _buildMonthSelector(),
                      SizedBox(height: 16),
                      _buildTIRChart(),
                      SizedBox(height: 16),
                      _buildStats(),
                      SizedBox(height: 16),
                      _buildHbA1c(),
                      SizedBox(height: 16),
                      _buildModelProgress(),
                      SizedBox(height: 16),
                      _buildRecommendations(),
                    ],
                  ),
                ),
    );
  }

  Widget _buildMonthSelector() {
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
                  if (selectedMonth == 1) {
                    selectedMonth = 12;
                    selectedYear--;
                  } else {
                    selectedMonth--;
                  }
                });
                _loadData();
              },
            ),
            Text(
              '$selectedYear年$selectedMonth月',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            IconButton(
              icon: Icon(Icons.chevron_right),
              onPressed: () {
                setState(() {
                  if (selectedMonth == 12) {
                    selectedMonth = 1;
                    selectedYear++;
                  } else {
                    selectedMonth++;
                  }
                });
                _loadData();
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTIRChart() {
    final tirTrend = (reportData?['tir_trend'] as List<dynamic>?) ?? [];

    if (tirTrend.isEmpty) {
      return Card(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Text('暂无TIR趋势数据'),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('TIR趋势', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 16),
            Container(
              height: 200,
              child: LineChart(
                LineChartData(
                  gridData: FlGridData(show: true, drawVerticalLine: false),
                  titlesData: FlTitlesData(
                    show: true,
                    rightTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    topTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        interval: 5,
                        getTitlesWidget: (value, meta) {
                          return Text(
                            '${value.toInt()}日',
                            style: TextStyle(fontSize: 10),
                          );
                        },
                      ),
                    ),
                  ),
                  borderData: FlBorderData(show: false),
                  lineBarsData: [
                    LineChartBarData(
                      spots: tirTrend
                          .asMap()
                          .entries
                          .map((entry) => FlSpot(
                                entry.key.toDouble() + 1,
                                (entry.value['tir'] as num).toDouble(),
                              ))
                          .toList(),
                      isCurved: true,
                      color: Color(0xFF007A8C),
                      barWidth: 2,
                      dotData: FlDotData(show: false),
                      belowBarData: BarAreaData(
                        show: true,
                        color: Color(0xFF007A8C).withOpacity(0.1),
                      ),
                    ),
                  ],
                  extraLinesData: ExtraLinesData(
                    horizontalLines: [
                      HorizontalLine(
                        y: 70,
                        color: Colors.green.withOpacity(0.5),
                        strokeWidth: 1,
                        dashArray: [5, 5],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStats() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            _buildStatItem('平均TIR', '${(reportData?['avg_tir'] as num?)?.toStringAsFixed(1) ?? '-'}%'),
            _buildStatItem('平均血糖', '${(reportData?['avg_glucose'] as num?)?.toStringAsFixed(1) ?? '-'} mmol/L'),
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

  Widget _buildHbA1c() {
    final hba1c = reportData?['hba1c_estimate'];

    return Card(
      color: Colors.blue[50],
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          children: [
            Text('糖化血红蛋白估算', style: TextStyle(fontSize: 14, color: Colors.grey[600])),
            SizedBox(height: 8),
            Text(
              hba1c != null ? '$hba1c%' : '-',
              style: TextStyle(fontSize: 36, fontWeight: FontWeight.bold, color: Color(0xFF007A8C)),
            ),
            SizedBox(height: 4),
            Text(
              '基于本月平均血糖估算',
              style: TextStyle(fontSize: 12, color: Colors.grey[500]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildModelProgress() {
    final progress = (reportData?['model_progress'] as Map<String, dynamic>?) ?? {};

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('模型学习进度', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            _buildProgressRow('学习阶段', _getStageName(progress['stage'])),
            _buildProgressRow('训练样本', '${progress['training_samples'] ?? 0}条'),
            if (progress['mae_30'] != null)
              _buildProgressRow('30分钟MAE', '${progress['mae_30']} mmol/L'),
            if (progress['mae_60'] != null)
              _buildProgressRow('60分钟MAE', '${progress['mae_60']} mmol/L'),
            if (progress['mae_90'] != null)
              _buildProgressRow('90分钟MAE', '${progress['mae_90']} mmol/L'),
          ],
        ),
      ),
    );
  }

  Widget _buildProgressRow(String label, String value) {
    return Padding(
      padding: EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: Colors.grey[600])),
          Text(value, style: TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  String _getStageName(dynamic stage) {
    switch (stage) {
      case 'initial': return '初始阶段';
      case 'cold_start': return '冷启动阶段';
      case 'stable': return '稳定阶段';
      default: return stage?.toString() ?? '未知';
    }
  }

  Widget _buildRecommendations() {
    final recommendations = (reportData?['recommendations'] as List<dynamic>?) ?? [];

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.lightbulb, color: Colors.green),
                SizedBox(width: 8),
                Text('管理建议', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            SizedBox(height: 12),
            ...recommendations.map((item) => Padding(
              padding: EdgeInsets.symmetric(vertical: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.check_circle, color: Colors.green, size: 16),
                  SizedBox(width: 8),
                  Expanded(child: Text(item.toString())),
                ],
              ),
            )).toList(),
          ],
        ),
      ),
    );
  }
}
