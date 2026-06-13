// flutter/lib/screens/exercise/exercise_prescription_screen.dart
// 运动处方推荐页面

import 'package:flutter/material.dart';
import '../../services/api_service.dart';

class ExercisePrescriptionScreen extends StatefulWidget {
  @override
  _ExercisePrescriptionScreenState createState() => _ExercisePrescriptionScreenState();
}

class _ExercisePrescriptionScreenState extends State<ExercisePrescriptionScreen> {
  final _apiService = ApiService();

  // 处方数据
  Map<String, dynamic>? prescription;
  bool isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadPrescription();
  }

  Future<void> _loadPrescription() async {
    setState(() {
      isLoading = true;
    });

    try {
      final data = await _apiService.getExercisePrescription();
      setState(() {
        prescription = data;
      });
    } catch (e) {
      print('加载处方失败: $e');
    } finally {
      setState(() {
        isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('运动处方'),
      ),
      body: isLoading
          ? Center(child: CircularProgressIndicator())
          : prescription == null
              ? Center(child: Text('暂无处方推荐'))
              : SingleChildScrollView(
                  padding: EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 处方卡片
                      _buildPrescriptionCard(),

                      SizedBox(height: 16),

                      // 运动类型图标
                      _buildExerciseIcon(),

                      SizedBox(height: 16),

                      // 详细说明
                      _buildDetails(),

                      SizedBox(height: 16),

                      // 注意事项
                      _buildNotes(),
                    ],
                  ),
                ),
    );
  }

  Widget _buildPrescriptionCard() {
    return Card(
      color: Color(0xFF007A8C),
      child: Padding(
        padding: EdgeInsets.all(24),
        child: Column(
          children: [
            Text(
              '推荐运动',
              style: TextStyle(color: Colors.white70, fontSize: 14),
            ),
            SizedBox(height: 8),
            Text(
              _getExerciseName(prescription!['exercise_type']),
              style: TextStyle(
                color: Colors.white,
                fontSize: 32,
                fontWeight: FontWeight.bold,
              ),
            ),
            SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _buildStatItem('时长', '${prescription!['duration_min']}分钟'),
                _buildStatItem('强度', _getIntensityName(prescription!['intensity'])),
                _buildStatItem('预计降糖', '${prescription!['expected_glucose_drop']} mmol/L'),
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
        Text(
          value,
          style: TextStyle(
            color: Colors.white,
            fontSize: 18,
            fontWeight: FontWeight.bold,
          ),
        ),
        SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(color: Colors.white70, fontSize: 12),
        ),
      ],
    );
  }

  Widget _buildExerciseIcon() {
    IconData icon;
    switch (prescription!['exercise_type']) {
      case 'walking':
        icon = Icons.directions_walk;
        break;
      case 'running':
        icon = Icons.directions_run;
        break;
      case 'cycling':
        icon = Icons.directions_bike;
        break;
      case 'swimming':
        icon = Icons.pool;
        break;
      default:
        icon = Icons.fitness_center;
    }

    return Center(
      child: Container(
        width: 120,
        height: 120,
        decoration: BoxDecoration(
          color: Color(0xFF007A8C).withOpacity(0.1),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, size: 64, color: Color(0xFF007A8C)),
      ),
    );
  }

  Widget _buildDetails() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '运动详情',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 12),
            _buildDetailRow('运动类型', _getExerciseName(prescription!['exercise_type'])),
            _buildDetailRow('建议时长', '${prescription!['duration_min']} 分钟'),
            _buildDetailRow('运动强度', _getIntensityName(prescription!['intensity'])),
            _buildDetailRow('预计血糖下降', '${prescription!['expected_glucose_drop']} mmol/L'),
          ],
        ),
      ),
    );
  }

  Widget _buildDetailRow(String label, String value) {
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

  Widget _buildNotes() {
    return Card(
      color: Colors.blue[50],
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.info, color: Colors.blue),
                SizedBox(width: 8),
                Text(
                  '注意事项',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            SizedBox(height: 8),
            Text(prescription!['notes'] ?? ''),
            SizedBox(height: 8),
            Text('• 运动前请确保血糖在安全范围内'),
            Text('• 运动过程中如感到不适请立即停止'),
            Text('• 建议随身携带糖果以防低血糖'),
          ],
        ),
      ),
    );
  }

  String _getExerciseName(String type) {
    switch (type) {
      case 'walking':
        return '步行';
      case 'running':
        return '跑步';
      case 'cycling':
        return '骑行';
      case 'swimming':
        return '游泳';
      case 'yoga':
        return '瑜伽';
      default:
        return type;
    }
  }

  String _getIntensityName(String intensity) {
    switch (intensity) {
      case 'low':
        return '低强度';
      case 'moderate':
        return '中等强度';
      case 'high':
        return '高强度';
      default:
        return intensity;
    }
  }
}
