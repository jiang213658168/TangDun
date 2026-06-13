// flutter/lib/screens/home/home_screen.dart
// 首页Dashboard - 真实API调用

import 'package:flutter/material.dart';
import '../../config.dart';
import '../../services/api_service.dart';
import '../../widgets/glucose_chart.dart';
import '../../widgets/alert_banner.dart';

class HomeScreen extends StatefulWidget {
  @override
  _HomeScreenState createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _apiService = ApiService();

  // 真实数据
  double? currentGlucose;
  String? trend;
  double? change30min;
  List<Map<String, dynamic>> glucoseData = [];
  List<dynamic> alerts = [];
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
      // 并行加载数据
      final results = await Future.wait([
        _apiService.getLatestGlucose(),
        _apiService.getGlucoseTrend(),
        _apiService.getGlucoseRecords(limit: 288),
        _apiService.getAlerts(isRead: 0),
      ]);

      final latest = results[0] as Map<String, dynamic>;
      final trendData = results[1] as Map<String, dynamic>;
      final records = results[2] as List<dynamic>;
      final alertList = results[3] as List<dynamic>;

      setState(() {
        currentGlucose = (latest['value'] as num).toDouble();
        trend = trendData['trend'] as String?;
        change30min = (trendData['change_30min'] as num?)?.toDouble();

        glucoseData = records.map((r) => {
          'time': DateTime.parse(r['timestamp']).hour + DateTime.parse(r['timestamp']).minute / 60.0,
          'value': (r['value'] as num).toDouble(),
        }).toList();

        alerts = alertList;
        isLoading = false;
      });
    } catch (e) {
      setState(() {
        errorMessage = '加载数据失败: $e';
        isLoading = false;
      });
    }
  }

  Color _getGlucoseColor(double value) {
    if (value < AppConfig.targetLow) {
      return Colors.red;
    } else if (value > AppConfig.targetHigh) {
      return Colors.orange;
    } else {
      return Colors.green;
    }
  }

  IconData _getTrendIcon(String? trend) {
    switch (trend) {
      case 'rising_fast':
        return Icons.arrow_upward;
      case 'rising':
        return Icons.trending_up;
      case 'stable':
        return Icons.trending_flat;
      case 'falling':
        return Icons.trending_down;
      case 'falling_fast':
        return Icons.arrow_downward;
      default:
        return Icons.remove;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('糖盾'),
        actions: [
          IconButton(
            icon: Icon(Icons.refresh),
            onPressed: _loadData,
          ),
        ],
      ),
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
                        // 预警横幅
                        if (alerts.isNotEmpty)
                          AlertBanner(
                            message: alerts.first['message'] ?? '有新的预警',
                            severity: alerts.first['severity'] ?? 'warning',
                            onTap: () {
                              _showAlertDetails(alerts.first);
                            },
                          ),

                        // 当前血糖值卡片
                        _buildGlucoseCard(),

                        SizedBox(height: 16),

                        // 今日血糖曲线图
                        _buildGlucoseChart(),

                        SizedBox(height: 16),

                        // 快捷操作区
                        _buildQuickActions(),
                      ],
                    ),
                  ),
                ),
    );
  }

  Widget _buildGlucoseCard() {
    if (currentGlucose == null) {
      return Card(
        child: Padding(
          padding: EdgeInsets.all(20),
          child: Center(child: Text('暂无血糖数据')),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: EdgeInsets.all(20),
        child: Column(
          children: [
            Text(
              '当前血糖',
              style: TextStyle(fontSize: 16, color: Colors.grey[600]),
            ),
            SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text(
                  currentGlucose!.toStringAsFixed(1),
                  style: TextStyle(
                    fontSize: 48,
                    fontWeight: FontWeight.bold,
                    color: _getGlucoseColor(currentGlucose!),
                  ),
                ),
                SizedBox(width: 8),
                Text('mmol/L', style: TextStyle(fontSize: 16, color: Colors.grey[600])),
                SizedBox(width: 16),
                Icon(
                  _getTrendIcon(trend),
                  color: _getGlucoseColor(currentGlucose!),
                  size: 32,
                ),
              ],
            ),
            SizedBox(height: 8),
            if (change30min != null)
              Text(
                '30分钟变化: ${change30min! >= 0 ? '+' : ''}${change30min!.toStringAsFixed(1)} mmol/L',
                style: TextStyle(fontSize: 14, color: Colors.grey[600]),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildGlucoseChart() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('今日血糖曲线', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 16),
            Container(
              height: 200,
              child: glucoseData.isNotEmpty
                  ? GlucoseChart(
                      data: glucoseData,
                      targetLow: AppConfig.targetLow,
                      targetHigh: AppConfig.targetHigh,
                    )
                  : Center(child: Text('暂无数据')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildQuickActions() {
    return Row(
      children: [
        Expanded(
          child: ElevatedButton.icon(
            onPressed: () {
              Navigator.pushNamed(context, '/meal/record');
            },
            icon: Icon(Icons.camera_alt),
            label: Text('拍照记录'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Color(0xFF007A8C),
              foregroundColor: Colors.white,
              padding: EdgeInsets.symmetric(vertical: 12),
            ),
          ),
        ),
        SizedBox(width: 12),
        Expanded(
          child: OutlinedButton.icon(
            onPressed: () {
              Navigator.pushNamed(context, '/prediction');
            },
            icon: Icon(Icons.show_chart),
            label: Text('查看预测'),
            style: OutlinedButton.styleFrom(
              foregroundColor: Color(0xFF007A8C),
              padding: EdgeInsets.symmetric(vertical: 12),
            ),
          ),
        ),
      ],
    );
  }

  void _showAlertDetails(Map<String, dynamic> alert) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(
              alert['severity'] == 'critical' ? Icons.dangerous : Icons.warning,
              color: alert['severity'] == 'critical' ? Colors.red : Colors.orange,
            ),
            SizedBox(width: 8),
            Text('预警详情'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('类型: ${_getAlertTypeName(alert['alert_type'])}'),
            SizedBox(height: 8),
            Text('严重程度: ${alert['severity'] == 'critical' ? '严重' : '警告'}'),
            SizedBox(height: 8),
            if (alert['glucose_value'] != null)
              Text('血糖值: ${alert['glucose_value']} mmol/L'),
            SizedBox(height: 8),
            Text(alert['message'] ?? ''),
            SizedBox(height: 16),
            Text('处理建议:', style: TextStyle(fontWeight: FontWeight.bold)),
            SizedBox(height: 4),
            Text(_getAlertAction(alert['alert_type'], alert['glucose_value'])),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await _apiService.markAlertRead(alert['id']);
              Navigator.pop(context);
              _loadData(); // 刷新数据
            },
            child: Text('标记已读'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context),
            child: Text('确定'),
          ),
        ],
      ),
    );
  }

  String _getAlertTypeName(String? type) {
    switch (type) {
      case 'low_glucose': return '低血糖预警';
      case 'high_glucose': return '高血糖预警';
      case 'rapid_change': return '血糖快速变化';
      default: return '预警';
    }
  }

  String _getAlertAction(String? type, double? glucose) {
    if (type == 'low_glucose') {
      if (glucose != null && glucose < 3.0) {
        return '严重低血糖！请立即补充15g快速碳水（葡萄糖片、果汁），15分钟后复查';
      }
      return '请补充15g快速碳水，15分钟后复查血糖';
    } else if (type == 'high_glucose') {
      if (glucose != null && glucose > 13.9) {
        return '血糖严重偏高，请检测酮体并考虑补充胰岛素';
      }
      return '请多喝水，1小时后复查血糖';
    }
    return '请继续监测血糖';
  }
}
