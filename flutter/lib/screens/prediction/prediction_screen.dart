// flutter/lib/screens/prediction/prediction_screen.dart
// 预测模块主页 - 真实API调用

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import '../../widgets/prediction_chart.dart';
import '../../config.dart';

class PredictionScreen extends StatefulWidget {
  @override
  _PredictionScreenState createState() => _PredictionScreenState();
}

class _PredictionScreenState extends State<PredictionScreen> {
  final _apiService = ApiService();

  Map<String, dynamic>? prediction;
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
      final results = await Future.wait([
        _apiService.getPrediction(horizon: 60),
        _apiService.getAlerts(isRead: 0),
      ]);

      setState(() {
        prediction = results[0] as Map<String, dynamic>;
        alerts = results[1] as List<dynamic>;
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
      appBar: AppBar(title: Text('血糖预测')),
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
                        _buildRiskLevel(),
                        SizedBox(height: 16),
                        _buildPredictionDetails(),
                        SizedBox(height: 16),
                        _buildModelInfo(),
                      ],
                    ),
                  ),
                ),
    );
  }

  Widget _buildRiskLevel() {
    final riskLevel = prediction?['risk_level'] ?? 'normal';
    Color color;
    String text;
    IconData icon;

    switch (riskLevel) {
      case 'low_risk':
        color = Colors.orange;
        text = '低血糖风险';
        icon = Icons.warning;
        break;
      case 'high_risk':
        color = Colors.red;
        text = '高血糖风险';
        icon = Icons.dangerous;
        break;
      default:
        color = Colors.green;
        text = '正常';
        icon = Icons.check_circle;
    }

    return Card(
      color: color.withOpacity(0.1),
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(icon, color: color, size: 32),
            SizedBox(width: 12),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('风险等级', style: TextStyle(fontSize: 12, color: Colors.grey[600])),
                Text(text, style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: color)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPredictionDetails() {
    final modelType = prediction?['model_type_name'] ?? 'unknown';

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('预测详情', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            Text('预测模型: $modelType'),
            SizedBox(height: 8),
            Text('预测时间: ${prediction?['prediction_time'] ?? ''}'),
          ],
        ),
      ),
    );
  }

  Widget _buildModelInfo() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('模型信息', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            Text('当前使用模型: ${prediction?['model_type_name'] ?? '未知'}'),
            SizedBox(height: 8),
            Text('说明: 预测基于Bergman生理模型和Transformer-LSTM数据驱动模型的融合'),
          ],
        ),
      ),
    );
  }
}
